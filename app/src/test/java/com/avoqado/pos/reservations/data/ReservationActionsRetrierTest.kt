package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationActionsRetrierTest {

    private val dao: PendingReservationActionDao = mockk(relaxed = true)
    private val api: ReservationApi = mockk()
    private val connectivity: ConnectivityMonitor = mockk()
    private val reservationRepository: ReservationRepository = mockk(relaxed = true)
    private val retrier = ReservationActionsRetrier(dao, api, connectivity, reservationRepository)

    init {
        every { connectivity.isOnlineFlow } returns MutableStateFlow(false)
    }

    @Test
    fun `drain deletes entry on successful api call`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 1, reservationId = "r1", action = "CONFIRM"),
        )
        coEvery { api.confirm("r1") } returns Result.success(stub("r1"))

        retrier.drain()

        coVerify(exactly = 1) { dao.delete(1L) }
        coVerify(exactly = 0) { dao.incrementAttempt(any()) }
    }

    @Test
    fun `drain increments attempt count on api failure`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 2, reservationId = "r2", action = "CHECK_IN"),
        )
        coEvery { api.checkIn("r2") } returns Result.failure(RuntimeException("timeout"))

        retrier.drain()

        coVerify(exactly = 1) { dao.incrementAttempt(2L) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    /**
     * Reemplaza a un test anterior que exigía BORRAR la entrada al agotar los
     * intentos. Ese era el bug: la acción del mesero desaparecía sin rastro y
     * nadie se enteraba. Ahora se conserva para la cuarentena — pero sigue sin
     * reintentarse, que era la otra mitad correcta del comportamiento viejo.
     */
    @Test
    fun `al agotar los intentos deja de llamar al server pero conserva la entrada`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 3, reservationId = "r3", action = "COMPLETE", attemptCount = 5),
        )

        retrier.drain()

        coVerify(exactly = 0) { dao.delete(3L) }
        coVerify(exactly = 0) { api.complete(any()) }
    }

    @Test
    fun `drain deletes entry for unknown action without calling api`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 4, reservationId = "r4", action = "UNKNOWN_ACTION"),
        )

        retrier.drain()

        coVerify(exactly = 1) { dao.delete(4L) }
    }

    @Test
    fun `drain processes multiple entries in order`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 10, reservationId = "r10", action = "CONFIRM"),
            PendingReservationActionEntity(rowId = 11, reservationId = "r11", action = "CHECK_IN"),
        )
        coEvery { api.confirm("r10") } returns Result.success(stub("r10"))
        coEvery { api.checkIn("r11") } returns Result.success(stub("r11"))

        retrier.drain()

        coVerify(exactly = 1) { dao.delete(10L) }
        coVerify(exactly = 1) { dao.delete(11L) }
    }

    private fun stub(id: String) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CONFIRMED, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )

    /**
     * Agotar los reintentos NO puede borrar la acción.
     *
     * Antes sí: tras 5 intentos se hacía `delete` y la acción del mesero
     * desaparecía sin rastro — la mesa quedaba sin confirmar o un cliente sin
     * cancelar, y no había dónde enterarse. Conservarla es lo que permite
     * listarla en la cuarentena, igual que los cobros rechazados.
     */
    @Test
    fun `una accion agotada se conserva para la cuarentena, no se borra`() = runTest {
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(
                rowId = 9,
                reservationId = "r9",
                action = "CANCEL",
                attemptCount = 5,
            ),
        )

        retrier.drain()

        coVerify(exactly = 0) { dao.delete(9L) }
        coVerify(exactly = 0) { dao.incrementAttempt(9L) }
    }

    @Test
    fun `una accion agotada ya no se reintenta contra el server`() = runTest {
        // Reintentar para siempre martillea al server con algo que ya rechazó.
        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 10, reservationId = "r10", action = "CONFIRM", attemptCount = 5),
        )

        retrier.drain()

        coVerify(exactly = 0) { api.confirm(any()) }
    }
}
