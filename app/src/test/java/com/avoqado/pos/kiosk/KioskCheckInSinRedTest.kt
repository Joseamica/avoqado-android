package com.avoqado.pos.kiosk

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskRosterDto
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskDriver
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskPerson
import com.avoqado.pos.kiosk.domain.KioskState
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import com.avoqado.pos.reservations.data.ReservationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * La llegada en el kiosco SIN RED (Modo Autoservicio 0.e, founder 27-oct: «como hoy, con ✓»).
 *
 * 🔴 Lo que se prueba es la promesa: la persona ve su palomita, la llegada se guarda en el aparato ANTES de cualquier
 * red con lo que faltaba para el cierre (sin ningún reloj de pared), un solo mensajero la manda, y la lista caduca sola
 * sin borrar a quien está tecleando o pagando.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskCheckInSinRedTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = MainDispatcherRule(dispatcher)

    private val C = "2026-10-28T19:20:00.000Z"
    private val ana = KioskPerson("r1", "Ana R.", false, "Tapete 4")
    private val bea = KioskPerson("r2", "Bea S.", false)

    private fun lista(closesInMs: Long = 1_200_000, people: List<KioskPerson> = listOf(ana, bea)) =
        KioskRosterDto("Yoga", "2026-10-28T19:10:00.000Z", "María", people, C, closesInMs)

    private val api = mockk<KioskCheckInApi>().also {
        coEvery { it.checkInReservation(any(), any(), any(), any()) } returns Result.success(null)
        coEvery { it.roster() } returns Result.failure(IOException())
    }
    private val dao = FakeReservationDao()
    private val retrier = mockk<ReservationActionsRetrier>().also { coEvery { it.drain(any()) } returns emptyMap() }
    private val storage = mockk<SecureStorage> { every { venueId } returns "v1" }

    // Perezoso: KioskState toma Dispatchers.Main al construirse, y la regla lo cambia hasta que empieza la prueba.
    private val kiosk by lazy { KioskState() }

    /** null = el reloj monótono sigue al tiempo virtual de la prueba. */
    private var monoMs: Long? = null

    /**
     * 🔴 Al terminar se APAGA el kiosco: `runTest` corre `advanceUntilIdle` al final y el reloj del kiosco (cada 15 s)
     * nunca se queda quieto, así que giraba sin fin en tiempo virtual (medido: la corrida se congelaba).
     */
    private fun prueba(cuerpo: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            cuerpo()
        } finally {
            kiosk.setEnabled(false)
            runCurrent()
        }
    }

    private fun TestScope.arrancar() {
        KioskDriver(kiosk, mockk(relaxed = true), api, dao, retrier, storage).apply {
            mono = { (monoMs ?: testScheduler.currentTime) * 1_000_000 }
            attach()
        }
        kiosk.setEnabled(true)
        runCurrent()
    }

    /** La lista llega una vez; después, sin red. */
    private fun unaListaYSinRed(roster: KioskRosterDto? = lista()) {
        coEvery { api.roster() } returnsMany listOf(Result.success(roster), Result.failure(IOException()))
    }

    private fun TestScope.tocar(p: KioskPerson) {
        kiosk.onCheckIn?.invoke(p)
        runCurrent()
    }

    private fun pantalla() = kiosk.content.value as KioskContent.Roster
    private fun persona(id: String) = pantalla().people.first { it.reservationId == id }

    // MARK: - El toque

    @Test
    fun `sin red pinta la palomita, lo dice en gris y guarda ANTES de mandar, con lo que faltaba para el cierre`() = prueba {
        unaListaYSinRed()
        var filaAlMandar = false
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            filaAlMandar = dao.rows.any { it.action == KioskTap.ACTION }
            mapOf(dao.rows.last().rowId to KioskTap.Outcome.Kept)
        }
        arrancar()

        advanceTimeBy(300_000) // la lista se pidió con mono = 0 y dice que faltan 20 min; se toca a los 5 min
        tocar(ana)

        val r = pantalla()
        assertTrue(r.offline)
        assertFalse(r.failed)
        assertTrue(persona("r1").checkedIn)
        assertEquals("r1", r.justConfirmedId)
        assertEquals(listOf(KioskTap.ACTION to KioskTap.payload("v1", C, 900_000, name = "Ana R.")), dao.rows.map { it.action to it.payloadJson })
        assertEquals("r1", dao.rows.single().reservationId)
        assertTrue("la fila tiene que existir cuando se pide mandarla", filaAlMandar)
    }

    @Test
    fun `el conductor nunca hace su propio POST`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers { mapOf(dao.rows.last().rowId to KioskTap.Outcome.Sent) }
        arrancar()

        tocar(ana)

        coVerify(exactly = 1) { retrier.drain(KioskTap.ACTION) }
        coVerify(exactly = 0) { api.checkInReservation(any(), any(), any(), any()) }
    }

    @Test
    fun `con red confirma y limpia el aviso`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers { mapOf(dao.rows.last().rowId to KioskTap.Outcome.Kept) }
        arrancar()
        tocar(ana)
        assertTrue(pantalla().offline)

        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            dao.delete(fila)
            mapOf(fila to KioskTap.Outcome.Sent)
        }
        tocar(bea)

        assertFalse(pantalla().offline)
        assertFalse(pantalla().failed)
        assertTrue(persona("r2").checkedIn)
        assertEquals("r2", pantalla().justConfirmedId)
    }

    @Test
    fun `un 5xx en vivo no borra la fila y se dice como sin red`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            dao.incrementAttempt(fila, "Ocurrió un error inesperado en el servidor.")
            mapOf(fila to KioskTap.Outcome.Kept)
        }
        arrancar()

        tocar(ana)

        assertTrue(persona("r1").checkedIn)
        assertTrue(pantalla().offline)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `el servidor la rechaza en vivo - sin palomita, borra la fila y lo dice`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            dao.quarantine(fila, ReservationRepository.MAX_ATTEMPTS, "Fuera de horario")
            mapOf(fila to KioskTap.Outcome.Refused("Fuera de horario"))
        }
        arrancar()

        tocar(ana)

        assertTrue(pantalla().failed)
        assertFalse(pantalla().offline)
        assertFalse(persona("r1").checkedIn)
        assertNull(pantalla().justConfirmedId)
        assertTrue(dao.rows.isEmpty())
    }

    // Si otro drain (el de la red que volvió, o el de la lista) ya mandó la fila, el mapa no la trae: se lee de la tabla.

    @Test
    fun `si otro drain ya la mando y la borro, el toque lo lee de la tabla - palomita`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers { dao.delete(dao.rows.last().rowId); emptyMap() }
        arrancar()

        tocar(ana)

        assertTrue(persona("r1").checkedIn)
        assertFalse(pantalla().offline)
        assertFalse(pantalla().failed)
    }

    @Test
    fun `si otro drain ya la mando a cuarentena, el toque lo lee de la tabla - falla`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            dao.quarantine(dao.rows.last().rowId, ReservationRepository.MAX_ATTEMPTS, "No asistió")
            emptyMap()
        }
        arrancar()

        tocar(ana)

        assertTrue(pantalla().failed)
        assertFalse(persona("r1").checkedIn)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `si otro drain la dejo en la fila sin agotar, el toque lo lee de la tabla - palomita sin red`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } returns emptyMap()
        arrancar()

        tocar(ana)

        assertTrue(persona("r1").checkedIn)
        assertTrue(pantalla().offline)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `una segunda llegada no borra la palomita de la anterior`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            delay(10_000) // WiFi conectado sin datos: el envío espera su timeout
            mapOf(fila to KioskTap.Outcome.Kept)
        }
        arrancar()

        tocar(ana)
        advanceTimeBy(2_000)
        tocar(bea) // la de atrás toca mientras Ana sigue esperando

        // Ana ya quedó guardada: conserva su palomita (con el aviso en gris) y no tiene que volver a tocar.
        assertTrue(persona("r1").checkedIn)
        assertTrue(pantalla().offline)
        assertEquals("r2", pantalla().busyId)
        assertEquals(1, dao.rows.count { it.reservationId == "r1" })
    }

    @Test
    fun `un toque tras el cierre no borra a quien sigue en camino`() = prueba {
        unaListaYSinRed(lista(closesInMs = 60_000))
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            delay(10_000)
            mapOf(fila to KioskTap.Outcome.Kept)
        }
        arrancar()

        advanceTimeBy(58_000)
        tocar(ana) // en camino
        monoMs = 61_000 // ya cerró la ventana
        tocar(bea)

        assertEquals("r1", pantalla().busyId)
        assertNotNull(kiosk.lastRoster)
        assertEquals(listOf("r1"), dao.rows.map { it.reservationId })
    }

    @Test
    fun `si el servidor rechaza a la de antes en la vuelta de la de atras, se le quita la palomita`() = prueba {
        unaListaYSinRed()
        var vuelta = 0
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            vuelta++
            if (vuelta == 1) {
                delay(10_000) // el envío de Ana se queda esperando; el toque de Bea lo cancela
                emptyMap()
            } else {
                val a = dao.rows.first { it.reservationId == "r1" }.rowId
                val b = dao.rows.first { it.reservationId == "r2" }.rowId
                dao.quarantine(a, ReservationRepository.MAX_ATTEMPTS, "Ana R. ya estaba como «No asistió».")
                dao.delete(b)
                mapOf(a to KioskTap.Outcome.Refused("Ana R. ya estaba como «No asistió»."), b to KioskTap.Outcome.Sent)
            }
        }
        arrancar()

        tocar(ana)
        advanceTimeBy(2_000)
        tocar(bea)

        assertFalse("con red, un rechazo no pinta palomita", persona("r1").checkedIn)
        assertTrue(persona("r2").checkedIn)
        assertEquals(ReservationRepository.MAX_ATTEMPTS, dao.rows.single { it.reservationId == "r1" }.attemptCount)
    }

    @Test
    fun `una lista vacia que llega tarde no borra un toque en camino`() = prueba {
        var llamadas = 0
        coEvery { api.roster() } coAnswers {
            llamadas++
            if (llamadas == 1) Result.success(lista())
            else {
                delay(5_000) // salió antes del toque y llegó al SERVIDOR con la ventana ya cerrada
                Result.success(null)
            }
        }
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            delay(10_000)
            dao.delete(fila)
            mapOf(fila to KioskTap.Outcome.Sent)
        }
        arrancar()

        advanceTimeBy(16_000) // la consulta de los 15 s ya va en camino
        tocar(bea)
        advanceTimeBy(4_001) // llega `roster: null`

        assertEquals("r2", pantalla().busyId)

        advanceTimeBy(6_000) // regresa el envío de Bea
        assertTrue(persona("r2").checkedIn)
        assertEquals("r2", pantalla().justConfirmedId)
    }

    // MARK: - Un rechazo del SERVIDOR quita la palomita, venga de la vuelta que venga (Codex código r2 #1)

    @Test
    fun `si el envio de fondo rechazo a Ana, al tocar Bea se le quita la palomita`() = prueba {
        unaListaYSinRed()
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers { mapOf(dao.rows.last().rowId to KioskTap.Outcome.Kept) }
        arrancar()
        tocar(ana) // sin red: palomita en gris, fila en la cola
        assertTrue(persona("r1").checkedIn)

        // Vuelve la red: el reintentador de fondo manda a Ana y el SERVIDOR la rechaza (cuarentena).
        dao.quarantine(dao.rows.single { it.reservationId == "r1" }.rowId, ReservationRepository.MAX_ATTEMPTS, "No asistió")
        // El envío de Bea ya no trae a Ana en su mapa: sólo su propio resultado.
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val b = dao.rows.last().rowId
            dao.delete(b)
            mapOf(b to KioskTap.Outcome.Sent)
        }
        advanceTimeBy(2_000)
        tocar(bea)

        assertFalse("con red, un rechazo no pinta palomita", persona("r1").checkedIn)
        assertTrue(persona("r2").checkedIn)
    }

    @Test
    fun `si el envio del refresco rechaza a Ana, se le quita la palomita`() = prueba {
        coEvery { api.roster() } returns Result.success(lista())
        var vuelta = 0
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            vuelta++
            val a = dao.rows.single { it.reservationId == "r1" }.rowId
            if (vuelta == 1) mapOf(a to KioskTap.Outcome.Kept) // al tocar: sin respuesta
            else {
                dao.quarantine(a, ReservationRepository.MAX_ATTEMPTS, "No asistió") // el refresco la manda: rechazada
                mapOf(a to KioskTap.Outcome.Refused("No asistió"))
            }
        }
        arrancar()
        advanceTimeBy(1_000)
        tocar(ana)
        assertTrue(persona("r1").checkedIn)

        advanceTimeBy(29_001) // el refresco de los 30 s ya no espera al toque: pinta, vacía la cola y la rechaza

        assertEquals(2, vuelta)
        assertFalse("con red, un rechazo no pinta palomita", persona("r1").checkedIn)
    }

    @Test
    fun `nunca se le quita la palomita a quien el SERVIDOR da por llegada`() = prueba {
        var llamadas = 0
        coEvery { api.roster() } coAnswers {
            llamadas++
            Result.success(if (llamadas == 1) lista() else lista(people = listOf(ana.copy(checkedIn = true), bea)))
        }
        var vuelta = 0
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            vuelta++
            val a = dao.rows.single { it.reservationId == "r1" }.rowId
            if (vuelta == 1) mapOf(a to KioskTap.Outcome.Kept)
            else {
                dao.quarantine(a, ReservationRepository.MAX_ATTEMPTS, "Otra cosa")
                mapOf(a to KioskTap.Outcome.Refused("Otra cosa"))
            }
        }
        arrancar()
        advanceTimeBy(1_000)
        tocar(ana)
        advanceTimeBy(29_001)

        assertEquals(2, vuelta)
        assertTrue(persona("r1").checkedIn)
    }

    // MARK: - La lista y la cola

    @Test
    fun `la lista del servidor no borra una palomita que sigue en la cola, y despues vacia la cola del kiosco`() = prueba {
        dao.insert(PendingReservationActionEntity(reservationId = "r1", action = KioskTap.ACTION, payloadJson = KioskTap.payload("v1", C, 60_000)))
        unaListaYSinRed()
        arrancar()

        assertTrue(persona("r1").checkedIn)
        assertFalse(persona("r2").checkedIn)
        coVerify(exactly = 1) { retrier.drain(KioskTap.ACTION) }
    }

    @Test
    fun `sin clase en la lista tambien vacia la cola del kiosco`() = prueba {
        dao.insert(PendingReservationActionEntity(reservationId = "r1", action = KioskTap.ACTION, payloadJson = KioskTap.payload("v1", C, 60_000)))
        coEvery { api.roster() } returns Result.success(null)
        arrancar()

        assertEquals(KioskContent.Welcome, kiosk.content.value)
        coVerify(atLeast = 1) { retrier.drain(KioskTap.ACTION) }
    }

    @Test
    fun `sin llegadas en la cola la lista no despierta al reintentador`() = prueba {
        unaListaYSinRed()
        arrancar()

        assertTrue(kiosk.content.value is KioskContent.Roster)
        coVerify(exactly = 0) { retrier.drain(any()) }
    }

    // MARK: - La lista caduca sola

    @Test
    fun `la lista caduca al cerrar su ventana y Cancelar no la regresa`() = prueba {
        unaListaYSinRed(lista(closesInMs = 60_000))
        arrancar()

        advanceTimeBy(20_000)
        kiosk.onStart?.invoke()
        "5512".forEach { kiosk.onDigit?.invoke(it.toString()) }
        runCurrent()
        advanceTimeBy(40_001)

        assertNull(kiosk.lastRoster)
        val tecleando = kiosk.content.value as KioskContent.Identify
        assertEquals("5512", tecleando.national)

        kiosk.onRestart?.invoke()
        runCurrent()
        assertEquals(KioskContent.Welcome, kiosk.content.value)
    }

    @Test
    fun `caducar a media llegada espera su palomita`() = prueba {
        unaListaYSinRed(lista(closesInMs = 60_000))
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers {
            val fila = dao.rows.last().rowId
            delay(10_000)
            mapOf(fila to KioskTap.Outcome.Kept)
        }
        arrancar()

        advanceTimeBy(58_000)
        tocar(ana)
        advanceTimeBy(2_001) // vence la ventana con el toque en camino

        assertEquals("r1", pantalla().busyId)
        assertNotNull(kiosk.lastRoster)

        advanceTimeBy(8_000) // regresa el reintentador
        assertTrue(persona("r1").checkedIn)
        assertTrue(pantalla().offline)
        assertEquals("r1", pantalla().justConfirmedId)

        advanceTimeBy(8_000) // se encoge la palomita
        assertEquals(KioskContent.Welcome, kiosk.content.value)
        assertNull(kiosk.lastRoster)
    }

    @Test
    fun `caducar la lista no borra un pago en curso`() = prueba {
        unaListaYSinRed(lista(closesInMs = 60_000))
        arrancar()
        val pagando = KioskContent.Paying(customerName = "", pack = KioskPack("p1", "10 clases", 150_000, null), payUrl = "https://pay/x")
        kiosk.show(pagando)

        advanceTimeBy(60_001)

        assertEquals(pagando, kiosk.content.value)
    }

    @Test
    fun `una lista que tardo en llegar cierra antes, nunca despues`() = prueba {
        coEvery { api.roster() } coAnswers { delay(10_000); Result.success(lista(closesInMs = 30_000)) } andThen Result.failure(IOException())
        coEvery { retrier.drain(KioskTap.ACTION) } coAnswers { mapOf(dao.rows.last().rowId to KioskTap.Outcome.Kept) }
        arrancar()

        advanceTimeBy(29_000)
        tocar(ana)
        assertEquals(KioskTap.payload("v1", C, 1_000, name = "Ana R."), dao.rows.single().payloadJson)

        // Cierra a los 30 s (contados desde que se PIDIÓ), no a los 40 s: la palomita se encoge a los 37 s y ya no hay lista.
        advanceTimeBy(8_001)
        assertEquals(KioskContent.Welcome, kiosk.content.value)
        assertNull(kiosk.lastRoster)
    }

    @Test
    fun `un toque con la ventana ya vencida no guarda nada`() = prueba {
        unaListaYSinRed(lista(closesInMs = 60_000))
        arrancar()

        monoMs = 61_000 // el reloj monótono ya pasó el cierre, antes de que corra el delay
        tocar(ana)

        assertTrue(dao.rows.isEmpty())
        assertEquals(KioskContent.Welcome, kiosk.content.value)
        coVerify(exactly = 0) { retrier.drain(any()) }
    }

    @Test
    fun `el guardia del ultimo toque cuenta con el reloj monotono, no con la hora de pared`() = prueba {
        monoMs = 0
        coEvery { api.roster() } returns Result.success(lista())
        arrancar()
        kiosk.onPickCountry?.invoke() // un toque

        monoMs = 10_000
        advanceTimeBy(15_001)
        coVerify(exactly = 1) { api.roster() } // 10 s desde el toque: se respeta a quien tocó

        monoMs = 26_000
        advanceTimeBy(15_000)
        coVerify(exactly = 2) { api.roster() } // 26 s en el reloj monótono: la lista se vuelve a pedir
    }
}

/** La tabla `pending_reservation_action` en memoria, con el mismo comportamiento que las consultas de Room. */
internal class FakeReservationDao : PendingReservationActionDao {
    val rows = mutableListOf<PendingReservationActionEntity>()
    private var next = 1L

    override suspend fun insert(action: PendingReservationActionEntity): Long =
        next++.also { rows += action.copy(rowId = it) }

    override suspend fun deleteEquivalent(reservationId: String, action: String, payloadJson: String?) {
        rows.removeAll { it.reservationId == reservationId && it.action == action && it.payloadJson == payloadJson }
    }

    override suspend fun all() = rows.sortedBy { it.createdAt }.toList()
    override fun pendingCount(): Flow<Int> = flowOf(rows.size)
    override suspend fun exhausted(maxAttempts: Int) = rows.filter { it.attemptCount >= maxAttempts }
    override fun exhaustedCount(maxAttempts: Int): Flow<Int> = flowOf(rows.count { it.attemptCount >= maxAttempts })
    override suspend fun delete(rowId: Long) { rows.removeAll { it.rowId == rowId } }

    override suspend fun incrementAttempt(rowId: Long, error: String?) =
        update(rowId) { it.copy(attemptCount = it.attemptCount + 1, lastError = error) }

    override suspend fun pendingIds(action: String, maxAttempts: Int) =
        rows.filter { it.action == action && it.attemptCount < maxAttempts }.map { it.reservationId }

    override suspend fun countPending(action: String, maxAttempts: Int) = pendingIds(action, maxAttempts).size

    override suspend fun quarantine(rowId: Long, maxAttempts: Int, lastError: String?) =
        update(rowId) { it.copy(attemptCount = maxAttempts, lastError = lastError) }

    override suspend fun byId(rowId: Long) = rows.firstOrNull { it.rowId == rowId }
    /** Punto de corte para pruebas: corre al empezar [deleteQuarantined] (p. ej. `yield()` con el trabajo ya cancelado). */
    var alResolverCuarentena: suspend () -> Unit = {}
    override suspend fun deleteQuarantined(action: String, reservationId: String, maxAttempts: Int) {
        alResolverCuarentena()
        rows.removeAll { it.action == action && it.reservationId == reservationId && it.attemptCount >= maxAttempts }
    }

    private fun update(rowId: Long, f: (PendingReservationActionEntity) -> PendingReservationActionEntity) {
        val i = rows.indexOfFirst { it.rowId == rowId }
        if (i >= 0) rows[i] = f(rows[i])
    }
}
