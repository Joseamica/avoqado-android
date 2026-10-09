package com.avoqado.pos.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val comandasPendientesStore: com.avoqado.pos.printing.data.ComandasPendientesStore,
    private val replayDeComandas: com.avoqado.pos.printing.data.ReplayDeComandasPendientes,
    private val reservationRepository: com.avoqado.pos.reservations.data.ReservationRepository,
    private val tableSyncCoordinator: com.avoqado.pos.tables.data.TableSyncCoordinator,
    private val posModeManager: PosModeManager,
    private val inventoryCountSyncCoordinator: InventoryCountSyncCoordinator,
    cashDrawerRepository: com.avoqado.pos.cashdrawer.data.CashDrawerRepository,
    val venueSwitchState: com.avoqado.pos.settings.domain.VenueSwitchState,
    connectivityMonitor: ConnectivityMonitor,
    private val printConfigRepository: com.avoqado.pos.printing.routing.PrintConfigRepository,
) : ViewModel() {

    /**
     * Member injection keeps the existing pure-JVM constructor callers usable;
     * Hilt supplies the process singleton in production.
     */
    @Inject
    lateinit var deviceCapabilitySyncCoordinator:
        com.avoqado.pos.customerdisplay.DeviceCapabilitySyncCoordinator

    /**
     * El motor de la merma. Por miembro, igual que [deviceCapabilitySyncCoordinator], para no romper
     * a quien construye este ViewModel a mano en las pruebas de JVM.
     */
    @Inject
    lateinit var wasteSyncCoordinator: com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator

    /**
     * Etapa 3 del KDS (3.5, D12): el transporte de la red local y el hub Premium viven con la app; aquí sólo se
     * encienden y apagan. Por miembro, como [wasteSyncCoordinator], para no romper a quien construye este ViewModel a
     * mano en las pruebas de JVM.
     */
    @Inject
    lateinit var transporteLan: com.avoqado.pos.core.data.lan.TransporteLan

    @Inject
    lateinit var lanHubService: com.avoqado.pos.core.data.lan.LanHubService

    /** Etapa 3 del KDS (3.5, D7): las entregas por WiFi que quedaron en disco se retoman al abrir. */
    @Inject
    lateinit var replayDeEntregasKds: com.avoqado.pos.printing.data.ReplayDeEntregasKds

    @Inject
    lateinit var preparationDelivery: com.avoqado.pos.kds.data.PreparationDeliveryService

    private fun detenerRedLocal() {
        if (::preparationDelivery.isInitialized) preparationDelivery.stop()
        if (::lanHubService.isInitialized) lanHubService.stop()
        if (::transporteLan.isInitialized) transporteLan.detener()
        if (::replayDeEntregasKds.isInitialized) replayDeEntregasKds.detener()
    }

    private fun notifyDeviceSessionChanged() {
        if (::deviceCapabilitySyncCoordinator.isInitialized) {
            deviceCapabilitySyncCoordinator.onSessionChanged()
        }
    }

    private var inventorySyncRunning = false
    private var inventorySyncVenue: String? = null

    private fun startInventorySyncIfNeeded() {
        if (!secureStorage.isLoggedIn) return
        val venue = secureStorage.venueId
        if (inventorySyncRunning && inventorySyncVenue == venue) return
        inventoryCountSyncCoordinator.start(viewModelScope)
        // La merma vive con la sesión, como el conteo: su motor arranca y para con ella.
        if (::wasteSyncCoordinator.isInitialized) wasteSyncCoordinator.start(viewModelScope)
        inventorySyncRunning = true
        inventorySyncVenue = venue
    }

    private fun stopInventorySync() {
        inventoryCountSyncCoordinator.stop()
        if (::wasteSyncCoordinator.isInitialized) wasteSyncCoordinator.stop()
        inventorySyncRunning = false
        inventorySyncVenue = null
    }

    /** Offline-first Corte B: replay del outbox de comandas + reconciliación. */
    private fun startOfflineOutbox() {
        secureStorage.venueId?.let { venueId ->
            syncOutbox.start(venueId)
            tableSyncCoordinator.start()
            // 🔴 La comanda que no salió: se recupera lo guardado de ESTE venue y se arranca el
            // reloj que la reintenta sola. Es lo que hace que salga al prender la impresora, sin
            // que nadie tenga que tocar un botón. Ver [ReplayDeComandasPendientes].
            comandasPendientesStore.cargar(venueId)
            replayDeComandas.iniciar(viewModelScope)
            // Etapa 3 del KDS (3.4): la config de impresión entra YA (la guardada si no hay red): la banda de «Sin
            // conexión» tiene que poder decir qué estaciones salen en papel desde el primer minuto, no desde la primera
            // comanda. `topeMs = 0` = no espera a la red; la descarga sigue sola.
            viewModelScope.launch { printConfigRepository.refreshConTope(venueId, topeMs = 0) }
            // Etapa 3 del KDS (3.5, D12): la red local sigue a la sucursal. Idempotente por venue; con otra reinicia.
            if (::lanHubService.isInitialized) lanHubService.sincronizarVenue(venueId)
            if (::transporteLan.isInitialized) transporteLan.iniciar(venueId)
            if (::preparationDelivery.isInitialized) preparationDelivery.start(venueId)
            // Ronda 2 (N2): una pasada al abrir y otra cada minuto, como el reloj de la libreta. Idempotente: con otra
            // sucursal sólo cambia a cuál apunta. Su lazo atrapa todo menos la cancelación (N6: al cerrar sesión no hay
            // «tropezó» falso).
            if (::replayDeEntregasKds.isInitialized) replayDeEntregasKds.iniciar(viewModelScope, venueId)
        }
    }

    /**
     * Fire-and-forget settings refresh (carries the venue's plan block) +
     * tab recompute once it lands. Never blocks login/startup; errors are
     * swallowed inside the repository → plan stays as-is → fail-open.
     */
    private var settingsRefreshJob: Job? = null

    private fun refreshPlanAndSettings(rebuildNavigation: Boolean = true) {
        if (settingsRefreshJob?.isActive == true) return
        settingsRefreshJob = viewModelScope.launch {
            tpvSettingsRepository.refreshSettings()
            if (_isLoggedIn.value) {
                if (rebuildNavigation) refreshTabs() else {
                    _reservationsEnabled.value = secureStorage.reservationsEnabled
                    _accessVersion.value += 1
                }
            }
        }
    }

    /** A refreshed entitlement must not change the NavHost key or discard the current sale. */
    fun refreshPaidAccess() {
        if (_isLoggedIn.value) refreshPlanAndSettings(rebuildNavigation = false)
    }

    private val _isLoggedIn = MutableStateFlow(secureStorage.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        // Reactive logout: a wiped session (failed refresh) now routes the user
        // to Landing instead of leaving a zombie session behind.
        viewModelScope.launch {
            secureStorage.sessionInvalidated.collect {
                stopInventorySync()
                detenerRedLocal()
                _isLoggedIn.value = false
                notifyDeviceSessionChanged()
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

    /**
     * 🔴 LA ESPERA DE LOS COBROS SE DICE, NO SE DEDUCE (P2-4).
     *
     * Cuando la apertura de la caja no llega al servidor, los cobros encolados esperan a propósito
     * (ver `losCobrosPuedenSalir`) — pero la red está bien, así que el banner de «sin conexión» NO
     * sale y el cajero, que está en Cobrar y no en Caja, sólo ve subir un contador de pendientes
     * que va a leer como «no hay internet». Un estado que sólo vive en un `Log.w` no lo puede
     * resolver nadie.
     *
     * `null` = no hay nada que decir. La banda es la MISMA de siempre: ámbar, nunca roja.
     */
    val avisoDeCobrosRetenidos: StateFlow<String?> = cashDrawerRepository.estadoDeLosCobros
        .map { com.avoqado.pos.cashdrawer.data.textoDeCobrosRetenidos(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Etapa 3 del KDS (3.5, D11): sin red, las estaciones «sólo pantalla» salen en papel si la pantalla no contesta; la
     * banda lo dice fijo — nunca en rojo, nunca un aviso por comanda — SALVO en la tablet que ES la pantalla (receptor
     * vivo): ahí es cierto para las cajas y confuso para la cocina.
     * `by lazy`: combina con [transporteLan], que Hilt inyecta por miembro DESPUÉS del constructor.
     */
    val avisoDeCocinaSinRed: StateFlow<String?> by lazy {
        combine(printConfigRepository.config, transporteLan.receptorActivo) { config, esPantalla ->
            if (esPantalla) null else com.avoqado.pos.printing.routing.KitchenDeliveryPolicy.avisoSinRed(config)
        }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = null)
    }

    /** D11: tras 3 entregas seguidas sin acuse a una estación, «La pantalla de Barra no se alcanza por el WiFi», con o sin internet. */
    val avisoDeRacha: StateFlow<String?> by lazy {
        combine(printConfigRepository.config, transporteLan.racha.sinAlcance) { config, sinAlcance ->
            com.avoqado.pos.printing.routing.KitchenDeliveryPolicy.avisoDeRacha(sinAlcance, config)
        }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = null)
    }

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
    val reconciliationCount: StateFlow<Int> by lazy {
        // Los cobros en efectivo interrumpidos (la app murió a media petición) también piden a una persona.
        val interrumpidos = if (::cashPaymentRepositoryParaAvisos.isInitialized) {
            cashPaymentRepositoryParaAvisos.observarSinConfirmar().map { it.size }
        } else {
            kotlinx.coroutines.flow.flowOf(0)
        }
        combine(
            syncOutbox.rejectedCount,
            paymentSyncService.failedCount,
            reservationRepository.quarantinedCount,
            interrumpidos,
        ) { rejected, failed, reservas, cobros -> rejected + failed + reservas + cobros }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )
    }

    /** Por miembro, como [deviceCapabilitySyncCoordinator]: no rompe a quien construye este ViewModel a mano. */
    @Inject
    lateinit var cashPaymentRepositoryParaAvisos: com.avoqado.pos.payment.data.CashPaymentRepository

    private val _sessionGuardMessage = MutableStateFlow<String?>(null)
    val sessionGuardMessage: StateFlow<String?> = _sessionGuardMessage.asStateFlow()

    private val _reservationsEnabled = MutableStateFlow(secureStorage.reservationsEnabled)

    // Bumped on login/logout/venue-switch so the visibleTabs combine re-emits
    // even when reservations/venueMode didn't change but the role did.
    private val _roleVersion = MutableStateFlow(0)
    private val _accessVersion = MutableStateFlow(0)

    val visibleTabs: StateFlow<List<MainTab>> = combine(
        _reservationsEnabled,
        _roleVersion,
        posModeManager.currentMode,
        _accessVersion,
    ) { enabled, _, posMode, _ -> computeVisibleTabs(enabled, posMode) }
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
        viewModelScope.launch {
            combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { connected, reachable ->
                connected && reachable
            }.distinctUntilChanged().drop(1).collectLatest { ready ->
                if (ready) {
                    delay(1000)
                    refreshPaidAccess()
                }
            }
        }
        if (secureStorage.isLoggedIn) {
            viewModelScope.launch {
                // Versiones anteriores podían persistir el venue nuevo junto a
                // un JWT todavía ligado al anterior. sync/intents valida ambos,
                // así que reparar debe ocurrir antes de cualquier replay.
                // Este init vive después de los StateFlow que refreshTabs toca:
                // con Dispatchers.Main.immediate el bloque puede avanzar durante
                // la construcción y no debe observar propiedades sin inicializar.
                authRepository.repairCurrentVenueBinding()
                startInventorySyncIfNeeded()
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
        startInventorySyncIfNeeded()
        _roleVersion.value += 1
        notifyDeviceSessionChanged()
    }

    fun onLoginSuccess() {
        _isLoggedIn.value = true
        notifyDeviceSessionChanged()
        paymentSyncService.start()
        startOfflineOutbox()
        // refreshTabs rearma una sola generación de inventario para esta sesión/venue.
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

            // Spec §5: lo PROPIO se intenta subir antes de soltar el token (que el servidor usa como
            // autor), con un plazo corto. Sin red no retiene el cierre: se queda, con su dueño.
            if (::wasteSyncCoordinator.isInitialized) {
                wasteSyncCoordinator.vaciarAntesDeCerrarSesion(secureStorage.userId)
            }
            paymentSyncService.stop()
            stopInventorySync()
            syncOutbox.stop()
            detenerRedLocal()
            secureStorage.clearSession()
            _isLoggedIn.value = false
            notifyDeviceSessionChanged()
        }
    }

    fun clearSessionGuard() {
        _sessionGuardMessage.value = null
    }
}
