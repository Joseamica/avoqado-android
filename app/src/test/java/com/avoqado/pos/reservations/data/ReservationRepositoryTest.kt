package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.NoShowCorrectionRef
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.UndoNoShowPlan
import com.avoqado.pos.reservations.data.model.UndoNoShowResult
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // MARK: - D4 · «Sí llegó» es SOLO EN LÍNEA: mueve el paquete y el depósito en el servidor. Sin red no se encola.

    @Test
    fun `Si llego sin red no llama al servidor ni encola nada`() = runTest {
        every { connectivity.isOnline() } returns false

        val preview = repo.previewUndoNoShow("r1")
        val undo = repo.undoNoShow("r1")
        val resolve = repo.resolveNoShowCorrection("r1")
        // Lo mismo desde un aviso de otra sucursal.
        val previewA = repo.previewUndoNoShow("r1", venueId = "vA")
        val undoA = repo.undoNoShow("r1", venueId = "vA")

        for (r in listOf(preview, undo, resolve, previewA, undoA)) {
            assertTrue(r.exceptionOrNull() is ReservationRepository.OfflineRequiredException)
        }
        assertEquals(
            "Necesita conexión: deshacer un “No se presentó” cambia el paquete y el depósito",
            undo.exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { api.previewUndoNoShow(any(), any()) }
        coVerify(exactly = 0) { api.undoNoShow(any(), any(), any()) }
        coVerify(exactly = 0) { api.resolveNoShowCorrection(any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.enqueue(any()) }
        coVerify(exactly = 0) { pendingDao.insert(any()) }
    }

    @Test
    fun `Si llego en linea va a la ruta y avisa a las pantallas que algo cambio`() = runTest {
        every { connectivity.isOnline() } returns true
        val result = UndoNoShowResult(
            outcome = "UNDONE",
            reservation = stub("r1"),
            plan = UndoNoShowPlan(),
            correction = NoShowCorrectionRef(id = "c1"),
        )
        coEvery { api.undoNoShow("r1", null) } returns Result.success(result)
        val cambios = async(start = CoroutineStart.UNDISPATCHED) { repo.changes.first() }

        assertEquals(result, repo.undoNoShow("r1").getOrThrow())
        assertEquals(Unit, cambios.await())
    }

    @Test
    fun `Si llego de un aviso de otra sucursal - vista previa, POST y relectura llevan ESA sucursal`() = runTest {
        every { connectivity.isOnline() } returns true
        coEvery { api.previewUndoNoShow("r1", "vA") } returns Result.success(UndoNoShowPlan())
        coEvery { api.undoNoShow("r1", null, "vA") } returns
            Result.success(UndoNoShowResult("UNDONE", stub("r1"), UndoNoShowPlan(), NoShowCorrectionRef("c1")))
        coEvery { api.get("r1", "vA") } returns Result.success(stub("r1"))

        assertTrue(repo.previewUndoNoShow("r1", venueId = "vA").isSuccess)
        assertTrue(repo.undoNoShow("r1", venueId = "vA").isSuccess)
        assertTrue(repo.fetchOne("r1", venueId = "vA").isSuccess)
    }

    private fun stub(id: String) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CONFIRMED, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )
}
