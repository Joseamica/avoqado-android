package com.avoqado.pos.reservations.presentation.list

import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun stub(id: String, status: ReservationStatus) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )

    @Test
    fun `initial load filters by HOY tab statuses`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(match { it.statuses.containsAll(listOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)) }) } returns
            Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.CONFIRMED))))

        val vm = ReservationsListViewModel(repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.items.size)
        assertEquals("r1", state.items[0].id)
        assertTrue(!state.isLoading)
    }

    @Test
    fun `runTransition optimistically removes when terminal`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(any()) } returns Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.PENDING))))
        coEvery { repo.runAction("r1", ReservationAction.NO_SHOW, null) } returns Result.success(stub("r1", ReservationStatus.NO_SHOW))

        val vm = ReservationsListViewModel(repo)
        advanceUntilIdle()

        vm.runTransition("r1", ReservationAction.NO_SHOW)
        // After update call, pendingTransitionIds should contain "r1" before coroutine completes
        assertTrue(vm.state.value.pendingTransitionIds.contains("r1"))

        advanceUntilIdle()
        val final = vm.state.value
        assertTrue(final.pendingTransitionIds.isEmpty())
        assertTrue(final.items.none { it.id == "r1" })
    }

    @Test
    fun `runTransition rolls back on failure with error`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(any()) } returns Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.PENDING))))
        coEvery { repo.runAction("r1", ReservationAction.CONFIRM, null) } returns Result.failure(RuntimeException("HTTP 409"))

        val vm = ReservationsListViewModel(repo)
        advanceUntilIdle()

        vm.runTransition("r1", ReservationAction.CONFIRM)
        advanceUntilIdle()

        val final = vm.state.value
        assertTrue(final.pendingTransitionIds.isEmpty())
        assertEquals(1, final.items.size)
        assertTrue(final.error!!.isNotBlank())
    }
}
