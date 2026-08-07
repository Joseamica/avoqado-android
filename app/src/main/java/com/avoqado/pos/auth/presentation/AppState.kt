package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.navigation.MainNavigationState
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.navigation.MainTabsPolicy
import com.avoqado.pos.navigation.mainContentKey
import com.avoqado.pos.navigation.resolveTerminalStartTab
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
    private val authRepository: AuthRepository,
    val timeEntryRepository: TimeEntryRepository,
    val roleManager: RoleManager,
    private val planManager: PlanManager,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val paymentSyncService: PaymentSyncService,
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val reservationRepository: com.avoqado.pos.reservations.data.ReservationRepository,
    private val tableSyncCoordinator: com.avoqado.pos.tables.data.TableSyncCoordinator,
    private val posModeManager: PosModeManager,
    val venueSwitchState: com.avoqado.pos.settings.domain.VenueSwitchState,
    connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

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

    val failedPaymentCount: StateFlow<Int> = paymentSyncService.failedCount

    /** Operaciones offline esperando replay: outbox de mesas + cola de pagos. */
    val offlinePendingCount: StateFlow<Int> = combine(
        syncOutbox.pendingCount,
        paymentSyncService.pendingCount,
    ) { intents, payments -> intents + payments }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    val showOfflineBanner: StateFlow<Boolean> = combine(
        connectivityMonitor.isConnected,
        connectivityMonitor.isServerReachable,
    ) { connected, serverReachable -> !connected || !serverReachable }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * Operaciones offline RECHAZADAS por el server al reconectar (cuarentena):
     * un cobro/ronda que el reducer refutó necesita revisión del gerente.
     * Persiste aunque vuelva la conexión — es la superficie que arregla el
     * "rechazo silencioso" (el contador antes no lo consumía nadie).
     */
    val syncRejectedCount: StateFlow<Int> = syncOutbox.rejectedCount

    /**
     * Todo lo que requiere conciliación humana, aunque la red ya haya vuelto.
     *
     * Las reservas agotadas entran aquí a propósito: sin sumarlas la cuarentena
     * las listaría, pero nadie tendría motivo para abrirla — que es como se
     * perdían antes.
     */
    val reconciliationCount: StateFlow<Int> = combine(
        syncOutbox.rejectedCount,
        paymentSyncService.failedCount,
        reservationRepository.quarantinedCount,
    ) { rejected, failed, reservas -> rejected + failed + reservas }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    private val _sessionGuardMessage = MutableStateFlow<String?>(null)
    val sessionGuardMessage: StateFlow<String?> = _sessionGuardMessage.asStateFlow()

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

    // Start destination and rebuild key travel in one StateFlow. Keeping them
    // separate creates a race where the NavHost can rebuild with the previous
    // tab while fresh terminal capabilities are arriving.
    val mainNavigation: StateFlow<MainNavigationState> = combine(
        visibleTabs,
        _roleVersion,
        posModeManager.currentMode,
        tpvSettingsRepository.terminalNavigation,
    ) { tabs, roleVersion, posMode, terminal ->
        MainNavigationState(
            startTab = resolveTerminalStartTab(tabs, terminal),
            contentKey = mainContentKey(
                secureStorage.venueId,
                posMode,
                terminal,
                roleVersion,
            ),
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainNavigationState(
                startTab = resolveTerminalStartTab(
                    visibleTabs.value,
                    tpvSettingsRepository.terminalNavigation.value,
                ),
                contentKey = mainContentKey(
                    secureStorage.venueId,
                    posModeManager.currentMode.value,
                    tpvSettingsRepository.terminalNavigation.value,
                    _roleVersion.value,
                ),
            ),
        )

    init {
        if (secureStorage.isLoggedIn) {
            viewModelScope.launch {
                // Versiones anteriores podían persistir el venue nuevo junto a
                // un JWT todavía ligado al anterior. sync/intents valida ambos,
                // así que reparar debe ocurrir antes de cualquier replay.
                // Este init vive después de los StateFlow que refreshTabs toca:
                // con Dispatchers.Main.immediate el bloque puede avanzar durante
                // la construcción y no debe observar propiedades sin inicializar.
                authRepository.repairCurrentVenueBinding()
                paymentSyncService.start()
                startOfflineOutbox()
                refreshPlanAndSettings()
            }
        }
    }

    private fun computeVisibleTabs(
        reservationsEnabled: Boolean,
        posMode: PosMode = PosMode.RETAIL,
    ): List<MainTab> = MainTabsPolicy.visibleTabs(
        reservationsEnabled = reservationsEnabled,
        // Gate del plan: si el venue no tiene reservas, el toggle local no basta.
        planAllowsReservations = planManager.hasFeature("RESERVATIONS"),
        posMode = posMode,
        canAccessPOS = roleManager.canAccessPOS,
        canAccessInventory = roleManager.canAccessInventory,
        canAccessTransactions = roleManager.canAccessTransactions,
    )

    fun refreshTabs() {
        _reservationsEnabled.value = secureStorage.reservationsEnabled
        posModeManager.reloadForCurrentVenue()
        // Un cambio de venue debe rearmar los listeners/timer del outbox con
        // el nuevo contexto; start() reinicia cuando cambia el venue activo.
        startOfflineOutbox()
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
        viewModelScope.launch {
            val venueId = secureStorage.venueId
            val blocking = paymentSyncService.blockingWorkCount() +
                (venueId?.let { syncOutbox.blockingWorkCount(it) } ?: 0) +
                secureStorage.areaTicketRecoveryCount()
            if (blocking > 0) {
                _sessionGuardMessage.value = if (blocking == 1) {
                    "Hay 1 operación offline pendiente o en conciliación. Sincronízala o resuélvela antes de cerrar sesión para no perder su contexto."
                } else {
                    "Hay $blocking operaciones offline pendientes o en conciliación. Sincronízalas o resuélvelas antes de cerrar sesión para no perder su contexto."
                }
                return@launch
            }

            paymentSyncService.stop()
            syncOutbox.stop()
            secureStorage.clearSession()
            _isLoggedIn.value = false
        }
    }

    fun clearSessionGuard() {
        _sessionGuardMessage.value = null
    }
}
