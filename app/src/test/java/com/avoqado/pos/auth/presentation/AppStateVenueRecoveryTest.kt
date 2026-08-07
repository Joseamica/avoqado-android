package com.avoqado.pos.auth.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
            onOutboxStart = { events += "outbox" },
        )

        assertEquals(listOf("repair", "payments", "outbox"), events.take(3))
    }

    @Test
    fun `still starts offline services when venue token repair cannot reach the server`() {
        val events = mutableListOf<String>()

        createAppState(
            repairResult = false,
            onRepair = { events += "repair-failed" },
            onPaymentStart = { events += "payments" },
            onOutboxStart = { events += "outbox" },
        )

        assertEquals(listOf("repair-failed", "payments", "outbox"), events.take(3))
    }

    @Test
    fun `startup refresh completes without leaking an initialization exception`() = runTest {
        val appState = createAppState(
            repairResult = true,
            onRepair = {},
            onPaymentStart = {},
            onOutboxStart = {},
        )

        advanceUntilIdle()

        assertEquals(false, appState.visibleTabs.value.isEmpty())
    }

    private fun createAppState(
        repairResult: Boolean,
        onRepair: () -> Unit,
        onPaymentStart: () -> Unit,
        onOutboxStart: () -> Unit,
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
            coEvery { refreshSettings() } returns Unit
        }
        val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }

        return AppState(
            secureStorage = secureStorage,
            authRepository = authRepository,
            timeEntryRepository = mockk<TimeEntryRepository>(relaxed = true),
            roleManager = mockk<RoleManager>(relaxed = true),
            planManager = mockk<PlanManager>(relaxed = true),
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            syncOutbox = syncOutbox,
            reservationRepository = reservationRepository,
            tableSyncCoordinator = mockk<TableSyncCoordinator>(relaxed = true),
            posModeManager = posModeManager,
            venueSwitchState = mockk<VenueSwitchState>(relaxed = true),
            connectivityMonitor = connectivityMonitor,
        )
    }
}
