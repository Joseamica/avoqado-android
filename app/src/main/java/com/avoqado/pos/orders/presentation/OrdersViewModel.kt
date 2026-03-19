package com.avoqado.pos.orders.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: OrdersRepository,
) : ViewModel() {

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
        refresh()
        observeSearch()
    }

    // MARK: - Public Methods

    fun updateSearch(query: String) {
        _searchText.value = query
    }

    fun setStatusFilter(status: String?) {
        _statusFilter.value = status
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.loadOrders(
                page = 1,
                search = _searchText.value.takeIf { it.isNotBlank() },
                status = _statusFilter.value,
                append = false,
            )
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
                .debounce(400)
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }
}
