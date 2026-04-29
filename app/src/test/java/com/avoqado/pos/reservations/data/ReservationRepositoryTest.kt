package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationRepositoryTest {

    private val api: ReservationApi = mockk()
    private val pendingDao: PendingReservationActionDao = mockk(relaxed = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val repo = ReservationRepository(api, pendingDao, connectivity)

    @Test
    fun `runAction online calls api confirm`() = runTest {
        every { connectivity.isOnline() } returns true
        coEvery { api.confirm("r1") } returns Result.success(stub("r1"))

        val r = repo.runAction("r1", ReservationAction.CONFIRM)

        assertTrue(r.isSuccess)
        coVerify(exactly = 1) { api.confirm("r1") }
    }

    @Test
    fun `runAction offline enqueues to dao without calling api`() = runTest {
        every { connectivity.isOnline() } returns false

        val r = repo.runAction("r1", ReservationAction.CHECK_IN)

        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ReservationRepository.OfflineEnqueuedException)
        coVerify(exactly = 1) { pendingDao.enqueue(match { it.reservationId == "r1" && it.action == "CHECK_IN" }) }
        coVerify(exactly = 0) { api.checkIn(any()) }
    }

    @Test
    fun `runAction with cancel payload includes reason in stored payload`() = runTest {
        every { connectivity.isOnline() } returns false

        repo.runAction("r1", ReservationAction.CANCEL, ReservationRepository.ActionPayload.Cancel(reason = "Cliente cambió de plan"))

        coVerify { pendingDao.enqueue(match { it.payloadJson?.contains("Cliente cambió") == true }) }
    }

    private fun stub(id: String) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CONFIRMED, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )
}
