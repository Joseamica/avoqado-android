package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppState @Inject constructor(
    private val secureStorage: SecureStorage,
    val timeEntryRepository: TimeEntryRepository,
    val roleManager: RoleManager,
    private val paymentSyncService: PaymentSyncService,
    connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    init {
        if (secureStorage.isLoggedIn) {
            paymentSyncService.start()
        }
    }

    private val _isLoggedIn = MutableStateFlow(secureStorage.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val pendingPaymentCount: StateFlow<Int> = paymentSyncService.pendingCount

    val showOfflineBanner: StateFlow<Boolean> = combine(
        connectivityMonitor.isConnected,
        connectivityMonitor.isServerReachable,
    ) { connected, serverReachable -> !connected || !serverReachable }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val visibleTabs: List<MainTab>
        get() = MainTab.entries.filter { tab ->
            when (tab) {
                MainTab.CHECKOUT -> roleManager.canAccessPOS
                MainTab.INVENTORY -> roleManager.canAccessInventory
                MainTab.TRANSACTIONS -> roleManager.canAccessTransactions
                MainTab.NOTIFICATIONS -> true
                MainTab.MORE -> true
                MainTab.CALENDAR -> false // controlled by MainTabHostViewModel
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
