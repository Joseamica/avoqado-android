package com.avoqado.pos.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.inventory.data.CreatePOItemRequest
import com.avoqado.pos.inventory.data.CreateTransferItemRequest
import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.DestinoDeLaCancelacion
import com.avoqado.pos.inventory.data.DestinoDelEnvio
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
import com.avoqado.pos.inventory.data.ResultadoDeCierreCoordinado
import com.avoqado.pos.inventory.data.ConflictoRevision
import com.avoqado.pos.inventory.data.ReceiveItemRequest
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.SIN_VENUE
import com.avoqado.pos.inventory.data.model.InventoryTransfer
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockSortOption
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.scale.ScaleSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.avoqado.pos.core.data.network.ServerErrorText

private const val TAG = "📦 InventoryVM"

/** Espejo EXACTO de `PREMIUM_ONLY_CODES` en el backend y de iOS. */
private const val SCALE_FEATURE_CODE = "SCALE_INTEGRATION"

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

enum class EstadoDelDetalleDeConteo {
    SIN_VERIFICAR,
    DISPONIBLE,
    NO_DISPONIBLE,
}

data class ConfirmacionDeDescarte internal constructor(
    val contadas: Int,
    internal val countId: String?,
    internal val venueId: String?,
)

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val planManager: PlanManager,
    private val scaleSettingsRepository: ScaleSettingsRepository,
    private val stockRefresher: StockRefresher,
    refreshGateFactory: RefreshGateFactory,
    private val borradores: BorradorDeConteoStore,
    private val connectivityMonitor: ConnectivityMonitor,
    /** null sólo conserva constructores JVM anteriores; Hilt siempre entrega el singleton. */
    private val inventoryCountSyncCoordinator: InventoryCountSyncCoordinator? = null,
) : ViewModel() {

    private val gate = refreshGateFactory.create(viewModelScope)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

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

    /** Última nota que sabemos que el servidor recibió. `null` = sólo tenemos el borrador. */
    private var notaReconocidaPorServidor: String? = null
    private var notaPendienteDeEnviar = false

    private val _scaleIntegrationSettings = MutableStateFlow<ScaleIntegrationSettings?>(null)
    val scaleIntegrationSettings: StateFlow<ScaleIntegrationSettings?> =
        _scaleIntegrationSettings.asStateFlow()

    private val _selectedDetail = MutableStateFlow<StockCount?>(null)
    val selectedDetail: StateFlow<StockCount?> = _selectedDetail.asStateFlow()

    private val _estadoDelDetalle = MutableStateFlow(EstadoDelDetalleDeConteo.SIN_VERIFICAR)
    val estadoDelDetalle: StateFlow<EstadoDelDetalleDeConteo> = _estadoDelDetalle.asStateFlow()

    private val _detalleNoActualizado = MutableStateFlow(false)
    val detalleNoActualizado: StateFlow<Boolean> = _detalleNoActualizado.asStateFlow()

    private val _puedeContinuarDetalle = MutableStateFlow(false)
    val puedeContinuarDetalle: StateFlow<Boolean> = _puedeContinuarDetalle.asStateFlow()

    private var generacionDelDetalle = 0L
    private var venueDelDetalle: String? = null
    private var ultimoVenueObservadoEnDetalle: String? = null
    private data class ClaveDeRefrescoDelDetalle(val generacion: Long, val countId: String, val venueId: String?)
    private val refrescosDelDetalleEnCurso = mutableSetOf<ClaveDeRefrescoDelDetalle>()

    private val _confirmacionDeDescarte = MutableStateFlow<ConfirmacionDeDescarte?>(null)
    val confirmacionDeDescarte: StateFlow<ConfirmacionDeDescarte?> = _confirmacionDeDescarte.asStateFlow()

    // Detalle de un artículo tocado en la Descripción general.
    private val _selectedStockItem = MutableStateFlow<StockItem?>(null)
    val selectedStockItem: StateFlow<StockItem?> = _selectedStockItem.asStateFlow()

    fun selectStockItem(item: StockItem?) {
        _selectedStockItem.value = item
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }

    /**
     * `SCALE_INTEGRATION` es PREMIUM en el backend y NADIE lo exigía: ni la ruta,
     * ni iOS, ni aquí. La báscula funcionaba en cualquier plan.
     *
     * Falla ABIERTO con el plan desconocido, igual que el resto del gating.
     */
    val hasScaleIntegration: Boolean
        get() = planManager.hasFeature(SCALE_FEATURE_CODE)

    /** Etiqueta del plan que incluye la báscula ("Premium"). */
    val scaleTierLabel: String
        get() = planManager.requiredTierLabel(SCALE_FEATURE_CODE) ?: "Premium"

    /**
     * La báscula es una ayuda opcional. Si el local, perfil, permiso o red no están disponibles,
     * el conteo manual continúa sin mostrar un error bloqueante.
     */
    fun loadScaleIntegrationSettings() {
        // El candado de plan va ANTES de la red: el server ya responde 403 sin
        // `SCALE_INTEGRATION`, esto sólo evita el viaje. El conteo manual sigue
        // igual — la báscula nunca bloquea contar.
        if (!hasScaleIntegration) {
            _scaleIntegrationSettings.value = null
            return
        }
        viewModelScope.launch {
            _scaleIntegrationSettings.value = runCatching {
                scaleSettingsRepository.settings()
            }.onFailure { error ->
                Log.w(TAG, "Scale settings unavailable; keeping manual stock count", error)
            }.getOrNull()
        }
    }

    fun selectSection(section: InventorySection) {
        _selectedSection.value = section
        // Load data for the section if needed
        when (section) {
            InventorySection.PURCHASE_ORDERS -> loadPurchaseOrders()
            InventorySection.TRANSFERS -> loadTransfers()
            InventorySection.COUNTS -> viewModelScope.launch { repository.fetchStockCounts() }
            InventorySection.METRICS -> { /* Computed from existing data, no fetch needed */ }
            else -> { /* La carga inicial la dispara la UI vía el gate (autoRefresh) */ }
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

    // MARK: - Refresco (spec estrategia-de-refresco: gate + despacho por sección)

    /** Guard §4.5 — stock: con un conteo en curso, en revisión o guardando,
     *  ni el auto-refresh ni el gesto pisan la pantalla. */
    private fun workInProgress(): Boolean =
        _showCounting.value || _showReview.value || _isSaving.value

    /**
     * Contrato §4.2: sin launch interno; refresca lo que la sección ACTIVA
     * muestra (§10 del spec: un gesto que anima sin refrescar lo visible es
     * mentira). NO toca el catálogo del POS a propósito: ese sólo se vuelve a
     * pedir cuando algo MUEVE stock (ver [refreshStockLevels]).
     */
    suspend fun refreshNow(): Result<Unit> = when (_selectedSection.value) {
        InventorySection.OVERVIEW, InventorySection.METRICS -> combineResults(
            repository.fetchStockOverview(),
            repository.fetchRawMaterials(),
        )
        InventorySection.COUNTS -> repository.fetchStockCounts()
        InventorySection.PURCHASE_ORDERS -> combineResults(
            repository.fetchPurchaseOrders(),
            repository.fetchSuppliers(),
        )
        InventorySection.TRANSFERS -> repository.fetchTransfers()
        // Traslados entre sucursales vive en InterVenueTransfersViewModel:
        // esa sección no se envuelve con el gesto (ver InventoryScreen).
        InventorySection.INTER_VENUE -> Result.success(Unit)
    }

    private fun combineResults(a: Result<Unit>, b: Result<Unit>): Result<Unit> =
        if (a.isFailure) a else b

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

    /**
     * Existencias tras un movimiento de stock. Delega en [StockRefresher], que
     * refresca TAMBIÉN el catálogo del POS — si no, lo que acabas de reponer
     * contándolo se queda "Agotado" en la pantalla de cobro.
     */
    private suspend fun refreshStockLevels() {
        stockRefresher.refreshAfterStockChange()
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
                    // Recibir mercancía SUBE el stock — misma pantalla, mismo
                    // refresco obligatorio que al confirmar un conteo.
                    refreshStockLevels()
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
     *  server zeroes whatever it receives as counted.
     *
     *  Se DERIVA de `countedAt` en [actualizarLineas]: mantenerlo aparte era lo que
     *  hacía que un cíclico (todas sus líneas con `id = ""`) marcara todas al contar una. */
    private val _touchedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val touchedItemIds: StateFlow<Set<String>> = _touchedItemIds.asStateFlow()

    /** El cajero pidió salir: la pantalla pregunta qué hacer con lo contado. */
    private val _salidaPendiente = MutableStateFlow(false)
    val salidaPendiente: StateFlow<Boolean> = _salidaPendiente.asStateFlow()

    /** Líneas contadas aquí que el servidor todavía no confirmó por PUT (sólo FULL: un cíclico no existe allá). */
    private val _pendientesDeEnviar = MutableStateFlow<Set<String>>(emptySet())
    val pendientesDeEnviar: StateFlow<Set<String>> = _pendientesDeEnviar.asStateFlow()

    private val _sinRedAlEnviar = MutableStateFlow(false)
    val sinRedAlEnviar: StateFlow<Boolean> = _sinRedAlEnviar.asStateFlow()

    /** El servidor dijo que este conteo ya no está en progreso (lo cerró otro aparato). Lo local se conserva. */
    private val _conflictoDelServidor = MutableStateFlow<String?>(null)
    val conflictoDelServidor: StateFlow<String?> = _conflictoDelServidor.asStateFlow()

    /** 409 de revisión o base legacy desconocida. Nunca se reutiliza como “conteo cerrado”. */
    private val _conflictoDeRevision = MutableStateFlow(borradores.leer()?.conflictoRevision)
    val conflictoDeRevision: StateFlow<ConflictoRevision?> = _conflictoDeRevision.asStateFlow()

    val soloConsulta: StateFlow<Boolean> = combine(
        _conflictoDelServidor,
        _conflictoDeRevision,
    ) { cerrado, revision -> cerrado != null || revision != null }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _conflictoDelServidor.value != null || _conflictoDeRevision.value != null,
        )

    private val _borradorLocal = MutableStateFlow(borradores.leer())
    val borradorLocal: StateFlow<BorradorDeConteo?> = _borradorLocal.asStateFlow()

    /**
     * La sucursal en la que se ABRIÓ este conteo, y su nombre. Se fija al empezar o retomar y no
     * cambia con el aparato.
     *
     * 🔴 El POS cambia de sucursal sin cerrar sesión, y el conteo no viaja con él: su id sólo
     * existe en `/venues/<la de origen>/…`. Sin esta ancla, seguir contando en la otra mandaba el
     * avance a la sucursal equivocada, el servidor contestaba 404 y la pantalla lo traducía a
     * «este conteo ya se cerró desde otro aparato» — un CONFLICTO falso sobre trabajo que estaba
     * perfectamente bien, y que además apaga el envío para el resto de la sesión.
     */
    private var _venueDelConteo: String? = null
    private var _nombreDelVenueDelConteo: String? = null

    /**
     * «Hay red» = el aparato tiene red **y** el servidor contesta. Son dos cosas distintas y en el
     * ICP la segunda se cae sola: el WiFi de la tienda sigue en pie mientras el túnel, el proxy o
     * la API están muertos. Mirando sólo `isConnected`, la banda decía que todo iba bien, el
     * botón «Confirmar» tocaba la red para nada, y al recuperarse el servidor no se disparaba el
     * replay porque `isConnected` nunca volvió a emitir.
     *
     * `ConnectivityMonitor` ya publicaba las dos señales (y hace ping cada 10 s mientras el
     * servidor está caído): lo único que faltaba era combinarlas en un solo sitio, para que la
     * banda, el guard de confirmar y el disparador de reconexión no puedan discrepar.
     */
    private val conectado: StateFlow<Boolean> =
        combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { red, servidor ->
            red && servidor
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            connectivityMonitor.isConnected.value && connectivityMonitor.isServerReachable.value,
        )

    /**
     * Cuánto trabajo vive SÓLO en este aparato: en un conteo que YA existe en el servidor, lo que
     * falta por subir; en un cíclico que todavía no se ha creado allá, TODO lo contado — porque
     * allá no hay ni una línea.
     */
    private val soloEnElAparato: StateFlow<Int> =
        combine(_activeCount, _pendientesDeEnviar, _countItems) { enElServidor, pendientes, lineas ->
            if (enElServidor != null) pendientes.size else ConteoEnCurso.contadas(lineas)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Lo ÚNICO que la banda ámbar del conteo tiene que pintar, ya resuelto aquí: el conflicto
     * manda, y si no lo hay se dice cuánto trabajo vive sólo en este aparato.
     *
     * 🔴 Nace de que en un CÍCLICO la banda no podía salir NUNCA (I1 de la revisión de la vista):
     * `_pendientesDeEnviar` sólo crece con un conteo que existe en el servidor, y
     * `_sinRedAlEnviar` sólo se enciende dentro de `enviarPendientes`, que en un cíclico sale en
     * su primera línea. El cajero contaba con el WiFi apagado y la pantalla no decía nada —
     * «funciona igual» SIN decirlo, que es justo lo que `todo-funciona-sin-red.md` no admite.
     *
     * Por eso «sin red» tiene DOS fuentes: el PUT que no salió (`code == 0`) y el monitor de
     * conectividad. La segunda es la que cubre al cíclico, que no manda nada, y también al conteo
     * que se ABRE ya sin red: el monitor es un StateFlow y no vuelve a emitir, así que un latch
     * encendido al perder la red nunca se encendería para un conteo empezado después.
     *
     * Sin nada que sólo viva aquí no hay banda, ni siquiera sin red: avisar de cero enseña a
     * ignorar el aviso.
     */
    private val conflictoVisible = combine(
        _conflictoDelServidor,
        _conflictoDeRevision,
    ) { cerrado, revision -> cerrado ?: revision?.let(ConteoEnCurso::descripcionConflictoRevision) }

    val bandaDeAviso: StateFlow<String?> =
        combine(
            conflictoVisible,
            _sinRedAlEnviar,
            conectado,
            soloEnElAparato,
            _pendientesDeEnviar,
        ) { conflicto, noSalioElPut, conectado, soloAqui, pendientes ->
            when {
                conflicto != null -> conflicto
                soloAqui == 0 -> null
                noSalioElPut || !conectado -> ConteoEnCurso.avisoSinRed(soloAqui)
                pendientes.isNotEmpty() -> ConteoEnCurso.avisoPendienteDeSubir(pendientes.size)
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Single-flight del PUT incremental: nunca dos avances del mismo conteo en vuelo. */
    private var enviando = false

    /**
     * Un solo escritor del conteo EN EL SERVIDOR, incremental y cierre incluidos.
     *
     * 🔴 El `enviando` de arriba sólo ordenaba los PUT incrementales entre sí; el cierre iba por
     * fuera. Con el WiFi del ICP eso basta para sellar un número que el cajero ya corrigió: sale
     * el PUT de `5`, tarda, se corrige a `7`, se toca «Confirmar», el PUT final de `7` llega
     * primero y el de `5` DESPUÉS — el `/confirm` cierra el conteo con `5` y el borrador se borra
     * porque el cierre «salió bien». Con el candado compartido, el PUT viejo no puede aterrizar
     * después del final: el cierre espera su turno.
     */
    private val candadoDeEnvio = Mutex()

    init {
        val coordinator = inventoryCountSyncCoordinator
        if (coordinator != null) {
            // El coordinador drena todas las sucursales; esta pantalla sólo acepta eventos de la
            // sucursal y conteo que tiene abiertos para no inyectar snapshots ajenos.
            viewModelScope.launch {
                coordinator.cambios.collect { cambio ->
                    if (!esConteoVisible(cambio.venueId, cambio.countId)) return@collect
                    aplicarBorradorVigente(cambio.borrador)
                    aplicarRespuestaDeSync(cambio.respuesta)
                }
            }
        } else {
        // Al volver la red sale lo que se quedó en el aparato: el avance y, si la hubo, la cancelación.
        viewModelScope.launch {
            var huboCorte = false
            // El flanco lo da la señal COMBINADA: un servidor que vuelve sin que el WiFi se haya
            // movido es exactamente el caso que antes no reproducía nada.
            conectado.collect { hayRed ->
                if (!hayRed) {
                    huboCorte = true
                    return@collect
                }
                enviarCancelacionesPendientes()
                // 🔴 Con la pantalla del conteo cerrada, `enviarPendientes` salía en su primera
                // línea (`_activeCount` es null tras «Guardar el avance») y lo pendiente se
                // quedaba aquí hasta que alguien volviera a abrir ese conteo. La cola vive en el
                // DISCO, no en la pantalla — igual que la del cajón.
                if (_activeCount.value != null) enviarPendientes() else enviarPendientesDelDisco()
                if (huboCorte) {
                    huboCorte = false
                    recuperarCatalogoSiQuedoVacio()
                }
            }
        }
        }
    }

    /**
     * D1 del QA sin red (OrderPAD 3, 7-sep): el catálogo se pide UNA vez al entrar y, si esa
     * petición murió por falta de red, nadie la reintenta — ni siquiera cuando la red vuelve.
     * Medido con `/health` ya en 200: «Descripción general» decía «Sin artículos con inventario»
     * en un venue con 28 productos, y el conteo CÍCLICO quedaba inservible porque «Agregar
     * artículos» abría vacío. Es `lista-vacia-no-es-fallo-de-red` sobre el catálogo, y sólo se
     * curaba reiniciando la app.
     *
     * Va FUERA del gate de refresco a propósito: esto es RECUPERACIÓN, no refresco — el gate se
     * apaga con un conteo en curso (§4.5) y es justo cuando más falta hace. Y es seguro
     * precisamente porque sólo corre con la lista VACÍA: no hay nada que pisar.
     */
    private suspend fun recuperarCatalogoSiQuedoVacio() {
        // 🔴 Son DOS peticiones y pueden fallar por separado: `stock-overview` vuelve y
        // `raw-materials` no. Cortando por la primera, los INSUMOS no reaparecían nunca y el
        // cíclico se quedaba sin la mitad de lo que se puede contar (P2 #2 de Codex).
        val faltanProductos = repository.stockItems.value.isEmpty()
        val faltanInsumos = repository.countableRawMaterials.value.isEmpty()
        if (!faltanProductos && !faltanInsumos) return
        // ⚠️ Declarado: una lista vacía LEGÍTIMA (un venue sin insumos) se vuelve a pedir en cada
        // reconexión. Es barato y acotado a los bordes; distinguir «vacío» de «falló» exigiría que
        // el repositorio publicara el estado de cada carga, y eso es otro cambio.
        Log.d(TAG, "🔁 Catálogo incompleto (productos=$faltanProductos, insumos=$faltanInsumos): se vuelve a pedir")
        if (faltanProductos) repository.fetchStockOverview()
        if (faltanInsumos) repository.fetchRawMaterials()
    }

    /**
     * El mismo rescate, pero a petición de la pantalla: al empezar un cíclico y al abrir
     * «Agregar artículos». Cubre el caso que la reconexión no ve — la red nunca se cayó, pero la
     * primera carga falló (servidor caído, 5xx) y `isConnected` no vuelve a emitir.
     */
    fun asegurarCatalogo() {
        if (repository.stockItems.value.isNotEmpty() && repository.countableRawMaterials.value.isNotEmpty()) return
        // Sin red no se intenta: el catálogo no está en el aparato y pedirlo sólo suma un error.
        if (!conectado.value) return
        viewModelScope.launch { recuperarCatalogoSiQuedoVacio() }
    }

    fun refrescarBorradorLocal() { _borradorLocal.value = borradores.leer() }

    private fun esConteoVisible(venueId: String, countId: String): Boolean =
        venueId == _venueDelConteo &&
            countId == _activeCount.value?.id &&
            venueId == repository.venueIdActual()

    private fun aplicarBorradorVigente(borrador: BorradorDeConteo?) {
        if (borrador == null || !esConteoVisible(borrador.venueId, borrador.countId.orEmpty())) return
        _borradorLocal.value = borrador
        _pendientesDeEnviar.value = borrador.pendientesDeEnviar
        notaPendienteDeEnviar = ConteoEnCurso.notaPendienteDeEnviar(borrador)
        _conflictoDeRevision.value = borrador.conflictoRevision
        _activeCount.value = _activeCount.value?.copy(revision = borrador.revision)
    }

    private fun aplicarRespuestaDeSync(respuesta: RespuestaHttp?) {
        if (respuesta == null) return
        when {
            respuesta.code in 200..299 -> _sinRedAlEnviar.value = false
            respuesta.codigo == ConteoEnCurso.CODIGO_APLICANDO -> {
                _sinRedAlEnviar.value = false
                _errorMessage.value = ServerErrorText.humanize(
                    respuesta.body,
                    "El conteo se está aplicando. Espera unos segundos y vuelve a intentarlo.",
                )
            }
            respuesta.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION -> {
                _sinRedAlEnviar.value = false
                _errorMessage.value = ConteoEnCurso.CONFLICTO_REVISION
            }
            respuesta.code == 404 -> {
                _conflictoDelServidor.value = ConteoEnCurso.CONFLICTO
                _sinRedAlEnviar.value = false
            }
            respuesta.code == 0 -> _sinRedAlEnviar.value = true
            respuesta.code in setOf(400, 403, 422) -> {
                _sinRedAlEnviar.value = false
                _errorMessage.value = ServerErrorText.humanize(
                    respuesta.body,
                    "No se pudo guardar el avance en el servidor",
                )
            }
        }
    }

    /** ÚNICO escritor de `_countItems`: `touchedItemIds` se deriva de `countedAt`, nunca se mantiene aparte. */
    private fun actualizarLineas(lineas: List<StockCountItem>) {
        _countItems.value = lineas
        _touchedItemIds.value = ConteoEnCurso.idsContadas(lineas)
    }

    private fun venueIdActual(): String? = borradores.leer()?.venueId ?: repository.venueIdActual()

    /** Ancla el conteo a la sucursal en la que se abre. Se llama en los CUATRO puntos de entrada. */
    private fun fijarSucursalDelConteo() {
        _venueDelConteo = venueIdActual()
        _nombreDelVenueDelConteo = repository.venueNameActual()
    }

    /**
     * ¿El aparato se movió de sucursal desde que se abrió el conteo? Devuelve `true` (y lo DICE)
     * cuando sí, para que quien llama no toque la red.
     *
     * Falla ABIERTO a propósito: sin sucursal conocida en alguno de los dos lados no se bloquea
     * nada — impedir guardar por no saber dónde estamos sería peor que el defecto.
     */
    private fun cambioDeSucursal(): Boolean {
        val delConteo = _venueDelConteo ?: return false
        val actual = repository.venueIdActual() ?: return false
        if (actual == delConteo) return false
        _errorMessage.value = ConteoEnCurso.cambiasteDeSucursal(_nombreDelVenueDelConteo)
        Log.w(TAG, "⛔ El conteo es de $delConteo y el aparato está en $actual: no se toca la red")
        return true
    }
    /**
     * Escribe el conteo en curso a disco. Se llama después de CADA cambio de lo contado y
     * ANTES de tocar la red: si el proceso muere entre teclear y el PUT, la línea ya está aquí.
     * Sin nada que perder (cero líneas contadas y sin una edición de nota pendiente) borra en vez
     * de dejar un borrador vacío. Una nota vaciada sí es trabajo local hasta que el servidor pueda
     * reconocer ese borrado.
     */
    private fun persistirBorrador(edicionLocal: Boolean = false) {
        val lineas = _countItems.value
        val venueId = _venueDelConteo ?: venueIdActual() ?: ""
        val anterior = venueId.takeIf { it.isNotBlank() }?.let(borradores::leer)
        val hayAlgo = ConteoEnCurso.contadas(lineas) > 0 || notaPendienteDeEnviar ||
            anterior?.conflictoRevision != null || anterior?.revisionConPutFinalConfirmado != null
        if (!hayAlgo) {
            if (venueId.isBlank() || !borradores.borrar(venueId)) {
                Log.e(TAG, "❌ El borrador vacío no se pudo borrar del disco")
            }
            refrescarBorradorLocal()
            return
        }
        // 🔴 El venue del CONTEO, no el del aparato: si alguien cambió de sucursal a media
        // captura, estampar el actual convertiría lo contado en A en un conteo de B. El almacén
        // rechaza esa escritura (su guarda M4) y aquí queda dicho en el log.
        val snapshot = BorradorDeConteo(
                venueId = venueId,
                countId = _activeCount.value?.id,
                type = _activeCountType.value,
                lineas = lineas,
                nota = _countNote.value,
                notaPendienteDeEnviar = notaPendienteDeEnviar,
                pendientesDeEnviar = _pendientesDeEnviar.value,
                revision = if (anterior != null && anterior.countId == _activeCount.value?.id) {
                    // null es una base desconocida real: un GET posterior no la convierte en la
                    // revisión desde la que nació este borrador.
                    anterior.revision
                } else {
                    _activeCount.value?.revision
                },
                conflictoRevision = _conflictoDeRevision.value
                    ?: anterior?.takeIf { it.countId == _activeCount.value?.id }?.conflictoRevision,
                revisionConPutFinalConfirmado = anterior
                    ?.takeIf { it.countId == _activeCount.value?.id }
                    ?.revisionConPutFinalConfirmado,
                actualizadoEn = System.currentTimeMillis(),
        )
        val guardado = if (edicionLocal) {
            borradores.guardarEdicion(venueId, snapshot)
        } else {
            borradores.guardar(venueId, snapshot)
        }
        // No se puede hacer nada más aquí —la red todavía puede salvar lo que el disco no—, pero
        // callarlo dejaba al aparato actuando como si la línea estuviera a salvo.
        if (!guardado) Log.e(TAG, "❌ El avance del conteo NO quedó en disco")
        aplicarBorradorVigente(borradores.leer(venueId))
    }

    /**
     * Un conteo en curso por aparato, como Square.
     *
     * 🔴 El almacén guarda UN borrador por sucursal, así que abrir otro conteo lo pisa. Antes se
     * pisaba en silencio: bastaba tocar «Continuar» en un conteo distinto —o empezar uno nuevo—
     * para que las líneas que este aparato todavía no había mandado dejaran de existir.
     *
     * «Trabajo que el servidor no tiene» son las líneas pendientes de PUT, un cíclico que ni
     * siquiera existe allá (vive ENTERO en el borrador), y una edición local de la nota. Un conteo
     * cuyas líneas y nota ya están sincronizadas se puede reemplazar sin preguntar.
     *
     * @param idDestino el conteo que se quiere abrir; `null` = uno nuevo, que nunca es el del borrador.
     */
    private fun otroBorradorConTrabajo(idDestino: String?): Boolean {
        val b = borradores.leer() ?: return false
        if (idDestino != null && b.countId == idDestino) return false
        return b.pendientesDeEnviar.isNotEmpty() ||
            ConteoEnCurso.notaPendienteDeEnviar(b) ||
            (b.countId == null && ConteoEnCurso.contadas(b.lineas) > 0)
    }

    /** true = no se abre nada y se dice por qué. */
    private fun bloqueadoPorOtroBorrador(idDestino: String?): Boolean {
        if (!otroBorradorConTrabajo(idDestino)) return false
        // 🔴 La hoja se cierra ANTES de poner el mensaje. El `SnackbarHost` que lo pinta vive en
        // `InventoryScreen`, DEBAJO de la ventana del `ModalBottomSheet`, así que con la hoja abierta el
        // aviso se emite y NO se ve (medido en la tablet, QA pasada 2 · DP2-1): el cajero vuelve a tocar
        // creyendo que el botón está muerto y nunca se entera de que tiene un conteo suyo sin terminar.
        _showCountTypeSheet.value = false
        _errorMessage.value = ConteoEnCurso.HAY_OTRO_BORRADOR
        Log.w(TAG, "⛔ Ya hay un conteo sin terminar en el aparato: no se abre otro")
        return true
    }

    fun startCycleCount() {
        if (bloqueadoPorOtroBorrador(null)) return
        fijarSucursalDelConteo()
        // Un cíclico se arma ELIGIENDO artículos: sin catálogo el selector abre vacío y no se
        // puede ni empezar.
        asegurarCatalogo()
        _activeCountType.value = StockCountType.CYCLE
        _activeCount.value = null
        actualizarLineas(emptyList())
        _selectedItemIndex.value = -1
        _countedText.value = ""
        _countNote.value = ""
        notaReconocidaPorServidor = ""
        notaPendienteDeEnviar = false
        _pendientesDeEnviar.value = emptySet()
        _conflictoDelServidor.value = null
        _conflictoDeRevision.value = null
        _sinRedAlEnviar.value = false
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
        if (bloqueadoPorOtroBorrador(count.id)) return
        retomarConteo(count, conflicto = null)
    }

    /**
     * El cuerpo de retomar, con el conflicto como PARÁMETRO: al retomar un conteo que el servidor
     * ya cerró hay que abrirlo igual (para consulta) pero con la banda puesta, y ponerla después
     * no serviría — `resumeCount` la borra al empezar y ya habría salido un PUT contra un conteo
     * que no existe. Con el conflicto puesto, `enviarPendientes` no manda nada.
     */
    private fun retomarConteo(
        count: StockCount,
        conflicto: String?,
        notaDelServidorConfirmada: Boolean = true,
        conflictoRevision: ConflictoRevision? = null,
    ) {
        // Lo contado en ESTE aparato gana línea por línea; el servidor manda QUÉ líneas existen.
        val local = borradores.leer()?.takeIf { it.countId == count.id }
        fijarSucursalDelConteo()
        _activeCountType.value = count.type
        _activeCount.value = count
        // Sólo lo PENDIENTE gana: una línea que este aparato ya subió y el servidor confirmó
        // vuelve con el valor bueno —el de quien la haya corregido después—, y resucitar el mío
        // sería pisar esa corrección y sellarla al confirmar.
        actualizarLineas(
            ConteoEnCurso.fusionar(count.items, local?.lineas, local?.pendientesDeEnviar.orEmpty()),
        )
        _selectedItemIndex.value = ConteoEnCurso.primerPendiente(_countItems.value)
        _countedText.value = ""
        val notaLocalPendiente = local?.let(ConteoEnCurso::notaPendienteDeEnviar) == true
        notaReconocidaPorServidor = when {
            notaDelServidorConfirmada -> count.note.orEmpty()
            local?.notaPendienteDeEnviar == false -> local.nota
            else -> null
        }
        notaPendienteDeEnviar = notaLocalPendiente
        _countNote.value = when {
            notaLocalPendiente -> local!!.nota
            notaDelServidorConfirmada -> count.note.orEmpty()
            else -> local?.nota ?: count.note.orEmpty()
        }
        // Un pendiente cuya línea el servidor ya no tiene no se puede mandar NUNCA (`fusionar` la
        // descarta), así que arrastrarlo dejaría el aviso «1 línea guardada en este aparato»
        // encendido para siempre.
        val idsVigentes = _countItems.value.map { it.id }.toSet()
        _pendientesDeEnviar.value = local?.pendientesDeEnviar.orEmpty().intersect(idsVigentes)
        _conflictoDelServidor.value = conflicto
        _conflictoDeRevision.value = conflictoRevision ?: local?.conflictoRevision
        _sinRedAlEnviar.value = false
        _showCountTypeSheet.value = false
        _showCounting.value = true
        persistirBorrador()
        enviarPendientes()
        Log.d(TAG, "▶️ Conteo retomado: ${count.id} (${count.items.size} artículos, ${_pendientesDeEnviar.value.size} por enviar)")
    }

    fun startFullCount() {
        // 🔴 ANTES del `createStockCount`: negarse DESPUÉS dejaría un conteo huérfano IN_PROGRESS
        // en el servidor que nadie va a cerrar.
        if (bloqueadoPorOtroBorrador(null)) return
        fijarSucursalDelConteo()
        viewModelScope.launch {
            _isSaving.value = true
            _activeCountType.value = StockCountType.FULL
            repository.createStockCount(StockCountType.FULL).fold(
                onSuccess = { count ->
                    _activeCount.value = count
                    actualizarLineas(count.items)
                    _selectedItemIndex.value = if (count.items.isNotEmpty()) 0 else -1
                    _countedText.value = ""
                    _countNote.value = ""
                    notaReconocidaPorServidor = count.note.orEmpty()
                    notaPendienteDeEnviar = false
                    _pendientesDeEnviar.value = emptySet()
                    _conflictoDelServidor.value = null
                    _conflictoDeRevision.value = null
                    _sinRedAlEnviar.value = false
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
        if (soloConsulta.value) return
        // Save any pending count for the current selected item before mutating the list
        saveCurrentCount()

        val existingIds = _countItems.value.map { it.productId }.toSet()
        val newItems = items.filter { it.id !in existingIds }.map { stock ->
            // Ingredient lines mirror the server's compat shape: productId
            // carries the raw material id, itemType switches the display.
            val isRaw = repository.isRawMaterial(stock.id)
            StockCountItem(
                // 🔴 Cada línea con su PROPIA llave: con `id = ""` todas compartían la de
                // `touchedItemIds`, así que contar una marcaba las demás como contadas.
                id = java.util.UUID.randomUUID().toString(),
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
        actualizarLineas(_countItems.value + newItems)

        // Auto-select the first newly added item so the user can start counting it immediately
        _selectedItemIndex.value = previousSize
        _countedText.value = ""
        persistirBorrador(edicionLocal = true)
    }

    fun selectCountItem(index: Int) {
        saveCurrentCount()
        _selectedItemIndex.value = index
        val item = _countItems.value.getOrNull(index)
        _countedText.value = if (item != null && item.yaSeConto) {
            formatQuantity(item.counted)
        } else {
            ""
        }
    }

    fun updateCountedText(text: String) {
        // 🔴 Con el cierre en vuelo, la captura se congela. Si no, el cajero corrige un número
        // durante «Confirmando…», el confirm sella el valor VIEJO y al salir bien borra el
        // borrador: la corrección desaparece sin que nadie la haya visto fallar.
        if (_isSaving.value || soloConsulta.value) return
        _countedText.value = text
    }

    fun incrementCount() {
        if (soloConsulta.value) return
        val current = _countedText.value.toDoubleOrNull() ?: 0.0
        _countedText.value = formatQuantity(current + 1)
    }

    fun decrementCount() {
        if (soloConsulta.value) return
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

    /**
     * La nota NO se escribe a disco por tecla: `persistirBorrador` serializa el conteo entero y
     * hace un `commit()` con fsync en el hilo de la UI, así que una nota de 40 caracteres serían
     * 40 en una tablet barata. Se guarda al terminar de contar, al pedir salir, al guardar el
     * avance y al confirmar.
     *
     * Declarado: una nota tecleada justo antes de que el proceso muera se pierde. No es dinero —
     * lo que sí lo es (una cantidad) sigue escribiéndose síncrono en cuanto se teclea.
     */
    fun updateCountNote(note: String) {
        // Igual que la cantidad: lo que se edite después de tomar la foto del PUT final podría
        // quedar borrado por un confirm exitoso. La UI ya enseña «Confirmando…» durante este lapso.
        if (_isSaving.value || soloConsulta.value) return
        _countNote.value = note
        notaPendienteDeEnviar = notaReconocidaPorServidor == null || note != notaReconocidaPorServidor
    }

    private fun saveCurrentCount() {
        // Hermano del guard de `updateCountedText`: mientras el conteo se cierra en el servidor no
        // se sella ni se manda nada nuevo.
        if (_isSaving.value || soloConsulta.value) return
        val index = _selectedItemIndex.value
        val text = _countedText.value
        // Empty field = the line was never counted; "0" = counted as zero.
        if (text.isBlank()) return
        if (index >= 0 && index < _countItems.value.size) {
            val counted = text.toDoubleOrNull() ?: 0.0
            // Un conteo físico no puede ser negativo: nadie cuenta "menos siete
            // cervezas" en el anaquel (una báscula con la tara mal puesta sí
            // puede mandarlo). El server también lo rechaza; aquí se avisa
            // ANTES de perder la captura. Espejo exacto de iOS.
            if (counted < 0) {
                _errorMessage.value = "La cantidad contada no puede ser negativa"
                return
            }
            val updated = _countItems.value.toMutableList()
            val item = updated[index]
            // Ya guardada con esta MISMA cantidad: no se re-sella ni se reescribe el disco.
            // `moveToNextItem` llama aquí y enseguida a `selectCountItem`, que vuelve a llamar
            // con el mismo texto — eran dos fsync del conteo entero y dos PUT por línea. Sí se
            // reintenta lo pendiente: volver a teclear el mismo número es lo que hace el cajero
            // cuando algo no salió.
            if (item.yaSeConto && item.counted == counted) {
                enviarPendientes()
                return
            }
            updated[index] = item.copy(
                counted = counted,
                difference = counted - item.expected,
                // Marca la línea como CONTADA. Sin esto un 0 tecleado a mano se
                // vería igual que uno sin contar, que es justo lo que se acaba
                // de arreglar en el detalle.
                countedAt = java.time.Instant.now().toString(),
            )
            actualizarLineas(updated)
            // Sólo un conteo que YA existe en el servidor tiene a quién mandarle el avance.
            if (_activeCount.value != null && item.id.isNotBlank()) {
                _pendientesDeEnviar.value = _pendientesDeEnviar.value + item.id
            }
            // 🔴 Disco ANTES que red: si el proceso muere aquí, la línea ya está guardada.
            persistirBorrador(edicionLocal = true)
            enviarPendientes()
        }
    }

    fun finishCounting() {
        saveCurrentCount()
        persistirBorrador(edicionLocal = true) // por la nota, que ya no se escribe por tecla
        _showCounting.value = false
        _showReview.value = true
    }

    fun backToCounting() {
        _showReview.value = false
        _showCounting.value = true
    }

    /**
     * PUT del avance con lo pendiente. Single-flight; nunca bloquea la captura.
     *
     * Sólo se encadena otra pasada cuando la anterior SALIÓ: reintentar aquí un rechazo
     * o un fallo de red sería un bucle de PUTs contra el servidor. Lo que quedó pendiente
     * sale con la siguiente tecla o al volver la red.
     */
    private fun enviarPendientes() {
        val countId = _activeCount.value?.id ?: return
        // 🔴 `_isSaving` = el conteo se está CERRANDO. Ni uno más: el candado ordena el PUT que ya
        // iba en vuelo, pero un incremental que ARRANCA durante el cierre volvería a aterrizar
        // después del `/confirm` —y contra un conteo ya COMPLETED, que contesta 404— dejando la
        // banda de conflicto encendida sobre una pantalla que ya se cerró bien. No se pierde nada:
        // el PUT final manda TODAS las líneas contadas, no sólo las pendientes.
        if (enviando || _isSaving.value || _conflictoDelServidor.value != null ||
            _conflictoDeRevision.value != null
        ) return
        val lineas = ConteoEnCurso.lineasParaEnviar(_countItems.value, _pendientesDeEnviar.value)
        if (lineas.isEmpty()) return
        // Se comprueba con algo pendiente delante, no en cada tecla: así el aviso sale cuando de
        // verdad hay trabajo que no puede salir, en vez de repetirse por nada.
        if (cambioDeSucursal()) return
        val coordinator = inventoryCountSyncCoordinator
        if (coordinator != null) {
            val venueId = _venueDelConteo ?: return
            if (!conectado.value) {
                _sinRedAlEnviar.value = true
                return
            }
            enviando = true
            viewModelScope.launch {
                try {
                    val respuesta = coordinator.sincronizarAvanceAhora(venueId, countId)
                    if (esConteoVisible(venueId, countId)) {
                        aplicarBorradorVigente(borradores.leer(venueId))
                        aplicarRespuestaDeSync(respuesta)
                    }
                } finally {
                    enviando = false
                }
                if (_activeCount.value?.id == countId && _pendientesDeEnviar.value.isNotEmpty() &&
                    _conflictoDeRevision.value == null
                ) enviarPendientes()
            }
            return
        }
        enviando = true
        viewModelScope.launch {
            var enviado = false
            try {
                val r = putDelAvance(countId, lineas)
                when (r.destino) {
                    DestinoDelEnvio.ENVIADO -> {
                        aplicarEnvio(countId, r.confirmadas)
                        _sinRedAlEnviar.value = false
                        enviado = true
                    }
                    DestinoDelEnvio.REINTENTAR -> _sinRedAlEnviar.value = (r.code == 0)
                    DestinoDelEnvio.CONFLICTO -> {
                        _conflictoDelServidor.value = ConteoEnCurso.CONFLICTO
                        _sinRedAlEnviar.value = false
                    }
                    DestinoDelEnvio.RECHAZADO -> {
                        _sinRedAlEnviar.value = false
                        _errorMessage.value = ServerErrorText.humanize(r.cuerpo, "No se pudo guardar el avance en el servidor")
                    }
                }
            } finally {
                enviando = false
            }
            // Lo que se contó mientras viajaba el PUT anterior sale ahora.
            if (!enviado) return@launch
            if (_activeCount.value?.id == countId) {
                if (_pendientesDeEnviar.value.isNotEmpty()) enviarPendientes()
            } else {
                // 🔴 La pantalla se cerró MIENTRAS el PUT viajaba («Guardar el avance»): la cola en
                // memoria ya se limpió, así que el encadenado de arriba no ve nada y lo que se contó
                // entre medias se quedaba en el aparato hasta la siguiente tecla, reconexión o
                // apertura del conteo (P2 #1 de Codex). Una pasada MÁS por el disco, no un bucle:
                // si ésa tampoco alcanza, sale al reconectar.
                enviarPendientesDelDisco()
            }
        }
    }

    private data class ResultadoDelEnvio(
        val destino: DestinoDelEnvio,
        /** Las líneas que SALIERON y siguen siendo las que se mandaron — nunca «lo que queda pendiente». */
        val confirmadas: Set<String>,
        val code: Int,
        val cuerpo: String,
    )

    /**
     * Las líneas VIVAS de ESTE conteo: la pantalla si sigue abierta en él, y si no el borrador del
     * disco. Un conteo distinto —o ninguno— no tiene nada que ofrecer aquí: devolver las de otro
     * cerraría por error las de éste.
     */
    private fun lineasVigentes(countId: String): List<StockCountItem> =
        if (_activeCount.value?.id == countId) {
            _countItems.value
        } else {
            borradores.leer()?.takeIf { it.countId == countId }?.lineas.orEmpty()
        }

    /**
     * Cierra en la cola las líneas que SALIERON, restándolas de lo que hay VIVO en este momento.
     *
     * 🔴 Reasignar la foto tomada antes del viaje descartaba lo contado DURANTE el vuelo: se cuenta
     * A, el PUT viaja segundos con el WiFi del ICP, se cuenta B, y el 200 de A dejaba la cola en
     * {} — B se caía de la cola Y del borrador, el encadenado no la veía y el servidor nunca
     * recibía su cantidad, mientras la banda decía cero pendientes.
     *
     * 🔴 Con la pantalla ya cerrada (o en otro conteo) NO se persiste desde `_countItems`: está
     * vacío, así que `persistirBorrador` borraría el borrador entero — con su nota, que el PUT del
     * avance ni siquiera manda. Ahí se toca el borrador del disco, y sólo su cola.
     */
    private fun aplicarEnvio(countId: String, confirmadas: Set<String>) {
        if (_activeCount.value?.id == countId) {
            _pendientesDeEnviar.value = _pendientesDeEnviar.value - confirmadas
            persistirBorrador()
            return
        }
        val b = borradores.leer() ?: return
        if (b.countId != countId) return
        borradores.guardar(b.copy(pendientesDeEnviar = b.pendientesDeEnviar - confirmadas))
        refrescarBorradorLocal()
    }

    /**
     * El PUT del avance sin nada de estado de pantalla, para que lo usen los DOS caminos: la
     * captura y el replay desde el disco cuando la pantalla está cerrada. Devuelve QUÉ SALIÓ; a
     * quién se le resta lo decide `aplicarEnvio` mirando el estado vivo al volver — por eso aquí
     * no entra ninguna foto de la cola, que es justo lo que no se puede volver a asignar.
     *
     * 🔴 Una línea sólo cuenta como enviada si sigue siendo LA QUE SE MANDÓ. Cerrarla por su id a
     * secas dejaba al servidor con el valor viejo cuando el cajero corregía la cantidad mientras
     * el PUT viajaba: el 200 del envío VIEJO cerraba la línea NUEVA y ya nadie la mandaba. El
     * sello es el par (cantidad, countedAt) y no sólo el countedAt, porque dos `Instant.now()`
     * seguidos pueden salir iguales si el reloj de la JVM tiene poca resolución — y entonces la
     * protección dependería del reloj.
     */
    private suspend fun putDelAvance(
        countId: String,
        lineas: List<StockCountItem>,
    ): ResultadoDelEnvio = candadoDeEnvio.withLock {
        val sellos = lineas.associate { it.id to (it.counted to it.countedAt) }
        val r = repository.enviarAvance(countId, lineas)
        val destino = ConteoEnCurso.clasificarEnvio(r.code)
        val confirmadas = if (destino == DestinoDelEnvio.ENVIADO) {
            val ahora = lineasVigentes(countId).associate { it.id to (it.counted to it.countedAt) }
            sellos.filter { (id, sello) -> ahora[id] == sello }.keys
        } else {
            emptySet()
        }
        ResultadoDelEnvio(destino, confirmadas, r.code, r.body)
    }

    /** El PUT final y el `/confirm`, juntos y bajo el MISMO candado que el avance incremental. */
    private data class CierreDelConteo(
        val venueId: String,
        val countId: String,
        val put: RespuestaHttp? = null,
        val confirm: RespuestaHttp? = null,
        /** Sólo un 200 real de `/confirm`; un PUT 200 por sí solo nunca cierra la pantalla. */
        val completado: Boolean = false,
    )

    private suspend fun cerrarEnElServidor(
        countId: String,
        items: List<StockCountItem>,
        note: String?,
    ): CierreDelConteo {
        val coordinator = inventoryCountSyncCoordinator
        if (coordinator != null) {
            val venueId = _venueDelConteo
                ?: return CierreDelConteo(
                    venueId = "",
                    countId = countId,
                    put = RespuestaHttp(SIN_VENUE, "No venue"),
                )
            val resultado: ResultadoDeCierreCoordinado = coordinator.cerrarManualmente(venueId, countId)
            val mismaPantalla = esConteoVisible(venueId, countId)
            if (mismaPantalla) {
                aplicarBorradorVigente(borradores.leer(venueId))
                if (_conflictoDeRevision.value != null) {
                    _errorMessage.value = ConteoEnCurso.descripcionConflictoRevision(_conflictoDeRevision.value!!)
                }
            }
            return CierreDelConteo(
                venueId = venueId,
                countId = countId,
                put = resultado.put,
                confirm = resultado.confirm,
                completado = resultado.completado,
            )
        }
        val venueId = _venueDelConteo.orEmpty()
        return candadoDeEnvio.withLock {
            val put = repository.enviarFinal(countId, items, note)
            if (ConteoEnCurso.clasificarEnvio(put.code) != DestinoDelEnvio.ENVIADO) {
                return@withLock CierreDelConteo(venueId, countId, put = put)
            }
            reconocerPutFinal(countId, items, note)
            val confirm = repository.confirmarConteo(countId)
            CierreDelConteo(
                venueId = venueId,
                countId = countId,
                put = put,
                confirm = confirm,
                completado = confirm.code in 200..299,
            )
        }
    }

    /**
     * El PUT final ya llegó: guarda su ACK ANTES de tocar `/confirm`, porque el proceso puede morir
     * o el confirm puede fallar. Sólo reconoce la foto que realmente salió; una corrección viva
     * conserva su pendiente, igual que en el PUT incremental. La nota vacía es un delta explícito
     * y se reconoce sólo si sigue vacía cuando vuelve el ACK.
     */
    private fun reconocerPutFinal(
        countId: String,
        enviadas: List<StockCountItem>,
        notaEnviada: String?,
    ) {
        if (_activeCount.value?.id != countId) return
        val sellos = enviadas.associate { it.id to (it.counted to it.countedAt) }
        val ahora = _countItems.value.associate { it.id to (it.counted to it.countedAt) }
        val confirmadas = sellos.filter { (id, sello) -> ahora[id] == sello }.keys
        _pendientesDeEnviar.value = _pendientesDeEnviar.value - confirmadas
        if (notaEnviada != null && notaPendienteDeEnviar && _countNote.value == notaEnviada) {
            notaReconocidaPorServidor = notaEnviada
            notaPendienteDeEnviar = false
        }
        persistirBorrador()
    }

    /**
     * Traduce el cierre a lo que ve el cajero, con la MISMA tabla que el avance incremental.
     *
     * 🔴 Antes los dos pasos volvían como `Result.failure` sin código, así que un 404 —«otro
     * aparato ya cerró este conteo»— salía como «No se pudo guardar el conteo. Intenta de nuevo.»
     * y `_conflictoDelServidor` nunca se ponía: el cajero reintentaba contra un conteo que ya no
     * existía, sin banda y sin explicación posible.
     */
    private fun aplicarCierre(r: CierreDelConteo): Boolean {
        // Una respuesta que salió bajo A no publica borrador, error ni éxito dentro de B. El
        // borrador de A queda durable para que se reconozca al volver a esa sucursal.
        if (r.venueId.isBlank() || r.venueId != _venueDelConteo ||
            r.countId != _activeCount.value?.id || repository.venueIdActual() != r.venueId
        ) return false
        // El paso que MANDA es el último que llegó a hablar: si el PUT no salió, el confirm ni
        // siquiera se intentó.
        val paso = r.confirm ?: r.put ?: RespuestaHttp(0, "")
        if (paso.codigo == ConteoEnCurso.CODIGO_CONFLICTO_REVISION) {
            _errorMessage.value = _conflictoDeRevision.value
                ?.let(ConteoEnCurso::descripcionConflictoRevision)
                ?: ConteoEnCurso.CONFLICTO_REVISION
            aplicarBorradorVigente(_venueDelConteo?.let(borradores::leer))
            return false
        }
        if (paso.codigo == ConteoEnCurso.CODIGO_APLICANDO) {
            _errorMessage.value = ServerErrorText.humanize(
                paso.body,
                "El conteo se está aplicando. Espera unos segundos y vuelve a intentarlo.",
            )
            return false
        }
        return when (ConteoEnCurso.clasificarEnvio(paso.code)) {
            DestinoDelEnvio.ENVIADO -> {
                if (r.completado) true
                else {
                    _errorMessage.value =
                        "El conteo sigue guardado en este aparato. Vuelve a confirmar para terminarlo."
                    false
                }
            }
            DestinoDelEnvio.CONFLICTO -> {
                // El borrador NO se borra: el texto promete que lo contado se conserva.
                _conflictoDelServidor.value = ConteoEnCurso.CONFLICTO
                _errorMessage.value = ConteoEnCurso.CONFLICTO
                Log.w(TAG, "⛔ El conteo ya no está en progreso en el servidor: no se cierra")
                false
            }
            DestinoDelEnvio.RECHAZADO -> {
                _errorMessage.value = ServerErrorText.humanize(paso.body, "No se pudo cerrar el conteo en el servidor")
                false
            }
            DestinoDelEnvio.REINTENTAR -> {
                _errorMessage.value = ServerErrorText.humanize(paso.body, "No se pudo cerrar el conteo. Intenta de nuevo.")
                false
            }
        }
    }

    /**
     * El mismo PUT, pero leyendo la cola del BORRADOR: es lo que corre al volver la red cuando el
     * cajero ya cerró la pantalla del conteo. Lo que no sale se conserva tal cual — un conflicto
     * o un rechazo se le enseña a la persona cuando retome el conteo, no en una pantalla ajena.
     */
    private suspend fun enviarPendientesDelDisco() {
        val b = borradores.leer() ?: return
        val countId = b.countId ?: return
        val lineas = ConteoEnCurso.lineasParaEnviar(b.lineas, b.pendientesDeEnviar)
        if (lineas.isEmpty() || enviando) return
        enviando = true
        try {
            val r = putDelAvance(countId, lineas)
            if (r.destino == DestinoDelEnvio.ENVIADO) {
                // El borrador se relee DENTRO de `aplicarEnvio`: reescribir el `b` de antes del
                // viaje revertía las líneas si alguien abrió el conteo y contó mientras viajaba.
                aplicarEnvio(countId, r.confirmadas)
                Log.d(TAG, "📤 Avance del borrador enviado al volver la red: ${lineas.size} línea(s)")
            } else {
                Log.w(TAG, "⚠️ El avance del borrador no salió (${r.code}): se conserva en el aparato")
            }
        } finally {
            enviando = false
        }
    }

    /**
     * Las cancelaciones que no pudieron salir viven en disco hasta que el servidor las acepte.
     *
     * En PLURAL: sin red se puede descartar un conteo y luego otro, y con una sola ranura el
     * segundo borraba al primero — que se quedaba abierto para siempre.
     */
    private fun enviarCancelacionesPendientes() {
        inventoryCountSyncCoordinator?.let {
            it.solicitarSync()
            return
        }
        val ids = borradores.cancelacionesPendientes()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var algunaSalio = false
            for (countId in ids) {
                val r = repository.cancelStockCount(countId)
                when (ConteoEnCurso.clasificarCancelacion(r.code, r.body)) {
                    DestinoDeLaCancelacion.HECHA -> {
                        borradores.quitarCancelacionPendiente(countId)
                        algunaSalio = true
                    }
                    DestinoDeLaCancelacion.REINTENTAR -> Unit
                    DestinoDeLaCancelacion.RECHAZADA -> {
                        borradores.quitarCancelacionPendiente(countId)
                        _errorMessage.value = ServerErrorText.humanize(r.body, "No se pudo cancelar el conteo en el servidor")
                    }
                }
            }
            // Una sola relectura al final: la lista no cambia por cada una.
            if (algunaSalio) repository.fetchStockCounts()
        }
    }

    fun pedirSalida() {
        saveCurrentCount()
        persistirBorrador(edicionLocal = true) // por la nota, que ya no se escribe por tecla
        _salidaPendiente.value = true
    }

    fun cancelarSalida() { _salidaPendiente.value = false }

    /**
     * El primer toque sólo pide confirmación cuando hay trabajo capturado. Una cantidad 0 con
     * `countedAt` cuenta como trabajo: el cajero sí tomó una decisión y se perdería igual.
     */
    fun pedirDescarte() {
        if (_isSaving.value) return
        saveCurrentCount()
        persistirBorrador(edicionLocal = true)
        val contadas = ConteoEnCurso.contadas(_countItems.value)
        if (contadas == 0) {
            descartarConteo()
            return
        }
        _salidaPendiente.value = false
        _confirmacionDeDescarte.value = ConfirmacionDeDescarte(
            contadas = contadas,
            countId = _activeCount.value?.id,
            venueId = _venueDelConteo ?: repository.venueIdActual(),
        )
    }

    /** Volver o cerrar el segundo diálogo no toca disco, líneas ni servidor. */
    fun cancelarDescarte() {
        if (_confirmacionDeDescarte.value == null) return
        _confirmacionDeDescarte.value = null
        _salidaPendiente.value = true
    }

    /**
     * Consume el permiso antes de ejecutar: dos toques sólo pueden llegar una vez a la operación
     * durable. También vuelve a comprobar conteo, sucursal y el guard de guardado.
     */
    fun confirmarDescarte() {
        if (_isSaving.value) return
        val confirmacion = _confirmacionDeDescarte.value ?: return
        if (confirmacion.countId != _activeCount.value?.id) return
        if (confirmacion.venueId != (_venueDelConteo ?: repository.venueIdActual())) return
        if (cambioDeSucursal()) return
        _confirmacionDeDescarte.value = null
        descartarConteo()
    }

    private fun limpiarEstadoDeConteo() {
        _venueDelConteo = null
        _nombreDelVenueDelConteo = null
        _showCounting.value = false
        _showReview.value = false
        _salidaPendiente.value = false
        _confirmacionDeDescarte.value = null
        _activeCount.value = null
        actualizarLineas(emptyList())
        _selectedItemIndex.value = -1
        _countedText.value = ""
        _countNote.value = ""
        notaReconocidaPorServidor = null
        notaPendienteDeEnviar = false
        _pendientesDeEnviar.value = emptySet()
        _conflictoDelServidor.value = null
        _conflictoDeRevision.value = null
        _sinRedAlEnviar.value = false
    }

    /** El borrador ya está en disco: sólo se cierra la pantalla y se intenta mandar lo pendiente. */
    fun guardarYSalir() {
        saveCurrentCount()
        persistirBorrador(edicionLocal = true)
        enviarPendientes()
        limpiarEstadoDeConteo()
        viewModelScope.launch { repository.fetchStockCounts() }
    }

    /**
     * Descartar = borrar lo local y, si el conteo existe en el servidor, cancelarlo allá (con cola
     * si no hay red).
     *
     * 🔴 Las dos escrituras van en UNA: borrar el borrador y encolar la cancelación eran dos
     * `commit()` seguidos, y un proceso que muere entre ellos dejaba el conteo `IN_PROGRESS` en el
     * servidor sin nada en el aparato que volviera a intentarlo. Y si la escritura falla, la
     * pantalla NO se limpia: enseñar el conteo cerrado sobre un borrador que sigue en disco es
     * peor que no cerrarlo.
     */
    fun descartarConteo() {
        if (cambioDeSucursal()) return
        val countId = _activeCount.value?.id
        val coordinator = inventoryCountSyncCoordinator
        if (coordinator != null) {
            val venueId = _venueDelConteo ?: repository.venueIdActual()
            if (venueId == null) {
                _errorMessage.value = "No se pudo identificar la sucursal del conteo."
                return
            }
            viewModelScope.launch {
                _isSaving.value = true
                try {
                    if (!coordinator.descartarManualmente(venueId, countId)) {
                        _errorMessage.value = "No se pudo descartar el conteo en este aparato. Intenta de nuevo."
                        aplicarBorradorVigente(borradores.leer(venueId))
                        return@launch
                    }
                    limpiarEstadoDeConteo()
                    refrescarBorradorLocal()
                    repository.fetchStockCounts()
                } finally {
                    _isSaving.value = false
                }
            }
            return
        }
        if (!borradores.descartarYEncolarCancelacion(countId.orEmpty())) {
            _errorMessage.value = "No se pudo descartar el conteo en este aparato. Intenta de nuevo."
            Log.e(TAG, "❌ Descartar no quedó en disco: la pantalla se conserva")
            refrescarBorradorLocal()
            return
        }
        limpiarEstadoDeConteo()
        refrescarBorradorLocal()
        if (countId != null) enviarCancelacionesPendientes()
    }

    /** Desde la tarjeta «sin terminar en este aparato». Sin red también funciona: las líneas viven en el borrador. */
    fun continuarBorrador() {
        val b = borradores.leer() ?: return
        // El borrador dice de qué sucursal es; el nombre sale del aparato, que es donde está
        // (`leer()` sólo devuelve borradores de la sucursal actual).
        _venueDelConteo = b.venueId.takeIf { it.isNotBlank() } ?: repository.venueIdActual()
        _nombreDelVenueDelConteo = repository.venueNameActual()
        if (b.countId == null) {
            // Un cíclico sin crear no existe en el servidor: se retoma entero desde el disco.
            _activeCountType.value = StockCountType.CYCLE
            _activeCount.value = null
            actualizarLineas(b.lineas)
            _selectedItemIndex.value = ConteoEnCurso.primerPendiente(b.lineas)
            _countedText.value = ""
            _countNote.value = b.nota
            notaReconocidaPorServidor = null
            notaPendienteDeEnviar = ConteoEnCurso.notaPendienteDeEnviar(b)
            _pendientesDeEnviar.value = emptySet()
            _conflictoDelServidor.value = null
            _conflictoDeRevision.value = b.conflictoRevision
            _sinRedAlEnviar.value = false
            _showCounting.value = true
            return
        }
        // Continuar ES este borrador, nunca otro: la guarda de `resumeCount` no aplica aquí.
        val desdeElDisco = StockCount(
            id = b.countId,
            type = b.type,
            status = "IN_PROGRESS",
            itemCount = b.lineas.size,
            note = b.nota.ifBlank { null },
            items = b.lineas,
            revision = b.revision,
        )
        val conflictoSeguro = b.conflictoRevision ?: if (b.revision == null) {
            ConflictoRevision(
                code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                message = ConteoEnCurso.REVISION_DESCONOCIDA,
                venueId = b.venueId,
                countId = b.countId,
            )
        } else {
            null
        }
        if (conflictoSeguro != null) {
            if (b.conflictoRevision == null) {
                borradores.guardarConflicto(b.venueId, b.countId, conflictoSeguro)
            }
            retomarConteo(
                desdeElDisco,
                conflicto = null,
                notaDelServidorConfirmada = false,
                conflictoRevision = conflictoSeguro,
            )
            return
        }
        val enServidor = repository.stockCounts.value.firstOrNull { it.id == b.countId }
        when {
            // Sin la lista del servidor (sin red) se retoma con lo que hay en disco.
            enServidor == null -> retomarConteo(
                desdeElDisco,
                conflicto = null,
                notaDelServidorConfirmada = false,
            )
            enServidor.status == "IN_PROGRESS" -> retomarConteo(enServidor, conflicto = null)
            // 🔴 Otro aparato lo cerró. Se abre lo LOCAL para consulta y se avisa; NO se borra
            // nada. El texto promete que lo contado se conserva, y borrarlo aquí era la UI
            // mintiendo (spec §5: lo local nunca se descarta en silencio). Sólo «Descartar el
            // conteo» borra, porque ahí lo decide el cajero.
            ConteoEnCurso.puedeReintentarConfirmacion(b) -> retomarConteo(
                desdeElDisco,
                conflicto = null,
                notaDelServidorConfirmada = false,
            )
            else -> retomarConteo(
                desdeElDisco,
                conflicto = ConteoEnCurso.CONFLICTO,
                notaDelServidorConfirmada = false,
            )
        }
    }

    fun confirmCount() {
        // Con el conteo ya cerrado desde otro aparato, confirmar da 404 y el mensaje sería «No se
        // pudo guardar el conteo. Intenta de nuevo.» — falso: reintentar no puede funcionar. Se
        // dice lo que de verdad pasó y no se toca el servidor.
        _conflictoDelServidor.value?.let {
            _errorMessage.value = it
            return
        }
        _conflictoDeRevision.value?.let {
            _errorMessage.value = ConteoEnCurso.descripcionConflictoRevision(it)
            return
        }
        // 🔴 Sin red no se intenta siquiera, y se DICE. Antes el botón no hacía nada visible: ni
        // diálogo, ni toast, ni un spinner que resolviera en error (D2 del QA). Confirmar es
        // online-only a propósito —el ajuste lo aplica el servidor—, así que lo honesto es
        // guardar el trabajo aquí y nombrar lo que falta.
        // Cambiar de sucursal a media captura deja este conteo fuera de alcance: confirmarlo
        // mandaría el cierre a `/venues/<la otra>/…` y el 404 se leería como «lo cerró otro
        // aparato», que sería falso.
        if (cambioDeSucursal()) return
        if (!conectado.value) {
            saveCurrentCount()
            persistirBorrador(edicionLocal = true)
            _errorMessage.value = ConteoEnCurso.CONFIRMAR_SIN_RED
            Log.w(TAG, "⛔ Confirmar sin red: el conteo se queda en el aparato")
            return
        }
        // Antes de tocar la red, como siempre: si el confirm falla, la nota y lo contado siguen
        // en el aparato.
        persistirBorrador(edicionLocal = true)
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val items = _countItems.value
                val venueDeOperacion = _venueDelConteo
                val notaCompleta = _countNote.value
                val notaEraPendiente = notaPendienteDeEnviar
                // El PUT incremental nunca manda notas. El final sólo manda una nota que cambió
                // aquí: reenviar una nota sincronizada pisaría la corrección de otro aparato.
                // La cadena vacía sí viaja: distingue «borrar la nota» de «no hay delta de nota».
                val note = _countNote.value.takeIf { notaPendienteDeEnviar }

                // Every step's Result is now checked: before, a failed
                // update/confirm STILL closed the review as a success and
                // discarded the whole count with no error.
                var cierreConfirmado: CierreDelConteo? = null

                // 🔴 Sólo un cíclico que NO existe todavía en el servidor se crea: retomar uno
                // ya creado por esta rama dejaba DOS conteos abiertos con las mismas líneas.
                if (_activeCountType.value == StockCountType.CYCLE && _activeCount.value == null) {
                    // Cycle: create on backend first, then update and confirm
                    val productIds = items.filter { !it.isIngredient }.map { it.productId }
                    val rawMaterialIds = items.filter { it.isIngredient }.mapNotNull { it.rawMaterialId }
                    val createResult = repository.createStockCount(StockCountType.CYCLE, productIds, rawMaterialIds)
                    createResult.fold(
                        onSuccess = { count ->
                            // 🔴 Se recorren las líneas del SERVIDOR, no las locales: `createStockCount`
                            // DESCARTA en silencio lo que no puede contar (un producto dado de baja
                            // entre que se listó y se creó el conteo, o uno cuyo inventario sale de una
                            // receta). Recorrer las locales dejaba esas líneas con su UUID de aquí, y
                            // el PUT del servidor es TODO-O-NADA: un id que no reconoce tumba el conteo
                            // entero con un 400 que reintentar no puede arreglar nunca (el cuerpo sale
                            // igual), dejando el trabajo del cajero atrapado. Lo contado y su countedAt
                            // siguen siendo los del aparato; la diferencia se recalcula contra el
                            // `expected` del servidor, igual que `ConteoEnCurso.fusionar`.
                            val localesPorProducto = items.associateBy { it.productId }
                            val lineasRemapeadas = count.items.map { s ->
                                val l = localesPorProducto[s.productId]
                                if (l != null && l.yaSeConto) {
                                    s.copy(counted = l.counted, difference = l.counted - s.expected, countedAt = l.countedAt)
                                } else {
                                    s
                                }
                            }
                            val mismaPantalla = venueDeOperacion != null &&
                                _venueDelConteo == venueDeOperacion &&
                                repository.venueIdActual() == venueDeOperacion &&
                                _activeCountType.value == StockCountType.CYCLE &&
                                _activeCount.value == null
                            if (!mismaPantalla) {
                                // El create de A sí ocurrió y no se puede dejar huérfano, pero su
                                // callback tardío tampoco puede mutar la pantalla ni el draft de B.
                                if (venueDeOperacion != null) {
                                    val draftCreado = BorradorDeConteo(
                                        venueId = venueDeOperacion,
                                        countId = count.id,
                                        type = StockCountType.CYCLE,
                                        lineas = lineasRemapeadas,
                                        nota = notaCompleta,
                                        notaPendienteDeEnviar = notaEraPendiente,
                                        pendientesDeEnviar = ConteoEnCurso.idsContadas(lineasRemapeadas),
                                        revision = count.revision,
                                        actualizadoEn = System.currentTimeMillis(),
                                    )
                                    if (!borradores.guardar(venueDeOperacion, draftCreado)) {
                                        Log.e(TAG, "❌ El cíclico creado en $venueDeOperacion no quedó en disco")
                                    } else {
                                        inventoryCountSyncCoordinator?.solicitarSync()
                                    }
                                }
                                return@fold
                            }
                            _activeCount.value = count
                            actualizarLineas(lineasRemapeadas)
                            // El cíclico ya existe y sus ids locales dejaron de servir. Desde este
                            // punto TODA línea contada remapeada es pendiente del servidor; se
                            // persiste countId + líneas + cola ANTES del PUT final.
                            _pendientesDeEnviar.value = ConteoEnCurso.idsContadas(_countItems.value)
                            persistirBorrador()
                            // Lo que el cajero contó y el servidor NO creó no se aplica a inventario:
                            // se DICE (nunca se calla), y aun así el conteo se confirma — bloquearlo
                            // por esto dejaría al cajero sin poder cerrar lo que sí contó.
                            val idsDelServidor = count.items.map { it.productId }.toSet()
                            val descartadasContadas = items.count { it.yaSeConto && it.productId !in idsDelServidor }
                            if (descartadasContadas > 0) {
                                _errorMessage.value = ConteoEnCurso.lineasNoIncluidas(descartadasContadas)
                                Log.w(TAG, "⚠️ $descartadasContadas línea(s) contadas no entraron al conteo ${count.id}")
                            }
                            // Sólo lo que el cajero contó: mandar el resto como counted=0 pondría
                            // en cero existencia real.
                            val serverItems = _countItems.value.filter { it.yaSeConto }
                            val cierre = cerrarEnElServidor(count.id, serverItems, note)
                            if (aplicarCierre(cierre)) cierreConfirmado = cierre
                        },
                        onFailure = { e ->
                            if (_venueDelConteo == venueDeOperacion &&
                                repository.venueIdActual() == venueDeOperacion
                            ) {
                                _errorMessage.value = ServerErrorText.humanize(e.message, "Error al crear conteo")
                            }
                        },
                    )
                } else {
                    // Full: already created, just update and confirm.
                    // Only lines the cashier actually counted: the server
                    // stamps countedAt on every received item and applies
                    // exactly those on confirm.
                    val countId = _activeCount.value?.id
                    if (countId == null) {
                        _errorMessage.value = ConteoEnCurso.SIN_CONTEO_ACTIVO
                        Log.e(TAG, "❌ Se intentó confirmar un FULL sin conteo activo")
                        return@launch
                    }
                    // Se manda todo lo contado, no sólo lo pendiente: es idempotente y deja al
                    // servidor con lo que el aparato enseña aunque un PUT incremental se perdiera.
                    val countedOnly = items.filter { it.yaSeConto }
                    val cierre = cerrarEnElServidor(countId, countedOnly, note)
                    if (aplicarCierre(cierre)) cierreConfirmado = cierre
                }

                cierreConfirmado?.let { cierre ->
                    // Success - close and refresh (data preserved on failure)
                    val vigente = borradores.leer(cierre.venueId)
                    if (vigente?.countId == cierre.countId && !borradores.borrar(cierre.venueId)) {
                        // El conteo YA se aplicó en el servidor: no se puede deshacer ni fingir que
                        // falló. Lo que queda es que el borrador huérfano no se lleve por delante el
                        // siguiente conteo — lo bloquearía como «tienes uno sin terminar».
                        Log.e(TAG, "❌ El conteo se confirmó pero el borrador sigue en disco")
                    }
                    refrescarBorradorLocal()
                    // P2 #3 de Codex: el detalle desde el que se tocó «Continuar conteo» guarda una
                    // FOTO en `IN_PROGRESS`. Sin limpiarlo, al confirmar reaparece diciendo «En
                    // progreso · Continuar conteo» sobre un conteo que acaba de cerrarse.
                    _selectedDetail.value = null
                    limpiarEstadoDeConteo()
                    // El conteo YA movió el stock: sin esto la descripción
                    // general se queda con las cantidades de antes de contar.
                    refreshStockLevels()
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

    fun selectCountDetail(count: StockCount?) {
        generacionDelDetalle += 1
        venueDelDetalle = count?.let { repository.venueIdActual() }
        ultimoVenueObservadoEnDetalle = venueDelDetalle
        _selectedDetail.value = count
        _estadoDelDetalle.value = EstadoDelDetalleDeConteo.SIN_VERIFICAR
        _detalleNoActualizado.value = false
        _puedeContinuarDetalle.value = count?.status == "IN_PROGRESS"
    }

    /**
     * Revalida contra la lectura de conteos existente, sin escribir ni estrenar un endpoint.
     * Cada selección lleva generación propia: una respuesta A vieja no puede pisar B ni un A
     * abierto después. Dos señales idénticas de lifecycle mientras la misma petición sigue viva
     * se deduplican.
     */
    fun refreshSelectedCountDetail() {
        val selected = _selectedDetail.value ?: return
        val venueActual = repository.venueIdActual()
        if (venueActual != ultimoVenueObservadoEnDetalle) {
            // Cada transición OBSERVADA invalida la respuesta anterior, incluso A→B→A.
            generacionDelDetalle += 1
            ultimoVenueObservadoEnDetalle = venueActual
        }
        if (venueActual != venueDelDetalle) {
            _detalleNoActualizado.value = true
            _puedeContinuarDetalle.value = false
            return
        }
        val key = ClaveDeRefrescoDelDetalle(
            generacion = generacionDelDetalle,
            countId = selected.id,
            venueId = venueActual,
        )
        if (!refrescosDelDetalleEnCurso.add(key)) return

        viewModelScope.launch {
            try {
                val result = repository.fetchStockCounts()
                val sigueSiendoLaMismaSeleccion =
                    key.generacion == generacionDelDetalle &&
                        key.countId == _selectedDetail.value?.id &&
                        key.venueId == repository.venueIdActual() &&
                        key.venueId == venueDelDetalle
                if (!sigueSiendoLaMismaSeleccion) return@launch

                if (result.isFailure) {
                    // La red no invalida lo que ya sabemos ni la copia que permite consultar.
                    _detalleNoActualizado.value = true
                    return@launch
                }

                _detalleNoActualizado.value = false
                val authoritative = repository.stockCounts.value.firstOrNull { it.id == key.countId }
                if (authoritative == null) {
                    // La lista mobile omite cancelados. No inventamos CANCELLED: sólo sabemos que
                    // este registro ya no está disponible en la lectura exitosa del mismo venue.
                    _estadoDelDetalle.value = EstadoDelDetalleDeConteo.NO_DISPONIBLE
                    _puedeContinuarDetalle.value = false
                } else {
                    _selectedDetail.value = authoritative
                    _estadoDelDetalle.value = EstadoDelDetalleDeConteo.DISPONIBLE
                    _puedeContinuarDetalle.value = authoritative.status == "IN_PROGRESS"
                }
            } finally {
                refrescosDelDetalleEnCurso.remove(key)
            }
        }
    }

    fun formatQuantity(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.2f", value)
        }
    }
}
