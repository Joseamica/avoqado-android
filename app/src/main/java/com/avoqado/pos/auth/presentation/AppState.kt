package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.settings.domain.PosModeManager
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
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val tableSyncCoordinator: com.avoqado.pos.tables.data.TableSyncCoordinator,
    private val posModeManager: PosModeManager,
    val venueSwitchState: com.avoqado.pos.settings.domain.VenueSwitchState,
    connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    init {
        if (secureStorage.isLoggedIn) {
            paymentSyncService.start()
            startOfflineOutbox()
            refreshPlanAndSettings()
        }
    }

    /** Offline-first Corte B: replay del outbox de comandas + reconciliación. */
    private fun startOfflineOutbox() {
        secureStorage.venueId?.let { venueId ->
            syncOutbox.start(venueId)
            tableSyncCoordinator.start()
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

    init {
        // Reactive logout: a wiped session (failed refresh) now routes the user
        // to Landing instead of leaving a zombie session behind.
        viewModelScope.launch {
            secureStorage.sessionInvalidated.collect {
                _isLoggedIn.value = false
            }
        }
    }

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

    // Bumped on login/logout/venue-switch so the visibleTabs combine re-emits
    // even when reservations/venueMode didn't change but the role did.
    private val _roleVersion = MutableStateFlow(0)

    val visibleTabs: StateFlow<List<MainTab>> = combine(
        _reservationsEnabled,
        _roleVersion,
        posModeManager.currentMode,
    ) { enabled, _, posMode -> computeVisibleTabs(enabled, posMode) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = computeVisibleTabs(_reservationsEnabled.value, posModeManager.currentMode.value),
        )

    // Llave de rebuild total (Square): cambiar de sucursal o de modo recrea el
    // NavHost completo — todas las pantallas montan de cero y recargan datos
    // del venue/modo NUEVO en vez de quedarse con los del anterior.
    val contentKey: StateFlow<String> = combine(
        _roleVersion,
        posModeManager.currentMode,
    ) { _, posMode -> "${'$'}{secureStorage.venueId}:${'$'}{posMode.key}" }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = "${'$'}{secureStorage.venueId}:${'$'}{posModeManager.currentMode.value.key}",
        )

    private fun computeVisibleTabs(
        reservationsEnabled: Boolean,
        posMode: PosMode = PosMode.RETAIL,
    ): List<MainTab> {
        // UN solo modo de dispositivo (PosMode) — el VenueMode legacy
        // (Estándar/Reservas) quedó solo como migración de storage. Gate del
        // plan ANDed con el toggle local: si el venue nuevo no tiene reservas,
        // un modo Reservas persistido cae solo a Retail.
        val planAllowsReservations = planManager.hasFeature("RESERVATIONS")
        val inReservationsMode = reservationsEnabled &&
            planAllowsReservations &&
            posMode == PosMode.RESERVATIONS
        // TABLE_SERVICE (PRO): the Mesas tab appears only in Restaurante mode.
        // Plan gate ANDed like reservations — fail-open when the plan is unknown;
        // the screen itself shows the PRO upsell when the feature is locked.
        val inRestaurantMode = posMode == PosMode.RESTAURANT
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
        } else if (inRestaurantMode) {
            // Mesas leads — the restaurant flow starts at the floor plan.
            // Inventory stays alongside (same 6-tab pattern as reservations mode).
            listOf(
                MainTab.TABLES,
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
                MainTab.TABLES -> roleManager.canAccessPOS
            }
        }
    }

    fun refreshTabs() {
        _reservationsEnabled.value = secureStorage.reservationsEnabled
        posModeManager.reloadForCurrentVenue()
        _roleVersion.value += 1
    }

    fun onLoginSuccess() {
        _isLoggedIn.value = true
        paymentSyncService.start()
        startOfflineOutbox()
        refreshTabs()
        // Pull venue settings (incl. the plan block) right after login so
        // plan gates apply without waiting for a venue switch.
        refreshPlanAndSettings()
    }

    fun onLogout() {
        paymentSyncService.stop()
        syncOutbox.stop()
        secureStorage.clearSession()
        _isLoggedIn.value = false
    }
}
