package com.avoqado.pos.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.avoqado.pos.inventory.data.CreatePOItemRequest
import com.avoqado.pos.inventory.data.CreateTransferItemRequest
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.ReceiveItemRequest
import com.avoqado.pos.inventory.data.model.InventoryTransfer
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockSortOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "📦 InventoryVM"

// MARK: - Sidebar sections (matching Square inventory screenshot)

enum class InventorySection(val label: String) {
    OVERVIEW("Descripcion general"),
    COUNTS("Conteos"),
    PURCHASE_ORDERS("Ordenes de compra"),
    TRANSFERS("Transferencias"),
    METRICS("Metricas"),
}

// Keep InventoryTab for backward compatibility (used in overview content)
enum class InventoryTab(val label: String) {
    OVERVIEW("Resumen"),
    COUNTS("Conteos"),
}

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository,
) : ViewModel() {

    val stockItems = repository.stockItems
    val stockCounts = repository.stockCounts
    val purchaseOrders = repository.purchaseOrders
    val transfers = repository.transfers
    val suppliers = repository.suppliers
    val isLoading = repository.isLoading

    private val _selectedSection = MutableStateFlow(InventorySection.OVERVIEW)
    val selectedSection: StateFlow<InventorySection> = _selectedSection.asStateFlow()

    private val _selectedTab = MutableStateFlow(InventoryTab.OVERVIEW)
    val selectedTab: StateFlow<InventoryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(StockSortOption.NAME_ASC)
    val sortOption: StateFlow<StockSortOption> = _sortOption.asStateFlow()

    // Purchase order search
    private val _poSearchQuery = MutableStateFlow("")
    val poSearchQuery: StateFlow<String> = _poSearchQuery.asStateFlow()

    // Transfer search
    private val _transferSearchQuery = MutableStateFlow("")
    val transferSearchQuery: StateFlow<String> = _transferSearchQuery.asStateFlow()

    // Selected detail for purchase order
    private val _selectedPurchaseOrder = MutableStateFlow<PurchaseOrder?>(null)
    val selectedPurchaseOrder: StateFlow<PurchaseOrder?> = _selectedPurchaseOrder.asStateFlow()

    // Selected detail for transfer
    private val _selectedTransfer = MutableStateFlow<InventoryTransfer?>(null)
    val selectedTransfer: StateFlow<InventoryTransfer?> = _selectedTransfer.asStateFlow()

    // Error message for user feedback (Bug 4)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Saving state
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // MARK: - Stock Counting State

    private val _showCountTypeSheet = MutableStateFlow(false)
    val showCountTypeSheet: StateFlow<Boolean> = _showCountTypeSheet.asStateFlow()

    private val _showCounting = MutableStateFlow(false)
    val showCounting: StateFlow<Boolean> = _showCounting.asStateFlow()

    private val _showReview = MutableStateFlow(false)
    val showReview: StateFlow<Boolean> = _showReview.asStateFlow()

    private val _activeCount = MutableStateFlow<StockCount?>(null)
    val activeCount: StateFlow<StockCount?> = _activeCount.asStateFlow()

    private val _activeCountType = MutableStateFlow(StockCountType.CYCLE)
    val activeCountType: StateFlow<StockCountType> = _activeCountType.asStateFlow()

    private val _countItems = MutableStateFlow<List<StockCountItem>>(emptyList())
    val countItems: StateFlow<List<StockCountItem>> = _countItems.asStateFlow()

    private val _selectedItemIndex = MutableStateFlow(-1)
    val selectedItemIndex: StateFlow<Int> = _selectedItemIndex.asStateFlow()

    private val _countedText = MutableStateFlow("")
    val countedText: StateFlow<String> = _countedText.asStateFlow()

    private val _countNote = MutableStateFlow("")
    val countNote: StateFlow<String> = _countNote.asStateFlow()

    private val _selectedDetail = MutableStateFlow<StockCount?>(null)
    val selectedDetail: StateFlow<StockCount?> = _selectedDetail.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        refresh()
    }

    fun selectSection(section: InventorySection) {
        _selectedSection.value = section
        // Load data for the section if needed
        when (section) {
            InventorySection.PURCHASE_ORDERS -> loadPurchaseOrders()
            InventorySection.TRANSFERS -> loadTransfers()
            InventorySection.METRICS -> { /* Computed from existing data, no fetch needed */ }
            else -> { /* Already loaded on init */ }
        }
    }

    fun selectTab(tab: InventoryTab) {
        _selectedTab.value = tab
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun updateSort(option: StockSortOption) {
        _sortOption.value = option
    }

    fun updatePOSearch(query: String) {
        _poSearchQuery.value = query
    }

    fun updateTransferSearch(query: String) {
        _transferSearchQuery.value = query
    }

    fun selectPurchaseOrder(po: PurchaseOrder?) {
        _selectedPurchaseOrder.value = po
    }

    fun selectTransfer(transfer: InventoryTransfer?) {
        _selectedTransfer.value = transfer
    }

    fun refresh() {
        viewModelScope.launch {
            repository.fetchStockOverview()
            repository.fetchStockCounts()
        }
    }

    private fun loadPurchaseOrders() {
        viewModelScope.launch {
            repository.fetchPurchaseOrders()
            repository.fetchSuppliers()
        }
    }

    private fun loadTransfers() {
        viewModelScope.launch {
            repository.fetchTransfers()
        }
    }

    // MARK: - Create Purchase Order

    fun createPurchaseOrder(
        supplierName: String,
        items: List<CreatePOItemRequest> = emptyList(),
        notes: String? = null,
        expectedDeliveryDate: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = repository.createPurchaseOrder(
                    supplierName = supplierName,
                    items = items,
                    notes = notes,
                    expectedDeliveryDate = expectedDeliveryDate,
                )
                result.onSuccess {
                    Log.d(TAG, "✅ Purchase order created: ${it.id}")
                    onSuccess()
                }.onFailure { e ->
                    Log.e(TAG, "❌ Create PO failed: ${e.message}")
                    _errorMessage.value = e.message ?: "Error al crear orden de compra"
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Receive Purchase Order

    fun receivePurchaseOrder(
        poId: String,
        items: List<ReceiveItemRequest>,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = repository.receivePurchaseOrder(poId, items)
                result.onSuccess {
                    Log.d(TAG, "✅ PO received: $poId")
                    // Refresh the selected PO to reflect updated quantities
                    _selectedPurchaseOrder.value = null
                    onSuccess()
                }.onFailure { e ->
                    Log.e(TAG, "❌ Receive PO failed: ${e.message}")
                    _errorMessage.value = e.message ?: "Error al recibir mercancía"
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Create Transfer

    fun createTransfer(
        fromLocationName: String,
        toLocationName: String,
        items: List<CreateTransferItemRequest> = emptyList(),
        notes: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = repository.createTransfer(
                    fromLocationName = fromLocationName,
                    toLocationName = toLocationName,
                    items = items,
                    notes = notes,
                )
                result.onSuccess {
                    Log.d(TAG, "✅ Transfer created: ${it.id}")
                    onSuccess()
                }.onFailure { e ->
                    Log.e(TAG, "❌ Create transfer failed: ${e.message}")
                    _errorMessage.value = e.message ?: "Error al crear transferencia"
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Update Transfer Status

    fun updateTransferStatus(
        transferId: String,
        status: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = repository.updateTransferStatus(transferId, status)
                result.onSuccess {
                    Log.d(TAG, "Transfer status updated: $transferId -> $status")
                    // Update the selected transfer locally to reflect the new status
                    _selectedTransfer.value?.let { current ->
                        if (current.id == transferId) {
                            _selectedTransfer.value = current.copy(status = status)
                        }
                    }
                    onSuccess()
                }.onFailure { e ->
                    Log.e(TAG, "Update transfer status failed: ${e.message}")
                    _errorMessage.value = e.message ?: "Error al actualizar estado de transferencia"
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Stock Counting

    fun openCountTypeSheet() {
        _showCountTypeSheet.value = true
    }

    fun closeCountTypeSheet() {
        _showCountTypeSheet.value = false
    }

    fun startCycleCount() {
        _activeCountType.value = StockCountType.CYCLE
        _activeCount.value = null
        _countItems.value = emptyList()
        _selectedItemIndex.value = -1
        _countedText.value = ""
        _countNote.value = ""
        _showCountTypeSheet.value = false
        _showCounting.value = true
    }

    fun startFullCount() {
        viewModelScope.launch {
            _isSaving.value = true
            _activeCountType.value = StockCountType.FULL
            repository.createStockCount(StockCountType.FULL).fold(
                onSuccess = { count ->
                    _activeCount.value = count
                    _countItems.value = count.items.toMutableList()
                    _selectedItemIndex.value = if (count.items.isNotEmpty()) 0 else -1
                    _countedText.value = if (count.items.isNotEmpty()) "" else ""
                    _countNote.value = ""
                    _showCountTypeSheet.value = false
                    _showCounting.value = true
                    Log.d(TAG, "✅ Full count started with ${count.items.size} items")
                },
                onFailure = { e ->
                    _errorMessage.value = e.message ?: "Error al crear conteo"
                    Log.e(TAG, "❌ Start full count failed: ${e.message}")
                },
            )
            _isSaving.value = false
        }
    }

    fun addItemsToCycleCount(items: List<StockItem>) {
        // Save any pending count for the current selected item before mutating the list
        saveCurrentCount()

        val existingIds = _countItems.value.map { it.productId }.toSet()
        val newItems = items.filter { it.id !in existingIds }.map { stock ->
            StockCountItem(
                id = "",
                productId = stock.id,
                productName = stock.name,
                sku = stock.sku,
                gtin = stock.gtin,
                imageUrl = stock.imageUrl,
                expected = stock.onHand,
                counted = 0.0,
                difference = 0.0,
            )
        }

        if (newItems.isEmpty()) return

        val previousSize = _countItems.value.size
        _countItems.value = _countItems.value + newItems

        // Auto-select the first newly added item so the user can start counting it immediately
        _selectedItemIndex.value = previousSize
        _countedText.value = ""
    }

    fun selectCountItem(index: Int) {
        saveCurrentCount()
        _selectedItemIndex.value = index
        val item = _countItems.value.getOrNull(index)
        _countedText.value = if (item != null && item.counted > 0) {
            formatQuantity(item.counted)
        } else {
            ""
        }
    }

    fun updateCountedText(text: String) {
        _countedText.value = text
    }

    fun incrementCount() {
        val current = _countedText.value.toDoubleOrNull() ?: 0.0
        _countedText.value = formatQuantity(current + 1)
    }

    fun decrementCount() {
        val current = _countedText.value.toDoubleOrNull() ?: 0.0
        if (current > 0) {
            _countedText.value = formatQuantity(current - 1)
        }
    }

    fun moveToNextItem() {
        saveCurrentCount()
        val items = _countItems.value
        val currentIndex = _selectedItemIndex.value
        if (currentIndex < items.size - 1) {
            selectCountItem(currentIndex + 1)
        }
    }

    fun updateCountNote(note: String) {
        _countNote.value = note
    }

    private fun saveCurrentCount() {
        val index = _selectedItemIndex.value
        val text = _countedText.value
        if (index >= 0 && index < _countItems.value.size) {
            val counted = text.toDoubleOrNull() ?: 0.0
            val updated = _countItems.value.toMutableList()
            val item = updated[index]
            updated[index] = item.copy(
                counted = counted,
                difference = counted - item.expected,
            )
            _countItems.value = updated
        }
    }

    fun finishCounting() {
        saveCurrentCount()
        _showCounting.value = false
        _showReview.value = true
    }

    fun backToCounting() {
        _showReview.value = false
        _showCounting.value = true
    }

    fun confirmCount() {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val items = _countItems.value
                val note = _countNote.value.ifBlank { null }

                if (_activeCountType.value == StockCountType.CYCLE) {
                    // Cycle: create on backend first, then update and confirm
                    val productIds = items.map { it.productId }
                    val createResult = repository.createStockCount(StockCountType.CYCLE, productIds)
                    createResult.fold(
                        onSuccess = { count ->
                            _activeCount.value = count
                            // Map local items to server items with IDs
                            val serverItems = count.items.map { serverItem ->
                                val localItem = items.find { it.productId == serverItem.productId }
                                serverItem.copy(counted = localItem?.counted ?: 0.0)
                            }
                            val updateResult = repository.updateStockCount(count.id, serverItems, note)
                            if (updateResult.isSuccess) {
                                repository.confirmStockCount(count.id)
                            }
                        },
                        onFailure = { e ->
                            _errorMessage.value = e.message ?: "Error al crear conteo"
                        },
                    )
                } else {
                    // Full: already created, just update and confirm
                    val countId = _activeCount.value?.id ?: return@launch
                    val updateResult = repository.updateStockCount(countId, items, note)
                    if (updateResult.isSuccess) {
                        repository.confirmStockCount(countId)
                    }
                }

                // Success - close and refresh
                _showReview.value = false
                _showCounting.value = false
                _activeCount.value = null
                _countItems.value = emptyList()
                repository.fetchStockCounts()
                Log.d(TAG, "✅ Stock count confirmed")
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Error al confirmar conteo"
                Log.e(TAG, "❌ Confirm count error: ${e.message}")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun cancelCounting() {
        _showCounting.value = false
        _showReview.value = false
        _activeCount.value = null
        _countItems.value = emptyList()
        _selectedItemIndex.value = -1
        _countedText.value = ""
        _countNote.value = ""
    }

    fun selectCountDetail(count: StockCount?) {
        _selectedDetail.value = count
    }

    fun formatQuantity(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.2f", value)
        }
    }
}
