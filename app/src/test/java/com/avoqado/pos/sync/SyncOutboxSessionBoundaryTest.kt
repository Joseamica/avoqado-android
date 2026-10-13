package com.avoqado.pos.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.*
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.tables.data.*
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Outbox, coordinator, TableSession y DraftStore reales; sólo fronteras de IO controladas. */
class SyncOutboxSessionBoundaryTest {
    private class Fixture {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        val api = mockk<ApiService>(relaxed = true)
        init { com.avoqado.pos.core.data.sync.vincularCallFixtureQ16(api) }
        val storage = mockk<SecureStorage>(relaxed = true)
        val repository = mockk<TableServiceRepository>(relaxed = true)
        val session = TableSession()
        val rows = CopyOnWriteArrayList<SyncIntentEntity>()
        val history = CopyOnWriteArrayList<SyncIntentEntity>()
        val resolved = CopyOnWriteArrayList<Pair<String, String>>()
        val releases = AtomicInteger()
        val directory = Files.createTempDirectory("mesas-ack-context-owned").toFile()
        val drafts = TableRoundDraftStore(File(directory, "table-round-drafts"))
        val connected = MutableStateFlow(true)
        val reachable = MutableStateFlow(true)
        val outbox: SyncOutbox
        val coordinator: TableSyncCoordinator

        init {
            val prefs = mockk<SharedPreferences>(relaxed = true)
            every { prefs.getString(any(), any()) } returns "owned-device"
            val context = mockk<Context>(relaxed = true)
            every { context.filesDir } returns directory
            every { context.getSharedPreferences(any(), any()) } returns prefs
            val network = mockk<ConnectivityMonitor>(relaxed = true)
            every { network.isConnected } returns connected
            every { network.isServerReachable } returns reachable
            every { storage.venueId } returns VENUE
            every { storage.userId } returns USER
            val drawer = mockk<CashDrawerRepository>(relaxed = true)
            coEvery { drawer.sincronizarCajonPrimero() } returns true
            val cached = mockk<CachedPayloadDao>(relaxed = true)
            coEvery { dao.pendingFifo(VENUE, any()) } answers {
                rows.filter { it.status in setOf(SyncIntentEntity.STATUS_PENDING, SyncIntentEntity.STATUS_HELD) }.sortedBy { it.seq }.take(secondArg())
            }
            coEvery { dao.soltarRetenidos() } answers { releases.incrementAndGet(); 0 }
            coEvery { dao.preparationStatuses(VENUE, any()) } answers {
                val ids = secondArg<List<String>>(); history.filter { it.id in ids }
            }
            coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
                val id = firstArg<String>(); val status = arg<String>(1)
                resolved += id to status
                rows.replaceAll { row -> if (row.id == id) row.copy(status = status, errorCode = arg(2), message = arg(3), resultJson = arg(4)) else row }
                Unit
            }
            outbox = SyncOutbox(dao, api, network, storage, drawer, context, cached, session)
            coordinator = TableSyncCoordinator(outbox, session, repository, storage, dao)
            session.sincronizarContexto(USER, VENUE)
            session.start(active(), VENUE)
        }

        fun add(type: String = SyncIntentTypes.ADD_ITEMS, payload: String = """{"orderId":"order"}""") {
            val row = SyncIntentEntity("owned-intent", VENUE, USER, seq = 1, type = type, payloadJson = payload)
            rows += row; history += row
        }

        suspend fun startReady() {
            outbox.start(VENUE)
            waitUntil { releases.get() > 0 }
        }

        fun logoutAndLoginSameIdentity(): TableSession.Active {
            outbox.stop()
            session.clear()
            session.sincronizarContexto(USER, VENUE)
            val fresh = active().copy(orderNumber = "NEW", version = 77, totalCents = 7000, valorRondasSinRedCents = 7000)
            session.start(fresh, VENUE)
            outbox.start(VENUE)
            return fresh
        }

        fun close() { connected.value = false; reachable.value = false; outbox.stop(); directory.deleteRecursively() }
    }

    @Test fun `HTTP de sesion anterior se resuelve durable pero no altera nueva cuenta identica`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<SyncIntentsResponse>()
        coEvery { f.api.syncIntents(VENUE, any()) } coAnswers { entered.complete(Unit); reply.await() }
        f.add()
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        try {
            val oldEpoch = f.session.contextoActual()
            f.startReady(); awaitReal(entered)
            val fresh = f.logoutAndLoginSameIdentity()
            assertNotEquals(oldEpoch, f.session.contextoActual())
            reply.complete(SyncIntentsResponse(data = listOf(ack(version = 9))))
            val event = awaitReal(emitted)
            f.coordinator.handle(event)
            assertEquals(fresh, f.session.current())
            assertEquals(oldEpoch, event.contextoDeSesion ?: -1L)
            assertEquals(listOf("owned-intent" to SyncIntentEntity.STATUS_ACKED), f.resolved.toList())
            assertEquals(SyncIntentEntity.STATUS_ACKED, f.rows.single().status)
            coVerify(exactly = 0) { f.repository.refresh(any()) }
            coVerify(exactly = 0) { f.dao.preparationStatuses(any(), any()) }
        } finally { reply.complete(SyncIntentsResponse()); emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `ACK emitido antes del logout conserva contexto aunque consumidor lo retenga`() = runTest {
        val f = Fixture()
        coEvery { f.api.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(ack(version = 9)))
        f.add()
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        try {
            val oldEpoch = f.session.contextoActual()
            f.startReady(); val retained = awaitReal(emitted)
            val fresh = f.logoutAndLoginSameIdentity()
            f.coordinator.handle(retained)
            assertEquals(fresh, f.session.current())
            assertEquals(oldEpoch, retained.contextoDeSesion ?: -1L)
            coVerify(exactly = 0) { f.repository.refresh(any()) }
            coVerify(exactly = 0) { f.dao.preparationStatuses(any(), any()) }
        } finally { emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `ACK vigente que espera DAO no cruza cambio de contexto durante la lectura`() = runTest {
        val f = Fixture()
        coEvery { f.api.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(ack(version = 9)))
        f.add()
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        val reading = CompletableDeferred<Unit>()
        val query = CompletableDeferred<List<SyncIntentEntity>>()
        var handle: Deferred<Unit>? = null
        try {
            f.startReady(); val event = awaitReal(emitted)
            assertEquals(f.session.contextoActual(), event.contextoDeSesion)
            coEvery { f.dao.preparationStatuses(VENUE, any()) } coAnswers { reading.complete(Unit); query.await() }
            val handling = async(Dispatchers.Default) { f.coordinator.handle(event) }
            handle = handling
            awaitReal(reading)
            val fresh = f.logoutAndLoginSameIdentity()
            query.complete(f.history.toList()); awaitReal(handling)
            assertEquals(fresh, f.session.current())
            coVerify(exactly = 0) { f.repository.refresh(any()) }
        } finally { query.complete(emptyList()); handle?.cancelAndJoin(); emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `ACK actual promueve sesion estacionada y conserva resolve durable`() = runTest {
        val f = Fixture()
        val initial = active().copy(isProvisional = true, valorRondasSinRedCents = 11000)
        f.session.start(initial, VENUE); assertTrue(f.session.estacionar(VENUE, initial))
        f.add(SyncIntentTypes.OPEN_TABLE, """{"localOrderId":"order"}""")
        coEvery { f.api.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(SyncAck("owned-intent", "ACKED", result = buildJsonObject {
            put("localOrderId", "order"); put("orderId", "real-order"); put("orderNumber", "REAL"); put("version", 9)
        })))
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        try {
            f.startReady(); val event = awaitReal(emitted)
            assertEquals(f.session.contextoActual(), event.contextoDeSesion)
            f.coordinator.handle(event)
            assertNull(f.session.current())
            val restored = f.session.abrirDesdePiso(VENUE, initial.copy(orderId = "real-order", isProvisional = false))!!
            assertEquals("real-order", restored.orderId); assertEquals("local-origin", restored.localOrderId)
            assertEquals(0, restored.valorRondasSinRedCents); assertTrue(restored.requiereLecturaServidor)
            assertEquals(listOf("owned-intent" to SyncIntentEntity.STATUS_ACKED), f.resolved.toList())
            coVerify(exactly = 1) { f.repository.refresh(VENUE) }
        } finally { emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `rama RETRY lleva contexto local sin resolver ni retirar intent pendiente`() = runTest {
        val f = Fixture()
        coEvery { f.api.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(ack(status = "RETRY")))
        f.add()
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        try {
            f.startReady(); val event = awaitReal(emitted)
            assertEquals(f.session.contextoActual(), event.contextoDeSesion)
            assertTrue(event.isRetry); assertTrue(f.resolved.isEmpty())
            assertEquals(SyncIntentEntity.STATUS_PENDING, f.rows.single().status)
        } finally { emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `fallo real de promover draft emite RETRY etiquetado y conserva productos`() = runTest {
        val f = Fixture()
        val line = PendingLine(CartItem("product", CartItemType.ProductItem("p"), "Artículo", unitPrice = 11000), null)
        f.drafts.save(VENUE, "order", listOf(line), emptyMap())
        val digest = MessageDigest.getInstance("SHA-256").digest("$VENUE\u0000real-order".toByteArray()).joinToString("") { "%02x".format(it) }
        File(File(f.directory, "table-round-drafts"), "$digest.json").apply { mkdirs(); resolve("owned-blocker").writeText("OWNED") }
        f.add(SyncIntentTypes.OPEN_TABLE, """{"localOrderId":"order"}""")
        coEvery { f.api.syncIntents(VENUE, any()) } returns SyncIntentsResponse(data = listOf(SyncAck("owned-intent", "ACKED", result = buildJsonObject {
            put("localOrderId", "order"); put("orderId", "real-order")
        })))
        val emitted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.outbox.acks.first() }
        try {
            f.startReady(); val event = awaitReal(emitted)
            assertEquals(f.session.contextoActual(), event.contextoDeSesion)
            assertTrue(event.isRetry); assertEquals(SyncOutbox.LOCAL_DRAFT_RECOVERY_FAILED, event.errorCode)
            assertEquals(SyncIntentEntity.STATUS_PENDING, f.rows.single().status)
            assertEquals(listOf(line), f.drafts.load(VENUE, "order").pending)
            coVerify(exactly = 0) { f.dao.resolve("owned-intent", SyncIntentEntity.STATUS_ACKED, any(), any(), any()) }
        } finally { emitted.cancelAndJoin(); f.close() }
    }

    @Test fun `marca local nunca viaja en JSON ni puede ser elegida por servidor`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val tagged = ack().copy(contextoDeSesion = 99L)
        val wire = json.encodeToString(SyncAck.serializer(), tagged)
        assertFalse(wire.contains("contextoDeSesion"))
        val decoded = json.decodeFromString(SyncAck.serializer(), """{"id":"owned-intent","status":"ACKED","contextoDeSesion":99}""")
        assertNull(decoded.contextoDeSesion)
        assertNull(SyncAck("legacy", "ACKED").contextoDeSesion)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `Q15 cambio de actor en mismo venue rearma contador sin repetir replay`() = runTest {
        val f = Fixture()
        val original = MutableStateFlow(1)
        val nuevo = MutableStateFlow(1)
        var reads = 0
        every { f.dao.observarRevisionesQ15(VENUE, false, "") } answers {
            if (reads++ == 0) original else nuevo
        }
        f.connected.value = false; f.reachable.value = false
        f.outbox.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        try {
            f.outbox.start(VENUE); runCurrent()
            assertEquals(1, f.outbox.q15ReviewCount.value)
            every { f.storage.userId } returns "owned-manager"
            every { f.storage.userRole } returns "MANAGER"
            f.coordinator.onSessionChanged()
            original.value = 9; runCurrent()
            assertEquals(1, f.outbox.q15ReviewCount.value) // lectura vieja no publica
            f.outbox.start(VENUE); runCurrent()
            assertEquals(2, reads)
            assertEquals(1, f.outbox.q15ReviewCount.value)
            nuevo.value = 0; runCurrent() // revisión durable ya registrada
            assertEquals(0, f.outbox.q15ReviewCount.value)
            assertFalse(f.outbox.q15HistoryUnavailable.value)
            f.outbox.start(VENUE); runCurrent()
            assertEquals(2, reads) // contexto idéntico mantiene el único observador
            assertEquals(1, f.releases.get())
            coVerify(exactly = 1) { f.dao.deleteOldAcked(any()) }
            coVerify(exactly = 0) { f.api.syncIntents(any(), any()) }
        } finally { f.close(); runCurrent() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `Q15 cambio de venue no publica actor ni conteo anteriores y stop borra aviso`() = runTest {
        val f = Fixture()
        val anterior = MutableStateFlow(3)
        val actual = MutableStateFlow(1)
        every { f.dao.observarRevisionesQ15(VENUE, false, "") } returns anterior
        every { f.dao.observarRevisionesQ15("owned-next-venue", false, "") } returns actual
        f.connected.value = false; f.reachable.value = false
        f.outbox.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        try {
            f.outbox.start(VENUE); runCurrent()
            assertEquals(3, f.outbox.q15ReviewCount.value)
            every { f.storage.venueId } returns "owned-next-venue"
            f.coordinator.onSessionChanged()
            anterior.value = 8; runCurrent()
            assertEquals(3, f.outbox.q15ReviewCount.value)
            f.outbox.start("owned-next-venue"); runCurrent()
            assertEquals(1, f.outbox.q15ReviewCount.value)
            anterior.value = 7; runCurrent()
            assertEquals(1, f.outbox.q15ReviewCount.value)
            f.outbox.stop(); actual.value = 6; runCurrent()
            assertEquals(0, f.outbox.q15ReviewCount.value)
            assertFalse(f.outbox.q15HistoryUnavailable.value)
            assertEquals(1, f.releases.get())
        } finally { f.close(); runCurrent() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `Q15 fallo de lectura se anuncia y siguiente inicio idempotente recupera observacion`() = runTest {
        val f = Fixture()
        var reads = 0
        val count = MutableStateFlow(1)
        every { f.dao.observarRevisionesQ15(VENUE, false, "") } answers {
            if (reads++ == 0) kotlinx.coroutines.flow.flow<Int> { throw IllegalStateException("owned-read-failure") } else count
        }
        f.connected.value = false; f.reachable.value = false
        f.outbox.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        try {
            f.outbox.start(VENUE); runCurrent()
            assertTrue(f.outbox.q15HistoryUnavailable.value)
            f.outbox.start(VENUE); runCurrent()
            assertEquals(1, f.outbox.q15ReviewCount.value)
            assertFalse(f.outbox.q15HistoryUnavailable.value)
            assertEquals(1, f.releases.get())
        } finally { f.close(); runCurrent() }
    }

    companion object {
        private const val VENUE = "owned-boundary-venue"
        private const val USER = "owned-boundary-user"
        private fun active() = TableSession.Active("table", "5", null, "order", "OLD", 3, 11000, TableSession.Mode.ORDERING, localOrderId = "local-origin")
        private fun ack(status: String = "ACKED", version: Int = 9) = SyncAck("owned-intent", status, result = buildJsonObject { put("orderId", "order"); put("version", version) })
        private suspend fun waitUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (!condition()) delay(10) } }
        private suspend fun <T> awaitReal(deferred: Deferred<T>): T = withContext(Dispatchers.Default) { withTimeout(5_000) { deferred.await() } }
    }
}
