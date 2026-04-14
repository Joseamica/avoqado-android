package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppState @Inject constructor(
    private val secureStorage: SecureStorage,
    val timeEntryRepository: TimeEntryRepository,
    val roleManager: RoleManager,
    private val paymentSyncService: PaymentSyncService,
) : ViewModel() {

    init {
        if (secureStorage.isLoggedIn) {
            paymentSyncService.start()
        }
    }

    private val _isLoggedIn = MutableStateFlow(secureStorage.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val pendingPaymentCount: StateFlow<Int> = paymentSyncService.pendingCount

    val visibleTabs: List<MainTab>
        get() = MainTab.entries.filter { tab ->
            when (tab) {
                MainTab.CHECKOUT -> roleManager.canAccessPOS
                MainTab.INVENTORY -> roleManager.canAccessInventory
                MainTab.TRANSACTIONS -> roleManager.canAccessTransactions
                MainTab.NOTIFICATIONS -> true
                MainTab.MORE -> true
            }
        }

    fun onLoginSuccess() {
        _isLoggedIn.value = true
        paymentSyncService.start()
    }

    fun onLogout() {
        paymentSyncService.stop()
        secureStorage.clearSession()
        _isLoggedIn.value = false
    }
}
