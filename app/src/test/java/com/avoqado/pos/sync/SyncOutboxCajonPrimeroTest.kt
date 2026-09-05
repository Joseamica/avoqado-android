package com.avoqado.pos.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncIntentsRequest
import com.avoqado.pos.core.data.sync.SyncIntentsResponse
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P2-3 de la revisión independiente (5-sep-2026) — **el efectivo de MESAS salía por otra puerta,
 * sin barrera.**
 *
 * En este workspace un cobro en efectivo llega al servidor por DOS colas: la de
 * `PaymentSyncService` (venta rápida), que la ronda anterior sí protegió, y los intents `PAY_CASH`
 * de este outbox (mesas), que tiene su PROPIO observador de reconexión y su propio temporizador.
 * Al volver la red los dos corren a la vez, así que un `PAY_CASH` podía aterrizar antes que el
 * `POST /open` y nacer con `shiftId = null` — el defecto F1 completo, con el mismo dinero.
 *
 * 🔴 El corte NO reordena la cola: el FIFO por aparato es lo que hace que una mesa se abra antes de
 * que le agreguen artículos. Se manda todo lo anterior al primer `PAY_CASH` y ahí se detiene.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOutboxCajonPrimeroTest {

    private val dao = mockk<SyncIntentDao>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val cajon = mockk<CashDrawerRepository>(relaxed = true)

    /**
     * La cola PENDIENTE, con estado real: `pendingFifo` la lee y cada ack la consume. Sin estado,
     * el replay que `start()` dispara por su cuenta y el que llama la prueba leerían listas fijas y
     * el resultado dependería de quién corriera primero — flaky por diseño.
     */
    private val pendientes = CopyOnWriteArrayList<SyncIntentEntity>()
    private val pedidos = CopyOnWriteArrayList<SyncIntentsRequest>()

    private fun intent(id: String, seq: Long, type: String) = SyncIntentEntity(
        id = id,
        venueId = VENUE,
        staffId = "staff-1",
        seq = seq,
        type = type,
        payloadJson = "{}",
    )

    private fun outbox(cola: List<SyncIntentEntity>): SyncOutbox {
        pendientes.clear()
        pendientes.addAll(cola)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "device-fijo"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.pendingFifo(VENUE, any()) } answers { pendientes.toList() }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            pendientes.removeIf { it.id == id }
            Unit
        }
        coEvery { apiService.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            pedidos += request
            SyncIntentsResponse(data = request.intents.map { SyncAck(id = it.id, status = "ACKED") })
        }
        return SyncOutbox(dao, apiService, connectivityMonitor, secureStorage, cajon, context)
    }

    /** Los tipos que el outbox llegó a mandar, en orden, a lo largo de toda la corrida. */
    private fun tiposMandados(): List<String> = pedidos.flatMap { req -> req.intents.map { it.type } }

    // MARK: - La barrera

    /**
     * 🔴 P1 — con la apertura de la caja pendiente, lo que NO es dinero se manda y el `PAY_CASH`
     * (y todo lo posterior) espera.
     *
     * Un `OPEN_TABLE` o un `ADD_ITEMS` que aterricen sin caja abierta no pierden nada; un cobro en
     * efectivo, sí, y para siempre.
     */
    @Test
    fun `P1 con la caja pendiente el outbox se detiene en el primer PAY_CASH`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        val outbox = outbox(
            listOf(
                intent("i1", 1, "OPEN_TABLE"),
                intent("i2", 2, "ADD_ITEMS"),
                intent("i3", 3, "PAY_CASH"),
                intent("i4", 4, "CLEAR_TABLE"),
            ),
        )
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals(
            "Lo anterior al cobro sí sale; el cobro y lo posterior esperan a que la caja llegue",
            listOf("OPEN_TABLE", "ADD_ITEMS"),
            tiposMandados(),
        )
        assertEquals("El cobro sigue esperando en la cola, no se pierde", 2, pendientes.size)
        outbox.stop()
    }

    /** 🔴 P1 — con la caja pendiente y un `PAY_CASH` DE PRIMERO, no sale ni una petición. */
    @Test
    fun `P1 con la caja pendiente un PAY_CASH al frente no manda nada`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        val outbox = outbox(listOf(intent("i1", 1, "PAY_CASH"), intent("i2", 2, "CLEAR_TABLE")))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertTrue("Ni el cobro ni lo que va detrás pueden adelantarse a la caja", pedidos.isEmpty())
        outbox.stop()
    }

    /**
     * 🔴 P1 — sin apertura pendiente NADA cambia: el outbox drena como siempre, cobro incluido.
     *
     * Es la mitad que impide que el arreglo se coma el caso normal, que es el 99% de las corridas.
     */
    @Test
    fun `P1 sin la caja pendiente el outbox drena igual que antes`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        val outbox = outbox(
            listOf(
                intent("i1", 1, "OPEN_TABLE"),
                intent("i2", 2, "PAY_CASH"),
                intent("i3", 3, "CLEAR_TABLE"),
            ),
        )
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals(listOf("OPEN_TABLE", "PAY_CASH", "CLEAR_TABLE"), tiposMandados())
        assertTrue("La cola queda vacía", pendientes.isEmpty())
        outbox.stop()
    }

    // MARK: - La regla, suelta

    /** La decisión del corte, sin red ni base: es lo que hace legible el resto. */
    @Test
    fun `P2 el corte deja pasar todo lo anterior al primer PAY_CASH`() {
        val cola = listOf("OPEN_TABLE", "ADD_ITEMS", "PAY_CASH", "PAY_CASH", "CLEAR_TABLE")

        assertEquals(
            "Con la barrera abierta va el lote entero",
            cola.size,
            SyncOutbox.cuantosIntentsSePuedenMandar(cola, cobrosPuedenSalir = true),
        )
        assertEquals(
            "Con la barrera cerrada se corta ANTES del primer cobro",
            2,
            SyncOutbox.cuantosIntentsSePuedenMandar(cola, cobrosPuedenSalir = false),
        )
        assertEquals(
            "Un lote sin cobros en efectivo no se recorta nunca",
            3,
            SyncOutbox.cuantosIntentsSePuedenMandar(
                listOf("OPEN_TABLE", "ADD_ITEMS", "CLEAR_TABLE"),
                cobrosPuedenSalir = false,
            ),
        )
        assertEquals(
            "Un cobro al frente no deja pasar nada",
            0,
            SyncOutbox.cuantosIntentsSePuedenMandar(listOf("PAY_CASH", "CLEAR_TABLE"), cobrosPuedenSalir = false),
        )
    }

    private companion object {
        const val VENUE = "venue-1"
    }
}
