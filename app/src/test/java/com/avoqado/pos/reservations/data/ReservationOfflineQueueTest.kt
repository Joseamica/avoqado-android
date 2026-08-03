package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que ve y provoca el mesero cuando toca una reserva sin internet.
 *
 * Los tres defectos que cubren estos tests venían juntos y se alimentaban entre
 * sí: la acción se encolaba bien, pero la pantalla la anunciaba como un error en
 * inglés ("Action CONFIRM enqueued for retry") y la reserva se veía sin cambiar,
 * así que el mesero volvía a tocar — y cada toque encolaba OTRA fila, que el
 * reintentador reproducía entera al reconectar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationOfflineQueueTest {

    private val api: ReservationApi = mockk()
    private val dao: PendingReservationActionDao = mockk(relaxed = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val repository = ReservationRepository(api, dao, connectivity)

    private fun sinRed() {
        coEvery { connectivity.isOnline() } returns false
    }

    // MARK: - Lo que lee el mesero

    @Test
    fun `sin red el aviso esta en espanol y dice que se aplicara al reconectar`() = runTest {
        sinRed()

        val r = repository.runAction("r1", ReservationAction.CONFIRM)

        val e = r.exceptionOrNull() as? ReservationRepository.OfflineEnqueuedException
        assertNotNull("debe ser la excepción de encolado, no una genérica", e)
        val msg = e!!.message.orEmpty()
        assertTrue("debe decir que no hay conexión: $msg", msg.contains("Sin conexión"))
        assertTrue("debe decir que se aplicará después: $msg", msg.contains("al volver el internet"))
        assertFalse("no puede quedar el texto técnico en inglés: $msg", msg.contains("enqueued"))
    }

    @Test
    fun `cada accion tiene su propio aviso, no uno generico`() = runTest {
        val mensajes = ReservationAction.entries.map { ReservationRepository.mensajeEncolado(it) }
        assertEquals("ninguna acción puede compartir aviso", mensajes.size, mensajes.toSet().size)
        assertTrue("todos en español", mensajes.all { it.startsWith("Sin conexión") })
    }

    // MARK: - Lo que provoca al reconectar

    @Test
    fun `tocar dos veces la misma accion NO encola dos veces`() = runTest {
        sinRed()
        // `enqueue` del DAO borra la equivalente antes de insertar; aquí se
        // verifica que el repositorio le pasa la MISMA identidad las dos veces,
        // que es lo que permite el descarte.
        val capturadas = mutableListOf<PendingReservationActionEntity>()
        coEvery { dao.enqueue(capture(capturadas)) } returns 1L

        repository.runAction("r1", ReservationAction.NO_SHOW)
        repository.runAction("r1", ReservationAction.NO_SHOW)

        assertEquals(2, capturadas.size)
        assertEquals(capturadas[0].reservationId, capturadas[1].reservationId)
        assertEquals(capturadas[0].action, capturadas[1].action)
        assertEquals(capturadas[0].payloadJson, capturadas[1].payloadJson)
    }

    @Test
    fun `dos reservas distintas NO se confunden entre si`() = runTest {
        sinRed()
        val capturadas = mutableListOf<PendingReservationActionEntity>()
        coEvery { dao.enqueue(capture(capturadas)) } returns 1L

        repository.createReservation(crear("Ana"))
        repository.createReservation(crear("Beto"))

        // Mismo reservationId placeholder y misma acción: lo único que las
        // distingue es el payload. Si se dedupĺicara sin él, la reserva de Beto
        // borraría la de Ana y un cliente se quedaría sin mesa.
        assertEquals(2, capturadas.size)
        assertEquals(capturadas[0].reservationId, capturadas[1].reservationId)
        assertTrue(
            "el payload debe distinguirlas",
            capturadas[0].payloadJson != capturadas[1].payloadJson,
        )
    }

    @Test
    fun `una cancelacion se encola con su motivo, no vacia`() = runTest {
        sinRed()
        val slot = slot<PendingReservationActionEntity>()
        coEvery { dao.enqueue(capture(slot)) } returns 1L

        repository.runAction(
            "r1",
            ReservationAction.CANCEL,
            ReservationRepository.ActionPayload.Cancel("el cliente avisó por teléfono"),
        )

        assertTrue(
            "el motivo tiene que viajar en la cola o se pierde al reconectar",
            slot.captured.payloadJson.orEmpty().contains("el cliente avisó por teléfono"),
        )
    }

    @Test
    fun `con red NO se encola nada`() = runTest {
        coEvery { connectivity.isOnline() } returns true
        coEvery { api.confirm("r1") } returns Result.failure(RuntimeException("500 del server"))

        val r = repository.runAction("r1", ReservationAction.CONFIRM)

        // Un rechazo del server NO es un encolado: se propaga tal cual.
        assertFalse(r.isSuccess)
        assertFalse(r.exceptionOrNull() is ReservationRepository.OfflineEnqueuedException)
        coVerify(exactly = 0) { dao.enqueue(any()) }
    }

    private fun crear(nombre: String) = CreateReservationRequest(
        startsAt = "2026-08-10T18:00:00Z",
        endsAt = "2026-08-10T19:00:00Z",
        partySize = 2,
        duration = 60,
        guestName = nombre,
    )
}
