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
    private val pendingGrantQueue: com.avoqado.pos.customers.data.PendingGrantQueue,
    private val roleManager: com.avoqado.pos.core.domain.RoleManager,
) : ViewModel() {

    /// Role gate for selling/redeeming paid credit packs (WAITER can view a
    /// customer but must not move their money).
    val canManageCustomers: Boolean get() = roleManager.canManageCustomers

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

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    fun load(customerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = articlesRepository.fetchCustomerCredits(customerId)
            _loadError.value = result == null
            _balances.value = result ?: emptyList()
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

    /** Grant one or more packs to a customer after an in-person sale (used by the
     *  sell-from-grid flow: charge goes through the cart, credits granted on success). */
    fun grantPacks(packIds: List<String>, customerId: String) {
        viewModelScope.launch {
            packIds.forEach { packId ->
                val ok = articlesRepository.sellPackToCustomer(packId, customerId)
                if (!ok) {
                    // The customer already PAID — never drop the grant. Queue it
                    // durably; the queue retries on next app start / drain.
                    pendingGrantQueue.enqueue(packId, customerId)
                }
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
