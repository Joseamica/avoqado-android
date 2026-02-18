package com.avoqado.pos.transactions.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val transactions = repository.transactions
    val isLoading = repository.isLoading

    private val _selectedFilter = MutableStateFlow(TransactionFilter.TODAY)
    val selectedFilter: StateFlow<TransactionFilter> = _selectedFilter.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    init {
        loadTransactions()
    }

    fun selectFilter(filter: TransactionFilter) {
        _selectedFilter.value = filter
        loadTransactions()
    }

    fun selectTransaction(transaction: Transaction?) {
        _selectedTransaction.value = transaction
    }

    fun refresh() {
        loadTransactions()
    }

    private fun loadTransactions() {
        viewModelScope.launch {
            val now = LocalDate.now()
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE

            val (start, end) = when (_selectedFilter.value) {
                TransactionFilter.ALL -> null to null
                TransactionFilter.TODAY -> now.format(formatter) to now.format(formatter)
                TransactionFilter.WEEK -> now.minusDays(7).format(formatter) to now.format(formatter)
                TransactionFilter.MONTH -> now.withDayOfMonth(1).format(formatter) to now.format(formatter)
            }
            repository.fetchTransactions(startDate = start, endDate = end)
        }
    }
}
