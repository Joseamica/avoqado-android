package com.avoqado.pos.areatickets.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketException
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.IntentoDeVale
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.data.PendingAreaTicketPrintRecord
import com.avoqado.pos.areatickets.data.RenglonDeComandaDeVale
import com.avoqado.pos.areatickets.data.SETTLEMENT_ROUTE_EXTERNAL
import com.avoqado.pos.areatickets.data.toAreaTicketData
import com.avoqado.pos.areatickets.data.ValeSinConfirmar
import com.avoqado.pos.core.data.local.LlaveDeEmisionDeVales
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.printing.ComandaMoment
import com.avoqado.pos.core.domain.printing.FulfillmentMode
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.routing.RoutableItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class AreaTicketPdfExport(
    val ticketId: String,
    val code: String,
    val fileName: String,
    val bytes: ByteArray,
    /** El negocio al PREPARAR el PDF: su registro pendiente va a ese negocio aunque la sesión cambie mientras se guarda. */
    val venueId: String?,
)

data class AreaTicketOperationsState(
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val preparingPdf: Boolean = false,
    val settings: AreaTicketSettingsData? = null,
    val pending: List<AreaTicket> = emptyList(),
    val pendingReprintCode: String? = null,
    /** 🔴 D5: el vale que se pidió en una vez anterior de la app y nunca se confirmó (de este negocio). */
    val valeSinConfirmar: ValeSinConfirmar? = null,
    /** D5: su aviso está abierto (al abrir la pantalla, o al tocar «Emitir vale» mientras exista). */
    val mostrarValeSinConfirmar: Boolean = false,
    /** D5 (Codex r3 #3): el [error] salió de «Recuperar»: se enseña aunque no haya configuración de vales (sin red). */
    val errorDelValeSinConfirmar: Boolean = false,
    val pdfExport: AreaTicketPdfExport? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val issueWorkspace: Boolean
        get() = settings?.let {
            it.areaTickets.entitled &&
                it.areaTickets.enabled &&
                it.terminal.canIssueAreaTickets &&
                it.terminal.fulfillmentArea?.active == true &&
                it.terminal.defaultWorkspace == "AREA_OPERATIONS"
        } == true

    val deliveryWorkspace: Boolean
        get() = settings?.let {
            it.areaTickets.entitled &&
                it.areaTickets.enabled &&
                it.terminal.canDeliverAreaTickets &&
                it.terminal.fulfillmentArea?.active == true
        } == true

    val canConfirmDeliveryWithPaper: Boolean
        get() = deliveryWorkspace && settings?.areaTickets?.deliveryVerificationMode in setOf(
            "PAPER_CONFIRMATION",
            "PAPER_OR_SCAN",
        )

    val canScanDeliveryReceipt: Boolean
        get() = deliveryWorkspace && settings?.areaTickets?.deliveryVerificationMode in setOf(
            "RECEIPT_SCAN",
            "PAPER_OR_SCAN",
        )

    /** D5: su aviso se ve aunque haya un error (lo lleva adentro) y aunque no haya configuración (Codex r3 #3). */
    val valeSinConfirmarVisible: Boolean
        get() = mostrarValeSinConfirmar && valeSinConfirmar != null && pendingReprintCode == null

    val checkoutBlockingError: String?
        get() = error.takeIf { (settings != null || pendingReprintCode != null || errorDelValeSinConfirmar) && !valeSinConfirmarVisible }

    /**
     * Codex final #3: se está emitiendo (o reimprimiendo) un vale desde el modo de vales. El vale sale con la foto del
     * carrito del toque y al terminar se vacía el carrito: Checkout no deja tocar catálogo, carrito ni escaneos mientras.
     */
    val issuing: Boolean
        get() = issueWorkspace && submitting
}

@HiltViewModel
class AreaTicketOperationsViewModel @Inject constructor(
    private val repository: AreaTicketRepository,
    private val printerService: PrinterService,
    private val pdfGenerator: AreaTicketPdfGenerator,
    private val secureStorage: SecureStorage,
    private val comandaDispatcher: ComandaDispatcher,
    /** Pistola y lector integrado. Default para las pruebas que lo construyen a mano; Hilt inyecta el singleton real. */
    private val lectorHidBus: com.avoqado.pos.pos.data.LectorHidBus = com.avoqado.pos.pos.data.LectorHidBus(),
) : ViewModel() {
    /** Códigos de la pistola o del lector integrado: la entrega los escucha con su escáner abierto. */
    val codigosEscaneados: kotlinx.coroutines.flow.SharedFlow<String>
        get() = lectorHidBus.codigos

    /**
     * Codex (5-oct): la comanda no muere si se cierra la pantalla de vales a media impresión o reintento.
     * ponytail: si la app muere en ese lapso la comanda se pierde (los vales no tienen la libreta del mostrador);
     * si llega a pasar, encolarla en ReplayDeComandasPendientes.
     */
    private val comandaScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(AreaTicketOperationsState())
    val state: StateFlow<AreaTicketOperationsState> = _state.asStateFlow()
    private var refreshRequestId = 0L

    /**
     * 🔴 D2 (vales-ciclo): la llave de emisión vive en el aparato, una por negocio (`SecureStorage.llaveDeEmisionDeVales`), no
     * en esta pantalla. Se suelta sólo ① al confirmar el servidor el vale de ESA llave (abajo, en [issue]; si otra pantalla ya
     * la cambió, no se toca) y ② al vaciar el carrito a mano (`CartViewModel.vaciar`); sin llave, se crea y se guarda en disco
     * ANTES del POST, y si el disco no la confirmó (Codex r6 #1, r7 #2) da null y el vale no se pide. Un 409, reimprimir o el PDF no la tocan: con llave nueva, el carrito
     * cambiado tras una respuesta perdida era un SEGUNDO vale. Antes había una copia en memoria que quedaba vieja.
     */
    private fun llaveDeEmision(venueId: String, intento: IntentoDeVale): Pair<String, LlaveDeEmisionDeVales.Guardado> =
        // r8 #2: también la que ya estaba se vuelve a guardar: tras un commit fallido sólo vive en la memoria del proceso.
        // D5: junto con lo que se va a mandar, en el mismo commit, para pedirlo igual si la app se cierra antes de saber.
        (secureStorage.llaveDeEmisionDeVales(venueId) ?: UUID.randomUUID().toString())
            .let { it to secureStorage.guardarIntentoDeVale(venueId, it, intento) }

    /**
     * 🔴 D5: la primera vez que este proceso ve [venueId], lo que quedó a medias de la vez anterior pasa a «sin confirmar»
     * (`SecureStorage.revisarValeAMedias`). Devuelve el de este negocio, si hay. El filtro deja fuera lo que no es un registro
     * de verdad (un `SecureStorage` falso de prueba devuelve llaves vacías).
     */
    private fun cargarValeSinConfirmar(venueId: String?, mostrar: Boolean = false): ValeSinConfirmar? {
        if (venueId.isNullOrBlank()) return null
        if (ValesRevisadosEnEsteProceso.primeraVez(venueId)) secureStorage.revisarValeAMedias(venueId)
        val vale = secureStorage.valeSinConfirmar(venueId)?.takeIf { it.llave.isNotBlank() && it.intentos.isNotEmpty() }
        _state.value = _state.value.copy(
            valeSinConfirmar = vale,
            mostrarValeSinConfirmar = vale != null && (mostrar || _state.value.mostrarValeSinConfirmar),
        )
        return vale
    }

    // D12. Antes del `init`: su `refresh()` ya manda los registros pendientes.
    private val pendingRecordsJson = Json { ignoreUnknownKeys = true }
    private val pendingRecordsSerializer = ListSerializer(PendingAreaTicketPrintRecord.serializer())
    private val flushMutex = Mutex()

    /** Codex final #2: UN envío de pendientes a la vez, compartido; quien espera con tope suelta la espera, no el envío. */
    private var flushJob: Job? = null

    /**
     * Codex r4: el aviso de disco de ESTA operación gana sobre su mensaje de éxito. Los tres cierres
     * (emitir, reimprimir, PDF) usan `message = consumeOutputWarning() ?: "<su mensaje de siempre>"`.
     */
    private var outputWarning: String? = null

    /**
     * J7 (vales-ciclo r0): la venta del carrito que emitió el vale pendiente de papel. Reimprimir o guardar su PDF sólo puede
     * terminar ESA venta. Sólo en memoria: tras reiniciar no se sabe y no se termina ninguna.
     */
    private var ventaDelValePendiente: String? = null

    init {
        _state.value = _state.value.copy(
            pendingReprintCode = secureStorage.pendingAreaTicketPrintCode.takeIf {
                val pendingVenue = secureStorage.pendingAreaTicketPrintVenueId
                pendingVenue == null || pendingVenue == secureStorage.venueId
            },
        )
        cargarValeSinConfirmar(secureStorage.venueId, mostrar = true)
        refresh()
    }

    fun refresh(loadPendingDelivery: Boolean = false): Job {
        val requestId = ++refreshRequestId
        // D12: en su propia corrutina, no delante de la configuración: con un WiFi sin salida cada registro espera
        // su timeout, y mientras tanto la terminal de área se quedaría sin su modo de vales.
        retryPendingPrintRecords()
        return viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val settings = try {
                repository.settings()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId != refreshRequestId) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "No se pudo cargar la operación de vales.",
                )
                return@launch
            }
            if (requestId != refreshRequestId) return@launch
            val canLoadPending = loadPendingDelivery &&
                settings.areaTickets.entitled &&
                settings.areaTickets.enabled &&
                settings.terminal.canDeliverAreaTickets &&
                settings.terminal.fulfillmentArea?.active == true
            if (!canLoadPending) {
                _state.value = _state.value.copy(
                    loading = false,
                    settings = settings,
                    pending = emptyList(),
                    error = null,
                )
                return@launch
            }
            try {
                val pending = repository.pendingDelivery().tickets
                if (requestId != refreshRequestId) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    settings = settings,
                    pending = pending,
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId != refreshRequestId) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    settings = settings,
                    pending = emptyList(),
                    error = error.message ?: "No se pudo cargar la operación de vales.",
                )
            }
        }
    }

    fun issue(
        cart: CartState,
        /** D1: corre ya validado, justo antes de pedir el vale (marca la copia guardada). `false` = no se emite. */
        antesDeEmitir: () -> Boolean = { true },
        onIssued: (ventaQueEmitio: String?) -> Unit,
    ) {
        if (_state.value.submitting) return
        val invalid = cart.items.any { it.locked || it.type !is CartItemType.ProductItem }
        if (cart.items.isEmpty() || invalid) {
            _state.value = _state.value.copy(
                error = "El vale sólo puede incluir productos del área; quita importes libres, membresías o vales escaneados.",
            )
            return
        }
        areaTicketIssueBlocker(cart)?.let { motivo ->
            _state.value = _state.value.copy(error = motivo)
            return
        }
        // 🔴 D5 ④: con un vale sin confirmar de este negocio no se pide otro (ni la copia guardada restaurada ni un carrito
        // armado de nuevo sacan un segundo vale sin que el cajero decida). Se abre su aviso. Cobrar sí se puede.
        if (cargarValeSinConfirmar(secureStorage.venueId) != null) {
            _state.value = _state.value.copy(mostrarValeSinConfirmar = true)
            return
        }
        // D1 (vales-ciclo, Codex r4): ya validado y ANTES de pedir. Marcar antes de validar dejaba «ya se mandó a cobrar» en una
        // copia cuyo vale nunca se pidió.
        if (!antesDeEmitir()) {
            _state.value = _state.value.copy(error = "No se pudo preparar el vale del carrito guardado. Intenta de nuevo.")
            return
        }
        // El negocio de ESTE vale, leído al tocar: la sesión puede cerrarse desde el hilo de OkHttp mientras sale el papel.
        val venueId = secureStorage.venueId
        // D5: lo que se manda se arma UNA vez, al tocar; es lo mismo que queda en disco con la llave.
        val intento = intentoDeVale(cart)
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            // D2 (Codex r10 #1): la llave se toma (y se guarda) AL TOCAR, antes de esperar lo pendiente. Si mientras tanto otra
            // pantalla recupera el vale y la suelta, este reintento sale con ELLA y el servidor compara; con una nueva, otro vale.
            val (llave, guardado) = llaveDeEmision(venueId.orEmpty(), intento)
            if (guardado != LlaveDeEmisionDeVales.Guardado.SI) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = if (guardado == LlaveDeEmisionDeVales.Guardado.TOPE) VALE_CAMBIO_DEMASIADO else "No se pudo preparar el vale. Intenta de nuevo.",
                )
                return@launch
            }
            // Codex r2 #3: DESPUÉS de `submitting`; si no, mientras esto espera a la red un segundo toque emitiría otro vale.
            runCatching { flushWithinBudget() }
            // Task 13: si en esa espera se cambió de negocio, el vale nacería en el NUEVO y su registro y su reimpresión
            // irían al viejo. No se crea nada; la llave sigue para el reintento.
            if (secureStorage.venueId != venueId) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "Cambiaste de negocio mientras se emitía el vale. Vuelve a intentarlo.",
                )
                return@launch
            }
            runCatching {
                val ticket = repository.issue(intento.lineas, llave)
                // D5 (Codex r1 #5): la misma llave puede traer de vuelta un vale que ya no sirve (vencido, cancelado, cobrado).
                // La llave ya se usó: se suelta; el carrito se queda y el siguiente intento sale como vale nuevo.
                valeQueYaNoSirve(ticket)?.let { motivo ->
                    secureStorage.soltarLlaveDeEmisionDeVales(venueId.orEmpty(), siEs = llave)
                    throw IllegalStateException(motivo)
                }
                // Primero persiste la recuperación: si la app muere al limpiar el
                // carrito, el mismo vale todavía puede reimprimirse al volver.
                secureStorage.pendingAreaTicketPrintCode = ticket.code
                secureStorage.pendingAreaTicketPrintVenueId = venueId
                _state.value = _state.value.copy(pendingReprintCode = ticket.code)
                ventaDelValePendiente = cart.identidadDeVenta
                // La llave protege la creación, no la impresión. Una vez que el
                // servidor aceptó este vale, el siguiente carrito necesita otra
                // llave aunque todavía quede pendiente reimprimir el papel.
                secureStorage.soltarLlaveDeEmisionDeVales(venueId.orEmpty(), siEs = llave)
                // El vale ya quedó emitido en servidor. Desde este punto el carrito no
                // puede seguir cobrable aunque falle la salida en papel: imprimir es una
                // recuperación secundaria del mismo vale, no parte de su creación.
                onIssued(cart.identidadDeVenta)
                try {
                    printAndRecord(ticket, reprint = false, venueId)
                } finally {
                    // Después del vale (si comparten impresora, el vale sale primero) y aunque su papel falle: el vale ya
                    // existe y la cocina tiene que enterarse. La reimpresión NO pasa por aquí: no repite la comanda.
                    despacharComanda(ticket, intento.comanda, venueId)
                }
                ticket
            }.onSuccess { ticket ->
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = consumeOutputWarning() ?: "Vale ${ticket.code} emitido correctamente.",
                )
                ventaDelValePendiente = null
            }.onFailure { error ->
                // D2: un 409 `AREA_TICKET_IDEMPOTENCY_CONFLICT` NO rota la llave (antes sí: segundo vale con todo). El aviso
                // dice cómo salir: el carrito como estaba recupera ESE vale, y «Vaciar» suelta la llave.
                _state.value = _state.value.copy(
                    submitting = false,
                    error = areaTicketIssueFailureMessage(
                        error,
                        externalArea = _state.value.settings?.terminal?.fulfillmentArea?.settlementRoute == SETTLEMENT_ROUTE_EXTERNAL,
                    ),
                )
            }
        }
    }

    fun reprintPending(onIssued: (ventaQueEmitio: String?) -> Unit = {}) {
        val code = _state.value.pendingReprintCode ?: return
        if (_state.value.submitting || _state.value.preparingPdf) return
        val venueId = secureStorage.venueId
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                val resolution = repository.resolveCheckoutScan(code)
                val ticket = resolution.ticket
                    ?: throw IllegalStateException("No se encontró el vale $code para reimpresión.")
                printAndRecord(ticket, reprint = true, venueId)
                ticket
            }.onSuccess { ticket ->
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = consumeOutputWarning() ?: "Vale ${ticket.code} reimpreso correctamente.",
                )
                onIssued(ventaDelValePendiente.also { ventaDelValePendiente = null })
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo reimprimir el vale $code.",
                )
            }
        }
    }

    fun preparePendingPdf() {
        val code = _state.value.pendingReprintCode ?: return
        if (_state.value.submitting || _state.value.preparingPdf) return
        val venueId = secureStorage.venueId
        viewModelScope.launch {
            _state.value = _state.value.copy(preparingPdf = true, error = null)
            runCatching {
                val resolution = repository.resolveCheckoutScan(code)
                val ticket = resolution.ticket
                    ?: throw IllegalStateException("No se encontró el vale $code para exportar.")
                val bytes = withContext(Dispatchers.Default) {
                    pdfGenerator.generate(ticket.toPrintable(), configuredSymbology())
                }
                AreaTicketPdfExport(
                    ticketId = ticket.id,
                    code = ticket.code,
                    fileName = "vale-area-${ticket.code}.pdf",
                    bytes = bytes,
                    venueId = venueId,
                )
            }.onSuccess { export ->
                _state.value = _state.value.copy(
                    preparingPdf = false,
                    pdfExport = export,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    preparingPdf = false,
                    pdfExport = null,
                    error = error.message ?: "No se pudo preparar el PDF.",
                )
            }
        }
    }

    fun cancelPendingPdfExport() {
        _state.value = _state.value.copy(preparingPdf = false, pdfExport = null)
    }

    fun failPendingPdfExport(message: String) {
        _state.value = _state.value.copy(
            preparingPdf = false,
            pdfExport = null,
            error = message,
        )
    }

    fun confirmPendingPdfSaved(onIssued: (ventaQueEmitio: String?) -> Unit) {
        val export = _state.value.pdfExport ?: return
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                recordOutput(export.venueId, export.ticketId, export.code, reprint = false, reason = "Vale guardado como PDF por el operador.")
            }.onSuccess {
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    pdfExport = null,
                    message = consumeOutputWarning() ?: "Vale ${export.code} guardado como PDF.",
                )
                onIssued(ventaDelValePendiente.also { ventaDelValePendiente = null })
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    pdfExport = null,
                    error = error.message
                        ?: "El PDF se guardó, pero no se pudo confirmar la salida del vale.",
                )
            }
        }
    }

    fun deliverWithPaper(ticketId: String) {
        if (!_state.value.canConfirmDeliveryWithPaper) {
            _state.value = _state.value.copy(
                error = "Esta terminal no permite confirmar entregas con el vale impreso.",
                message = null,
            )
            return
        }
        deliver(ticketId, scannedReceipt = false)
    }

    fun deliverByReceiptCode(code: String) {
        if (_state.value.submitting) return
        if (!_state.value.canScanDeliveryReceipt) {
            _state.value = _state.value.copy(
                error = "Esta terminal no permite confirmar entregas escaneando el comprobante.",
                message = null,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null, message = null)
            val paidTickets = try {
                repository.resolveDelivery(code).tickets.filter { it.status == "PAID" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo comprobar la entrega.",
                )
                return@launch
            }
            if (paidTickets.isEmpty()) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "El comprobante no contiene productos pagados pendientes de entrega para esta área.",
                )
                return@launch
            }

            var deliveredCount = 0
            var alreadyDeliveredCount = 0
            var deliveryError: Exception? = null
            for (ticket in paidTickets) {
                try {
                    val result = repository.fulfill(ticket.id, scannedReceipt = true)
                    if (result.alreadyFulfilled) alreadyDeliveredCount += 1 else deliveredCount += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    deliveryError = error
                    break
                }
            }

            val errorMessage = deliveryFeedbackError(
                deliveredCount = deliveredCount,
                alreadyDeliveredCount = alreadyDeliveredCount,
                failure = deliveryError,
            )
            val successMessage = if (errorMessage == null) deliveredMessage(deliveredCount) else null
            finishDeliveryAfterRefresh(message = successMessage, error = errorMessage)
        }
    }

    /**
     * 🔴 D5 ⑤: pide el vale sin confirmar con SU llave y lo que se mandó, del intento más nuevo al más viejo (un 409 de llave
     * con otro contenido pasa al anterior). El SERVIDOR devuelve el que ya existía o lo crea si nunca llegó. Sale como un vale
     * recién emitido: papel, registro de impresión y comanda. Otro error (sin red) se detiene y el aviso se queda.
     * [onRecuperado] recibe el intento que respondió (`CartViewModel.alRecuperarVale`: su copia guardada y la venta en pantalla).
     */
    fun recuperarValeSinConfirmar(onRecuperado: (IntentoDeVale) -> Boolean = { true }) {
        if (_state.value.submitting) return
        val venueId = secureStorage.venueId
        val vale = cargarValeSinConfirmar(venueId) ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null, errorDelValeSinConfirmar = false)
            val respuesta = try {
                vale.intentos.asReversed().firstNotNullOfOrNull { intento ->
                    try {
                        repository.issue(intento.lineas, vale.llave) to intento
                    } catch (conflicto: AreaTicketException) {
                        if (conflicto.code != "AREA_TICKET_IDEMPOTENCY_CONFLICT") throw conflicto
                        null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fallaDelValeSinConfirmar(
                    areaTicketIssueFailureMessage(
                        error,
                        externalArea = _state.value.settings?.terminal?.fulfillmentArea?.settlementRoute == SETTLEMENT_ROUTE_EXTERNAL,
                    ),
                )
                return@launch
            }
            if (respuesta == null) {
                fallaDelValeSinConfirmar(VALE_NO_SE_PUDO_RECUPERAR)
                return@launch
            }
            val (ticket, intento) = respuesta
            valeQueYaNoSirve(ticket)?.let { motivo ->
                secureStorage.olvidarValeSinConfirmar(venueId.orEmpty(), vale.llave)
                _state.value = _state.value.copy(valeSinConfirmar = null, mostrarValeSinConfirmar = false)
                fallaDelValeSinConfirmar(motivo)
                return@launch
            }
            // Codex r3 #2: la copia guardada (D1) se retira ANTES de olvidar el registro, que es lo único que la liga al vale; si
            // no se pudo retirar (r4 #6), el registro se queda para reintentarlo.
            if (!onRecuperado(intento)) {
                fallaDelValeSinConfirmar("No se pudo retirar la copia guardada de este vale. Intenta de nuevo.")
                return@launch
            }
            // Codex r3 #1: el papel pendiente y olvidar el registro, en UN commit. Si no quedó, no se imprime: el registro
            // sigue y «Recuperar» otra vez trae el mismo vale.
            if (!secureStorage.valeRecuperado(venueId.orEmpty(), vale.llave, ticket.code)) {
                fallaDelValeSinConfirmar("No se pudo guardar en este aparato. Intenta de nuevo.")
                return@launch
            }
            _state.value = _state.value.copy(pendingReprintCode = ticket.code, valeSinConfirmar = null, mostrarValeSinConfirmar = false)
            ventaDelValePendiente = null
            // Codex r4 #4: el SERVIDOR dice si su papel ya salió (PRINTED no se deshace). Entonces no sale otro ORIGINAL ni otra
            // comanda: queda su reimpresión, como cualquier papel pendiente.
            if (ticket.printStatus == "PRINTED") {
                _state.value = _state.value.copy(submitting = false)
                return@launch
            }
            runCatching {
                try {
                    printAndRecord(ticket, reprint = false, venueId)
                } finally {
                    despacharComanda(ticket, intento.comanda, venueId)
                }
            }.onSuccess {
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = consumeOutputWarning() ?: "Vale ${ticket.code} recuperado.",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(submitting = false, error = error.message ?: "No se pudo imprimir el vale ${ticket.code}.")
            }
        }
    }

    private fun fallaDelValeSinConfirmar(mensaje: String) {
        _state.value = _state.value.copy(submitting = false, error = mensaje, errorDelValeSinConfirmar = true)
    }

    /** D5 ⑥: el cajero ya no lo quiere. Sin red. Si el vale sí se creó, queda en el SERVIDOR como hoy (cancelar puede dar merma). */
    fun descartarValeSinConfirmar() {
        if (_state.value.submitting) return
        val venueId = secureStorage.venueId.orEmpty()
        _state.value.valeSinConfirmar?.let { secureStorage.olvidarValeSinConfirmar(venueId, it.llave) }
        cargarValeSinConfirmar(venueId)
    }

    /** D5: el texto del aviso, con la hora del negocio. `null` si no hay vale sin confirmar. */
    fun avisoDeValeSinConfirmar(): String? = _state.value.valeSinConfirmar?.let { vale ->
        val hora = vale.intentos.last().hora.takeIf { it > 0 }?.let {
            com.avoqado.pos.core.util.VenueTimeZone.at(Instant.ofEpochMilli(it), secureStorage.venueTimezone)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
        avisoDeValeSinConfirmar(vale, hora)
    }

    /** D5: cerrar el aviso sin decidir; vuelve al tocar «Emitir vale». */
    fun ocultarValeSinConfirmar() {
        _state.value = _state.value.copy(mostrarValeSinConfirmar = false)
    }

    fun dismissFeedback() {
        _state.value = _state.value.copy(message = null, error = null, errorDelValeSinConfirmar = false)
    }

    /** El toast de éxito se cierra solo: no se lleva un aviso que llegó mientras tanto (una comanda que no salió). */
    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Codex r2 #5: el ViewModel sobrevive a la navegación; Checkout lo llama al volver a la pantalla. */
    fun retryPendingPrintRecords() {
        launchFlush()
    }

    private fun launchFlush(): Job =
        flushJob?.takeIf { it.isActive }
            ?: viewModelScope.launch { runCatching { flushPendingPrintRecords() } }.also { flushJob = it }

    fun dismissPendingReprint() {
        // Keep the persisted code so a process restart can offer recovery again.
        // Dismissing only releases the current screen after a printer outage.
        _state.value = _state.value.copy(
            preparingPdf = false,
            pendingReprintCode = null,
            pdfExport = null,
            error = null,
        )
    }

    private fun deliver(ticketId: String, scannedReceipt: Boolean) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null, message = null)
            val result = try {
                repository.fulfill(ticketId, scannedReceipt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finishDeliveryAfterRefresh(
                    message = null,
                    error = error.message ?: "No se pudo registrar la entrega.",
                )
                return@launch
            }
            if (result.alreadyFulfilled) {
                finishDeliveryAfterRefresh(
                    message = null,
                    error = "Este vale ya había sido entregado por otra terminal. Verifica los productos antes de continuar.",
                )
            } else {
                finishDeliveryAfterRefresh(message = "Entrega registrada.", error = null)
            }
        }
    }

    private suspend fun finishDeliveryAfterRefresh(message: String?, error: String?) {
        refresh(loadPendingDelivery = true).join()
        val refreshError = _state.value.error
        val finalError = when {
            error != null && refreshError != null ->
                "$error No se pudo actualizar la lista: $refreshError Verifica los productos antes de continuar."
            error != null ->
                "$error La lista fue actualizada; verifica los productos antes de continuar."
            refreshError != null ->
                if (message == null) refreshError else "$message No se pudo actualizar la lista: $refreshError"
            else -> null
        }
        _state.value = _state.value.copy(
            submitting = false,
            message = message.takeIf { finalError == null },
            error = finalError,
        )
    }

    private fun deliveredMessage(count: Int): String =
        "$count ${if (count == 1) "vale entregado" else "vales entregados"}."

    private fun deliveryFeedbackError(
        deliveredCount: Int,
        alreadyDeliveredCount: Int,
        failure: Exception?,
    ): String? {
        if (alreadyDeliveredCount == 0 && failure == null) return null
        val parts = mutableListOf<String>()
        if (deliveredCount > 0) parts += deliveredMessage(deliveredCount)
        if (alreadyDeliveredCount > 0) {
            parts += "$alreadyDeliveredCount ${if (alreadyDeliveredCount == 1) "vale ya había sido entregado" else "vales ya habían sido entregados"} por otra terminal."
        }
        if (failure != null) {
            parts += "No se pudo completar el resto: ${failure.message ?: "No se pudo registrar la entrega."}"
        }
        return parts.joinToString(" ")
    }

    /**
     * La comanda del vale (§5.6): sale al EMITIR en las áreas que preparan antes de que el cliente pague —eso lo decide
     * [com.avoqado.pos.core.domain.printing.AreaComandaPolicy]— y se rutea por estación igual que la del mostrador
     * (Cocina, Bebidas…). Corre aparte: con reintentos tarda hasta ~1 min y jamás frena el vale. Una que se rindió
     * se DICE, sin tapar el aviso del vale si lo hay.
     */
    private fun despacharComanda(ticket: AreaTicket, renglones: List<RenglonDeComandaDeVale>, venueId: String?) {
        // D5: de los renglones guardados con el intento (con su categoría, Codex r2 #3), no del carrito vivo: «Recuperar» no tiene.
        val lineas = renglones.map { renglon ->
            RoutableItem(
                orderItemId = renglon.id,
                productId = renglon.productId,
                categoryId = renglon.categoryId,
                productName = renglon.nombre,
                quantity = renglon.cantidad,
                modifiers = renglon.modificadores,
                notes = renglon.notas,
            )
        }
        val avisar: (String?, List<String>) -> Unit = { causa, estaciones ->
            val aviso = "No salió la comanda de ${estaciones.joinToString()} del vale ${ticket.code}. " +
                (causa ?: "La impresora no respondió.") + " Avísale a la cocina."
            _state.value = _state.value.copy(error = listOfNotNull(_state.value.error, aviso).joinToString("\n\n"))
        }
        comandaScope.launch {
            try {
                comandaDispatcher.dispatchAreaComanda(
                    venueId = venueId,
                    lines = lineas,
                    areaTicketCode = ticket.code,
                    areaName = ticket.fulfillmentArea.name,
                    mode = FulfillmentMode.fromServer(ticket.fulfillmentArea.fulfillmentMode),
                    moment = ComandaMoment.AREA_TICKET_ISSUED,
                    alCambiarEstado = { estado -> if (estado is EstadoDeComanda.NoSalio) avisar(estado.causa, estado.estaciones) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("🍳", "❌ La comanda del vale ${ticket.code} reventó al despacharse: ${e.message}", e)
                avisar(e.message, listOf("Cocina"))
            }
        }
    }

    private suspend fun printAndRecord(ticket: AreaTicket, reprint: Boolean, venueId: String?) {
        val printer = printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT)
        if (printer == null) {
            recordFailedPrint(ticket, reprint, "No hay impresora de recibos configurada.", "PRINTER_NOT_CONFIGURED")
            throw IllegalStateException(
                areaTicketPrintFailureMessage(
                    code = ticket.code,
                    error = IllegalStateException("No hay impresora configurada."),
                ),
            )
        }
        val printable = ticket.toPrintable(reprint)
        runCatching {
            printerService.printAreaTicket(printable, printer, configuredSymbology())
        }
            .onSuccess { recordOutput(venueId, ticket.id, ticket.code, reprint) }
            .onFailure { error ->
                recordFailedPrint(ticket, reprint, error.message, "PRINT_FAILED")
                throw IllegalStateException(
                    areaTicketPrintFailureMessage(ticket.code, error),
                    error,
                )
            }
    }

    /**
     * P1 de duplicado (Task 12): registrar un intento FALLIDO es auditoría y nunca tapa la falla de impresión. Si se
     * colara su error de red, en caja externa la pantalla diría «no se puede emitir» de un vale YA emitido, y con la
     * llave ya rotada, volver a emitir crearía un segundo vale.
     */
    private suspend fun recordFailedPrint(ticket: AreaTicket, reprint: Boolean, reason: String?, errorCode: String) {
        runCatching {
            repository.recordPrint(ticket.id, printed = false, reprint = reprint, reason = reason, errorCode = errorCode)
        }.onFailure { android.util.Log.w(TAG, "No se pudo registrar el intento fallido del vale ${ticket.code}", it) }
    }

    /**
     * D12: la salida (papel o PDF) ya ocurrió. Primero disco —la lista y soltar el pendiente de reimpresión, en UN
     * commit—, después red. Un registro que falla no es una impresión que falló: sólo lanza si no hay negocio.
     *
     * [venueId] es el del INICIO de la operación. Un registro sin negocio nunca se mandaría (la cola se filtra por
     * negocio) y ya habría soltado la reimpresión: en ese caso no se guarda y queda lo de antes —el error de «sin local»
     * y la reimpresión ofrecida—.
     */
    private suspend fun recordOutput(venueId: String?, ticketId: String, code: String, reprint: Boolean, reason: String? = null) {
        if (venueId.isNullOrBlank()) {
            throw AreaTicketException("VENUE_REQUIRED", "Selecciona un local antes de continuar.", false)
        }
        val record = PendingAreaTicketPrintRecord(
            venueId = venueId,
            ticketId = ticketId,
            code = code,
            reprint = reprint,
            idempotencyKey = UUID.randomUUID().toString(),
            reason = reason,
        )
        val persisted = commitPendingRecords(readPendingRecords() + record, clearPendingReprint = true)
        _state.value = _state.value.copy(pendingReprintCode = null)
        if (persisted) {
            flushWithinBudget()
            return
        }
        // Codex r3 #2: el disco no lo guardó y no se finge. Se suelta el pendiente de reimpresión ANTES de la red (si
        // la app muere esperando, al volver tampoco ofrece un segundo papel), se manda ya con SU llave y se avisa.
        secureStorage.pendingAreaTicketPrintCode = null
        secureStorage.pendingAreaTicketPrintVenueId = null
        runCatching {
            repository.recordPrint(ticketId, printed = true, reprint = reprint, reason = reason, idempotencyKey = record.idempotencyKey)
        }
        outputWarning = "El vale salió, pero este aparato no pudo guardar su registro. No lo reimprimas."
    }

    private fun consumeOutputWarning(): String? = outputWarning.also { outputWarning = null }

    private fun readPendingRecords(): List<PendingAreaTicketPrintRecord> =
        secureStorage.pendingAreaTicketPrintRecords
            ?.let { raw -> runCatching { pendingRecordsJson.decodeFromString(pendingRecordsSerializer, raw) }.getOrNull() }
            .orEmpty()

    /** @return false si el disco no aceptó la escritura (Codex r3 #2: no se finge persistencia). */
    private fun commitPendingRecords(records: List<PendingAreaTicketPrintRecord>, clearPendingReprint: Boolean): Boolean {
        val json = records.takeIf { it.isNotEmpty() }?.let { pendingRecordsJson.encodeToString(pendingRecordsSerializer, it) }
        return secureStorage.commitAreaTicketPrintRecords(json, clearPendingReprint).also { ok ->
            if (!ok) android.util.Log.w(TAG, "No se pudo guardar en disco el registro de impresión pendiente")
        }
    }

    /**
     * Lo que espera el cajero —antes de emitir y después del papel— va con tope: con WiFi sin salida cada registro tarda
     * un timeout entero de OkHttp (30 s), y la espera incluye la de un reintento que ya iba. Al vencer el tope se suelta
     * la ESPERA, no el envío (Codex final #2): el envío compartido sigue y también manda lo que estaba detrás de una
     * cabeza lenta. Nada se borra por cortar: lo pendiente sigue en disco.
     */
    private suspend fun flushWithinBudget() {
        withTimeoutOrNull(FLUSH_BUDGET_MS) { launchFlush().join() }
    }

    /**
     * Manda cada pendiente de ESTE negocio con SU llave. Se borra sólo al lograrlo o si el vale ya no existe.
     * Relee el disco en cada vuelta: lo que `recordOutput` agrega mientras este envío sigue vivo sale en la misma
     * corrida. Cada registro se intenta una vez por corrida; el que falla espera al siguiente reintento.
     */
    private suspend fun flushPendingPrintRecords() = flushMutex.withLock {
        val venueId = secureStorage.venueId ?: return@withLock
        val intentados = mutableSetOf<String>()
        while (true) {
            val record = readPendingRecords()
                .firstOrNull { it.venueId == venueId && it.idempotencyKey !in intentados } ?: break
            intentados += record.idempotencyKey
            // Codex r3 #1: el repositorio lee el negocio en cada petición; si cambió a media cola, se detiene.
            if (secureStorage.venueId != record.venueId) break
            val done = try {
                repository.recordPrint(
                    record.ticketId,
                    printed = true,
                    reprint = record.reprint,
                    reason = record.reason,
                    idempotencyKey = record.idempotencyKey,
                )
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: AreaTicketException) {
                // Sólo «el vale ya no existe» borra, y sólo si se preguntó al MISMO negocio. Sesión, permisos, 5xx o red: se conserva.
                error.code == "AREA_TICKET_NOT_FOUND" && secureStorage.venueId == record.venueId
            } catch (error: Exception) {
                false
            }
            if (done) {
                commitPendingRecords(readPendingRecords().filterNot { it.idempotencyKey == record.idempotencyKey }, clearPendingReprint = false)
            } else {
                android.util.Log.w(TAG, "Registro de impresión del vale ${record.code} sigue pendiente")
            }
        }
    }

    private fun AreaTicket.toPrintable(reprint: Boolean = false) = toAreaTicketData(secureStorage.venueDisplayName, isReprint = reprint)

    private fun configuredSymbology() =
        when (_state.value.settings?.areaTickets?.codeSymbology) {
            "CODE39" -> BarcodeSymbology.CODE39
            else -> BarcodeSymbology.CODE128_C
        }

    private companion object {
        const val TAG = "AreaTicketOps"
        const val FLUSH_BUDGET_MS = 5_000L
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun areaTicketPrintFailureMessage(code: String, error: Throwable): String {
    // Conserva el detalle técnico en la causa/auditoría, no en la pantalla del
    // operador. En todos los casos el vale ya existe y reintentar no debe emitirlo.
    return "No pudimos imprimir el vale $code, pero ya quedó creado y pendiente de reimpresión. " +
        "Verifica que la impresora esté encendida y conectada a la misma red, o configúrala en Más → Impresora. " +
        "También puedes guardarlo como PDF."
}

/** 🔴 D5: el proceso de la app. La primera vez que ve un negocio, lo que quedó a medias pasa a «sin confirmar». */
internal object ValesRevisadosEnEsteProceso {
    private val negocios = mutableSetOf<String>()

    @Synchronized fun primeraVez(venueId: String): Boolean = negocios.add(venueId)

    /** Las pruebas simulan así que la app se cerró y se volvió a abrir. */
    @Synchronized fun olvidar() = negocios.clear()
}

internal const val VALE_CAMBIO_DEMASIADO =
    "Este vale cambió demasiadas veces sin confirmarse. Vacía el carrito para empezar otro."
internal const val VALE_NO_SE_PUDO_RECUPERAR =
    "No se pudo recuperar: Avoqado tiene ese vale con otros productos. Descártalo y vuelve a armar el carrito."

/** D5: mismo texto en iOS (`avisoDeValeSinConfirmar`). */
internal fun avisoDeValeSinConfirmar(vale: ValeSinConfirmar, hora: String?): String {
    val productos = vale.intentos.last().lineas.size.let { if (it == 1) "1 producto" else "$it productos" }
    return "Un vale quedó sin confirmar ($productos${hora?.let { ", $it" } ?: ""}): no se sabe si salió. " +
        "«Recuperar» lo trae sin duplicarlo; si nunca llegó, lo crea ahora."
}

/** D5: lo que se manda al pedir el vale de [cart], armado una sola vez (es lo que se guarda con la llave). */
internal fun intentoDeVale(cart: CartState, ahora: Long = System.currentTimeMillis()): IntentoDeVale = IntentoDeVale(
    lineas = cart.items.map { item ->
        IssueAreaTicketLineRequest(
            clientLineId = item.id.ifBlank { UUID.randomUUID().toString() },
            productId = (item.type as CartItemType.ProductItem).productId,
            quantity = item.quantity.toString(),
            weightKg = item.weightKg?.let { String.format(Locale.US, "%.3f", it) },
            notes = item.itemNote,
            modifierIds = item.selectedModifiers.map { it.modifierId },
            discountId = item.itemDiscountId,
        )
    },
    comanda = cart.items.map { item ->
        RenglonDeComandaDeVale(
            id = item.id,
            productId = (item.type as? CartItemType.ProductItem)?.productId,
            categoryId = item.categoryId,
            nombre = item.nombreEnCocina,
            cantidad = item.quantity,
            modificadores = item.selectedModifiers.map { it.modifierName },
            notas = item.itemNote,
        )
    },
    carritoGuardadoId = cart.carritoGuardadoId,
    hora = ahora,
)

/**
 * 🔴 D5 (Codex r1 #5): la misma llave devuelve el vale que ya existía, en el estado que tenga hoy. Uno vencido, cancelado o
 * cobrado no se imprime como si fuera nuevo. `null` = sirve. Un estado de paso (CLAIMED) o uno que esta versión no conoce no se
 * frena (como hoy).
 */
internal fun valeQueYaNoSirve(ticket: AreaTicket, ahora: Instant = Instant.now()): String? {
    val vencido = ticket.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { !it.isAfter(ahora) } == true
    val estado = when (ticket.status) {
        "ISSUED" -> if (vencido) "vencido" else return null
        "EXPIRED" -> "vencido"
        "CANCELLED" -> "cancelado"
        "PAID" -> "cobrado"
        "DELIVERED" -> "entregado"
        // CLAIMED (en un cobro de vales) es de paso: el papel es el mismo código que la caja ya tiene.
        else -> return null
    }
    return "El vale ${ticket.code} ya está $estado: no se imprime."
}

/**
 * D13: lo que el vale NO puede representar. El vale sólo manda producto, cantidad, peso, nota, extras y
 * descuento por renglón (su id de catálogo); todo lo demás —cortesía, precio manual, promoción y lo de la
 * cuenta: descuento, premio, impuesto agregado— se perdería en silencio y la caja cobraría otro importe.
 * Espejo (ampliado) de la guarda de iOS (`CheckoutView.issueAreaTicket`).
 */
internal fun areaTicketIssueBlocker(cart: CartState): String? {
    val renglon = cart.items.any { it.isCortesia || it.priceAdjustment != null || it.promotionInstanceId != null }
    val cuenta = cart.orderDiscount != null || cart.pendingStampReward != null || cart.orderTaxPercent != null
    return if (renglon || cuenta) {
        "El vale sólo puede llevar productos del área a su precio. Quita cortesías, precios manuales, " +
            "promociones, descuentos de cuenta, impuestos agregados o premios; se aplican al cobrar."
    } else {
        null
    }
}

internal fun areaTicketIssueFailureMessage(error: Throwable, externalArea: Boolean): String {
    val code = (error as? AreaTicketException)?.code
    return when {
        // D2: la llave ya creó un vale con otro contenido (su respuesta se perdió y el carrito cambió). Mismo texto en iOS.
        code == "AREA_TICKET_IDEMPOTENCY_CONFLICT" ->
            "Este vale ya se creó con otro contenido. Deja el carrito como estaba para recuperarlo, o vacíalo."
        // Sin red en caja externa: decir qué hacer, no prometer «el POS normal» (ahí cobra otra caja).
        externalArea && code == "AREA_TICKETS_REQUIRE_CONNECTION" ->
            "Sin conexión con Avoqado no se puede emitir el vale. Reintenta cuando vuelva la conexión; " +
                "mientras, la caja principal puede capturar los productos a mano."
        else -> error.message ?: "No se pudo emitir el vale."
    }
}
