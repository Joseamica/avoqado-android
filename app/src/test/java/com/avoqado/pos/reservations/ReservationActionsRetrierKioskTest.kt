package com.avoqado.pos.reservations

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.FakeReservationDao
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import com.avoqado.pos.reservations.data.ReservationApi
import com.avoqado.pos.reservations.data.ReservationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * El reintentador es el ÚNICO mensajero de las llegadas del kiosco (Modo Autoservicio 0.e).
 *
 * 🔴 Las manda por la ruta del kiosco, a la sucursal de la fila, con el cierre que vio la persona y lo que faltaba para
 * él. Sin red o con 5xx la fila se queda; un 4xx va a cuarentena de una vez con el texto del servidor; y nunca va la
 * misma fila dos veces a la vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationActionsRetrierKioskTest {

    private val C = "2026-10-28T19:20:00.000Z"
    private val MAX = ReservationRepository.MAX_ATTEMPTS

    private val dao = FakeReservationDao()
    private val api = mockk<ReservationApi>()
    private val kioskApi = mockk<KioskCheckInApi>()
    private val storage = mockk<SecureStorage> { every { venueId } returns "v1" }
    private val retrier = ReservationActionsRetrier(dao, api, mockk(relaxed = true), mockk(relaxed = true), kioskApi, storage)

    private suspend fun llegada(reservationId: String = "r1", payload: String? = KioskTap.payload("v1", C, 900_000)): Long =
        dao.insert(PendingReservationActionEntity(reservationId = reservationId, action = KioskTap.ACTION, payloadJson = payload))

    private fun fila(rowId: Long) = dao.rows.first { it.rowId == rowId }

    // Codex, código r3: una cuarentena VIEJA de la misma reserva no puede quitarle el ✓ a una llegada que el servidor ya
    // aceptó. Cuando una llegada sale bien, las cuarentenas de esa reserva quedan resueltas.
    @Test
    fun `una llegada aceptada resuelve la cuarentena vieja de la misma reserva`() = runTest {
        val vieja = llegada()
        dao.quarantine(vieja, MAX, "Error del servidor")
        val otraReserva = llegada(reservationId = "r2")
        dao.quarantine(otraReserva, MAX, "No asistió")
        val nueva = llegada()
        coEvery { kioskApi.checkInReservation(any(), "r1", any(), any()) } returns Result.success(Unit)

        val mandadas = retrier.drain(KioskTap.ACTION)

        assertEquals(mapOf(nueva to KioskTap.Outcome.Sent), mandadas)
        assertEquals(listOf(otraReserva), dao.rows.map { it.rowId })
    }

    // Codex, código r4: si otro toque cancela este trabajo entre borrar la fila y resolver la cuarentena vieja, quedaba
    // sólo la cuarentena y el ✓ de una llegada aceptada se volvía a quitar.
    @Test
    fun `cancelar justo despues del si no deja la cuarentena vieja`() = runTest {
        val vieja = llegada()
        dao.quarantine(vieja, MAX, "Error del servidor")
        llegada()
        dao.alResolverCuarentena = { yield() }
        coEvery { kioskApi.checkInReservation(any(), "r1", any(), any()) } coAnswers {
            currentCoroutineContext().job.cancel()
            Result.success(Unit)
        }

        launch { retrier.drain(KioskTap.ACTION) }.join()

        assertTrue("quedó: ${dao.rows}", dao.rows.isEmpty())
    }

    @Test
    fun `KIOSK_CHECK_IN se manda a su sucursal con lo que faltaba para el cierre`() = runTest {
        val id = llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.success(Unit)

        val mandadas = retrier.drain()

        coVerify(exactly = 1) { kioskApi.checkInReservation("v1", "r1", C, 900_000) }
        assertTrue(dao.rows.isEmpty())
        assertEquals(mapOf(id to KioskTap.Outcome.Sent), mandadas)
    }

    @Test
    fun `sin red o con 5xx la fila se queda - sin red no gasta intento y 5xx si`() = runTest {
        val id = llegada()

        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.failure(IOException())
        assertEquals(mapOf(id to KioskTap.Outcome.Kept), retrier.drain(KioskTap.ACTION))
        assertEquals(0, fila(id).attemptCount)

        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns
            Result.failure(KioskCheckInApi.Rejected(503, "caído"))
        assertEquals(mapOf(id to KioskTap.Outcome.Kept), retrier.drain(KioskTap.ACTION))
        assertEquals(1, fila(id).attemptCount)
        assertEquals("caído", fila(id).lastError)
    }

    @Test
    fun `un 4xx va a cuarentena de una vez con el texto del servidor`() = runTest {
        val id = llegada()
        val texto = "Ana R. llegó a las 13:05 sin conexión, pero su reserva ya estaba como «No asistió»."
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.failure(KioskCheckInApi.Rejected(409, texto))

        val mandadas = retrier.drain(KioskTap.ACTION)

        assertEquals(MAX, fila(id).attemptCount)
        assertEquals(texto, fila(id).lastError)
        assertEquals(mapOf(id to KioskTap.Outcome.Refused(texto)), mandadas)

        retrier.drain(KioskTap.ACTION) // en cuarentena ya no se vuelve a mandar
        coVerify(exactly = 1) { kioskApi.checkInReservation(any(), any(), any(), any()) }
    }

    @Test
    fun `un 429 deja la fila y gasta un intento`() = runTest {
        val id = llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns
            Result.failure(KioskCheckInApi.Rejected(429, "Demasiadas solicitudes"))

        assertEquals(mapOf(id to KioskTap.Outcome.Kept), retrier.drain(KioskTap.ACTION))
        assertEquals(1, fila(id).attemptCount)
        assertEquals("Demasiadas solicitudes", fila(id).lastError)
    }

    /** El cierre que vio la persona, en hora del SERVIDOR, hace [msAgo] ms. */
    private fun cierreHace(msAgo: Long) = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - msAgo).toString()

    private val HORA = 60 * 60_000L

    @Test
    fun `una llegada guardada con la fecha del aparato atrasada se manda igual`() = runTest {
        // El aparato tenía 4 días de atraso al guardarla; ya la corrigieron. El cierre (hora del SERVIDOR) fue hace 5 min.
        val id = dao.insert(
            PendingReservationActionEntity(
                reservationId = "r1",
                action = KioskTap.ACTION,
                payloadJson = KioskTap.payload("v1", cierreHace(5 * 60_000L), 900_000),
                createdAt = System.currentTimeMillis() - 96 * HORA,
            ),
        )
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.success(Unit)

        assertEquals(mapOf(id to KioskTap.Outcome.Sent), retrier.drain(KioskTap.ACTION))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `sin red y con un cierre de hace mas de 72 h va a cuarentena, y las de atras si se intentan`() = runTest {
        val vieja = dao.insert(
            PendingReservationActionEntity(
                reservationId = "r1",
                action = KioskTap.ACTION,
                payloadJson = KioskTap.payload("v1", cierreHace(73 * HORA), 900_000),
                createdAt = System.currentTimeMillis() - 73 * HORA,
            ),
        )
        val nueva = llegada("r2", KioskTap.payload("v1", cierreHace(5 * 60_000L), 900_000))
        coEvery { kioskApi.checkInReservation(any(), "r1", any(), any()) } returns Result.failure(IOException())
        coEvery { kioskApi.checkInReservation(any(), "r2", any(), any()) } returns Result.success(Unit)

        retrier.drain(KioskTap.ACTION)

        assertEquals(MAX, fila(vieja).attemptCount)
        assertEquals("La llegada lleva más de 72 horas sin poder enviarse.", fila(vieja).lastError)
        // La vieja no cuenta como «sin red»: no frena a las de atrás.
        coVerify(exactly = 1) { kioskApi.checkInReservation("v1", "r2", any(), any()) }
        assertTrue(dao.rows.none { it.rowId == nueva })
    }

    /**
     * 🔴 El tope local de 72 h nunca puede mandar a cuarentena una llegada que el SERVIDOR aceptaría (Codex código r2 #2).
     * Pide las DOS edades: desde el cierre (hora del SERVIDOR) y desde `createdAt` (hora del aparato al guardarla), las dos
     * contra la hora del aparato AHORA. Un reloj atrasado al guardar falla la primera; uno adelantado todo el tiempo, la
     * segunda. Sólo «bien al guardar y luego salta más de 72 h hacia adelante» la manda antes de tiempo (residual).
     */
    @Test
    fun `tabla de verdad del tope de 72 h segun el reloj del aparato`() = runTest {
        data class Caso(val nombre: String, val creadaHace: Long, val cierreHace: Long, val relojAdelantado: Long, val cuarentena: Boolean)
        val dia = 24 * HORA
        val casos = listOf(
            Caso("reloj bien", creadaHace = 60_000L, cierreHace = 5 * 60_000L, relojAdelantado = 0, cuarentena = false),
            Caso("atrasado al guardar y ya corregido", creadaHace = 4 * dia, cierreHace = 5 * 60_000L, relojAdelantado = 0, cuarentena = false),
            Caso("adelantado todo el tiempo", creadaHace = 60_000L - 4 * dia, cierreHace = 5 * 60_000L, relojAdelantado = 4 * dia, cuarentena = false),
            Caso("bien al guardar y luego adelantado (residual)", creadaHace = 60_000L, cierreHace = 5 * 60_000L, relojAdelantado = 4 * dia, cuarentena = true),
            Caso("73 h sin red de verdad", creadaHace = 73 * HORA, cierreHace = 73 * HORA + 5 * 60_000L, relojAdelantado = 0, cuarentena = true),
        )
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.failure(IOException())

        for (caso in casos) {
            val verdad = System.currentTimeMillis()
            // `createdAt` es la hora del APARATO al guardarla; el cierre, la del SERVIDOR; «ahora», la del aparato hoy.
            val id = dao.insert(
                PendingReservationActionEntity(
                    reservationId = "r1",
                    action = KioskTap.ACTION,
                    payloadJson = KioskTap.payload("v1", java.time.Instant.ofEpochMilli(verdad - caso.cierreHace).toString(), 900_000),
                    createdAt = verdad - caso.creadaHace,
                ),
            )
            retrier.ahora = { verdad + caso.relojAdelantado }

            retrier.drain(KioskTap.ACTION)

            assertEquals(caso.nombre, if (caso.cuarentena) MAX else 0, fila(id).attemptCount)
            dao.delete(id)
        }
    }

    @Test
    fun `una llegada de otra sucursal con un cierre de hace mas de 72 h espera a su sucursal intacta`() = runTest {
        val id = llegada(payload = KioskTap.payload("v2", cierreHace(73 * HORA), 900_000))

        retrier.drain(KioskTap.ACTION)

        coVerify(exactly = 0) { kioskApi.checkInReservation(any(), any(), any(), any()) }
        assertEquals(0, fila(id).attemptCount)
        assertEquals(null, fila(id).lastError)
    }

    @Test
    fun `una fila de otra sucursal se salta sin gastar intento`() = runTest {
        val id = llegada(payload = KioskTap.payload("v2", C, 900_000))

        val mandadas = retrier.drain()

        coVerify(exactly = 0) { kioskApi.checkInReservation(any(), any(), any(), any()) }
        assertEquals(0, fila(id).attemptCount)
        assertTrue(mandadas.isEmpty())
    }

    @Test
    fun `una fila sin payload legible no se borra`() = runTest {
        val id = llegada(payload = "{}")

        retrier.drain()

        assertEquals(1, fila(id).attemptCount)
        assertEquals("Llegada sin datos guardados", fila(id).lastError)
        coVerify(exactly = 0) { kioskApi.checkInReservation(any(), any(), any(), any()) }
    }

    @Test
    fun `drain(KIOSK_CHECK_IN) no toca las filas del personal`() = runTest {
        val personal = dao.insert(PendingReservationActionEntity(reservationId = "r9", action = "CONFIRM"))
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.success(Unit)

        retrier.drain(KioskTap.ACTION)

        coVerify(exactly = 0) { api.confirm(any(), any()) }
        assertEquals(listOf(personal), dao.rows.map { it.rowId })
        assertEquals(0, fila(personal).attemptCount)
    }

    @Test
    fun `dos drain a la vez mandan una sola vez`() = runTest {
        llegada()
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } coAnswers { delay(1_000); Result.success(Unit) }

        launch { retrier.drain() }
        launch { retrier.drain(KioskTap.ACTION) }
        advanceUntilIdle()

        coVerify(exactly = 1) { kioskApi.checkInReservation(any(), any(), any(), any()) }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `tras un corte de red las demas llegadas esperan a la siguiente vuelta, sin gastar intento`() = runTest {
        val primera = llegada("r1")
        val segunda = llegada("r2")
        coEvery { kioskApi.checkInReservation(any(), any(), any(), any()) } returns Result.failure(IOException())

        val mandadas = retrier.drain(KioskTap.ACTION)

        // Una sola espera de red por vuelta, no una por fila: quien acaba de tocar no espera a las de antes.
        coVerify(exactly = 1) { kioskApi.checkInReservation(any(), any(), any(), any()) }
        assertEquals(mapOf(primera to KioskTap.Outcome.Kept), mandadas)
        assertEquals(listOf(0, 0), listOf(fila(primera).attemptCount, fila(segunda).attemptCount))
    }
}
