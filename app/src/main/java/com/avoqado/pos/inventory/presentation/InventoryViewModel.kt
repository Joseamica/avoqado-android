package com.avoqado.pos.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.model.StockSortOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val isLoading = repository.isLoading

    private val _selectedTab = MutableStateFlow(InventoryTab.OVERVIEW)
    val selectedTab: StateFlow<InventoryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(StockSortOption.NAME_ASC)
    val sortOption: StateFlow<StockSortOption> = _sortOption.asStateFlow()

    init {
        refresh()
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

    fun refresh() {
        viewModelScope.launch {
            repository.fetchStockOverview()
            repository.fetchStockCounts()
        }
    }
}
