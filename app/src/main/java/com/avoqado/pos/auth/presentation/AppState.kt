package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.domain.VenueMode
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppState @Inject constructor(
    private val secureStorage: SecureStorage,
    val timeEntryRepository: TimeEntryRepository,
    val roleManager: RoleManager,
    private val planManager: PlanManager,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val paymentSyncService: PaymentSyncService,
    connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    init {
        if (secureStorage.isLoggedIn) {
            paymentSyncService.start()
            refreshPlanAndSettings()
        }
    }

    /**
     * Fire-and-forget settings refresh (carries the venue's plan block) +
     * tab recompute once it lands. Never blocks login/startup; errors are
     * swallowed inside the repository → plan stays as-is → fail-open.
     */
    private fun refreshPlanAndSettings() {
        viewModelScope.launch {
            tpvSettingsRepository.refreshSettings()
            refreshTabs()
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

    // Bumped on login/logout/venue-switch so the visibleTabs combine re-emits
    // even when reservations/venueMode didn't change but the role did.
    private val _roleVersion = MutableStateFlow(0)

    val visibleTabs: StateFlow<List<MainTab>> = combine(
        _reservationsEnabled,
        _venueMode,
        _roleVersion,
    ) { enabled, mode, _ -> computeVisibleTabs(enabled, mode) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = computeVisibleTabs(_reservationsEnabled.value, _venueMode.value),
        )

    private fun computeVisibleTabs(
        reservationsEnabled: Boolean,
        venueMode: VenueMode,
    ): List<MainTab> {
        // Plan gate ANDed with the local toggle: a stale local
        // `reservationsEnabled` can never expose the Calendar tab on a plan
        // without RESERVATIONS. Fail-open when the plan is unknown.
        val planAllowsReservations = planManager.hasFeature("RESERVATIONS")
        val inReservationsMode = reservationsEnabled &&
            planAllowsReservations &&
            venueMode == VenueMode.RESERVATIONS
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
        _roleVersion.value += 1
    }

    fun onLoginSuccess() {
        _isLoggedIn.value = true
        paymentSyncService.start()
        refreshTabs()
        // Pull venue settings (incl. the plan block) right after login so
        // plan gates apply without waiting for a venue switch.
        refreshPlanAndSettings()
    }

    fun onLogout() {
        paymentSyncService.stop()
        secureStorage.clearSession()
        _isLoggedIn.value = false
    }
}
