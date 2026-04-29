package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.domain.VenueMode
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

    private val _reservationsEnabled = MutableStateFlow(secureStorage.reservationsEnabled)
    private val _venueMode = MutableStateFlow(VenueMode.fromStorage(secureStorage.venueMode))

    val visibleTabs: StateFlow<List<MainTab>> = combine(
        _reservationsEnabled,
        _venueMode,
    ) { enabled, mode -> computeVisibleTabs(enabled, mode) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = computeVisibleTabs(_reservationsEnabled.value, _venueMode.value),
        )

    private fun computeVisibleTabs(
        reservationsEnabled: Boolean,
        venueMode: VenueMode,
    ): List<MainTab> {
        val inReservationsMode = reservationsEnabled && venueMode == VenueMode.RESERVATIONS
        val ordered = if (inReservationsMode) {
            // Calendar leads, but Inventory is preserved alongside.
            listOf(
                MainTab.CALENDAR,
                MainTab.CHECKOUT,
                MainTab.INVENTORY,
                MainTab.TRANSACTIONS,
                MainTab.NOTIFICATIONS,
                MainTab.MORE,
            )
        } else {
            listOf(
                MainTab.CHECKOUT,
                MainTab.INVENTORY,
                MainTab.TRANSACTIONS,
                MainTab.NOTIFICATIONS,
                MainTab.MORE,
            )
        }
        return ordered.filter { tab ->
            when (tab) {
                MainTab.CHECKOUT -> roleManager.canAccessPOS
                MainTab.INVENTORY -> roleManager.canAccessInventory
                MainTab.TRANSACTIONS -> roleManager.canAccessTransactions
                MainTab.NOTIFICATIONS -> true
                MainTab.MORE -> true
                MainTab.CALENDAR -> true
            }
        }
    }

    fun refreshTabs() {
        _reservationsEnabled.value = secureStorage.reservationsEnabled
        _venueMode.value = VenueMode.fromStorage(secureStorage.venueMode)
    }

    fun onLoginSuccess() {
        _isLoggedIn.value = true
        paymentSyncService.start()
        refreshTabs()
    }

    fun onLogout() {
        paymentSyncService.stop()
        secureStorage.clearSession()
        _isLoggedIn.value = false
    }
}
