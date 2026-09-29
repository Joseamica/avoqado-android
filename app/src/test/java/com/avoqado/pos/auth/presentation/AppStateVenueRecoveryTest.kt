package com.avoqado.pos.auth.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.lan.RachaSinAcuse
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.settings.domain.PosModeManager
import com.avoqado.pos.settings.domain.VenueSwitchState
import com.avoqado.pos.tables.data.TableSyncCoordinator
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateVenueRecoveryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `repairs the persisted venue token before starting offline replay`() {
        val events = mutableListOf<String>()

        createAppState(
            repairResult = true,
            onRepair = { events += "repair" },
            onPaymentStart = { events += "payments" },
            onInventoryStart = { events += "inventory" },
            onOutboxStart = { events += "outbox" },
        )

        assertEquals(listOf("repair", "inventory", "payments", "outbox"), events.take(4))
    }

    @Test
    fun `still starts offline services when venue token repair cannot reach the server`() {
        val events = mutableListOf<String>()

        createAppState(
            repairResult = false,
            onRepair = { events += "repair-failed" },
            onPaymentStart = { events += "payments" },
            onInventoryStart = { events += "inventory" },
            onOutboxStart = { events += "outbox" },
        )

        assertEquals(listOf("repair-failed", "inventory", "payments", "outbox"), events.take(4))
    }

    @Test
    fun `startup refresh completes without leaking an initialization exception`() = runTest {
        val appState = createAppState(
            repairResult = true,
            onRepair = {},
            onPaymentStart = {},
            onInventoryStart = {},
            onOutboxStart = {},
        )

        advanceUntilIdle()

        assertEquals(false, appState.visibleTabs.value.isEmpty())
    }

    @Test
    fun `logout detiene inventory sync sin bloquear por trabajo durable separado`() = runTest {
        val events = mutableListOf<String>()
        val appState = createAppState(
            repairResult = true,
            onRepair = {},
            onPaymentStart = {},
            onInventoryStart = {},
            onInventoryStop = { events += "inventory-stop" },
            onOutboxStart = {},
        )

        appState.onLogout()
        advanceUntilIdle()

        assertEquals(listOf("inventory-stop"), events)
    }

    @Test
    fun `logout detiene la sesion y el login siguiente inicia un solo replay nuevo`() = runTest {
        val events = mutableListOf<String>()
        val appState = createAppState(
            repairResult = true,
            onRepair = {},
            onPaymentStart = {},
            onInventoryStart = { events += "inventory-start" },
            onInventoryStop = { events += "inventory-stop" },
            onOutboxStart = {},
        )
        advanceUntilIdle()
        events.clear()

        appState.onLogout()
        advanceUntilIdle()
        appState.onLoginSuccess()

        assertEquals(listOf("inventory-stop", "inventory-start"), events)
    }

    @Test
    fun `reconnect refreshes paid access without rebuilding the checkout or refreshing after logout`() = runTest {
        val connected = MutableStateFlow(false)
        var refreshes = 0
        val state = createAppState(true, {}, {}, {}, onOutboxStart = {}, connected = connected,
            onSettingsRefresh = { refreshes++ })
        advanceUntilIdle()
        val initial = refreshes
        val checkoutKey = state.mainNavigation.value.contentKey
        connected.value = true
        advanceTimeBy(1100)
        runCurrent()
        assertEquals(initial + 1, refreshes)
        assertEquals(checkoutKey, state.mainNavigation.value.contentKey)
        state.onLogout()
        advanceUntilIdle()
        connected.value = false
        connected.value = true
        advanceTimeBy(1100)
        runCurrent()
        assertEquals(initial + 1, refreshes)
    }

    /**
     * Etapa 3 del KDS (3.5, D12): la red local sigue a la sesión. La sucursal la enciende (el hub del venue viejo se
     * desengancha ANTES de iniciar el transporte), y al cerrar sesión se apaga el hub antes que el transporte.
     */
    @Test
    fun `la red local se enciende con la sucursal y al cerrar sesion se apaga el hub antes que el transporte`() = runTest {
        val appState = createAppState(repairResult = true, onRepair = {}, onPaymentStart = {}, onInventoryStart = {}, onOutboxStart = {})
        val transporte = mockk<com.avoqado.pos.core.data.lan.TransporteLan>(relaxed = true)
        val hub = mockk<com.avoqado.pos.core.data.lan.LanHubService>(relaxed = true)
        appState.transporteLan = transporte
        appState.lanHubService = hub

        appState.onLoginSuccess()
        io.mockk.verifyOrder {
            hub.sincronizarVenue("venue-atole")
            transporte.iniciar("venue-atole")
        }

        appState.onLogout()
        advanceUntilIdle()
        io.mockk.verifyOrder {
            hub.stop()
            transporte.detener()
        }
    }

    private fun createAppState(
        repairResult: Boolean,
        onRepair: () -> Unit,
        onPaymentStart: () -> Unit,
        onInventoryStart: () -> Unit,
        onInventoryStop: () -> Unit = {},
        onOutboxStart: () -> Unit,
        connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        onSettingsRefresh: () -> Unit = {},
        printConfigRepository: com.avoqado.pos.printing.routing.PrintConfigRepository =
            mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>(relaxed = true) {
                every { config } returns MutableStateFlow(com.avoqado.pos.printing.routing.PrintConfig())
            },
    ): AppState {
        val secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { isLoggedIn } returns true
            every { venueId } returns "venue-atole"
            every { reservationsEnabled } returns false
            every { sessionInvalidated } returns MutableSharedFlow()
        }
        val authRepository = mockk<AuthRepository>(relaxed = true) {
            coEvery { repairCurrentVenueBinding() } coAnswers {
                onRepair()
                repairResult
            }
        }
        val paymentSyncService = mockk<PaymentSyncService>(relaxed = true) {
            every { pendingCount } returns MutableStateFlow(0)
            every { failedCount } returns MutableStateFlow(0)
            every { start() } answers { onPaymentStart() }
        }
        val syncOutbox = mockk<SyncOutbox>(relaxed = true) {
            every { pendingCount } returns MutableStateFlow(0)
            every { rejectedCount } returns MutableStateFlow(0)
            every { start("venue-atole") } answers { onOutboxStart() }
        }
        val reservationRepository = mockk<ReservationRepository>(relaxed = true) {
            every { quarantinedCount } returns MutableStateFlow(0)
        }
        val posModeManager = mockk<PosModeManager>(relaxed = true) {
            every { currentMode } returns MutableStateFlow(PosMode.RETAIL)
        }
        val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true) {
            every { terminalNavigation } returns MutableStateFlow(TerminalNavigationSettings.DEFAULT)
            coEvery { refreshSettings() } coAnswers { onSettingsRefresh() }
        }
        val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true) {
            every { isConnected } returns connected
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val inventorySync = mockk<InventoryCountSyncCoordinator>(relaxed = true) {
            every { start(any()) } answers { onInventoryStart() }
            every { stop() } answers { onInventoryStop() }
            every { blockingWorkCount() } returns 0
        }

        return AppState(
            secureStorage = secureStorage,
            authRepository = authRepository,
            timeEntryRepository = mockk<TimeEntryRepository>(relaxed = true),
            roleManager = mockk<RoleManager>(relaxed = true),
            planManager = mockk<PlanManager>(relaxed = true),
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            comandasPendientesStore = mockk(relaxed = true),
            replayDeComandas = mockk(relaxed = true),
            syncOutbox = syncOutbox,
            reservationRepository = reservationRepository,
            tableSyncCoordinator = mockk<TableSyncCoordinator>(relaxed = true),
            posModeManager = posModeManager,
            inventoryCountSyncCoordinator = inventorySync,
            cashDrawerRepository = mockk<com.avoqado.pos.cashdrawer.data.CashDrawerRepository>(relaxed = true) {
                every { estadoDeLosCobros } returns MutableStateFlow(
                    com.avoqado.pos.cashdrawer.data.EstadoDeLosCobros.LIBRES,
                )
            },
            venueSwitchState = mockk<VenueSwitchState>(relaxed = true),
            connectivityMonitor = connectivityMonitor,
            printConfigRepository = printConfigRepository,
        )
    }

    /**
     * Etapa 3 del KDS (3.4): la config de impresión se precarga al arrancar (la GUARDADA si no hay red), para que la
     * banda de «Sin conexión» diga qué estaciones salen en papel desde el primer minuto, no desde la primera comanda.
     */
    @Test
    fun `P1 al arrancar precarga la config de impresion sin esperar a la red`() {
        val printConfig = mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>(relaxed = true) {
            every { config } returns MutableStateFlow(com.avoqado.pos.printing.routing.PrintConfig())
        }

        createAppState(
            repairResult = true,
            onRepair = {},
            onPaymentStart = {},
            onInventoryStart = {},
            onOutboxStart = {},
            printConfigRepository = printConfig,
        )

        // atLeast(1), no exactly(1): startOfflineOutbox() YA se invoca dos veces al arrancar (init
        // directo + refreshPlanAndSettings -> refreshTabs -> startOfflineOutbox), algo previo a esta
        // tarea (ver AppState.kt) — exactly(1) es un falso rojo. atLeast(1) con los args exactos sigue
        // cayendo si la precarga se quita.
        io.mockk.coVerify(atLeast = 1) { printConfig.refreshConTope("venue-atole", 0L) }
    }

    /** Etapa 3 del KDS (3.5, D11): en la tablet que ES la pantalla la banda no dice lo del papel; la racha se dice con o sin red. */
    @Test
    fun `P1 con el receptor vivo la banda calla lo del papel, y tras 3 sin acuse dice que la pantalla no se alcanza`() {
        val barra = StationInfo(id = "st_barra", name = "Barra", hasKitchenDisplay = true)
        val printConfig = mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>(relaxed = true) {
            every { config } returns MutableStateFlow(com.avoqado.pos.printing.routing.PrintConfig(stations = listOf(barra)))
        }
        val receptorActivo = MutableStateFlow(false)
        val racha = RachaSinAcuse()
        val transporte = mockk<TransporteLan>(relaxed = true) {
            every { this@mockk.receptorActivo } returns receptorActivo
            every { this@mockk.racha } returns racha
        }
        val appState = createAppState(repairResult = true, onRepair = {}, onPaymentStart = {}, onInventoryStart = {}, onOutboxStart = {}, printConfigRepository = printConfig)
        appState.transporteLan = transporte

        // Con tope: si la banda nunca llega al texto esperado, la prueba falla en vez de colgar la corrida entera.
        runBlocking {
            withTimeout(10_000) {
                assertEquals("Las comandas de Barra salen en papel si la pantalla no contesta", appState.avisoDeCocinaSinRed.first { it != null })
                receptorActivo.value = true
                assertEquals(null, appState.avisoDeCocinaSinRed.first { it == null })
                repeat(3) { racha.registrar(setOf("st_barra"), emptySet()) }
                assertEquals("La pantalla de Barra no se alcanza por el WiFi", appState.avisoDeRacha.first { it != null })
            }
        }
    }
}
