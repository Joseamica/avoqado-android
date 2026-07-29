package com.avoqado.pos.cashdrawer.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "💰 CashDrawerVM"

enum class CashDrawerSection(val label: String) {
    CURRENT("Caja en curso"),
    HISTORY("Historial"),
}

@HiltViewModel
class CashDrawerViewModel @Inject constructor(
    private val repository: CashDrawerRepository,
    private val printerService: PrinterService,
) : ViewModel() {

    // MARK: - State

    private val _currentSession = MutableStateFlow<CashDrawerSessionEntity?>(null)
    val currentSession: StateFlow<CashDrawerSessionEntity?> = _currentSession.asStateFlow()

    private val _events = MutableStateFlow<List<CashDrawerEventEntity>>(emptyList())
    val events: StateFlow<List<CashDrawerEventEntity>> = _events.asStateFlow()

    private val _expectedAmountCents = MutableStateFlow(0)
    val expectedAmountCents: StateFlow<Int> = _expectedAmountCents.asStateFlow()

    private val _closedSessions = MutableStateFlow<List<CashDrawerSessionEntity>>(emptyList())
    val closedSessions: StateFlow<List<CashDrawerSessionEntity>> = _closedSessions.asStateFlow()

    private val _selectedSection = MutableStateFlow(CashDrawerSection.CURRENT)
    val selectedSection: StateFlow<CashDrawerSection> = _selectedSection.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Tender breakdown for the corte (card + cash + other), keyed nowhere —
    // refetched per report shown. Empty until loaded / on failure.
    private val _tenderBreakdown = MutableStateFlow<List<CashDrawerRepository.TenderRow>>(emptyList())
    val tenderBreakdown: StateFlow<List<CashDrawerRepository.TenderRow>> = _tenderBreakdown.asStateFlow()

    /** Fetch the payment-method breakdown for a session's window (corte de caja). */
    fun loadTenderBreakdown(fromMillis: Long, toMillis: Long) {
        viewModelScope.launch {
            _tenderBreakdown.value = repository.getTenderBreakdown(fromMillis, toMillis)
        }
    }

    // End of day ("Cierre del día") — day summary + blockers.
    private val _endOfDay = MutableStateFlow<com.avoqado.pos.cashdrawer.data.EndOfDaySummary?>(null)
    val endOfDay: StateFlow<com.avoqado.pos.cashdrawer.data.EndOfDaySummary?> = _endOfDay.asStateFlow()

    private val _isLoadingEndOfDay = MutableStateFlow(false)
    val isLoadingEndOfDay: StateFlow<Boolean> = _isLoadingEndOfDay.asStateFlow()

    fun loadEndOfDay() {
        viewModelScope.launch {
            _isLoadingEndOfDay.value = true
            _endOfDay.value = repository.getEndOfDay()
            _isLoadingEndOfDay.value = false
        }
    }

    // MARK: - Init

    init {
        syncAndLoad()
    }

    private fun syncAndLoad() {
        viewModelScope.launch {
            try {
                repository.syncFromApi()
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ API sync failed, using local data: ${e.message}")
            }
            loadCurrentSession()
        }
    }

    // MARK: - Navigation

    fun selectSection(section: CashDrawerSection) {
        _selectedSection.value = section
        when (section) {
            CashDrawerSection.CURRENT -> loadCurrentSession()
            CashDrawerSection.HISTORY -> loadHistory()
        }
    }

    // MARK: - Load Data

    fun loadCurrentSession() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = repository.getOpenSession()
                _currentSession.value = session
                if (session != null) {
                    _events.value = repository.getEvents(session.id)
                    _expectedAmountCents.value = repository.computeExpectedAmount(
                        session.id,
                        session.startingAmountCents,
                    )
                } else {
                    _events.value = emptyList()
                    _expectedAmountCents.value = 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading session: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Sync from API first, then read from Room
                repository.syncFromApi()
                _closedSessions.value = repository.getHistory()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading history: ${e.message}")
                // Fall back to local data
                try {
                    _closedSessions.value = repository.getHistory()
                } catch (_: Exception) { /* ignore */ }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /// Drawer-op failures were Log.e-only — the VM had NO error channel at
    /// all, so a failed pay-out/close looked identical to success.
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // MARK: - Actions

    fun openSession(startingAmountCents: Int) {
        viewModelScope.launch {
            try {
                val session = repository.openSession(startingAmountCents)
                _currentSession.value = session
                _events.value = repository.getEvents(session.id)
                _expectedAmountCents.value = startingAmountCents
                Log.d(TAG, "✅ Session opened")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error opening session: ${e.message}")
                errorMessage.value = "No se pudo abrir la caja. Intenta de nuevo."
            }
        }
    }

    fun addPayIn(amountCents: Int, note: String?) {
        viewModelScope.launch {
            try {
                repository.addPayIn(amountCents, note)
                loadCurrentSession()
                Log.d(TAG, "✅ Pay-in added: $amountCents")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding pay-in: ${e.message}")
                errorMessage.value = "No se pudo registrar la entrada de efectivo."
            }
        }
    }

    fun addPayOut(amountCents: Int, note: String?) {
        viewModelScope.launch {
            try {
                repository.addPayOut(amountCents, note)
                loadCurrentSession()
                Log.d(TAG, "✅ Pay-out added: $amountCents")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding pay-out: ${e.message}")
                errorMessage.value = "No se pudo registrar la salida de efectivo."
            }
        }
    }

    /// Returns true only when the close actually succeeded — callers must gate
    /// the daily report on this (before, the report was FABRICATED client-side
    /// and shown even when the close failed).
    suspend fun closeSession(actualAmountCents: Int, note: String?): Boolean {
        return try {
                repository.closeSession(actualAmountCents, note)
                _currentSession.value = null
                _events.value = emptyList()
                _expectedAmountCents.value = 0
                Log.d(TAG, "✅ Session closed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing session: ${e.message}")
            errorMessage.value = "No se pudo cerrar la caja. Intenta de nuevo."
            false
        }
    }

    // MARK: - Load events for a specific session (used by history -> report)

    fun loadEventsForSession(sessionId: String, onResult: (List<CashDrawerEventEntity>) -> Unit) {
        viewModelScope.launch {
            try {
                val events = repository.getEvents(sessionId)
                onResult(events)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading events for session $sessionId: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    // MARK: - Helpers

    fun cashSalesTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.CASH_SALE.name }
            .sumOf { it.amountCents }
    }

    fun payInsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_IN.name }
            .sumOf { it.amountCents }
    }

    fun payOutsTotal(): Int {
        return _events.value
            .filter { it.type == CashDrawerEventType.PAY_OUT.name }
            .sumOf { it.amountCents }
    }

    /** Nombre del local, para el encabezado del corte impreso. */
    val venueName: String get() = repository.venueName

    // MARK: - Imprimir el corte

    /**
     * Resultado de intentar imprimir el corte. Se expone como estado para que la
     * pantalla diga qué pasó: el botón antes sólo lanzaba un Toast de "no
     * disponible" que además en la Sunmi queda detrás de la pantalla del cliente,
     * o sea que para el cajero el botón simplemente no hacía nada.
     */
    sealed interface PrintCorteResult {
        data class Success(val wasPartial: Boolean) : PrintCorteResult
        data class Failure(val reason: String) : PrintCorteResult
    }

    private val _printCorteResult = MutableStateFlow<PrintCorteResult?>(null)
    val printCorteResult: StateFlow<PrintCorteResult?> = _printCorteResult.asStateFlow()

    private val _isPrintingCorte = MutableStateFlow(false)
    val isPrintingCorte: StateFlow<Boolean> = _isPrintingCorte.asStateFlow()

    fun clearPrintCorteResult() {
        _printCorteResult.value = null
    }

    /**
     * Imprime el corte de caja en la impresora de recibos.
     *
     * El desglose por método sale de [tenders] (lo que mandó el server, con TODOS
     * los métodos). Si viene vacío —sin conexión— se imprime sólo el efectivo y
     * se dice en el papel, porque un corte que afirma "Tarjeta $0.00" cuando en
     * realidad no pudo consultar es peor que uno incompleto y honesto.
     */
    fun printCorte(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
        tenders: List<CashDrawerRepository.TenderRow>,
        venueName: String,
        // Corte PARCIAL: se saca con la caja todavía abierta para revisar el turno a
        // media jornada. No cierra nada, no cuenta el dinero y no arroja diferencia
        // — sólo dice cuánto DEBERÍA haber en el cajón en este momento.
        isPartial: Boolean = false,
    ) {
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT)
        if (printer == null) {
            _printCorteResult.value = PrintCorteResult.Failure(
                "No hay impresora de recibos configurada. Ve a Más > Impresora para agregar una.",
            )
            return
        }

        viewModelScope.launch {
            _isPrintingCorte.value = true
            try {
                val data = buildCorteTicket(session, events, tenders, venueName, printer.paperWidth, isPartial)
                printerService.sendPrintData(data, printer)
                _printCorteResult.value = PrintCorteResult.Success(isPartial)
                Log.d(TAG, "✅ Corte${if (isPartial) " parcial" else ""} impreso en ${printer.name}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ No se pudo imprimir el corte: ${e.message}", e)
                _printCorteResult.value = PrintCorteResult.Failure(
                    "No se pudo imprimir: ${e.message ?: "revisa que la impresora esté encendida y conectada"}",
                )
            } finally {
                _isPrintingCorte.value = false
            }
        }
    }

    /**
     * Corte PARCIAL de la caja en curso: lo que el operador saca a media jornada
     * para revisar cómo va el turno sin cerrarlo.
     *
     * Trae el desglose fresco desde la apertura hasta AHORA antes de imprimir —
     * usar el que quedó cacheado imprimiría una foto vieja, y el punto de una
     * lectura parcial es justamente saber cómo va en este momento. Si la consulta
     * falla (sin red) igual se imprime con el efectivo local, que es dato bueno:
     * negarse a imprimir sería peor que imprimir un corte honestamente parcial.
     */
    fun printPartialCorte(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
    ) {
        viewModelScope.launch {
            val tenders = runCatching {
                repository.getTenderBreakdown(session.openedAt, System.currentTimeMillis())
            }.getOrDefault(emptyList())
            _tenderBreakdown.value = tenders
            printCorte(
                session = session,
                events = events,
                tenders = tenders,
                venueName = venueName,
                isPartial = true,
            )
        }
    }

    private fun buildCorteTicket(
        session: CashDrawerSessionEntity,
        events: List<CashDrawerEventEntity>,
        tenders: List<CashDrawerRepository.TenderRow>,
        venueName: String,
        paperWidth: com.avoqado.pos.printing.data.model.PaperWidth,
        isPartial: Boolean,
    ): ByteArray {
        val zone = com.avoqado.pos.core.util.VenueTimeZone.zoneId()
        val fecha = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("es", "MX"))
        val hora = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale("es", "MX"))
        fun money(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)
        fun at(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone)

        fun sumOf(type: CashDrawerEventType) =
            events.filter { it.type == type.name }.sumOf { it.amountCents }

        val cashSales = sumOf(CashDrawerEventType.CASH_SALE)
        val payIns = sumOf(CashDrawerEventType.PAY_IN)
        val payOuts = sumOf(CashDrawerEventType.PAY_OUT)
        val expected = session.startingAmountCents + cashSales + payIns - payOuts
        val actual = session.actualAmountCents ?: 0
        val diff = actual - expected
        val hasServerBreakdown = tenders.isNotEmpty()
        val totalSales = if (hasServerBreakdown) tenders.sumOf { it.totalCents } else cashSales
        val txCount = events.count { it.type == CashDrawerEventType.CASH_SALE.name }

        val p = ESCPOSPrinter(paperWidth)
        p.reset()
        p.setAlignment(ESCPOSPrinter.TextAlignment.CENTER)
        p.setBold(true)
        p.setLargeText(true)
        p.printLine(if (isPartial) "CORTE PARCIAL" else "CORTE DE CAJA")
        p.setLargeText(false)
        p.printLine(venueName)
        p.setBold(false)
        if (isPartial) {
            // Que quede en el papel: este ticket NO cerró el turno. Sin esto, dos
            // cortes del mismo día se confunden y alguien cuadra contra el equivocado.
            p.setBold(true)
            p.printLine("LA CAJA SIGUE ABIERTA")
            p.setBold(false)
        }
        p.printLine(at(session.openedAt).format(fecha))
        p.printLine(
            if (isPartial) {
                "Apertura: " + at(session.openedAt).format(hora) +
                    "  Impreso: " + java.time.ZonedDateTime.now(zone).format(hora)
            } else {
                "Apertura: " + at(session.openedAt).format(hora) +
                    "  Cierre: " + (session.closedAt?.let { at(it).format(hora) } ?: "--")
            },
        )
        p.printLine("Operador: ${session.openedByName}")
        p.printDivider()

        p.setAlignment(ESCPOSPrinter.TextAlignment.LEFT)
        p.setBold(true)
        p.printLine(if (hasServerBreakdown) "RESUMEN DE VENTAS" else "RESUMEN DE VENTAS (EFECTIVO)")
        p.setBold(false)
        p.printTwoColumns(if (hasServerBreakdown) "Ventas totales" else "Ventas en efectivo", money(totalSales))
        p.printTwoColumns("Transacciones", "$txCount")
        p.printTwoColumns("Ticket promedio", money(if (txCount > 0) totalSales / txCount else 0))
        p.printDivider()

        p.setBold(true)
        p.printLine("DESGLOSE POR MÉTODO DE PAGO")
        p.setBold(false)
        if (hasServerBreakdown) {
            tenders.sortedByDescending { it.totalCents }.forEach {
                p.printTwoColumns(tenderLabel(it.method), money(it.totalCents))
            }
        } else {
            p.printTwoColumns("Efectivo", money(cashSales))
            p.printLine("Sin conexión: sólo se muestra el efectivo.")
            p.printLine("Tarjeta y otros medios aparecerán al")
            p.printLine("recuperar la conexión.")
        }
        p.printDivider()

        p.setBold(true)
        p.printLine("MOVIMIENTOS DE EFECTIVO")
        p.setBold(false)
        p.printTwoColumns("Monto inicial", money(session.startingAmountCents))
        p.printTwoColumns("Ventas en efectivo", "+" + money(cashSales))
        p.printTwoColumns("Ingresos", "+" + money(payIns))
        p.printTwoColumns("Egresos", "-" + money(payOuts))
        p.printDivider()
        p.setBold(true)
        p.printTwoColumns("Efectivo esperado", money(expected))
        if (isPartial) {
            // NADA de "Conteo real" ni de diferencia: el dinero no se ha contado.
            // Imprimir "Faltante $1,058.00" aquí sería inventar un descuadre.
            p.setBold(false)
            p.printLine("Cuenta el cajón y cierra la caja para")
            p.printLine("obtener el corte definitivo.")
        } else {
            p.printTwoColumns("Conteo real", money(actual))
            p.printTwoColumns(
                when {
                    diff > 0 -> "Sobrante"
                    diff < 0 -> "Faltante"
                    else -> "Diferencia"
                },
                money(kotlin.math.abs(diff)),
            )
            p.setBold(false)
        }
        p.feedLines(3)
        p.cut()
        return p.getData()
    }
}
