package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.Q15_PAYMENT_TTL_SQL
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.OrderModifierRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * BORRADOR TEST-ONLY: no ejecutado, no cambia el candidato.
 * Destino propuesto: app/src/test/java/com/avoqado/pos/payment/PaymentSyncWakeupTest.kt.
 *
 * PaymentSyncService.start/syncNow, su Job, el drenado, cortesía y HTTP son REALES.
 * Reutiliza la fixture JDBC/SQLite/OkHttp de Q16; NO acredita generación de Room.
 * La barrera retiene una foto YA LEÍDA por getPendingPayments. Devuelve después
 * ESA foto vacía aunque K haya pasado a PENDING: no añade hooks de producto.
 *
 * No runTest/advanceUntilIdle/advanceTimeBy: runBlocking + scheduler.runCurrent
 * sólo ejecutan el instante actual. El timer de 15 minutos nunca se adelanta.
 * MainDispatcherRule restablece Main; el Job raíz de prueba se cancela y termina
 * antes de cerrar SQLite/HTTP y antes de que la regla restaure Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentSyncWakeupTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(30, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private class ReadGate(private val finishAfterCancellation: Boolean = false) {
        var snapshot: List<PendingPaymentEntity>? = null
            private set
        val release = CompletableDeferred<Unit>()
        suspend fun hold(alreadyRead: List<PendingPaymentEntity>) {
            snapshot = alreadyRead
            if (finishAfterCancellation) withContext(NonCancellable) { release.await() }
            else release.await()
        }
    }

    private data class Sent(val path: String, val body: String)

    private inner class Harness : AutoCloseable {
        val f = Q16PrecioCapturadoTest.Fixture()
        // Injected through the EXISTING context entry. This owns test cleanup,
        // not a new coordinator or a substitute for PaymentSyncService.syncJob.
        private val ownedScope = SupervisorJob()
        private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val gates = mutableMapOf<Int, ReadGate>()
        val sent = CopyOnWriteArrayList<Sent>()
        var reads = 0
            private set
        var cleanups = 0
            private set
        var httpFailure = false
        var allowedCleanups = Int.MAX_VALUE
        val unexpectedCleanup = CompletableDeferred<Unit>()
        private val holdUnexpectedCleanup = CompletableDeferred<Unit>()

        init {
            f.realWriters()
            f.service.usarContextoDeSincronizacion(ownedScope + StandardTestDispatcher(scheduler))
            every { f.storage.userId } returns STAFF
            every { f.storage.userRole } returns "MANAGER"
            every { f.outbox.contextoDeSesionQ15() } returns 7L
            every { f.drawer.operacionesVivasDelCajon() } returns 0
            every { f.drawer.cierreQueEspera() } returns null
            every { f.drawer.cajasQueEsperanSuApertura() } returns emptySet()
            every { f.drawer.idDeCajaParaElServidor(BOX, Q16PrecioCapturadoTest.VENUE) } returns BOX
            coEvery { f.outbox.replayNow(any()) } returns 0
            coEvery { f.outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)

            coEvery { f.dao.getPendingPayments(any()) } coAnswers {
                reads++
                val snapshot = f.query(
                    "SELECT * FROM pending_payments WHERE syncStatus='PENDING' ORDER BY createdAt,id LIMIT :n",
                    mapOf("n" to firstArg<Int>()),
                ).map(f::decode)
                gates.remove(reads)?.hold(snapshot)
                snapshot
            }
            coEvery { f.dao.deleteSynced(any()) } coAnswers {
                cleanups++ // Once per real sync Job, unlike reads per batch/round.
                if (cleanups > allowedCleanups) {
                    unexpectedCleanup.complete(Unit)
                    // A defective autonomous loop is observable and bounded;
                    // never turn it into a passing result or an infinite test.
                    holdUnexpectedCleanup.await()
                }
                f.sql(Q15_PAYMENT_TTL_SQL, mapOf("olderThan" to firstArg<Long>()))
                Unit
            }
            // Exact current @Query body of PendingPaymentDao.retryFailed.
            // This only stages the race. It is NOT the future full-source CAS,
            // nor the public retryFailedPayment path that requires CANCELLED0.
            coEvery { f.dao.retryFailed(any()) } answers {
                f.sql("UPDATE pending_payments SET syncStatus = 'PENDING', retryCount = 0, lastError = NULL, lastRetryAt = NULL WHERE id = :id AND syncStatus = 'FAILED' AND q15Reason IS NULL",
                    mapOf("id" to firstArg<String>()))
                Unit
            }
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    sent += Sent(path, request.body.readUtf8())
                    if (httpFailure) return MockResponse().setResponseCode(503).setBody("""{"error":"owned-network-failure"}""")
                    return when {
                        path.endsWith("/orders") -> MockResponse().setResponseCode(200).setBody(
                            """{"success":true,"data":{"id":"$ORDER","total":0,"items":[{"id":"$ITEM","externalId":"$LINE","isCortesia":true,"cortesiaReason":"$REASON"}]}}""",
                        )
                        path.endsWith("/orders/$ORDER/pay") -> MockResponse().setResponseCode(200).setBody(
                            """{"success":true,"payment":{"paymentId":"$PAYMENT","orderId":"$ORDER","venueId":"${Q16PrecioCapturadoTest.VENUE}","status":"COMPLETED","method":"CASH","splitType":"FULLPAYMENT","amount":0,"tipAmount":0,"changeCents":0,"orderTotalCents":0,"remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}""",
                        )
                        else -> MockResponse().setResponseCode(404).setBody("""{"error":"unexpected-test-route"}""")
                    }
                }
            }
        }

        suspend fun seedOriginalFailed(): Map<String, Any?> {
            val order = CreateOrderRequest(
                items = listOf(OrderItemRequest(
                    productId = PRODUCT, name = "Artículo de prueba", quantity = 1, unitPrice = 9000,
                    modifiers = listOf(OrderModifierRequest(MOD_A, "Extra A", 1000), OrderModifierRequest(MOD_B, "Extra B", 1000)),
                    externalId = LINE, isCortesia = true, cortesiaReason = REASON,
                )), subtotal = 11000, total = 0, paymentMethod = "CASH", createdAtLocal = CAPTURED_AT,
            )
            val key = CashPaymentRepository(f.storage, f.dao).queueCashPayment(
                order, STAFF, 0, 0, null, orderExternalId = KEY, idempotencyKey = KEY,
                amountCents = 0, tipCents = 0, venueId = Q16PrecioCapturadoTest.VENUE,
                capturadaEn = CAPTURED_AT, cajaDelCobro = BOX,
            )
            assertEquals(KEY, key)
            f.dao.updateStatusWithError(KEY, "FAILED", "Cortesía antigua conservada")
            return f.row(KEY).also {
                assertEquals("FAILED", it["syncStatus"])
                assertNull(it["q15Reason"])
                assertEquals(0, (it["amountCents"] as Number).toInt())
                assertEquals(0, (it["tipCents"] as Number).toInt())
                assertNotNull(it["q15CaptureJson"])
                assertNotNull(it["orderRequestJson"])
            }
        }

        fun gateRead(number: Int, delayedCancellation: Boolean = false) = ReadGate(delayedCancellation).also { gates[number] = it }

        fun assertEmptySnapshot(gate: ReadGate) {
            assertNotNull("The REAL drain must already have read its last empty snapshot", gate.snapshot)
            assertTrue(gate.snapshot!!.isEmpty())
        }

        suspend fun makeSameKeyPending(before: Map<String, Any?>) {
            f.dao.retryFailed(KEY)
            val pending = f.row(KEY)
            assertEquals("PENDING", pending["syncStatus"])
            assertSourcePreserved(before, pending)
        }

        fun observeCurrentCompletion(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { done ->
            observers.launch {
                f.service.esperarSincronizacionActualQ16() // joins the actual Job; no reflection or replacement.
                done.complete(Unit)
            }
        }

        fun pumpUntil(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                scheduler.runCurrent()
                if (condition()) return
                Thread.sleep(1) // Real local IO only; never advances coroutine virtual time.
            } while (System.nanoTime() < deadline)
            assertTrue(message, condition())
        }

        fun assertOneOriginalPayment(before: Map<String, Any?>) {
            pumpUntil("K must leave through the existing drain without a second external trigger") { f.row(KEY)["syncStatus"] == "SYNCED" }
            val finished = observeCurrentCompletion()
            pumpUntil("The real drain and completion hook must finish") { finished.isCompleted }
            scheduler.runCurrent()
            val after = f.row(KEY)
            assertSourcePreserved(before, after)
            assertNull(after["q15Reason"])
            assertEquals(listOf("orders", "pay"), sent.map { if (it.path.endsWith("/pay")) "pay" else if (it.path.endsWith("/orders")) "orders" else "unexpected" })
            assertEquals(2, f.server.requestCount)
            val creation = JSONObject(sent[0].body)
            assertEquals(KEY, creation.getString("externalId"))
            assertEquals(STAFF, creation.getString("staffId"))
            assertEquals(0, creation.getInt("total")); assertEquals(0, creation.getInt("tip"))
            assertEquals(BOX, creation.getString("cashDrawerSessionId"))
            val line = creation.getJSONArray("items").getJSONObject(0)
            assertEquals(LINE, line.getString("externalId")); assertTrue(line.getBoolean("isCortesia"))
            assertEquals(REASON, line.getString("cortesiaReason"))
            val pay = JSONObject(sent[1].body)
            assertTrue(sent[1].path.endsWith("/orders/$ORDER/pay"))
            assertEquals(KEY, pay.getString("idempotencyKey"))
            assertEquals(STAFF, pay.getString("staffId"))
            assertEquals(0, pay.getInt("amount")); assertEquals(0, pay.getInt("tip"))
            assertEquals("CASH", pay.getString("method")); assertEquals("FULLPAYMENT", pay.getString("splitType"))
            assertEquals(BOX, pay.getString("cashDrawerSessionId"))
            assertTrue(pay.getBoolean("isOfflineReplay"))
            assertEquals("No timer or reconnect time may be used to rescue K", 0L, scheduler.currentTime)
        }

        override fun close() {
            // Complete even non-cancellable test gates before cancelling their owner.
            // Removed gates are also released explicitly by each test's finally block.
            gates.values.forEach { it.release.complete(Unit) }
            f.service.stop()
            ownedScope.cancel()
            observers.cancel()
            try {
                pumpUntil("No owned service coroutine may outlive SQLite/HTTP/Main") { ownedScope.isCompleted }
            } finally {
                f.close()
            }
        }
    }

    private fun assertSourcePreserved(before: Map<String, Any?>, after: Map<String, Any?>) {
        for (column in listOf("id", "venueId", "original", "q15CaptureJson", "orderRequestJson", "createdAt", "amountCents", "tipCents", "capturedOrderRef", "method", "splitType", "customerId", "tenderTypeId", "tenderRevision", "cashTenderedCents", "changeCents")) {
            assertEquals("Original column $column must remain intact", before[column], after[column])
        }
        val preserved = JSONObject(after["original"] as String)
        assertEquals(STAFF, preserved.getString("staff")); assertEquals(BOX, preserved.getString("caja"))
    }

    @Test fun `control a pending original drains through real HTTP at ordinary start`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 1
            val before = h.seedOriginalFailed()
            h.makeSameKeyPending(before)
            h.f.service.start()
            h.assertOneOriginalPayment(before)
            assertEquals(1, h.cleanups)
        }
    }

    @Test fun `request while busy after last empty read wakes the same drain when its owner finishes`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 2
            val before = h.seedOriginalFailed()
            val gate = h.gateRead(1)
            try {
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(gate)
                assertEquals(1, h.cleanups); assertTrue(h.sent.isEmpty())
                h.makeSameKeyPending(before)
                h.f.service.syncNow() // Sole external request, while the actual syncJob is suspended/active.
                assertEquals(1, h.reads); assertTrue(h.sent.isEmpty())
                gate.release.complete(Unit) // The old drain receives EMPTY; it cannot make progress itself.
                h.assertOneOriginalPayment(before)
                assertEquals("Exactly one extra real sync Job", 2, h.cleanups)
                coVerify(exactly = 2) { h.f.dao.deleteSynced(any()) }
            } finally { gate.release.complete(Unit) }
        }
    }

    @Test fun `busy request burst coalesces into one additional real sync Job`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 2
            val before = h.seedOriginalFailed()
            val gate = h.gateRead(1)
            try {
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(gate)
                h.makeSameKeyPending(before)
                repeat(32) { h.f.service.syncNow() } // All requests precede the same owner's real completion.
                assertEquals(1, h.cleanups); assertEquals(1, h.reads)
                gate.release.complete(Unit)
                h.assertOneOriginalPayment(before)
                assertEquals(2, h.cleanups)
            } finally { gate.release.complete(Unit) }
        }
    }

    @Test fun `stop cancels the coalesced wake and leaves K durable without sending`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 1
            val before = h.seedOriginalFailed()
            val gate = h.gateRead(1)
            try {
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(gate)
                val finished = h.observeCurrentCompletion()
                h.makeSameKeyPending(before); h.f.service.syncNow()
                h.f.service.stop(); gate.release.complete(Unit)
                h.pumpUntil("Cancelled owner must complete") { finished.isCompleted }
                scheduler.runCurrent()
                assertEquals("PENDING", h.f.row(KEY)["syncStatus"])
                assertSourcePreserved(before, h.f.row(KEY))
                assertEquals(1, h.cleanups); assertTrue(h.sent.isEmpty())
                assertEquals(0, h.f.server.requestCount); assertEquals(0L, scheduler.currentTime)
            } finally { gate.release.complete(Unit) }
        }
    }

    @Test fun `old cancelled finalizer cannot erase the newer owner's busy wake`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 3
            val before = h.seedOriginalFailed()
            val old = h.gateRead(1, delayedCancellation = true)
            val current = h.gateRead(2)
            try {
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(old)
                val oldFinished = h.observeCurrentCompletion()
                h.f.service.syncNow() // Old lifecycle's request must be invalidated by stop.
                h.f.service.stop()
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(current)
                h.makeSameKeyPending(before); h.f.service.syncNow() // Request belongs to the NEW current owner.
                old.release.complete(Unit)
                h.pumpUntil("Old cancelled Job must really finish before releasing the current one") { oldFinished.isCompleted }
                scheduler.runCurrent()
                assertEquals("Old finalizer must not launch another drain beside the current owner", 2, h.reads)
                assertTrue(h.sent.isEmpty())
                current.release.complete(Unit)
                h.assertOneOriginalPayment(before)
                assertEquals("Old, new, and ONE wake of the new owner", 3, h.cleanups)
            } finally { old.release.complete(Unit); current.release.complete(Unit) }
        }
    }

    @Test fun `network failure without external request does not schedule an extra sync Job`() = runBlocking {
        Harness().use { h ->
            val before = h.seedOriginalFailed()
            h.makeSameKeyPending(before); h.httpFailure = true
            h.allowedCleanups = 1 // Detect and suspend any erroneous auto-generated new Job.
            h.f.service.start()
            h.pumpUntil("The real HTTP503 must finish and preserve the original retry") { (h.f.row(KEY)["retryCount"] as Number).toInt() == 1 }
            scheduler.runCurrent()
            assertFalse("An HTTP failure is not an external wake request", h.unexpectedCleanup.isCompleted)
            assertEquals(1, h.cleanups); assertEquals(1, h.f.server.requestCount)
            assertEquals("PENDING", h.f.row(KEY)["syncStatus"])
            assertSourcePreserved(before, h.f.row(KEY))
            assertEquals(0L, scheduler.currentTime)
        }
    }

    @Test fun `actor change invalidates a busy wake without sending the original`() = runBlocking {
        Harness().use { h ->
            h.allowedCleanups = 1
            val before = h.seedOriginalFailed()
            val gate = h.gateRead(1)
            try {
                h.f.service.start(); scheduler.runCurrent(); h.assertEmptySnapshot(gate)
                val finished = h.observeCurrentCompletion()
                h.makeSameKeyPending(before); h.f.service.syncNow()
                every { h.f.storage.userId } returns KEY
                gate.release.complete(Unit)
                h.pumpUntil("Previous actor's owner must finish without a new wake") { finished.isCompleted }
                scheduler.runCurrent()
                assertEquals("PENDING", h.f.row(KEY)["syncStatus"])
                assertSourcePreserved(before, h.f.row(KEY))
                assertEquals(1, h.cleanups); assertTrue(h.sent.isEmpty())
                assertEquals(0, h.f.server.requestCount); assertEquals(0L, scheduler.currentTime)
            } finally { gate.release.complete(Unit) }
        }
    }

    companion object {
        // Fixed cuid-v1-shaped identities; each test owns a fresh temporary DB/server.
        private const val KEY = "cjld2cjxh0000qzrmn831i7rn"
        private const val ORDER = "cjld2cjxh0001qzrmn831i7rn"
        private const val LINE = "cjld2cjxh0002qzrmn831i7rn"
        private const val STAFF = "cjld2cjxh0003qzrmn831i7rn"
        private const val PRODUCT = "cjld2cjxh0004qzrmn831i7rn"
        private const val PAYMENT = "cjld2cjxh0005qzrmn831i7rn"
        private const val BOX = "cjld2cjxh0006qzrmn831i7rn"
        private const val MOD_A = "cjld2cjxh0007qzrmn831i7rn"
        private const val MOD_B = "cjld2cjxh0008qzrmn831i7rn"
        private const val ITEM = "cjld2cjxh0009qzrmn831i7rn"
        private const val REASON = "Invitación de la casa"
        private const val CAPTURED_AT = 1793040000000L
    }
}
