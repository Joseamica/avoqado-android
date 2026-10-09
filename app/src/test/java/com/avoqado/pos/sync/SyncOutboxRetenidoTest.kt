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
    private val cachedPayloads = mockk<com.avoqado.pos.core.data.local.database.CachedPayloadDao>(relaxed = true)

    private val cola = CopyOnWriteArrayList<SyncIntentEntity>()
    private val mandados = CopyOnWriteArrayList<String>()
    private val vacio = JsonObject(emptyMap())
    private val startupReleases = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun startReady(subject: SyncOutbox) {
        val before = startupReleases.get()
        subject.start(VENUE)
        esperarHasta { startupReleases.get() > before }
    }

    private fun intent(id: String, seq: Long, type: String, status: String = STATUS_PENDING) =
        SyncIntentEntity(id = id, venueId = VENUE, staffId = "staff-1", seq = seq, type = type, payloadJson = "{}", status = status)

    private fun outbox(inicial: List<SyncIntentEntity> = emptyList(), filesDirectory: java.io.File? = null): SyncOutbox {
        cola.clear(); cola.addAll(inicial); mandados.clear()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "device-fijo"
        val context = mockk<Context>(relaxed = true)
        if (filesDirectory != null) every { context.filesDir } returns filesDirectory
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        every { secureStorage.venueId } returns VENUE
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { dao.pendingFifo(VENUE, any()) } answers {
            cola.filter { it.status == STATUS_PENDING || it.status == STATUS_HELD }.sortedBy { it.seq }
        }
        coEvery { dao.pendingFifoLane(VENUE, any(), any()) } answers {
            val kitchen = secondArg<Boolean>()
            cola.filter { (it.status == STATUS_PENDING || it.status == STATUS_HELD) &&
                (it.type == "KDS_ITEM_PROGRESS") == kitchen }.sortedBy { it.seq }.take(arg<Int>(2))
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
            startupReleases.incrementAndGet()
            n
        }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>(); val status = arg<String>(1)
            if (status == STATUS_PENDING) cola.replaceAll { if (it.id == id) it.copy(status = status, errorCode = arg(2), message = arg(3)) else it }
            else cola.removeIf { it.id == id }
            Unit
        }
        coEvery { apiService.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            mandados += request.intents.map { it.id }
            SyncIntentsResponse(data = request.intents.map { SyncAck(id = it.id, status = "ACKED") })
        }
        return SyncOutbox(dao, apiService, connectivityMonitor, secureStorage, cajon, context, cachedPayloads)
    }

    @Test fun `negotiated preparation passes held financial round without advancing cash`() = runTest {
        val subject = outbox()
        coEvery { cachedPayloads.get("preparation_capabilities:$VENUE") } returns
            com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation_capabilities:$VENUE", VENUE,
                """{"version":1,"enabled":true,"replayLaneVersion":1}""", 100)
        try {
            startReady(subject)
            cola += listOf(intent("round", 1, "ADD_ITEMS", STATUS_HELD), intent("preparation", 2, "KDS_ITEM_PROGRESS"), intent("cash", 3, "PAY_CASH"))
            subject.replayNow(VENUE)
            assertEquals(listOf("preparation"), mandados.distinct())
            assertEquals(listOf("round", "cash"), cola.map { it.id })
        } finally { subject.stop() }
    }

    @Test fun `financial retry does not block negotiated preparation and retries do not spin`() = runTest {
        val subject = outbox()
        coEvery { cachedPayloads.get("preparation_capabilities:$VENUE") } returns
            com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation_capabilities:$VENUE", VENUE,
                """{"version":1,"enabled":true,"replayLaneVersion":1}""", 100)
        try {
            startReady(subject)
            coEvery { apiService.syncIntents(VENUE, any()) } answers {
                val request = secondArg<SyncIntentsRequest>()
                mandados += request.intents.map { it.id }
                SyncIntentsResponse(data = request.intents.map { SyncAck(id = it.id, status = if (it.type == "KDS_ITEM_PROGRESS") "ACKED" else "RETRY") })
            }
            cola += listOf(intent("round", 1, "ADD_ITEMS"), intent("preparation", 2, "KDS_ITEM_PROGRESS"), intent("cash", 3, "PAY_CASH"))
            subject.replayNow(VENUE)
            assertTrue("kitchen must be acknowledged despite finance RETRY", cola.none { it.id == "preparation" })
            assertEquals(listOf("round", "cash"), cola.map { it.id })
            assertTrue("one attempt per lane in a replay", mandados.count { it == "round" } <= 2)
        } finally { subject.stop() }
    }

    @Test fun `old disabled unsupported or foreign capabilities keep the global barrier`() = runTest {
        for ((scope, body) in listOf(
            VENUE to """{"version":1,"enabled":true}""",
            VENUE to """{"version":1,"enabled":false,"replayLaneVersion":1}""",
            VENUE to """{"version":1,"enabled":true,"replayLaneVersion":2}""",
            "another-venue" to """{"version":1,"enabled":true,"replayLaneVersion":1}""",
        )) {
            val subject = outbox()
            coEvery { cachedPayloads.get("preparation_capabilities:$VENUE") } returns
                com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation_capabilities:$VENUE", scope, body, 100)
            try {
                startReady(subject)
                cola += listOf(intent("round", 1, "ADD_ITEMS", STATUS_HELD), intent("preparation", 2, "KDS_ITEM_PROGRESS"))
                subject.replayNow(VENUE)
                assertTrue("Unnegotiated server must retain the barrier", mandados.isEmpty())
            } finally { subject.stop() }
        }
    }

    @Test fun `drawer barrier keeps cash and subsequent finance but allows negotiated kitchen`() = runTest {
        val subject = outbox()
        coEvery { cachedPayloads.get("preparation_capabilities:$VENUE") } returns
            com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation_capabilities:$VENUE", VENUE,
                """{"version":1,"enabled":true,"replayLaneVersion":1}""", 100)
        coEvery { cajon.sincronizarCajonPrimero() } returns false
        try {
            startReady(subject)
            cola += listOf(intent("cash", 1, "PAY_CASH"), intent("preparation", 2, "KDS_ITEM_PROGRESS"), intent("close", 3, "CLEAR_TABLE"))
            subject.replayNow(VENUE)
            assertEquals(listOf("preparation"), mandados.distinct())
            assertEquals(listOf("cash", "close"), cola.map { it.id })
        } finally { subject.stop() }
    }

    @Test fun `empty sync response preserves pending intent without a hot loop`() = runTest {
        val subject = outbox()
        coEvery { apiService.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = emptyList())
        try {
            startReady(subject)
            cola += intent("round", 1, "ADD_ITEMS")
            withContext(Dispatchers.Default) { withTimeout(5_000) { subject.replayNow(VENUE) } }
            assertEquals(listOf("round"), cola.map { it.id })
        } finally { subject.stop() }
    }

    @Test fun `open table ack preserves drafts and prepared round before becoming terminal`() = runTest {
        val parent = java.nio.file.Files.createTempDirectory("provisional-draft").toFile()
        var subject: SyncOutbox? = null
        try {
            val store = com.avoqado.pos.tables.data.TableRoundDraftStore(java.io.File(parent, "table-round-drafts"))
            val line = com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(
                com.avoqado.pos.pos.data.model.CartItem(id = "component", type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("coffee"),
                    name = "Café", unitPrice = 4950, promotionInstanceId = "combo-instance", promotionId = "combo", promotionGroupId = "g1", promotionOptionId = "opt1",
                    serviceCourse = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("desserts", "Con el postre", "STANDARD")), "Con el postre")
            val roundLine = line.copy(item = line.item.copy(id = "prepared", promotionInstanceId = null))
            val before = store.save(VENUE, "local-order", listOf(line), mapOf("stable-round" to listOf(roundLine)))
            val open = intent("open", 1, "OPEN_TABLE").copy(payloadJson = "{\"localOrderId\":\"local-order\"}")
            subject = outbox(listOf(open), parent)
            val pendingAtAck = CopyOnWriteArrayList<Int>()
            coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
                pendingAtAck += store.load(VENUE, "real-order").pending.size
                cola.removeIf { it.id == firstArg<String>() }; Unit
            }
            coEvery { apiService.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(SyncAck(id = "open", status = "ACKED",
                result = kotlinx.serialization.json.buildJsonObject {
                    put("localOrderId", kotlinx.serialization.json.JsonPrimitive("local-order")); put("orderId", kotlinx.serialization.json.JsonPrimitive("real-order"))
                })))
            subject.start(VENUE)
            subject.replayNow(VENUE)
            assertEquals(listOf(1), pendingAtAck.toList())
            val recovered = com.avoqado.pos.tables.data.TableRoundDraftStore(java.io.File(parent, "table-round-drafts")).load(VENUE, "real-order")
            assertEquals(before.pending, recovered.pending)
            assertEquals(before.rounds, recovered.rounds)
            assertEquals(before.createdAtLocal, recovered.createdAtLocal)
            assertEquals(recovered, store.load(VENUE, "local-order"))
            assertTrue(store.load("another-venue", "real-order").pending.isEmpty())
        } finally { subject?.stop(); parent.deleteRecursively() }
    }

    @Test fun `failed draft promotion keeps open intent pending and retries without losing products`() = runTest {
        val parent = java.nio.file.Files.createTempDirectory("provisional-draft-failure").toFile()
        var subject: SyncOutbox? = null
        try {
            val root = java.io.File(parent, "table-round-drafts")
            val store = com.avoqado.pos.tables.data.TableRoundDraftStore(root)
            val line = com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(
                com.avoqado.pos.pos.data.model.CartItem(id = "component", type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("coffee"), name = "Café", unitPrice = 9900), null)
            store.save(VENUE, "local-order", listOf(line), emptyMap())
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$VENUE\u0000real-order".toByteArray()).joinToString("") { "%02x".format(it) }
            val blocked = java.io.File(root, "$digest.json").apply { mkdirs(); resolve("owned-blocker").writeText("FULLTEST") }
            subject = outbox(listOf(intent("open", 1, "OPEN_TABLE").copy(payloadJson = "{\"localOrderId\":\"local-order\"}")), parent)
            coEvery { apiService.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(SyncAck(id = "open", status = "ACKED",
                result = kotlinx.serialization.json.buildJsonObject {
                    put("localOrderId", kotlinx.serialization.json.JsonPrimitive("local-order")); put("orderId", kotlinx.serialization.json.JsonPrimitive("real-order"))
                })))
            subject.start(VENUE); subject.replayNow(VENUE)
            coVerify(exactly = 0) { dao.resolve("open", "ACKED", any(), any(), any()) }
            assertEquals(STATUS_PENDING, cola.single().status)
            assertEquals("LOCAL_DRAFT_RECOVERY_FAILED", cola.single().errorCode)
            assertEquals(listOf(line), store.load(VENUE, "local-order").pending)
            blocked.deleteRecursively()
            subject.replayNow(VENUE)
            assertEquals(listOf(line), store.load(VENUE, "real-order").pending)
            coVerify(exactly = 1) { dao.resolve("open", "ACKED", any(), any(), any()) }
        } finally { subject?.stop(); parent.deleteRecursively() }
    }

    @Test fun `recovered round preserves original local timestamp for promotion pricing`() = runTest {
        val outbox = outbox()
        outbox.enqueue(VENUE, "ADD_ITEMS", vacio, id = "recovered", retenido = true, createdAtLocal = 1_234_567_890_000L)
        assertEquals(1_234_567_890_000L, cola.single().createdAt)
        outbox.stop()
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
        // El `launch` de start() corre en Dispatchers.IO, fuera del reloj virtual de runTest: hay que esperar a que
        // termine de verdad (Task 7 review, 2026-09-28) antes de afirmar sobre `soltarRetenidos`, o la aserción
        // puede correr ANTES de que ese launch haya llegado a decidir si soltaba o no.
        coVerify(timeout = 2_000, exactly = 2) { dao.deleteOldAcked(any()) }
        outbox.replayNow(VENUE)

        coVerify(exactly = 1) { dao.soltarRetenidos() }
        assertEquals(STATUS_HELD, cola.single { it.id == "ronda" }.status)
        outbox.stop()
    }

    @Test
    fun `P1 blockingWorkCount cuenta las rondas retenidas - logout y cambio de sucursal no las ignoran`() = runTest {
        val outbox = outbox(listOf(intent("ronda", 1, "ADD_ITEMS", STATUS_HELD)))
        coEvery { dao.pendingCount(VENUE) } returns 0
        coEvery { dao.rejectedCount(VENUE) } returns 0
        coEvery { dao.heldCount(VENUE) } answers { cola.count { it.status == STATUS_HELD } }

        assertEquals(1, outbox.blockingWorkCount(VENUE))
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
