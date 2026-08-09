package com.avoqado.pos.areatickets.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.data.moneyToCents
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AreaTicketPdfExport(
    val ticketId: String,
    val code: String,
    val fileName: String,
    val bytes: ByteArray,
)

data class AreaTicketOperationsState(
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val preparingPdf: Boolean = false,
    val settings: AreaTicketSettingsData? = null,
    val pending: List<AreaTicket> = emptyList(),
    val pendingReprintCode: String? = null,
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

    val checkoutBlockingError: String?
        get() = error.takeIf { settings != null || pendingReprintCode != null }
}

@HiltViewModel
class AreaTicketOperationsViewModel @Inject constructor(
    private val repository: AreaTicketRepository,
    private val printerService: PrinterService,
    private val pdfGenerator: AreaTicketPdfGenerator,
    private val secureStorage: SecureStorage,
) : ViewModel() {
    private val _state = MutableStateFlow(AreaTicketOperationsState())
    val state: StateFlow<AreaTicketOperationsState> = _state.asStateFlow()
    private var refreshRequestId = 0L
    private var issueIdempotencyKey = secureStorage.pendingAreaTicketIssueKey
        ?: UUID.randomUUID().toString().also { secureStorage.pendingAreaTicketIssueKey = it }

    init {
        _state.value = _state.value.copy(
            pendingReprintCode = secureStorage.pendingAreaTicketPrintCode.takeIf {
                val pendingVenue = secureStorage.pendingAreaTicketPrintVenueId
                pendingVenue == null || pendingVenue == secureStorage.venueId
            },
        )
        refresh()
    }

    fun refresh(loadPendingDelivery: Boolean = false): Job {
        val requestId = ++refreshRequestId
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

    fun issue(cart: CartState, onIssued: () -> Unit) {
        if (_state.value.submitting) return
        val invalid = cart.items.any { it.locked || it.type !is CartItemType.ProductItem }
        if (cart.items.isEmpty() || invalid) {
            _state.value = _state.value.copy(
                error = "El vale sólo puede incluir productos del área; quita importes libres, membresías o vales escaneados.",
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                val lines = cart.items.map { item ->
                    IssueAreaTicketLineRequest(
                        clientLineId = item.id.ifBlank { UUID.randomUUID().toString() },
                        productId = (item.type as CartItemType.ProductItem).productId,
                        quantity = item.quantity.toString(),
                        weightKg = item.weightKg?.let { String.format(Locale.US, "%.3f", it) },
                        notes = item.itemNote,
                        modifierIds = item.selectedModifiers.map { it.modifierId },
                        discountId = item.itemDiscountId,
                    )
                }
                val ticket = repository.issue(lines, issueIdempotencyKey)
                // Primero persiste la recuperación: si la app muere al limpiar el
                // carrito, el mismo vale todavía puede reimprimirse al volver.
                secureStorage.pendingAreaTicketPrintCode = ticket.code
                secureStorage.pendingAreaTicketPrintVenueId = secureStorage.venueId
                _state.value = _state.value.copy(pendingReprintCode = ticket.code)
                // La llave protege la creación, no la impresión. Una vez que el
                // servidor aceptó este vale, el siguiente carrito necesita otra
                // llave aunque todavía quede pendiente reimprimir el papel.
                issueIdempotencyKey = UUID.randomUUID().toString()
                secureStorage.pendingAreaTicketIssueKey = issueIdempotencyKey
                // El vale ya quedó emitido en servidor. Desde este punto el carrito no
                // puede seguir cobrable aunque falle la salida en papel: imprimir es una
                // recuperación secundaria del mismo vale, no parte de su creación.
                onIssued()
                printAndRecord(ticket, reprint = false)
                ticket
            }.onSuccess { ticket ->
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = "Vale ${ticket.code} emitido correctamente.",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo emitir el vale.",
                )
            }
        }
    }

    fun reprintPending(onIssued: () -> Unit = {}) {
        val code = _state.value.pendingReprintCode ?: return
        if (_state.value.submitting || _state.value.preparingPdf) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                val resolution = repository.resolveCheckoutScan(code)
                val ticket = resolution.ticket
                    ?: throw IllegalStateException("No se encontró el vale $code para reimpresión.")
                printAndRecord(ticket, reprint = true)
                ticket
            }.onSuccess { ticket ->
                issueIdempotencyKey = UUID.randomUUID().toString()
                secureStorage.pendingAreaTicketIssueKey = issueIdempotencyKey
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = "Vale ${ticket.code} reimpreso correctamente.",
                )
                onIssued()
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

    fun confirmPendingPdfSaved(onIssued: () -> Unit) {
        val export = _state.value.pdfExport ?: return
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                repository.recordPrint(
                    ticketId = export.ticketId,
                    printed = true,
                    reprint = false,
                    reason = "Vale guardado como PDF por el operador.",
                )
            }.onSuccess {
                issueIdempotencyKey = UUID.randomUUID().toString()
                secureStorage.pendingAreaTicketIssueKey = issueIdempotencyKey
                secureStorage.pendingAreaTicketPrintCode = null
                secureStorage.pendingAreaTicketPrintVenueId = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    pdfExport = null,
                    message = "Vale ${export.code} guardado como PDF.",
                )
                onIssued()
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

    fun dismissFeedback() {
        _state.value = _state.value.copy(message = null, error = null)
    }

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

    private suspend fun printAndRecord(ticket: AreaTicket, reprint: Boolean) {
        val printer = printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT)
        if (printer == null) {
            repository.recordPrint(
                ticketId = ticket.id,
                printed = false,
                reprint = reprint,
                reason = "No hay impresora de recibos configurada.",
                errorCode = "PRINTER_NOT_CONFIGURED",
            )
            throw IllegalStateException(
                areaTicketPrintFailureMessage(
                    code = ticket.code,
                    error = IllegalStateException("No hay impresora configurada."),
                ),
            )
        }
        val printable = ticket.toPrintable()
        runCatching {
            printerService.printAreaTicket(printable, printer, configuredSymbology())
        }
            .onSuccess { repository.recordPrint(ticket.id, printed = true, reprint = reprint) }
            .onFailure { error ->
                repository.recordPrint(
                    ticket.id,
                    printed = false,
                    reprint = reprint,
                    reason = error.message,
                    errorCode = "PRINT_FAILED",
                )
                throw IllegalStateException(
                    areaTicketPrintFailureMessage(ticket.code, error),
                    error,
                )
            }
    }

    private fun AreaTicket.toPrintable() = AreaTicketData(
        areaTicketCode = code,
        areaName = fulfillmentArea.name,
        items = lines.map { line ->
            ReceiptItem(
                name = line.productNameSnapshot,
                quantity = line.quantity.toBigDecimalOrNull()?.toInt() ?: 1,
                unitPrice = line.unitPrice.moneyToCents(),
                totalPrice = line.total.moneyToCents(),
                note = line.notes,
                weightSummary = line.weightKg?.let {
                    "$it kg × $${String.format(Locale.US, "%.2f", line.unitPrice.toDoubleOrNull() ?: 0.0)}/kg"
                },
            )
        },
        totalCents = total.moneyToCents(),
        venueName = secureStorage.venueDisplayName,
        timestamp = runCatching { Date.from(Instant.parse(issuedAt)) }.getOrDefault(Date()),
        holdsProduct = fulfillmentArea.fulfillmentMode == "HOLD_UNTIL_PAID",
    )

    private fun configuredSymbology() =
        when (_state.value.settings?.areaTickets?.codeSymbology) {
            "CODE39" -> BarcodeSymbology.CODE39
            else -> BarcodeSymbology.CODE128_C
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
