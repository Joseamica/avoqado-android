package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.OrderRepository
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
    val roleManager: RoleManager,
    private val orderRepository: OrderRepository,
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

    // Refund
    private val _showRefundSheet = MutableStateFlow(false)
    val showRefundSheet: StateFlow<Boolean> = _showRefundSheet.asStateFlow()

    private val _refundState = MutableStateFlow<RefundUiState>(RefundUiState.Idle)
    val refundState: StateFlow<RefundUiState> = _refundState.asStateFlow()

    init {
        refresh()
        observeSearch()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchText
                .debounce(400)
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            repository.fetchTransactions(
                page = 1,
                search = _searchText.value.ifBlank { null },
            )
        }
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

        viewModelScope.launch {
            val detail = repository.fetchTransactionDetail(transactionId)
            _selectedTransaction.value = detail
            _isLoadingDetail.value = false
        }
    }

    fun clearSelection() {
        _selectedTransactionId.value = null
        _selectedTransaction.value = null
    }

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
            _refundState.value = RefundUiState.Error("Ingresa una razon para el reembolso")
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

                    // Refresh transactions list
                    refresh()
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
