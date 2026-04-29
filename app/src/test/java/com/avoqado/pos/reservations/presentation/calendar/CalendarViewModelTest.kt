package com.avoqado.pos.reservations.presentation.calendar

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun s() { Dispatchers.setMain(dispatcher) }
    @After fun t() { Dispatchers.resetMain() }

    private fun stub(id: String, status: ReservationStatus) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "..."
    )

    @Test
    fun `initial fetch covers selected day`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.pendingActionsCount } returns kotlinx.coroutines.flow.flowOf(0)
        val storage: SecureStorage = mockk()
        every { storage.venueTimezone } returns "America/Mexico_City"
        coEvery { repo.fetchCalendar(any(), any()) } returns Result.success(listOf(stub("r1", ReservationStatus.CONFIRMED)))

        val connectivity: ConnectivityMonitor = mockk(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val vm = CalendarViewModel(repo, storage, connectivity)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.reservations.size)
        assertEquals(false, vm.state.value.isLoading)
    }

    @Test
    fun `setDate triggers re-fetch`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.pendingActionsCount } returns kotlinx.coroutines.flow.flowOf(0)
        val storage: SecureStorage = mockk()
        every { storage.venueTimezone } returns "America/Mexico_City"
        coEvery { repo.fetchCalendar(any(), any()) } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(stub("r1", ReservationStatus.PENDING))),
        )

        val connectivity: ConnectivityMonitor = mockk(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val vm = CalendarViewModel(repo, storage, connectivity)
        advanceUntilIdle()
        vm.setDate(LocalDate.now().plusDays(1))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.reservations.size)
    }
}
