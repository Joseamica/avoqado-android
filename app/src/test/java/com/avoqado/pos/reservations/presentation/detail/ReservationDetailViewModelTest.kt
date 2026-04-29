package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun s() { Dispatchers.setMain(dispatcher) }
    @After fun t() { Dispatchers.resetMain() }

    private fun stub(status: ReservationStatus = ReservationStatus.CONFIRMED) = Reservation(
        id = "r1", venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "..."
    )

    private val capProvider: Provider<ReservationsCapability> = Provider { ReservationsCapability(true, true, true, true) }

    @Test
    fun `loads reservation on init`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub())

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("r1", s.reservation?.id)
        assertEquals(false, s.isLoading)
    }

    @Test
    fun `runAction confirm transitions optimistically`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.CONFIRM, null) } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))
        advanceUntilIdle()
        vm.runAction(ReservationAction.CONFIRM)
        advanceUntilIdle()

        val s = vm.state.value
        assertNull(s.pendingAction)
        assertEquals(ReservationStatus.CONFIRMED, s.reservation?.status)
        assertEquals(ReservationAction.CONFIRM, s.justCompletedAction)
    }

    @Test
    fun `runAction failure rolls back`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.NO_SHOW, null) } returns Result.failure(RuntimeException("HTTP 409"))

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))
        advanceUntilIdle()
        vm.runAction(ReservationAction.NO_SHOW)
        advanceUntilIdle()

        val s = vm.state.value
        assertNull(s.pendingAction)
        assertEquals(ReservationStatus.PENDING, s.reservation?.status)
        assertTrue(s.error!!.contains("409"))
    }
}
