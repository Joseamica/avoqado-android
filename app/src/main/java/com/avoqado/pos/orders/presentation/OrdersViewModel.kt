package com.avoqado.pos.orders.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderSummary
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

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: OrdersRepository,
    refreshGateFactory: RefreshGateFactory,
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
        viewModelScope.launch {
            repository.loadOrderDetail(orderId)
        }
    }

    fun clearSelection() {
        _selectedOrderId.value = null
        repository.clearSelectedOrder()
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
