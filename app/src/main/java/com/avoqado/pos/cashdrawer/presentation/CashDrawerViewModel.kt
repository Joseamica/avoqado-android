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
import com.avoqado.pos.core.domain.RoleManager
import javax.inject.Inject
import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder

private const val TAG = "💰 CashDrawerVM"

enum class CashDrawerSection(val label: String) {
    CURRENT("Caja en curso"),
    HISTORY("Historial"),
}

@HiltViewModel
class CashDrawerViewModel @Inject constructor(
    private val repository: CashDrawerRepository,
    private val printerService: PrinterService,
    private val roleManager: RoleManager,
) : ViewModel() {

    /**
     * Conteo CIEGO de verdad (P1 Codex 27-ago): esconder el esperado sólo en la hoja de cierre no
     * servía si la cabecera lo enseñaba todo el día. Toast lo resuelve por PERMISO ("Cash Drawers
     * (Blind)" vs "Full"); aquí se espeja el permiso PROPIO del server `cash-drawer:view-expected`
     * (MANAGER+ de fábrica). 🔴 No puede ser `shifts:close`: el alias con `tpv-shifts:close` se lo
     * regala a cajeros y meseros (Codex, 2ª auditoría). Quien no lo tiene cuenta a ciegas.
     */
    val puedeVerEsperado: Boolean
        get() = roleManager.hasVenuePermission(PERMISO_VER_ESPERADO, ROLES_VER_ESPERADO)

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
    /** `null` = no se pudo consultar el desglose; lista vacía = el corte no tuvo cobros. */
    /**
     * 🔴 Movimientos que el servidor RECHAZÓ de plano. Antes se descartaban con un `Log.w` y
     * nadie se enteraba: el cajero cerraba su caja creyendo que su retiro había llegado, y el
     * arqueo salía con un faltante que no se podía explicar. Un fallo silencioso en dinero es
     * peor que uno ruidoso.
     */
    private val _rechazadas = MutableStateFlow<List<CashDrawerRepository.OperacionRechazada>>(emptyList())
    val rechazadas: StateFlow<List<CashDrawerRepository.OperacionRechazada>> = _rechazadas.asStateFlow()

    private val _tenderBreakdown = MutableStateFlow<List<CashDrawerRepository.TenderRow>?>(null)
    val tenderBreakdown: StateFlow<List<CashDrawerRepository.TenderRow>?> = _tenderBreakdown.asStateFlow()

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

    /** El cajero ya vio el aviso y decidió qué hacer con ese dinero: se saca de la cola. */
    fun descartarRechazada(op: CashDrawerRepository.OperacionRechazada) {
        viewModelScope.launch {
            runCatching { repository.descartarRechazada(op.localKey) }
            _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
        }
    }

    // MARK: - Load Data

    fun loadCurrentSession() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Un cierre que se quedó sin red se reintenta cada vez que se entra a Caja, no sólo al
                // crear el ViewModel (que sobrevive entre visitas): visto en la Samsung, 27-ago.
                runCatching { repository.reproducirCierresPendientes() }
                _rechazadas.value = runCatching { repository.operacionesRechazadas() }.getOrDefault(emptyList())
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
        tenders: List<CashDrawerRepository.TenderRow>?,
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
                val data = CorteTicketBuilder.build(
                    session = session,
                    events = events,
                    tenders = tenders,
                    venueName = venueName,
                    paperWidth = printer.paperWidth,
                    isPartial = isPartial,
                    showExpected = !isPartial || puedeVerEsperado, // conteo ciego también en el ticket del corte parcial
                    // La integrada de Sunmi necesita el cambio a un solo byte o el
                    // papel sale en blanco. Mismo criterio que `escposFor`.
                    switchToSingleByteFirst =
                        printer.connectionTypeEnum == com.avoqado.pos.printing.data.model.PrinterConnectionType.INTERNAL,
                )
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
            // `null` si no se pudo consultar — NUNCA `emptyList()`, que significa "no hubo cobros".
            val tenders = runCatching {
                repository.getTenderBreakdown(session.openedAt, System.currentTimeMillis())
            }.getOrNull()
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


}

/** Espejo EXACTO del permiso del server que gobierna el back-office de turnos. */
const val PERMISO_VER_ESPERADO = "cash-drawer:view-expected"
val ROLES_VER_ESPERADO = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")
