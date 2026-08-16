package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.transactions.data.RefundItem
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.AmountOperator
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionActiveFilter
import com.avoqado.pos.transactions.data.model.TransactionFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

// MARK: - Refund UI State

sealed interface RefundUiState {
    data object Idle : RefundUiState
    data object Loading : RefundUiState
    data class Success(val message: String) : RefundUiState
    data class Error(val message: String) : RefundUiState
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    val refundRepository: RefundRepository,
    val cashDrawerRepository: CashDrawerRepository,
    /** Para abrir la devolución de un cobro con tarjeta en una terminal física. */
    val terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
    val roleManager: RoleManager,
    /**
     * Sólo para saber si el local activó el PIN de autorización de gerente: es
     * lo que decide si "Emitir reembolso" se esconde (como hoy) o se ve con
     * candado para que el 403 pueda abrir el teclado.
     */
    val tpvSettingsRepository: com.avoqado.pos.tpvsettings.data.TpvSettingsRepository,
    private val orderRepository: OrderRepository,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    refreshGateFactory: RefreshGateFactory,
) : ViewModel() {

    val transactions = repository.transactions
    val isLoading = repository.isLoading
    val isLoadingMore = repository.isLoadingMore

    // Search
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    // Filters
    private val _filters = MutableStateFlow(TransactionFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    private val _activeFilter = MutableStateFlow<TransactionActiveFilter?>(null)
    val activeFilter: StateFlow<TransactionActiveFilter?> = _activeFilter.asStateFlow()

    // Detail
    private val _selectedTransactionId = MutableStateFlow<String?>(null)
    val selectedTransactionId: StateFlow<String?> = _selectedTransactionId.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    // Print receipt (Ventas → detalle → "Imprimir")
    private val _isPrintingReceipt = MutableStateFlow(false)
    val isPrintingReceipt: StateFlow<Boolean> = _isPrintingReceipt.asStateFlow()

    private val _printReceiptResult = MutableStateFlow<String?>(null)
    val printReceiptResult: StateFlow<String?> = _printReceiptResult.asStateFlow()

    // Refund
    private val _showRefundSheet = MutableStateFlow(false)
    val showRefundSheet: StateFlow<Boolean> = _showRefundSheet.asStateFlow()

    private val _refundState = MutableStateFlow<RefundUiState>(RefundUiState.Idle)
    val refundState: StateFlow<RefundUiState> = _refundState.asStateFlow()

    private val gate = refreshGateFactory.create(viewModelScope)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    private val _detailRefundActive = MutableStateFlow(false)

    /** Espejo del refundFlowActive de iOS: la vista del detalle reporta aquí su sheet local. */
    fun setRefundFlowActive(active: Boolean) {
        _detailRefundActive.value = active
    }

    /** Guard §4.5 — dinero: con la devolución abierta (lista o detalle) o corriendo, ni el gesto refresca. */
    private fun workInProgress(): Boolean =
        _showRefundSheet.value || _refundState.value is RefundUiState.Loading || _detailRefundActive.value

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> =
        repository.fetchTransactions(page = 1, search = _searchText.value.ifBlank { null })

    fun autoRefresh() {
        viewModelScope.launch {
            gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            _isManualRefreshing.value = true
            try {
                gate.run(workInProgress = ::workInProgress, manual = true, block = ::refreshNow)
            } finally {
                _isManualRefreshing.value = false
            }
        }
    }

    /** Mutación local (devolución, etc.) = identidad nueva: invalida el TTL y re-pide (spec §4.4).
     *  Sin guard de busy: es post-mutación — recargar la lista no pisa ningún borrador. */
    fun invalidateAndRefresh() {
        gate.invalidate()
        viewModelScope.launch {
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    init {
        observeSearch()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchText
                .drop(1) // la emisión inicial cruda no es una búsqueda del usuario
                .debounce(400)
                .distinctUntilChanged()
                .collect {
                    gate.invalidate()
                    gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
                }
        }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun loadMoreIfNeeded(currentItem: Transaction) {
        val list = transactions.value
        if (list.isEmpty()) return
        if (currentItem.id != list.last().id) return
        if (!repository.canLoadMore()) return

        viewModelScope.launch {
            repository.fetchTransactions(
                page = repository.getNextPage(),
                search = _searchText.value.ifBlank { null },
            )
        }
    }

    // MARK: - Filtered + grouped transactions

    /** Apply client-side filters to the loaded list */
    val filteredTransactions: List<Transaction>
        get() {
            val list = transactions.value
            return if (_filters.value.hasActiveFilters) {
                _filters.value.apply(list)
            } else {
                list
            }
        }

    /** Grouped by date for display */
    val filteredGroupedTransactions: List<Pair<String, List<Transaction>>>
        get() = filteredTransactions
            .groupBy { it.dateGroup }
            .toList()

    // MARK: - Filters

    fun setActiveFilter(filter: TransactionActiveFilter?) {
        _activeFilter.value = filter
    }

    fun updateFilters(newFilters: TransactionFilters) {
        _filters.value = newFilters
    }

    fun clearAllFilters() {
        _filters.value = TransactionFilters()
    }

    // MARK: - Dynamic filter options (built from loaded data)

    fun methodOptions(): List<Pair<String, String>> {
        val methods = transactions.value.mapNotNull { it.method }.toSet()
        return methods.sorted().map { m ->
            when (m) {
                "CASH" -> m to "Efectivo"
                "CARD" -> m to "Tarjeta"
                "CREDIT_CARD" -> m to "Tarjeta de crédito"
                "DEBIT_CARD" -> m to "Tarjeta de débito"
                "TRANSFER" -> m to "Transferencia"
                else -> m to m.replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun staffOptions(): List<Pair<String, String>> {
        val names = transactions.value
            .mapNotNull { it.staffName }
            .filter { it.isNotEmpty() }
            .toSet()
        return names.sorted().map { it to it }
    }

    // MARK: - Transaction Selection

    fun selectTransaction(transactionId: String) {
        _selectedTransactionId.value = transactionId
        _isLoadingDetail.value = true
        _selectedTransaction.value = null
        // Don't carry the previous sale's "Recibo impreso" message into this one.
        _printReceiptResult.value = null

        viewModelScope.launch {
            val detail = repository.fetchTransactionDetail(transactionId)
            _selectedTransaction.value = detail
            _isLoadingDetail.value = false
        }
    }

    fun clearSelection() {
        _selectedTransactionId.value = null
        _selectedTransaction.value = null
        _printReceiptResult.value = null
    }

    // MARK: - Print Receipt

    /** Reprints a past sale's receipt on the configured RECEIPT printer(s) —
     *  same manualPrintReceipt path the payment-success screen uses. */
    fun printTransactionReceipt(transaction: Transaction) {
        viewModelScope.launch {
            _isPrintingReceipt.value = true
            _printReceiptResult.value = null
            try {
                _printReceiptResult.value =
                    when (val outcome = printerService.manualPrintReceipt(transaction.toReceiptData(venueName))) {
                        is PrinterService.PrintOutcome.Printed -> "Recibo impreso"
                        is PrinterService.PrintOutcome.OutOfPaper -> "La impresora no tiene papel"
                        is PrinterService.PrintOutcome.Failed -> "No se pudo imprimir: ${outcome.reason}"
                        is PrinterService.PrintOutcome.NoPrinter -> "No hay impresora de recibos configurada"
                    }
            } catch (e: Exception) {
                _printReceiptResult.value = "Error al imprimir: ${e.message ?: "desconocido"}"
            } finally {
                _isPrintingReceipt.value = false
            }
        }
    }

    fun clearPrintReceiptResult() {
        _printReceiptResult.value = null
    }

    private val venueName: String
        get() = secureStorage.venueName ?: "Avoqado"

    // MARK: - Unassociated Refund

    fun showRefundSheet() {
        _showRefundSheet.value = true
    }

    fun hideRefundSheet() {
        _showRefundSheet.value = false
        _refundState.value = RefundUiState.Idle
    }

    fun resetRefundState() {
        _refundState.value = RefundUiState.Idle
    }

    fun processUnassociatedRefund(
        amountText: String,
        reason: String,
        items: List<RefundItem> = emptyList(),
    ) {
        val amountDouble = amountText.toDoubleOrNull()
        if (amountDouble == null || amountDouble <= 0) {
            _refundState.value = RefundUiState.Error("Ingresa un monto valido")
            return
        }
        if (reason.isBlank()) {
            _refundState.value = RefundUiState.Error("Ingresa una razón para el reembolso")
            return
        }

        val amountCents = (amountDouble * 100).toInt()

        _refundState.value = RefundUiState.Loading
        viewModelScope.launch {
            val result = refundRepository.createUnassociatedRefund(amountCents, reason, items)
            result.fold(
                onSuccess = { refundResult ->
                    Log.d("💸", "✅ Refund processed: ${refundResult.message}")

                    // Record PAY_OUT in cash drawer if session is open
                    try {
                        val openSession = cashDrawerRepository.getOpenSession()
                        if (openSession != null) {
                            cashDrawerRepository.addPayOut(
                                amountCents = amountCents,
                                note = "Reembolso desasociado: $reason",
                            )
                            Log.d("💸", "✅ Cash drawer PAY_OUT recorded for refund")
                        }
                    } catch (e: Exception) {
                        Log.e("💸", "⚠️ Could not record cash drawer pay-out: ${e.message}")
                    }

                    _refundState.value = RefundUiState.Success(
                        refundResult.message ?: "Reembolso procesado",
                    )

                    // Refresh transactions list — mutación local = identidad nueva.
                    invalidateAndRefresh()
                },
                onFailure = { error ->
                    Log.e("💸", "❌ Refund failed: ${error.message}")
                    _refundState.value = RefundUiState.Error(
                        error.message ?: "Error al procesar el reembolso",
                    )
                },
            )
        }
    }

    // MARK: - Receipt Sending

    private val _isSendingReceipt = MutableStateFlow(false)
    val isSendingReceipt: StateFlow<Boolean> = _isSendingReceipt.asStateFlow()

    private val _receiptResultMessage = MutableStateFlow<String?>(null)
    val receiptResultMessage: StateFlow<String?> = _receiptResultMessage.asStateFlow()

    fun clearReceiptResult() {
        _receiptResultMessage.value = null
    }

    fun sendReceiptEmail(paymentId: String, email: String) {
        _isSendingReceipt.value = true
        _receiptResultMessage.value = null
        viewModelScope.launch {
            val result = orderRepository.sendReceiptEmail(paymentId, email)
            result.fold(
                onSuccess = {
                    Log.d("📧", "✅ Email receipt sent for transaction $paymentId")
                    _receiptResultMessage.value = "Recibo enviado por correo"
                },
                onFailure = { error ->
                    Log.e("📧", "❌ Email receipt failed: ${error.message}")
                    _receiptResultMessage.value = error.message ?: "Error al enviar recibo"
                },
            )
            _isSendingReceipt.value = false
        }
    }

    fun sendReceiptWhatsApp(paymentId: String, phone: String) {
        _isSendingReceipt.value = true
        _receiptResultMessage.value = null
        viewModelScope.launch {
            val result = orderRepository.sendReceiptWhatsApp(paymentId, phone)
            result.fold(
                onSuccess = {
                    Log.d("📨", "✅ WhatsApp receipt sent for transaction $paymentId")
                    _receiptResultMessage.value = "Recibo enviado por WhatsApp"
                },
                onFailure = { error ->
                    Log.e("📨", "❌ WhatsApp receipt failed: ${error.message}")
                    _receiptResultMessage.value = error.message ?: "Error al enviar recibo"
                },
            )
            _isSendingReceipt.value = false
        }
    }
}

// MARK: - Transaction → ReceiptData

/** Maps a past sale to the printable receipt shape used by PrinterService.
 *  Amounts arrive in pesos (Double) and ReceiptData wants cents. */
private fun Transaction.toReceiptData(venueName: String): ReceiptData {
    fun cents(value: Double): Int = kotlin.math.round(value * 100).toInt()

    val receiptItems = if (items.isNotEmpty()) {
        items.map { item ->
            ReceiptItem(
                name = item.productName.ifEmpty { "Artículo" },
                quantity = item.quantity,
                unitPrice = cents(item.unitPrice),
                totalPrice = cents(item.amount),
                modifiers = item.modifiers.map { it.name }.filter { it.isNotEmpty() }.ifEmpty { null },
            )
        }
    } else {
        // Sales without line items (quick amounts / older records): one summary line.
        listOf(ReceiptItem(name = "Venta", quantity = 1, unitPrice = cents(amount), totalPrice = cents(amount)))
    }

    return ReceiptData(
        orderNumber = orderNumber ?: id.takeLast(4),
        orderType = "En tienda",
        items = receiptItems,
        subtotal = cents(amount),
        taxAmount = 0,
        tipAmount = if (tipAmount > 0) cents(tipAmount) else null,
        total = cents(totalAmount),
        paymentMethod = methodDescription,
        venueName = venueName,
        cashierName = staffName,
        customerName = customerName,
        // Print the SALE's timestamp, not "now" — this is a reprint of a past sale.
        date = parsedDateTime?.toInstant()?.let { java.util.Date.from(it) } ?: java.util.Date(),
        transactionId = referenceNumber ?: id,
    )
}
