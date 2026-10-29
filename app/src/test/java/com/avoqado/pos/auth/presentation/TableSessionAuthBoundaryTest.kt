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


import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.tables.data.TableServiceRepository
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class TableSessionAuthBoundaryTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val invalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var user = "staff-a"
    private var venue = "venue-atole"
    private val storage = mockk<SecureStorage>(relaxed = true).also {
        every { it.userId } answers { user }
        every { it.venueId } answers { venue }
        every { it.isLoggedIn } returns true
        every { it.reservationsEnabled } returns false
        every { it.sessionInvalidated } returns invalidated
    }
    private val session = TableSession()
    private val tableCoordinator = io.mockk.spyk(TableSyncCoordinator(mockk<SyncOutbox>(relaxed = true), session,
        mockk<TableServiceRepository>(relaxed = true), storage, mockk<SyncIntentDao>(relaxed = true))).also {
        val ownedCoordinator = it
        every { ownedCoordinator.start() } answers { ownedCoordinator.onSessionChanged() }
    }
    private fun parked() {
        session.start(TableSession.Active("t", "5", null, "o", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true, valorRondasSinRedCents = 11000), venue)
        assertTrue(session.estacionar(venue, session.current()!!))
    }
    private fun restore() = requireNotNull(session.abrirDesdePiso(venue, TableSession.Active("t", "5", null, "o", "LOCAL", 1, 0,
        TableSession.Mode.ORDERING, isProvisional = true, saldoSinRedConocido = false)))
    @Test fun `refreshTabs real conserva memoria si identidad no cambio y elimina al cambiar venue usuario`() = runTest {
        val app = createAppState(storage, tableCoordinator, true, {}, {}, {}, onOutboxStart = {})
        try {
            advanceUntilIdle(); parked(); app.refreshTabs(); advanceUntilIdle()
            assertEquals(11000, restore().valorRondasSinRedCents)
            parked(); venue = "venue-other"; app.refreshTabs(); advanceUntilIdle()
            assertFalse(restore().saldoSinRedConocido)
            parked(); user = "staff-b"; app.refreshTabs(); advanceUntilIdle()
            assertFalse(restore().saldoSinRedConocido)
        } finally { app.viewModelScope.cancel() }
    }
    @Test fun `logout real y invalidacion reactiva limpian parking aun con preferencias viejas`() = runTest {
        val app = createAppState(storage, tableCoordinator, true, {}, {}, {}, onOutboxStart = {})
        try {
            advanceUntilIdle(); parked(); app.onLogout(); advanceUntilIdle()
            assertFalse(restore().saldoSinRedConocido)
            parked(); invalidated.emit(Unit); runCurrent()
            // El flow puede emitirse ANTES de clearSession; no dependemos de que getter ya sea nil.
            assertFalse(restore().saldoSinRedConocido)
        } finally { app.viewModelScope.cancel() }
    }
    private fun createAppState(
        ownedStorage: SecureStorage,
        tableCoordinator: TableSyncCoordinator,
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
        val secureStorage = ownedStorage
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
            tableSyncCoordinator = tableCoordinator,
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
        )
    }

}
