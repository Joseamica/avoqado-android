package com.avoqado.pos.reservations.presentation.calendar

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ClassSessionRepository
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.ReservationApiException
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
import java.time.ZoneId
import java.time.ZonedDateTime
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coVerify

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
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val storage: SecureStorage = mockk()
        every { storage.venueTimezone } returns "America/Mexico_City"
        every { storage.calendarViewForCurrentVenue } returns null
        every { storage.showClassSessionsForCurrentVenue } returns false
        coEvery { repo.fetchCalendar(any(), any(), any()) } returns Result.success(listOf(stub("r1", ReservationStatus.CONFIRMED)))

        val connectivity: ConnectivityMonitor = mockk(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        // v2.3.2 added classSessionRepository to the VM constructor; this test was never updated.
        val classRepo: ClassSessionRepository = mockk(relaxed = true)
        every { classRepo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow()
        coEvery { classRepo.fetchList(any(), any(), any()) } returns Result.success(emptyList())
        val vm = CalendarViewModel(repo, classRepo, storage, connectivity)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.reservations.size)
        assertEquals(false, vm.state.value.isLoading)
    }

    @Test
    fun `setDate triggers re-fetch`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.pendingActionsCount } returns kotlinx.coroutines.flow.flowOf(0)
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val storage: SecureStorage = mockk()
        every { storage.venueTimezone } returns "America/Mexico_City"
        every { storage.calendarViewForCurrentVenue } returns null
        every { storage.showClassSessionsForCurrentVenue } returns false
        coEvery { repo.fetchCalendar(any(), any(), any()) } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(stub("r1", ReservationStatus.PENDING))),
        )

        val connectivity: ConnectivityMonitor = mockk(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val classRepo: ClassSessionRepository = mockk(relaxed = true)
        every { classRepo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow()
        coEvery { classRepo.fetchList(any(), any(), any()) } returns Result.success(emptyList())
        val vm = CalendarViewModel(repo, classRepo, storage, connectivity)
        advanceUntilIdle()
        vm.setDate(LocalDate.now().plusDays(1))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.reservations.size)
    }

    @Test
    fun `drag reschedule over capacity retries only after operator consent`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.pendingActionsCount } returns kotlinx.coroutines.flow.flowOf(0)
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchCalendar(any(), any(), any()) } returns Result.success(emptyList())
        val storage: SecureStorage = mockk()
        every { storage.venueTimezone } returns "America/Mexico_City"
        every { storage.calendarViewForCurrentVenue } returns null
        every { storage.showClassSessionsForCurrentVenue } returns false
        val connectivity: ConnectivityMonitor = mockk(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val classRepo: ClassSessionRepository = mockk(relaxed = true)
        every { classRepo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow()
        coEvery { classRepo.fetchList(any(), any(), any()) } returns Result.success(emptyList())
        val starts = ZonedDateTime.of(2026, 4, 30, 15, 0, 0, 0, ZoneId.of("America/Mexico_City"))
        val ends = starts.plusHours(1)
        coEvery {
            repo.runAction("r1", ReservationAction.RESCHEDULE, match {
                it is ReservationRepository.ActionPayload.Reschedule && it.allowOverCapacity != true
            })
        } returns Result.failure(
            ReservationApiException(409, "El horario está lleno", "OVER_CAPACITY_CONFIRMATION_REQUIRED", "2 de 1"),
        )
        coEvery {
            repo.runAction("r1", ReservationAction.RESCHEDULE, match {
                it is ReservationRepository.ActionPayload.Reschedule && it.allowOverCapacity == true
            })
        } returns Result.success(stub("r1", ReservationStatus.CONFIRMED))

        val vm = CalendarViewModel(repo, classRepo, storage, connectivity)
        advanceUntilIdle()
        var finalSuccess: Boolean? = null
        vm.reschedule("r1", starts, ends, null, null) { success, _ -> finalSuccess = success }
        advanceUntilIdle()

        assertNotNull(vm.rescheduleOverCapacityConfirmation.value)
        assertNull(finalSuccess)

        vm.confirmRescheduleOverCapacity()
        advanceUntilIdle()

        assertEquals(true, finalSuccess)
        assertNull(vm.rescheduleOverCapacityConfirmation.value)
        coVerify(exactly = 1) {
            repo.runAction("r1", ReservationAction.RESCHEDULE, match {
                it is ReservationRepository.ActionPayload.Reschedule && it.allowOverCapacity == true
            })
        }
    }
}
