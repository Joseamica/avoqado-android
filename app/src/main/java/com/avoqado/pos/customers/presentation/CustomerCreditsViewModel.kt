package com.avoqado.pos.customers.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.CreditPurchaseBalance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Staff-facing prepaid credit-pack ("membresías") operations for a customer:
 * balance, sell in person, redeem. Mirrors iOS CreditPacksRepository usage.
 */
@HiltViewModel
class CustomerCreditsViewModel @Inject constructor(
    private val articlesRepository: ArticlesRepository,
) : ViewModel() {

    private val _balances = MutableStateFlow<List<CreditPurchaseBalance>>(emptyList())
    val balances: StateFlow<List<CreditPurchaseBalance>> = _balances.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _busyBalanceId = MutableStateFlow<String?>(null)
    val busyBalanceId: StateFlow<String?> = _busyBalanceId.asStateFlow()

    private val _sellingPackId = MutableStateFlow<String?>(null)
    val sellingPackId: StateFlow<String?> = _sellingPackId.asStateFlow()

    /** Packs available to sell (reuses the existing list load). */
    val packs: StateFlow<List<CreditPack>> = articlesRepository.creditPacks

    fun load(customerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _balances.value = articlesRepository.fetchCustomerCredits(customerId)
            _isLoading.value = false
        }
    }

    fun loadPacks() {
        viewModelScope.launch { articlesRepository.fetchCreditPacks() }
    }

    fun sell(packId: String, customerId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _sellingPackId.value = packId
            val ok = articlesRepository.sellPackToCustomer(packId, customerId)
            _sellingPackId.value = null
            if (ok) {
                load(customerId)
                onDone()
            }
        }
    }

    fun redeem(balanceId: String, customerId: String) {
        viewModelScope.launch {
            _busyBalanceId.value = balanceId
            val ok = articlesRepository.redeemCredit(balanceId)
            _busyBalanceId.value = null
            if (ok) load(customerId)
        }
    }
}
