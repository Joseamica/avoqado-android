package com.avoqado.pos.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentEntity.Companion.STATUS_HELD
import com.avoqado.pos.core.data.local.database.SyncIntentEntity.Companion.STATUS_PENDING
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncIntentsRequest
import com.avoqado.pos.core.data.sync.SyncIntentsResponse
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * La ronda en vuelo (spec 2026-09-27 §5): su red de seguridad se escribe RETENIDA; mientras lo está, NADA de lo que va
 * detrás sale (el FIFO es lo que impide cobrar una mesa antes de que llegue su ronda). Si el proceso muere con ella
 * retenida, el primer arranque la suelta y el servidor deduplica por su llave.
 *
 * Mismo andamio que `SyncOutboxCajonPrimeroTest`: cola con ESTADO real, porque el replay que dispara `start()` y el que
 * llama la prueba corren a la vez.
 */
class SyncOutboxRetenidoTest {

    private val dao = mockk<SyncIntentDao>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val cajon = mockk<CashDrawerRepository>(relaxed = true)

    private val cola = CopyOnWriteArrayList<SyncIntentEntity>()
    private val mandados = CopyOnWriteArrayList<String>()
    private val vacio = JsonObject(emptyMap())

    private fun intent(id: String, seq: Long, type: String, status: String = STATUS_PENDING) =
        SyncIntentEntity(id = id, venueId = VENUE, staffId = "staff-1", seq = seq, type = type, payloadJson = "{}", status = status)

    private fun outbox(inicial: List<SyncIntentEntity> = emptyList()): SyncOutbox {
        cola.clear(); cola.addAll(inicial); mandados.clear()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "device-fijo"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { dao.pendingFifo(VENUE, any()) } answers {
            cola.filter { it.status == STATUS_PENDING || it.status == STATUS_HELD }.sortedBy { it.seq }
        }
        coEvery { dao.insertWithNextSeq(any()) } answers {
            val seq = (cola.maxOfOrNull { it.seq } ?: 0L) + 1
            cola.add(firstArg<SyncIntentEntity>().copy(seq = seq))
            seq
        }
        coEvery { dao.soltar(any()) } answers {
            val id = firstArg<String>()
            val antes = cola.count { it.id == id && it.status == STATUS_HELD }
            cola.replaceAll { if (it.id == id && it.status == STATUS_HELD) it.copy(status = STATUS_PENDING) else it }
            antes
        }
        coEvery { dao.descartar(any()) } answers {
            val id = firstArg<String>()
            if (cola.removeIf { it.id == id && (it.status == STATUS_PENDING || it.status == STATUS_HELD) }) 1 else 0
        }
        coEvery { dao.soltarRetenidos() } answers {
            val n = cola.count { it.status == STATUS_HELD }
            cola.replaceAll { if (it.status == STATUS_HELD) it.copy(status = STATUS_PENDING) else it }
            n
        }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            cola.removeIf { it.id == firstArg<String>() }
            Unit
        }
        coEvery { apiService.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            mandados += request.intents.map { it.id }
            SyncIntentsResponse(data = request.intents.map { SyncAck(id = it.id, status = "ACKED") })
        }
        return SyncOutbox(dao, apiService, connectivityMonitor, secureStorage, cajon, context)
    }

    /** Espera en tiempo REAL (el scope del outbox corre en `Dispatchers.IO`, no en el reloj virtual de `runTest`). */
    private suspend fun esperarHasta(condicion: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condicion()) delay(10) }
    }

    @Test
    fun `P1 una ronda retenida no deja salir nada de lo que va detras`() = runTest {
        val outbox = outbox()
        outbox.start(VENUE)
        coVerify(timeout = 2_000) { dao.soltarRetenidos() }
        outbox.enqueue(VENUE, "OPEN_TABLE", vacio, id = "a")
        outbox.enqueue(VENUE, "ADD_ITEMS", vacio, id = "ronda", retenido = true)
        outbox.enqueue(VENUE, "CLEAR_TABLE", vacio, id = "b")

        outbox.replayNow(VENUE)

        assertTrue("lo de ANTES sí sale", "a" in mandados)
        assertFalse("la ronda retenida no se manda", "ronda" in mandados)
        assertFalse("lo de DETRÁS espera a la ronda", "b" in mandados)
        outbox.stop()
    }

    @Test
    fun `P1 al soltar la ronda sale ella y despues lo de detras, en orden`() = runTest {
        val outbox = outbox()
        outbox.start(VENUE)
        coVerify(timeout = 2_000) { dao.soltarRetenidos() }
        outbox.enqueue(VENUE, "OPEN_TABLE", vacio, id = "a")
        outbox.enqueue(VENUE, "ADD_ITEMS", vacio, id = "ronda", retenido = true)
        outbox.enqueue(VENUE, "CLEAR_TABLE", vacio, id = "b")

        outbox.soltar(VENUE, "ronda")
        outbox.replayNow(VENUE)
        esperarHasta { "b" in mandados }

        assertEquals(listOf("a", "ronda", "b"), mandados.distinct())
        outbox.stop()
    }

    @Test
    fun `con exito en linea la ronda se descarta y lo de detras sale`() = runTest {
        val outbox = outbox()
        outbox.start(VENUE)
        coVerify(timeout = 2_000) { dao.soltarRetenidos() }
        outbox.enqueue(VENUE, "ADD_ITEMS", vacio, id = "ronda", retenido = true)
        outbox.enqueue(VENUE, "CLEAR_TABLE", vacio, id = "b")

        outbox.descartar(VENUE, "ronda")
        outbox.replayNow(VENUE)
        esperarHasta { "b" in mandados }

        assertFalse("la red de seguridad sobraba: nunca se manda", "ronda" in mandados)
        outbox.stop()
    }

    @Test
    fun `P1 la ronda retenida de un proceso que murio vuelve a la cola al arrancar y se reproduce`() = runTest {
        val outbox = outbox(listOf(intent("ronda", 1, "ADD_ITEMS", STATUS_HELD)))

        outbox.start(VENUE)
        esperarHasta { "ronda" in mandados }

        coVerify(exactly = 1) { dao.soltarRetenidos() }
        outbox.stop()
    }

    @Test
    fun `soltar lo retenido es sólo al PRIMER arranque - un cambio de sucursal no suelta una ronda en vuelo`() = runTest {
        val outbox = outbox()
        outbox.start(VENUE)
        coVerify(timeout = 2_000, exactly = 1) { dao.soltarRetenidos() }
        outbox.enqueue(VENUE, "ADD_ITEMS", vacio, id = "ronda", retenido = true)

        outbox.stop()
        outbox.start(VENUE)
        outbox.replayNow(VENUE)

        coVerify(exactly = 1) { dao.soltarRetenidos() }
        assertEquals(STATUS_HELD, cola.single { it.id == "ronda" }.status)
        outbox.stop()
    }

    @Test
    fun `el corte es puro`() {
        assertEquals(1, SyncOutbox.hastaElPrimerRetenido(listOf(STATUS_PENDING, STATUS_HELD, STATUS_PENDING)))
        assertEquals(2, SyncOutbox.hastaElPrimerRetenido(listOf(STATUS_PENDING, STATUS_PENDING)))
        assertEquals(0, SyncOutbox.hastaElPrimerRetenido(listOf(STATUS_HELD)))
    }

    private companion object {
        const val VENUE = "venue-1"
    }
}
