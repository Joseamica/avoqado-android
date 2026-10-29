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
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import java.io.File
import java.nio.file.Files
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
    init { com.avoqado.pos.core.data.sync.vincularCallFixtureQ16(apiService) }
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
    private val recursos = mutableListOf<Pair<SyncOutbox, File>>()

    @After fun limpiarRecursosPropios() {
        try { recursos.forEach { it.first.stop() } }
        finally { recursos.forEach { it.second.deleteRecursively() } }
    }

    private fun intent(id: String, seq: Long, type: String, localOrderId: String = id): SyncIntentEntity {
        val payload = if (type == "PAY_CASH") """{"orderId":"order-cajon","amountCents":13300,"tipCents":0,"method":"CASH"}"""
            else if (type == "OPEN_TABLE" || type == "ADD_ITEMS") """{"localOrderId":"$localOrderId"}""" else "{}"
        return SyncIntentEntity(
            id = id, venueId = VENUE, staffId = "staff-1", seq = seq, type = type, payloadJson = payload,
            q15CaptureJson = if (type == "PAY_CASH") requireNotNull(ConciliacionDelCobro.capturarIntent(VENUE, id, payload)) else null,
            capturedOrderRef = if (type == "PAY_CASH") "order-cajon" else null,
        )
    }

    private fun outbox(cola: List<SyncIntentEntity>): SyncOutbox {
        pendientes.clear()
        pendientes.addAll(cola)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "device-fijo"
        val context = mockk<Context>(relaxed = true)
        val directory = Files.createTempDirectory("sync-cajon-owned").toFile()
        every { context.filesDir } returns directory
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.pendingFifo(VENUE, any()) } answers { pendientes.toList() }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>(); val status = arg<String>(1)
            if (status == SyncIntentEntity.STATUS_PENDING) pendientes.replaceAll {
                if (it.id == id) it.copy(status = status, errorCode = arg(2), message = arg(3), resultJson = arg(4)) else it
            } else pendientes.removeIf { it.id == id }
            Unit
        }
        coEvery { dao.resolverCobroQ15(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            assertEquals(VENUE, arg<String>(1))
            assertEquals("ACKED", arg<String>(3))
            assertEquals(null, arg<String?>(7))
            assertTrue(arg<String?>(8) != null)
            pendientes.removeIf { it.id == id }
            Unit // Stateful replay fixture; Room transaction has its own instrumented controls.
        }
        coEvery { apiService.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            pedidos += request
            SyncIntentsResponse(data = request.intents.map { wire ->
                val result = if (wire.type == "PAY_CASH") JsonObject(mapOf(
                    "paymentId" to JsonPrimitive("server-" + wire.id), "orderId" to JsonPrimitive("order-cajon"),
                    "amount" to JsonPrimitive(13300), "tipAmount" to JsonPrimitive(0), "method" to JsonPrimitive("CASH"),
                )) else if (wire.type == "OPEN_TABLE") JsonObject(mapOf(
                    "localOrderId" to requireNotNull(wire.payload["localOrderId"]),
                    "orderId" to JsonPrimitive("real-order"),
                )) else null
                SyncAck(id = wire.id, status = "ACKED", result = result)
            })
        }
        return SyncOutbox(dao, apiService, connectivityMonitor, secureStorage, cajon, context, mockk(relaxed = true))
            .also { recursos += it to directory }
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
                intent("i2", 2, "ADD_ITEMS", localOrderId = "i1"),
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


    @Test
    fun legacyWithoutCaptureSendsOnlyPrefixAndPreservesPayAndClear() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        val legacy = intent("legacy-pay", 2, "PAY_CASH").copy(q15CaptureJson = null)
        val outbox = outbox(listOf(intent("open", 1, "OPEN_TABLE"), legacy, intent("clear", 3, "CLEAR_TABLE")))
        coEvery { dao.retenerIntentQ15(VENUE, legacy.id, ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) } answers {
            val index = pendientes.indexOfFirst { it.id == legacy.id }
            check(index >= 0)
            pendientes[index] = pendientes[index].copy(status = "HELD", q15Reason = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA)
            1
        }
        try {
            outbox.start(VENUE)
            outbox.replayNow(VENUE)
            assertEquals(listOf("OPEN_TABLE"), tiposMandados())
            assertEquals(listOf(legacy.id, "clear"), pendientes.map { it.id })
            val retained = pendientes.single { it.id == legacy.id }
            assertEquals("HELD", retained.status)
            assertEquals(ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, retained.q15Reason)
            assertEquals(legacy.payloadJson, retained.payloadJson)
            assertEquals(null, retained.q15CaptureJson)
            coVerify(atLeast = 1) { dao.retenerIntentQ15(VENUE, legacy.id, ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) }
            coVerify(exactly = 0) { dao.resolverCobroQ15(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally { outbox.stop() }
    }

    // MARK: - La caja de la venta (efectivo sin red en el turno correcto, 21-oct-2026)

    /** Un intent con la caja en su payload, como lo escriben `PaymentFlowViewModel` (PAY_CASH) y `TablesViewModel` (OPEN_TABLE). */
    private fun intentConCaja(id: String, seq: Long, type: String, caja: String): SyncIntentEntity {
        val base = intent(id, seq, type)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(base.payloadJson) as JsonObject
        val conCaja = JsonObject(payload + ("cashDrawerSessionId" to JsonPrimitive(caja)))
        val texto = conCaja.toString()
        return base.copy(payloadJson = texto,
            q15CaptureJson = if (type == "PAY_CASH") requireNotNull(ConciliacionDelCobro.capturarIntent(VENUE, id, texto)) else null)
    }

    private fun cajaEnElCable(type: String): String? =
        (pedidos.flatMap { it.intents }.single { it.type == type }.payload["cashDrawerSessionId"] as? JsonPrimitive)?.content

    @Test
    fun `P1 el PAY_CASH y el OPEN_TABLE con caja viajan con la caja traducida al id del servidor`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        every { cajon.idDeCajaParaElServidor("caja-A-local", any()) } returns "srv-A"
        val outbox = outbox(listOf(intentConCaja("i1", 1, "OPEN_TABLE", "caja-A-local"), intentConCaja("i2", 2, "PAY_CASH", "caja-A-local")))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals("srv-A", cajaEnElCable("OPEN_TABLE"))
        assertEquals("srv-A", cajaEnElCable("PAY_CASH"))
        outbox.stop()
    }

    @Test
    fun `P1 una caja local sin alias se quita del cable y lo demas del payload no cambia`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        every { cajon.idDeCajaParaElServidor("caja-sin-alias", any()) } returns null
        val fila = intentConCaja("i1", 1, "PAY_CASH", "caja-sin-alias")
        val outbox = outbox(listOf(fila))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        val enviado = pedidos.flatMap { it.intents }.single()
        val guardado = kotlinx.serialization.json.Json.parseToJsonElement(fila.payloadJson) as JsonObject
        assertEquals(JsonObject(guardado - "cashDrawerSessionId"), enviado.payload)
        outbox.stop()
    }

    @Test
    fun `un intent sin caja viaja con su payload tal cual`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        val fila = intent("i1", 1, "PAY_CASH")
        val outbox = outbox(listOf(fila))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(fila.payloadJson), pedidos.flatMap { it.intents }.single().payload)
        coVerify(exactly = 0) { cajon.idDeCajaParaElServidor(any(), any()) }
        outbox.stop()
    }

    /** P1 (Codex r4 #1) — el OPEN_TABLE de una mesa abierta en la caja B espera a la apertura de B; lo de detrás también. */
    @Test
    fun `P1 el OPEN_TABLE de una caja cuya apertura sigue pendiente espera y lo de detras tambien`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        val outbox = outbox(listOf(intent("i1", 1, "CLEAR_TABLE"), intentConCaja("i2", 2, "OPEN_TABLE", "caja-B-local"),
            intentConCaja("i3", 3, "PAY_CASH", "caja-B-local")))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals(listOf("CLEAR_TABLE"), tiposMandados())
        assertEquals(listOf("i2", "i3"), pendientes.map { it.id })
        outbox.stop()
    }

    /** P1 — un PAY_CASH de una caja ya confirmada sale aunque haya otra apertura pendiente (barrera por caja). */
    @Test
    fun `P1 un PAY_CASH de una caja confirmada sale aunque otra apertura siga pendiente`() = runTest {
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        every { cajon.cajasQueEsperanSuApertura() } returns setOf("caja-B-local")
        every { cajon.idDeCajaParaElServidor("srv-A", any()) } returns "srv-A"
        val outbox = outbox(listOf(intentConCaja("i1", 1, "PAY_CASH", "srv-A")))
        outbox.start(VENUE)

        outbox.replayNow(VENUE)

        assertEquals(listOf("PAY_CASH"), tiposMandados())
        outbox.stop()
    }

    @Test
    fun `P2 el corte por caja, suelto`() {
        val esperando = setOf("B")
        assertEquals(3, SyncOutbox.cuantosIntentsSePuedenMandarPorCaja(
            listOf("PAY_CASH" to "A", "OPEN_TABLE" to "A", "CLEAR_TABLE" to null), sinCajaPuedenSalir = false, cajasEsperando = esperando))
        assertEquals(1, SyncOutbox.cuantosIntentsSePuedenMandarPorCaja(
            listOf("ADD_ITEMS" to null, "OPEN_TABLE" to "B", "PAY_CASH" to "A"), sinCajaPuedenSalir = true, cajasEsperando = esperando))
        assertEquals("un PAY_CASH sin caja conserva la regla global", 1, SyncOutbox.cuantosIntentsSePuedenMandarPorCaja(
            listOf("OPEN_TABLE" to null, "PAY_CASH" to null), sinCajaPuedenSalir = false, cajasEsperando = emptySet()))
        assertEquals("un OPEN_TABLE sin caja nunca espera", 2, SyncOutbox.cuantosIntentsSePuedenMandarPorCaja(
            listOf("OPEN_TABLE" to null, "ADD_ITEMS" to null), sinCajaPuedenSalir = false, cajasEsperando = esperando))
    }

    private companion object {
        const val VENUE = "venue-1"
    }
}
