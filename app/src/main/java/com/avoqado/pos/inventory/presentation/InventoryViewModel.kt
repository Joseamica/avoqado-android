package com.avoqado.pos.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
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
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.scale.ScaleSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.avoqado.pos.core.data.network.ServerErrorText

private const val TAG = "📦 InventoryVM"

// MARK: - Sidebar sections (matching Square inventory screenshot)

enum class InventorySection(val label: String) {
    OVERVIEW("Descripción general"),
    COUNTS("Conteos"),
    PURCHASE_ORDERS("Órdenes de compra"),
    TRANSFERS("Transferencias"),
    // Traslados de insumos ENTRE sucursales (CEDIS) — distinto del legacy
    // TRANSFERS, que es la vista de auditoría de movimientos internos.
    INTER_VENUE("Traslados"),
    METRICS("Métricas"),
}

// Keep InventoryTab for backward compatibility (used in overview content)
enum class InventoryTab(val label: String) {
    OVERVIEW("Resumen"),
    COUNTS("Conteos"),
}

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val planManager: PlanManager,
    private val scaleSettingsRepository: ScaleSettingsRepository,
) : ViewModel() {

    // MARK: - Plan gating (Phase ① — UI teaser only)

    /**
     * INVENTORY_TRACKING (Premium) gates the ADVANCED inventory sections
     * (counts, purchase orders, transfers, metrics). The basic stock overview
     * stays free. Fail-open when the plan is unknown.
     */
    val hasInventoryTracking: Boolean
        get() = planManager.hasFeature("INVENTORY_TRACKING")

    /** Tier label required for advanced inventory ("Premium"). */
    val inventoryTierLabel: String
        get() = planManager.requiredTierLabel("INVENTORY_TRACKING") ?: "Premium"

    val stockItems = repository.stockItems
    val countableRawMaterials = repository.countableRawMaterials
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

    fun clearErrorMessage() { _errorMessage.value = null }

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

    private val _scaleIntegrationSettings = MutableStateFlow<ScaleIntegrationSettings?>(null)
    val scaleIntegrationSettings: StateFlow<ScaleIntegrationSettings?> =
        _scaleIntegrationSettings.asStateFlow()

    private val _selectedDetail = MutableStateFlow<StockCount?>(null)
    val selectedDetail: StateFlow<StockCount?> = _selectedDetail.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }

    /**
     * La báscula es una ayuda opcional. Si el local, perfil, permiso o red no están disponibles,
     * el conteo manual continúa sin mostrar un error bloqueante.
     */
    fun loadScaleIntegrationSettings() {
        viewModelScope.launch {
            _scaleIntegrationSettings.value = runCatching {
                scaleSettingsRepository.settings()
            }.onFailure { error ->
                Log.w(TAG, "Scale settings unavailable; keeping manual stock count", error)
            }.getOrNull()
        }
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
            repository.fetchRawMaterials()
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
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al crear orden de compra")
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
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al recibir mercancía")
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Update Purchase Order Status

    fun updatePurchaseOrderStatus(
        poId: String,
        status: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = repository.updatePurchaseOrderStatus(poId, status)
                result.onSuccess {
                    Log.d(TAG, "✅ PO status updated: $poId -> $status")
                    // Update the selected PO locally to reflect state changes immediately.
                    _selectedPurchaseOrder.value?.let { current ->
                        if (current.id == poId) {
                            _selectedPurchaseOrder.value = current.copy(status = status)
                        }
                    }
                    onSuccess()
                }.onFailure { e ->
                    Log.e(TAG, "❌ Update PO status failed: ${e.message}")
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al actualizar estado de la orden de compra")
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
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al crear transferencia")
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
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al actualizar estado de transferencia")
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

    /** Ids of lines the cashier actually recorded (typing "0" counts; never
     *  touching the line does not). Only these are sent on confirm — the
     *  server zeroes whatever it receives as counted. */
    private val _touchedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val touchedItemIds: StateFlow<Set<String>> = _touchedItemIds.asStateFlow()

    fun startCycleCount() {
        _activeCountType.value = StockCountType.CYCLE
        _activeCount.value = null
        _countItems.value = emptyList()
        _selectedItemIndex.value = -1
        _countedText.value = ""
        _countNote.value = ""
        _touchedItemIds.value = emptySet()
        _showCountTypeSheet.value = false
        _showCounting.value = true
    }

    /**
     * Retoma un conteo que quedó a medias, en vez de empezar otro.
     *
     * No existía: la vista de detalle era sólo lectura y "Contar existencia"
     * SIEMPRE creaba uno nuevo. Resultado medido en la base del local de
     * pruebas: dos conteos completos abiertos desde el 11 de julio, con 51
     * artículos cada uno, abandonados casi un mes. El trabajo de contarlos se
     * perdía y la lista se llenaba de conteos que nadie iba a cerrar.
     *
     * Se posiciona en el primer artículo SIN contar, que es donde se dejó.
     */
    fun resumeCount(count: StockCount) {
        _activeCountType.value = count.type
        _activeCount.value = count
        _countItems.value = count.items.toMutableList()
        val primerPendiente = count.items.indexOfFirst { !it.yaSeConto }
        _selectedItemIndex.value = when {
            count.items.isEmpty() -> -1
            primerPendiente >= 0 -> primerPendiente
            else -> 0
        }
        _countedText.value = ""
        _countNote.value = count.note.orEmpty()
        _touchedItemIds.value = emptySet()
        _showCountTypeSheet.value = false
        _showCounting.value = true
        Log.d(TAG, "▶️ Conteo retomado: ${count.id} (${count.items.size} artículos)")
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
                    _countedText.value = ""
                    _countNote.value = ""
                    _touchedItemIds.value = emptySet()
                    _showCountTypeSheet.value = false
                    _showCounting.value = true
                    Log.d(TAG, "✅ Full count started with ${count.items.size} items")
                },
                onFailure = { e ->
                    _errorMessage.value = ServerErrorText.humanize(e.message, "Error al crear conteo")
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
            // Ingredient lines mirror the server's compat shape: productId
            // carries the raw material id, itemType switches the display.
            val isRaw = repository.isRawMaterial(stock.id)
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
                // Nace SIN contar: el 0 es un marcador de posición, no un dato.
                unit = stock.unit,
                rawMaterialId = if (isRaw) stock.id else null,
                itemType = if (isRaw) "RAW_MATERIAL" else null,
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
        _countedText.value = if (item != null && _touchedItemIds.value.contains(item.id)) {
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
        // Empty field = the line was never counted; "0" = counted as zero.
        if (text.isBlank()) return
        if (index >= 0 && index < _countItems.value.size) {
            val counted = text.toDoubleOrNull() ?: 0.0
            val updated = _countItems.value.toMutableList()
            val item = updated[index]
            updated[index] = item.copy(
                counted = counted,
                difference = counted - item.expected,
                // Marca la línea como CONTADA. Sin esto un 0 tecleado a mano se
                // vería igual que uno sin contar, que es justo lo que se acaba
                // de arreglar en el detalle.
                countedAt = java.time.Instant.now().toString(),
            )
            _countItems.value = updated
            _touchedItemIds.value = _touchedItemIds.value + item.id
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

                // Every step's Result is now checked: before, a failed
                // update/confirm STILL closed the review as a success and
                // discarded the whole count with no error.
                var confirmed = false

                if (_activeCountType.value == StockCountType.CYCLE) {
                    // Cycle: create on backend first, then update and confirm
                    val productIds = items.filter { !it.isIngredient }.map { it.productId }
                    val rawMaterialIds = items.filter { it.isIngredient }.mapNotNull { it.rawMaterialId }
                    val createResult = repository.createStockCount(StockCountType.CYCLE, productIds, rawMaterialIds)
                    createResult.fold(
                        onSuccess = { count ->
                            _activeCount.value = count
                            // Map local items to server items with IDs
                            val touched = _touchedItemIds.value
                            val serverItems = count.items.mapNotNull { serverItem ->
                                val localItem = items.find { it.productId == serverItem.productId }
                                // Skip lines the cashier never counted — sending
                                // them as counted=0 would zero real stock.
                                if (localItem == null || !touched.contains(localItem.id)) return@mapNotNull null
                                serverItem.copy(counted = localItem.counted)
                            }
                            val updateResult = repository.updateStockCount(count.id, serverItems, note)
                            if (updateResult.isSuccess) {
                                confirmed = repository.confirmStockCount(count.id).isSuccess
                                if (!confirmed) {
                                    _errorMessage.value = "No se pudo confirmar el conteo. Intenta de nuevo."
                                }
                            } else {
                                _errorMessage.value = "No se pudo guardar el conteo. Intenta de nuevo."
                            }
                        },
                        onFailure = { e ->
                            _errorMessage.value = ServerErrorText.humanize(e.message, "Error al crear conteo")
                        },
                    )
                } else {
                    // Full: already created, just update and confirm.
                    // Only lines the cashier actually counted: the server
                    // stamps countedAt on every received item and applies
                    // exactly those on confirm.
                    val countId = _activeCount.value?.id ?: return@launch
                    val touched = _touchedItemIds.value
                    val countedOnly = items.filter { touched.contains(it.id) }
                    val updateResult = repository.updateStockCount(countId, countedOnly, note)
                    if (updateResult.isSuccess) {
                        confirmed = repository.confirmStockCount(countId).isSuccess
                        if (!confirmed) {
                            _errorMessage.value = "No se pudo confirmar el conteo. Intenta de nuevo."
                        }
                    } else {
                        _errorMessage.value = "No se pudo guardar el conteo. Intenta de nuevo."
                    }
                }

                if (confirmed) {
                    // Success - close and refresh (data preserved on failure)
                    _showReview.value = false
                    _showCounting.value = false
                    _activeCount.value = null
                    _countItems.value = emptyList()
                    repository.fetchStockCounts()
                    Log.d(TAG, "✅ Stock count confirmed")
                }
            } catch (e: Exception) {
                _errorMessage.value = ServerErrorText.humanize(e.message, "Error al confirmar conteo")
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
