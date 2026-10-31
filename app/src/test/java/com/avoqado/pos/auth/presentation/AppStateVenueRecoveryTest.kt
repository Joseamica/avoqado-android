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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertTrue
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

    // ── D8: los carritos guardados siguen a la sucursal de la sesión al entrar y al salir, no sólo al cambiar ──

    @Test
    fun `D8 entrar y salir les avisan a los carritos guardados`() = runTest {
        val carritos = mockk<com.avoqado.pos.pos.data.SavedCartsRepository>(relaxed = true)
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, savedCartsRepository = carritos)
        advanceUntilIdle()
        clearMocks(carritos, answers = false)

        appState.onLoginSuccess()
        verify(atLeast = 1) { carritos.alCambiarLaSesion() }
        clearMocks(carritos, answers = false)

        appState.onLogout()
        advanceUntilIdle()
        verify(atLeast = 1) { carritos.alCambiarLaSesion() }
    }

    // ── La marca del negocio en la pantalla del cliente sigue a la sesión (QA F2-1) ──

    @Test
    fun `iniciar sesion pone la marca del negocio en la pantalla del cliente sin pasar por onResume`() = runTest {
        val pantalla = com.avoqado.pos.customerdisplay.CustomerDisplayState()
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, configurarSesion = {
            every { venueDisplayName } returns "Taquería Lupe"
            every { venueLogo } returns "https://storage.googleapis.com/b/lupe.png"
        })
        appState.customerDisplayState = pantalla
        advanceUntilIdle()

        appState.onLoginSuccess()

        assertEquals("Taquería Lupe", pantalla.venueName.value)
        assertEquals("https://storage.googleapis.com/b/lupe.png", pantalla.venueLogoUrl.value)
    }

    @Test
    fun `cerrar sesion deja la pantalla del cliente sin la marca del negocio`() = runTest {
        var conSesion = true
        val pantalla = com.avoqado.pos.customerdisplay.CustomerDisplayState()
            .apply { setVenueBranding("Taquería Lupe", "https://storage.googleapis.com/b/lupe.png") }
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, configurarSesion = {
            every { venueDisplayName } answers { if (conSesion) "Taquería Lupe" else null }
            every { venueLogo } answers { if (conSesion) "https://storage.googleapis.com/b/lupe.png" else null }
            every { clearSession() } answers { conSesion = false }
        })
        appState.customerDisplayState = pantalla
        advanceUntilIdle()

        appState.onLogout()
        advanceUntilIdle()

        assertEquals(null, pantalla.venueName.value)
        assertEquals(null, pantalla.venueLogoUrl.value)
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

    @Test
    fun `Q15 arranque y cambio PIN sincronizan contexto antes de iniciar observador`() {
        var contextReady = false
        val contextsAtStart = mutableListOf<Boolean>()
        val appState = createAppState(
            repairResult = true, onRepair = {}, onPaymentStart = {}, onInventoryStart = {},
            onTableStart = { contextReady = true },
            onOutboxStart = { contextsAtStart += contextReady },
        )
        org.junit.Assert.assertTrue(contextsAtStart.isNotEmpty())
        assertEquals("El contador debe capturar la identidad vigente al arrancar", true, contextsAtStart.all { it })
        contextReady = false // cambio de identidad por PIN: refreshTabs es el caller real
        val previousStarts = contextsAtStart.size
        appState.refreshTabs()
        org.junit.Assert.assertTrue(contextsAtStart.size > previousStarts)
        assertEquals("El contador debe capturar la identidad vigente tras PIN", true, contextsAtStart.drop(previousStarts).all { it })
    }

    /**
     * Task 4.1: un refresco de ajustes que sigue en vuelo cuando cambia la sesión. El login lanza el suyo (la respuesta
     * vieja ya no puede escribir: la descarta el boleto del repositorio) y el logout suelta los ajustes y cancela.
     */
    private class RefrescoQueNoTermina {
        val soltar = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lanzados = 0
        var cancelados = 0
        val repo = mockk<TpvSettingsRepository>(relaxed = true) {
            every { terminalNavigation } returns MutableStateFlow(TerminalNavigationSettings.DEFAULT)
            coEvery { refreshSettings() } coAnswers {
                lanzados++
                try {
                    soltar.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelados++
                    throw e
                }
            }
        }
    }

    @Test
    fun `onLoginSuccess con un refresco en vuelo lanza uno nuevo`() = runTest {
        val refresco = RefrescoQueNoTermina()
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, tpvSettingsRepository = refresco.repo)
        runCurrent()
        assertEquals("el arranque ya dejó uno en vuelo", 1, refresco.lanzados)

        appState.onLoginSuccess()
        runCurrent()

        assertEquals("el login no puede quedarse con los ajustes de la sesión anterior", 2, refresco.lanzados)
        assertEquals(1, refresco.cancelados)
        refresco.soltar.complete(Unit)
    }

    /**
     * Modo Autoservicio 0.e: las llegadas del kiosco guardadas sin red sólo se mandan con SU sucursal activa. Cerrar
     * sesión y entrar a otra las dejaría varadas, igual que los cobros sin red.
     */
    @Test
    fun `cerrar sesion se bloquea con llegadas del kiosco pendientes`() = runTest {
        var cerro = false
        val appState = createAppState(
            true, {}, {}, {}, onOutboxStart = {},
            configurarSesion = { every { clearSession() } answers { cerro = true } },
            llegadasDelKiosco = 1,
        )
        advanceUntilIdle()

        appState.onLogout()
        advanceUntilIdle()

        org.junit.Assert.assertFalse(cerro)
        val aviso = appState.sessionGuardMessage.value.orEmpty()
        org.junit.Assert.assertTrue("esperaba el aviso de pendientes, llegó «$aviso»", aviso.contains("1 operación offline"))
    }

    @Test
    fun `onLogout limpia los ajustes y cancela el refresco`() = runTest {
        val refresco = RefrescoQueNoTermina()
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, tpvSettingsRepository = refresco.repo)
        runCurrent()

        appState.onLogout()
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) { refresco.repo.clearCache() }
        assertEquals(1, refresco.cancelados)
        refresco.soltar.complete(Unit)
    }

    @Test
    fun `sesion expirada limpia los ajustes y cancela el refresco`() = runTest {
        val refresco = RefrescoQueNoTermina()
        val invalidada = MutableSharedFlow<Long>()
        createAppState(true, {}, {}, {}, onOutboxStart = {}, tpvSettingsRepository = refresco.repo, sessionInvalidated = invalidada)
        runCurrent()

        invalidada.emit(1L) // TokenRefreshAuthenticator: el refresco del token fue rechazado de verdad
        runCurrent()

        io.mockk.verify(exactly = 1) { refresco.repo.clearCache() }
        assertEquals(1, refresco.cancelados)
        refresco.soltar.complete(Unit)
    }

    @Test
    fun `D4 H4 el aviso de cierre de A que se procesa despues de que entro B no la apaga`() = runTest {
        val refresco = RefrescoQueNoTermina()
        val invalidada = MutableSharedFlow<Long>()
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, tpvSettingsRepository = refresco.repo,
            sessionInvalidated = invalidada, configurarSesion = {
                every { siSigueLaGeneracion(1L, any()) } returns false // el cierre de A (gen 1); ya entró B
            })
        runCurrent()

        invalidada.emit(1L)
        runCurrent()

        assertTrue("B sigue adentro", appState.isLoggedIn.value)
        io.mockk.verify(exactly = 0) { refresco.repo.clearCache() }
        refresco.soltar.complete(Unit)
    }

    @Test
    fun `D4 H6 si B entra con su PIN mientras el cierre de A espera, no se cierra la sesion de B`() = runTest {
        var gen = 5L
        val appState = createAppState(true, {}, {}, {}, onOutboxStart = {}, configurarSesion = {
            every { generacion } answers { gen }
            every { siSigueLaGeneracion(any(), any()) } answers { firstArg<Long>() == gen }
            every { clearSessionSiSigueLaGeneracion(any()) } answers { firstArg<Long>() == gen }
        })
        // B entra con su PIN justo mientras el cierre de A vacía la merma (la espera del cierre a mano).
        appState.wasteSyncCoordinator = mockk(relaxed = true) {
            coEvery { vaciarAntesDeCerrarSesion(any()) } coAnswers { gen = 6L }
        }
        advanceUntilIdle()

        appState.onLogout() // A toca «Cerrar sesión» (sello 5)
        advanceUntilIdle()

        assertTrue("B sigue adentro", appState.isLoggedIn.value)
    }

    private fun createAppState(
        repairResult: Boolean,
        onRepair: () -> Unit,
        onPaymentStart: () -> Unit,
        onInventoryStart: () -> Unit,
        onInventoryStop: () -> Unit = {},
        onOutboxStart: () -> Unit,
        onTableStart: () -> Unit = {},
        connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        onSettingsRefresh: () -> Unit = {},
        tpvSettingsRepository: TpvSettingsRepository? = null,
        sessionInvalidated: MutableSharedFlow<Long> = MutableSharedFlow(),
        printConfigRepository: com.avoqado.pos.printing.routing.PrintConfigRepository =
            mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>(relaxed = true) {
                every { config } returns MutableStateFlow(com.avoqado.pos.printing.routing.PrintConfig())
            },
        configurarSesion: SecureStorage.() -> Unit = {},
        storageReal: SecureStorage? = null,
        resolverReal: com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta? = null,
        llegadasDelKiosco: Int = 0,
        savedCartsRepository: com.avoqado.pos.pos.data.SavedCartsRepository? = null,
    ): AppState {
        val secureStorage = storageReal ?: mockk<SecureStorage>(relaxed = true) {
            every { isLoggedIn } returns true
            every { venueId } returns "venue-atole"
            every { reservationsEnabled } returns false
            every { this@mockk.sessionInvalidated } returns sessionInvalidated
            // D4: por defecto nadie entra ni sale a media prueba (la sesión sigue siendo la de su sello).
            every { siSigueLaGeneracion(any(), any()) } answers { secondArg<() -> Unit>().invoke(); true }
            every { clearSessionSiSigueLaGeneracion(any()) } answers { clearSession(); true }
            configurarSesion()
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
            coEvery { pendingKioskArrivals(any()) } returns llegadasDelKiosco
        }
        val posModeManager = mockk<PosModeManager>(relaxed = true) {
            every { currentMode } returns MutableStateFlow(PosMode.RETAIL)
        }
        val tpvSettingsRepository = tpvSettingsRepository ?: mockk<TpvSettingsRepository>(relaxed = true) {
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

        val plantilla = AppState(
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
            tableSyncCoordinator = mockk<TableSyncCoordinator>(relaxed = true) {
                every { start() } answers { onTableStart() }
            },
            posModeManager = posModeManager,
            inventoryCountSyncCoordinator = inventorySync,
            cashDrawerRepository = mockk<com.avoqado.pos.cashdrawer.data.CashDrawerRepository>(relaxed = true) {
                every { estadoDeLosCobros } returns MutableStateFlow(
                    com.avoqado.pos.cashdrawer.data.EstadoDeLosCobros.LIBRES,
                )
                every { cierreEsperaVentas } returns MutableStateFlow(0)
            },
            venueSwitchState = mockk<VenueSwitchState>(relaxed = true),
            connectivityMonitor = connectivityMonitor,
            printConfigRepository = printConfigRepository,
            savedCartsRepository = savedCartsRepository,
        )
        if (resolverReal == null) return plantilla
        // La fábrica generada ejecuta también la inyección de miembros de Hilt.
        val factory = Class.forName("com.avoqado.pos.auth.presentation.AppState_Factory")
        val crear = factory.methods.single { it.name == "create" }
        val providers = crear.genericParameterTypes.map { provider ->
            val tipo = (provider as java.lang.reflect.ParameterizedType).actualTypeArguments.single() as Class<*>
            val valor = when (tipo) {
                com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta::class.java -> resolverReal
                ConnectivityMonitor::class.java -> connectivityMonitor
                com.avoqado.pos.cashdrawer.data.CashDrawerRepository::class.java -> mockk<com.avoqado.pos.cashdrawer.data.CashDrawerRepository>(relaxed = true) {
                    every { estadoDeLosCobros } returns MutableStateFlow(com.avoqado.pos.cashdrawer.data.EstadoDeLosCobros.LIBRES)
                }
                else -> AppState::class.java.declaredFields.firstOrNull { it.type == tipo }
                    ?.let { runCatching { it.isAccessible = true; it.get(plantilla) }.getOrNull() }
                    ?: io.mockk.mockkClass(tipo.kotlin, relaxed = true)
            }
            dagger.internal.Provider<Any> { valor }
        }.toTypedArray()
        return (factory.getMethod("get").invoke(crear.invoke(null, *providers)) as AppState).also {
            androidx.lifecycle.ViewModelStore().apply { put("plantilla", plantilla); clear() }
        }
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

    @Test fun `R6 Hilt real recupera al entrar y cambiar negocio con red arriba sin duplicar callbacks`() = runBlocking {
        val prefs = com.avoqado.pos.giftcards.SolicitudesDeTarjetaTest.Preferencias()
        val storage = prefs.almacen()
        SecureStorage::class.java.getDeclaredField("sessionInvalidated").apply { isAccessible = true }
            .set(storage, SecureStorage::class.java.getDeclaredField("_sessionInvalidated").apply { isAccessible = true }.get(storage))
        val preferencias = SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) as android.content.SharedPreferences
        every { preferencias.getBoolean(any(), any()) } answers { secondArg() }
        val store = com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage)
        for (venue in listOf("a", "b")) org.junit.Assert.assertTrue(store.guardar(com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(
            tipo = "GIFT_CARD_SALE", llave = "k-$venue", venueId = venue, creadaEn = 1, orderId = "o-$venue", cuerpo = "{}")))
        val red = mockk<ConnectivityMonitor> {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val repo = mockk<com.avoqado.pos.giftcards.data.GiftCardsRepository>()
        val consultas = java.util.concurrent.CopyOnWriteArrayList<String>()
        io.mockk.coEvery { repo.cuenta(any(), any()) } coAnswers {
            consultas += firstArg<String>()
            com.avoqado.pos.giftcards.data.Respuesta.Ok(com.avoqado.pos.giftcards.data.CuentaDeTarjetas(
                secondArg(), "n", 500.0, "PENDING", "CONFIRMED", sobres = listOf(
                    com.avoqado.pos.giftcards.data.SobreDeLaCuenta("g", "1234", 500.0, 500.0, "ACTIVE", deliveryChannel = "PRINTED",
                        usada = false, puedeReenviar = false, puedeCambiarDestino = false, puedeRevelar = false)),
                cancel = com.avoqado.pos.giftcards.data.Cancelacion(true)))
        }
        val resolver = com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red)
        val app = createAppState(true, {}, {}, {}, onOutboxStart = {}, storageReal = storage, resolverReal = resolver)
        val vms = androidx.lifecycle.ViewModelStore().apply { put("r6", app) }
        try {
        org.junit.Assert.assertSame(resolver, app.solicitudesDeTarjeta)
        resolver.resolverAhora()
        assertEquals(emptyList<String>(), consultas)

        storage.userId = "ana"; storage.accessToken = "test-token"; storage.venueId = "a"
        app.onLoginSuccess() // Incluye notify + refreshTabs: un solo contexto, no dos pasadas.
        val a = kotlinx.coroutines.withTimeoutOrNull(1500) { resolver.ventasListas.first { it == listOf("k-a") } }
        assertEquals("El callback real de login debe despertar sin cambiar la red", listOf("k-a"), a)
        storage.venueId = "b"
        app.refreshTabs()
        val b = kotlinx.coroutines.withTimeoutOrNull(1500) { resolver.ventasListas.first { it == listOf("k-b") } }
        assertEquals(listOf("k-b"), b)
        assertEquals(listOf("a", "b"), consultas.toList())
        app.onLogout()
        assertEquals(emptyList<String>(), resolver.ventasListas.value)
        assertEquals(listOf("a", "b"), consultas.toList())
        } finally { vms.clear() }
    }

    @Test fun `R6 invalidar sesion limpia ventas listas aunque las preferencias aun tengan identidad`() = runBlocking {
        val prefs = com.avoqado.pos.giftcards.SolicitudesDeTarjetaTest.Preferencias().apply {
            values["venueId"] = "a"; values["accessToken"] = "fixture-con-sesion"
        }
        val storage = prefs.almacen()
        val store = com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage)
        org.junit.Assert.assertTrue(store.guardar(com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(
            tipo = "GIFT_CARD_SALE", llave = "k", venueId = "a", creadaEn = 1, orderId = "o", cuerpo = "{}")))
        val repo = mockk<com.avoqado.pos.giftcards.data.GiftCardsRepository>()
        io.mockk.coEvery { repo.cuenta(any(), any()) } returns com.avoqado.pos.giftcards.data.Respuesta.Ok(
            com.avoqado.pos.giftcards.data.CuentaDeTarjetas("o", "n", 500.0, "PENDING", "CONFIRMED", sobres = listOf(
                com.avoqado.pos.giftcards.data.SobreDeLaCuenta("g", "1234", 500.0, 500.0, "ACTIVE", deliveryChannel = "PRINTED",
                    usada = false, puedeReenviar = false, puedeCambiarDestino = false, puedeRevelar = false)), cancel = com.avoqado.pos.giftcards.data.Cancelacion(true)))
        val red = mockk<ConnectivityMonitor> {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val resolver = com.avoqado.pos.giftcards.data.ResolutorDeSolicitudesDeTarjeta(repo, store, storage, red)
        resolver.resolverAhora()
        assertEquals(listOf("k"), resolver.ventasListas.value)
        resolver.onSessionChanged(false)
        assertEquals("No conservar una lista accionable tras invalidar", emptyList<String>(), resolver.ventasListas.value)
    }
}
