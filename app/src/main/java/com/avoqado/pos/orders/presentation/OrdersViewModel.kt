package com.avoqado.pos.orders.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.RoutableItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Texto EXACTO cuando la comanda sí salió — vive aquí (no un literal repetido) para que
 * [OrdersScreen] pueda distinguir éxito de aviso sin arriesgarse a que los dos textos diverjan.
 */
internal const val MENSAJE_COMANDA_REIMPRESA = "Comanda reimpresa"

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: OrdersRepository,
    refreshGateFactory: RefreshGateFactory,
    private val comandaDispatcher: ComandaDispatcher,
    private val secureStorage: SecureStorage,
    /** Para resolver la CATEGORÍA de cada producto al reimprimir — ver [reimprimirComanda]. */
    private val productsRepository: ProductsRepository,
) : ViewModel() {

    // MARK: - Refresco (spec estrategia-de-refresco)

    private val gate = refreshGateFactory.create(viewModelScope)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // MARK: - Repository-backed State

    val orders = repository.orders
    val selectedOrder = repository.selectedOrder
    val isLoading = repository.isLoading
    val isLoadingMore = repository.isLoadingMore
    val isLoadingDetail = repository.isLoadingDetail
    val errorMessage = repository.errorMessage
    val hasMore = repository.hasMore

    // MARK: - Local State

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _selectedOrderId = MutableStateFlow<String?>(null)
    val selectedOrderId: StateFlow<String?> = _selectedOrderId.asStateFlow()

    // MARK: - Init

    init {
        // La carga inicial la dispara la UI vía el gate (autoRefresh).
        observeSearch()
    }

    // MARK: - Public Methods

    fun updateSearch(query: String) {
        _searchText.value = query
    }

    fun setStatusFilter(status: String?) {
        _statusFilter.value = status
        // Otro filtro = otra identidad (spec §4.4): invalida el TTL y re-pide.
        invalidateAndRefresh()
    }

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> = repository.loadOrders(
        page = 1,
        search = _searchText.value.takeIf { it.isNotBlank() },
        status = _statusFilter.value,
        append = false,
    )

    // Pantalla de solo lectura: sin borradores que proteger (spec §4.5).
    fun autoRefresh() {
        viewModelScope.launch {
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            _isManualRefreshing.value = true
            try {
                gate.run(workInProgress = { false }, manual = true, block = ::refreshNow)
            } finally {
                _isManualRefreshing.value = false
            }
        }
    }

    /** Búsqueda o filtro nuevos = identidad nueva: invalida el TTL y re-pide. */
    fun invalidateAndRefresh() {
        gate.invalidate()
        viewModelScope.launch {
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    fun loadMore() {
        val currentPage = repository.currentPage.value
        val hasMore = repository.hasMore.value
        if (!hasMore || repository.isLoadingMore.value) return

        viewModelScope.launch {
            repository.loadOrders(
                page = currentPage + 1,
                search = _searchText.value.takeIf { it.isNotBlank() },
                status = _statusFilter.value,
                append = true,
            )
        }
    }

    fun selectOrder(orderId: String) {
        _selectedOrderId.value = orderId
        // 🔴 Ya NO se borra el mensaje aquí. Era un parche para que el aviso de un pedido no se
        // arrastrara al siguiente, y ahora eso lo garantiza el propio `mensajeDeReimpresion`,
        // que filtra por id. Borrarlo además PERDÍA información: el cajero que abre otro pedido
        // y vuelve ya no encontraba el resultado de su reimpresión.
        viewModelScope.launch {
            repository.loadOrderDetail(orderId)
        }
    }

    fun clearSelection() {
        _selectedOrderId.value = null
        _mensajeDeReimpresion.value = null
        repository.clearSelectedOrder()
    }

    // MARK: - Reimpresión de comanda (mostrador, Task 6)

    private val venueId: String? get() = secureStorage.venueId

    private val _isReimprimiendoComanda = MutableStateFlow(false)
    val isReimprimiendoComanda: StateFlow<Boolean> = _isReimprimiendoComanda.asStateFlow()

    /**
     * El resultado de reimprimir pertenece a UN pedido concreto.
     *
     * 🔴 En tablet, el cajero puede seleccionar otro pedido mientras una reimpresión sigue en
     * vuelo. Publicando el texto a secas, el pedido B mostraba «Comanda reimpresa» — de una
     * reimpresión que fue del pedido A y que B nunca pidió (P1 #9 de la auditoría de Codex,
     * 2026-09-07). Atarlo al id no PIERDE el mensaje: reaparece si vuelve a ese pedido.
     */
    private data class MensajeDeReimpresion(val orderId: String, val texto: String)

    private val _mensajeDeReimpresion = MutableStateFlow<MensajeDeReimpresion?>(null)
    val mensajeDeReimpresion: StateFlow<String?> =
        combine(_mensajeDeReimpresion, _selectedOrderId) { mensaje, seleccionado ->
            mensaje?.takeIf { it.orderId == seleccionado }?.texto
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * El botón «Volver a imprimir» sólo existía en Mesas ([com.avoqado.pos.tables.presentation.TableOrderViewModel.reprintComandas]).
     * En mostrador — el caso real que abrió esta tarea— una comanda que no sale deja al cajero
     * sin nada que tocar más que gritarle el pedido a cocina. Rearma la comanda desde la orden
     * YA cobrada y la manda por el MISMO [ComandaDispatcher] que usa el cobro, marcada
     * `REIMPRESIÓN` para que cocina no la confunda con un pedido nuevo.
     *
     * 🔴 El resultado se MIRA, nunca se asume: cantar éxito sin haber impreso nada es
     * exactamente el bug ya medido en este repo (T3: ~10s esperando una impresora inalcanzable
     * y aun así paloma verde). [mensajeDeEstado] sólo dice "reimpresa" cuando el despachador
     * confirma [EstadoDeComanda.Salio].
     */
    fun reimprimirComanda(orderId: String) {
        if (_isReimprimiendoComanda.value) return
        val order = repository.selectedOrder.value?.takeIf { it.id == orderId }
        if (order == null) {
            _mensajeDeReimpresion.value = MensajeDeReimpresion(orderId, "No se encontró el pedido para reimprimir")
            return
        }

        // Mismo filtro que TableOrderViewModel.reprintComandas: sólo lo que es un PRODUCTO
        // real va a cocina — un cargo o un importe libre sin productId no tiene nada que
        // preparar.
        // 🔴 La CATEGORÍA decide a qué estación va cada producto. `OrderDetailItem` no la trae
        // (el servidor no la manda en el detalle), y dejarla en `null` hacía que el ruteo por
        // categoría no aplicara: un café que se imprime en Barra se iba a Cocina, la estación
        // por defecto — en silencio (P1 #8 de la auditoría de Codex, 2026-09-07). Se resuelve
        // del catálogo local por `productId`, que es EXACTAMENTE de donde la saca el carrito
        // (`CartViewModel`), así que reimprimir rutea igual que imprimió la primera vez.
        val categoriaPorProducto = productsRepository.products.value.associate { it.id to it.categoryId }

        val lineas = order.items
            ?.filter { it.productId != null }
            ?.map { item ->
                RoutableItem(
                    orderItemId = item.id,
                    productId = item.productId,
                    categoryId = item.productId?.let { categoriaPorProducto[it] },
                    productName = item.productName.ifBlank { "Artículo" },
                    quantity = item.quantity,
                    modifiers = item.modifiers?.map { modifier -> modifier.name } ?: emptyList(),
                    notes = item.notes,
                )
            }
            ?: emptyList()

        if (lineas.isEmpty()) {
            _mensajeDeReimpresion.value = MensajeDeReimpresion(orderId, "No hay artículos para reimprimir en este pedido")
            return
        }

        viewModelScope.launch {
            _isReimprimiendoComanda.value = true
            try {
                val estado = comandaDispatcher.dispatch(
                    venueId = venueId,
                    lines = lineas,
                    orderNumber = order.orderNumber,
                    orderType = "REIMPRESIÓN",
                    orderId = order.id,
                )
                _mensajeDeReimpresion.value = MensajeDeReimpresion(orderId, mensajeDeEstado(estado))
            } finally {
                _isReimprimiendoComanda.value = false
            }
        }
    }

    /** [EstadoDeComanda.Insistiendo] nunca llega aquí: [ComandaDispatcher.dispatch] sólo lo
     *  reporta por el callback `alCambiarEstado` mientras insiste — lo que DEVUELVE es
     *  siempre [EstadoDeComanda.Salio] o [EstadoDeComanda.NoSalio] (o `null` sin renglones). */
    private fun mensajeDeEstado(estado: EstadoDeComanda?): String = when (estado) {
        is EstadoDeComanda.NoSalio ->
            "No salió la comanda de: ${estado.estaciones.joinToString(", ")} · " +
                (estado.causa ?: "La impresora no respondió.")
        else -> MENSAJE_COMANDA_REIMPRESA
    }

    // MARK: - Grouping

    fun groupOrdersByDate(orders: List<OrderSummary>): List<Pair<String, List<OrderSummary>>> {
        return orders.groupBy { it.dateGroup }
            .toList()
            .sortedByDescending { (_, items) ->
                // Sort by first item's createdAt to maintain chronological order
                items.firstOrNull()?.createdAt ?: ""
            }
    }

    // MARK: - Private

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchText
                // drop(1) ANTES del debounce: la emisión inicial cruda no es una
                // búsqueda del usuario (la lección del fix de Transacciones — con
                // el orden invertido, teclear en los primeros 400 ms perdía la
                // primera búsqueda).
                .drop(1)
                .debounce(400)
                .distinctUntilChanged()
                .collect { invalidateAndRefresh() }
        }
    }
}
