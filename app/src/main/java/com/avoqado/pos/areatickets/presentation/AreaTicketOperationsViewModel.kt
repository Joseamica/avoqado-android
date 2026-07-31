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
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AreaTicketOperationsState(
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val settings: AreaTicketSettingsData? = null,
    val pending: List<AreaTicket> = emptyList(),
    val pendingReprintCode: String? = null,
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

    val checkoutBlockingError: String?
        get() = error.takeIf { settings != null || pendingReprintCode != null }
}

@HiltViewModel
class AreaTicketOperationsViewModel @Inject constructor(
    private val repository: AreaTicketRepository,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
) : ViewModel() {
    private val _state = MutableStateFlow(AreaTicketOperationsState())
    val state: StateFlow<AreaTicketOperationsState> = _state.asStateFlow()
    private var issueIdempotencyKey = secureStorage.pendingAreaTicketIssueKey
        ?: UUID.randomUUID().toString().also { secureStorage.pendingAreaTicketIssueKey = it }

    init {
        _state.value = _state.value.copy(
            pendingReprintCode = secureStorage.pendingAreaTicketPrintCode,
        )
        refresh()
    }

    fun refresh(loadPendingDelivery: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val settings = runCatching { repository.settings() }
                .getOrElse { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "No se pudo cargar la operación de vales.",
                    )
                    return@launch
                }
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
            runCatching { repository.pendingDelivery().tickets }
                .onSuccess { pending ->
                    _state.value = _state.value.copy(
                        loading = false,
                        settings = settings,
                        pending = pending,
                        error = null,
                    )
                }
                .onFailure { error ->
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
                secureStorage.pendingAreaTicketPrintCode = ticket.code
                _state.value = _state.value.copy(pendingReprintCode = ticket.code)
                printAndRecord(ticket, reprint = false)
                ticket
            }.onSuccess { ticket ->
                issueIdempotencyKey = UUID.randomUUID().toString()
                secureStorage.pendingAreaTicketIssueKey = issueIdempotencyKey
                secureStorage.pendingAreaTicketPrintCode = null
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = "Vale ${ticket.code} emitido correctamente.",
                )
                onIssued()
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo emitir el vale.",
                )
            }
        }
    }

    fun reprintPending() {
        val code = _state.value.pendingReprintCode ?: return
        if (_state.value.submitting) return
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
                _state.value = _state.value.copy(
                    submitting = false,
                    pendingReprintCode = null,
                    message = "Vale ${ticket.code} reimpreso correctamente.",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo reimprimir el vale $code.",
                )
            }
        }
    }

    fun deliverWithPaper(ticketId: String) {
        deliver(ticketId, scannedReceipt = false)
    }

    fun deliverByReceiptCode(code: String) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching {
                val resolution = repository.resolveDelivery(code)
                resolution.tickets
                    .filter { it.status == "PAID" }
                    .forEach { repository.fulfill(it.id, scannedReceipt = true) }
                resolution.tickets.size
            }.onSuccess { count ->
                _state.value = _state.value.copy(
                    submitting = false,
                    message = "$count ${if (count == 1) "vale entregado" else "vales entregados"}.",
                )
                refresh(loadPendingDelivery = true)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.message ?: "No se pudo comprobar la entrega.",
                )
            }
        }
    }

    fun dismissFeedback() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    fun dismissPendingReprint() {
        // Keep the persisted code so a process restart can offer recovery again.
        // Dismissing only releases the current screen after a printer outage.
        _state.value = _state.value.copy(pendingReprintCode = null, error = null)
    }

    private fun deliver(ticketId: String, scannedReceipt: Boolean) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            runCatching { repository.fulfill(ticketId, scannedReceipt) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        submitting = false,
                        message = "Entrega registrada.",
                    )
                    refresh(loadPendingDelivery = true)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        error = error.message ?: "No se pudo registrar la entrega.",
                    )
                }
        }
    }

    private suspend fun printAndRecord(ticket: AreaTicket, reprint: Boolean) {
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT)
        if (printer == null) {
            repository.recordPrint(
                ticketId = ticket.id,
                printed = false,
                reprint = reprint,
                reason = "No hay impresora de recibos configurada.",
                errorCode = "PRINTER_NOT_CONFIGURED",
            )
            throw IllegalStateException("El vale fue creado, pero no hay impresora configurada. Reimprime el vale ${ticket.code} antes de entregarlo.")
        }
        val printable = AreaTicketData(
            areaTicketCode = ticket.code,
            areaName = ticket.fulfillmentArea.name,
            items = ticket.lines.map { line ->
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
            totalCents = ticket.total.moneyToCents(),
            venueName = secureStorage.venueDisplayName,
            holdsProduct = ticket.fulfillmentArea.fulfillmentMode == "HOLD_UNTIL_PAID",
        )
        val symbology = when (_state.value.settings?.areaTickets?.codeSymbology) {
            "CODE39" -> BarcodeSymbology.CODE39
            else -> BarcodeSymbology.CODE128_C
        }
        runCatching { printerService.printAreaTicket(printable, printer, symbology) }
            .onSuccess { repository.recordPrint(ticket.id, printed = true, reprint = reprint) }
            .onFailure { error ->
                repository.recordPrint(
                    ticket.id,
                    printed = false,
                    reprint = reprint,
                    reason = error.message,
                    errorCode = "PRINT_FAILED",
                )
                throw error
            }
    }
}
