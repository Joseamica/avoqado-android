package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.payment.data.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Contrato de continuación de cortesía antigua: fuente/ACK completos y HTTP público.
 * Producción real al promover: DAO defaults/@Query, parser CortesiaPersistida,
 * PaymentSyncService, dos POST de revisión y drenado normal SQLite/HTTP.
 * El adaptador JDBC emula la envoltura @Transaction; NO acredita Room generado.
 * No es el test de despertar: el drenado inicial termina ocioso antes de confirmar.
 */
class PaymentLegacyCourtesyContinuationTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(90)

    private data class Sent(val path: String, val body: JSONObject)
    private class Harness : AutoCloseable {
        val f = Q16PrecioCapturadoTest.Fixture()
        private val owner = SupervisorJob()
        @Volatile var venue = Q16PrecioCapturadoTest.VENUE
        @Volatile var actor = ACTOR
        @Volatile var role = "MANAGER"
        @Volatile var epoch = 7L
        @Volatile var permissions = listOf("orders:read", "orders:comp")
        val sent = CopyOnWriteArrayList<Sent>()
        var casCalls = 0
        var rawCalls = 0
        var forceCasZero = false
        var beforeCas: (() -> Unit)? = null
        var afterCommit: (() -> Unit)? = null
        var afterRead: (suspend () -> Unit)? = null
        @Volatile var previewHook: (() -> Unit)? = null
        @Volatile var confirmHook: (() -> Unit)? = null
        @Volatile var previewCode = 200
        @Volatile var confirmCode = 200
        @Volatile var appliedOutcome = "APPLIED"
        @Volatile var corruptConfirm: ((JSONObject) -> JSONObject)? = null
        var txAttempt: CompletableDeferred<Unit>? = null
        var txEntered: CompletableDeferred<Unit>? = null
        lateinit var original: PendingPaymentEntity
        lateinit var preview: JSONObject
        private var inTransaction = false

        init {
            // Q16's small table/decoder omits persisted fields. Use the REAL
            // schema18 export for this full-source contract, keeping its owned
            // connection, server, service and production Q15 result SQL.
            val entities = JSONObject(File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/18.json").readText())
                .getJSONObject("database").getJSONArray("entities")
            for (name in listOf("pending_payments", "sales_notice_acknowledgements")) {
                val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == name }
                f.sql("DROP TABLE IF EXISTS $name")
                f.sql(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            }
            f.db.close()
            f.db = DriverManager.getConnection("jdbc:sqlite:" + f.directory.resolve("q16.sqlite").absolutePath,
                Properties().apply { setProperty("transaction_mode", "IMMEDIATE"); setProperty("busy_timeout", "5000") })
            f.realWriters()
            f.service.usarContextoDeSincronizacion(owner + Dispatchers.Unconfined)
            every { f.storage.venueId } answers { venue }
            every { f.storage.userId } answers { actor }
            every { f.storage.userRole } answers { role }
            every { f.storage.venuePermissions } answers { permissions }
            every { f.outbox.contextoDeSesionQ15() } answers { epoch }
            every { f.drawer.idDeCajaParaElServidor(BOX, Q16PrecioCapturadoTest.VENUE) } returns BOX
            coEvery { f.dao.insertRawSqlQ16(any()) } answers { save(firstArg()); Unit }
            coEvery { f.dao.findByIdQ15(any()) } answers { read(firstArg()) }
            coEvery { f.dao.findForVenue(any(), any()) } coAnswers {
                val result = read(secondArg()).takeIf { it?.venueId == firstArg<String>() }
                afterRead?.invoke()
                result
            }
            coEvery { f.dao.getPendingPayments(any()) } answers {
                f.query("SELECT * FROM pending_payments WHERE syncStatus='PENDING' ORDER BY createdAt,id LIMIT :n", mapOf("n" to firstArg<Int>())).map(::decode)
            }
            // FUTURE production defaults and SQL, never a test CAS algorithm.
            coEvery { f.dao.reanudarCortesiaAntiguaQ4(any(), any()) } coAnswers {
                casCalls++; beforeCas?.invoke(); callOriginal()
            }
            coEvery { f.dao.reanudarCortesiaAntiguaTxQ4(any(), any()) } coAnswers {
                transaction { callOriginal() }
            }
            coEvery { f.dao.reanudarCortesiaAntiguaSqlQ4(any(), any()) } answers {
                rawCalls++
                val count = if (forceCasZero) 0 else f.sql(Q4_COURTESY_RETRY_SQL, mapOf("venueId" to firstArg<String>(), "id" to secondArg<String>()))
                count
            }
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val body = JSONObject(request.body.readUtf8())
                    sent += Sent(path, body)
                    if (path.endsWith("/courtesy-review")) {
                        val confirm = body.optBoolean("confirm", false)
                        if (!confirm) previewHook?.invoke() else confirmHook?.invoke()
                        val snapshot = if (confirm) appliedSnapshot() else JSONObject(preview.toString())
                        val reply = JSONObject().put("success", true).put("data", JSONObject()
                            .put("outcome", if (confirm) appliedOutcome else "PREVIEW").put("originalPaymentKey", KEY).put("snapshot", snapshot))
                        return MockResponse().setResponseCode(if (confirm) confirmCode else previewCode)
                            .setBody((if (confirm) corruptConfirm?.invoke(reply) ?: reply else reply).toString())
                    }
                    if (path.endsWith("/orders")) return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":{"id":"$ORDER","total":0,"items":[{"id":"$ITEM","externalId":"$LINE","isCortesia":true,"cortesiaReason":"$REASON"}]}}""",
                    )
                    if (path.endsWith("/orders/$ORDER/pay")) return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"payment":{"paymentId":"$PAYMENT","orderId":"$ORDER","venueId":"${Q16PrecioCapturadoTest.VENUE}","amount":0,"tipAmount":0,"changeCents":0,"method":"CASH","splitType":"FULLPAYMENT","status":"COMPLETED","orderTotalCents":0,"remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}""",
                    )
                    return MockResponse().setResponseCode(404).setBody("""{"error":"unexpected-route"}""")
                }
            }
        }

        suspend fun seed(): PendingPaymentEntity {
            val request = CreateOrderRequest(items = listOf(OrderItemRequest(PRODUCT, "Artículo original", 1, 9000,
                modifiers = listOf(OrderModifierRequest(MOD_A, "Extra A", 1000), OrderModifierRequest(MOD_B, "Extra B", 1000)),
                externalId = LINE, isCortesia = true, cortesiaReason = REASON)),
                subtotal = 11000, total = 0, paymentMethod = "CASH", createdAtLocal = CAPTURED_AT)
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(request, HISTORICAL_ACTOR, 0, 0, null,
                orderExternalId = KEY, idempotencyKey = KEY, customerId = CUSTOMER, amountCents = 0, tipCents = 0,
                venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = CAPTURED_AT, cajaDelCobro = BOX)
            val captured = requireNotNull(read(KEY))
            original = captured.copy(syncStatus = "FAILED", retryCount = 4, lastRetryAt = CAPTURED_AT + 1,
                lastError = "Cortesía no confirmada", orderNumber = "ORIGINAL-110", rating = 5,
                q15InitialResultJson = "{\"error\":\"initial-failure\"}", q15ResultJson = "{\"error\":\"courtesy-not-confirmed\"}",
                q15DetectedAt = CAPTURED_AT + 2, q15ReviewedAt = CAPTURED_AT + 3, q15ReviewedBy = OLD_REVIEWER, q15ReviewRole = "MANAGER")
            save(original)
            val ack = linkedMapOf<String, Any?>("venueId" to original.venueId, "origin" to "PAYMENT", "id" to KEY,
                "actor" to OLD_REVIEWER, "acknowledgedAt" to CAPTURED_AT + 4, "capture" to original.q15CaptureJson,
                "body" to original.orderRequestJson, "result" to original.q15ResultJson, "reason" to null,
                "error" to original.lastError, "code" to "COURTESY_PENDING", "type" to "ORDER", "method" to "CASH",
                "amountCents" to 0, "tipCents" to 0, "tenderTypeId" to null, "tenderRevision" to null,
                "reference" to original.capturedOrderRef, "orderId" to null, "state" to "UNRESOLVED")
            insert("sales_notice_acknowledgements", ack)
            // Exact PUBLIC field names/units from courtesyReview.adapter.fixture.draft.ts.
            preview = JSONObject("""{"orderId":"$ORDER","externalId":"$KEY","orderNumber":"CANONICAL-110","version":1,"status":"CONFIRMED","paymentStatus":"PENDING","originSystem":"AVOQADO","contratoDePrecio":"IVA_INCLUIDO","tableId":null,"servedById":null,"subtotal":110,"discountAmount":0,"taxAmount":0,"serviceChargeAmount":0,"deliveryFeeAmount":0,"tipAmount":0,"total":110,"paidAmount":0,"remainingBalance":110,"lines":[{"id":"$ITEM","externalId":"$LINE","productId":"$PRODUCT","quantity":1,"modifierIds":["$MOD_A","$MOD_B"],"isCortesia":false,"cortesiaReason":null,"unitPrice":90,"total":110,"discountAmount":0,"taxAmount":0,"appliedDiscountId":null,"modifiers":[{"id":"$MOD_ROW_A","modifierId":"$MOD_A","quantity":1,"price":10},{"id":"$MOD_ROW_B","modifierId":"$MOD_B","quantity":1,"price":10}]}]}""")
            return original
        }

        private fun appliedSnapshot() = JSONObject(preview.toString()).apply {
            put("version", 2); put("subtotal", 0); put("total", 0); put("remainingBalance", 0)
            getJSONArray("lines").getJSONObject(0).apply { put("isCortesia", true); put("cortesiaReason", REASON); put("total", 0); put("discountAmount", 110) }
        }

        fun row() = requireNotNull(read(KEY))
        fun ack() = f.query("SELECT * FROM sales_notice_acknowledgements ORDER BY venueId,origin,id")
        fun all() = f.query("SELECT * FROM pending_payments ORDER BY id")
        fun read(id: String) = f.query("SELECT * FROM pending_payments WHERE id=:id", mapOf("id" to id)).singleOrNull()?.let(::decode)
        fun save(row: PendingPaymentEntity) = insert("pending_payments", columns(row))
        private fun insert(table: String, values: Map<String, Any?>) {
            val names = values.keys.toList()
            f.sql("INSERT OR REPLACE INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { ":$it" }})", values)
        }
        private suspend fun <T> transaction(block: suspend () -> T): T = withContext(Dispatchers.IO) {
            check(!inTransaction)
            inTransaction = true
            try {
                txAttempt?.complete(Unit)
                f.db.autoCommit = false // Xerial transaction_mode=IMMEDIATE: real write lock acquisition.
                txEntered?.complete(Unit)
                try { val result = block(); f.db.commit(); afterCommit?.invoke(); result }
                catch (error: Throwable) { f.db.rollback(); throw error }
            } finally { if (!f.db.autoCommit) f.db.autoCommit = true; inTransaction = false }
        }

        suspend fun finish() { f.awaitFinished(KEY); f.service.esperarSincronizacionActualQ16() }
        override fun close() { f.service.stop(); owner.cancel(); runBlocking { owner.join() }; f.close() }
    }

    // These DAO-only lambdas assert a known-current test context, not financial authorization.
    // The service positives below obtain confirmation over real local HTTP.
    @Test fun `CAS exact source changes only retry state and leaves the existing ACK untouched`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack()
            assertEquals(columns(source).keys, PendingPaymentEntity::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet())
            assertEquals(1, h.f.dao.reanudarCortesiaAntiguaQ4(source) { true })
            assertEquals(source.copy(syncStatus = "PENDING", retryCount = 0, lastError = null, lastRetryAt = null), h.row())
            assertEquals(ack, h.ack()); assertEquals(1, h.rawCalls); assertTrue(h.sent.isEmpty())
            assertEquals(0, h.f.dao.reanudarCortesiaAntiguaQ4(source) { true }) // stale source cannot enable a second time.
        }
    }

    @Test fun `CAS rejects a change in EACH of the 33 persisted source fields`() = runBlocking {
        for ((name, change) in mutations()) Harness().use { h ->
            val source = h.seed(); val changed = change(source)
            assertEquals(columns(source).keys, mutations().map { it.first }.toSet())
            h.f.sql("DELETE FROM pending_payments"); h.save(changed)
            val before = h.all(); val ack = h.ack()
            assertEquals(name, 0, h.f.dao.reanudarCortesiaAntiguaQ4(source) { true })
            assertEquals(name, before, h.all()); assertEquals(name, ack, h.ack()); assertEquals(name, 0, h.rawCalls)
        }
    }

    @Test fun `CAS refuses noneligible current states even with the exact matching snapshot`() = runBlocking {
        val invalid: List<(PendingPaymentEntity) -> PendingPaymentEntity> = listOf(
            { it.copy(syncStatus = "PENDING") }, { it.copy(syncStatus = "SYNCING") }, { it.copy(syncStatus = "SYNCED") },
            { it.copy(syncStatus = "EN_VUELO") }, { it.copy(q15Reason = "PRICE_PROOF_PENDING") },
            { it.copy(amountCents = 1) }, { it.copy(tipCents = 1) }, { it.copy(paymentType = "FAST") },
            { it.copy(method = "CARD") }, { it.copy(tenderTypeId = OTHER, tenderRevision = 1) },
        )
        for (change in invalid) Harness().use { h ->
            val row = change(h.seed()); h.save(row); val ack = h.ack()
            assertEquals(0, h.f.dao.reanudarCortesiaAntiguaQ4(row) { true }); assertEquals(row, h.row()); assertEquals(ack, h.ack())
        }
    }

    @Test fun `CAS affectedRows zero never becomes successful continuation`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); h.forceCasZero = true
            assertEquals(0, h.f.dao.reanudarCortesiaAntiguaQ4(source) { true }); assertEquals(source, h.row())
        }
    }

    @Test fun `preview reads the exact original and sends only the new PUBLIC PESOS review contract`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack()
            val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, proposal.original); assertEquals(ACTOR, proposal.actor); assertEquals("MANAGER", proposal.rol); assertEquals(7L, proposal.contexto)
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(0, h.casCalls)
            assertEquals(1, h.sent.size); assertReview(h.sent.single(), false)
        }
    }

    @Test fun `source without original complete courtesy evidence is preserved without preview or CAS`() = runBlocking {
        val invalid: List<(PendingPaymentEntity) -> PendingPaymentEntity> = listOf(
            { it.copy(q15CaptureJson = null) }, { it.copy(q15CaptureJson = "{}") },
            { it.copy(orderRequestJson = it.orderRequestJson + " ") }, // valid JSON, but no longer capture bytes.
            { it.copy(syncStatus = "EN_VUELO") }, { it.copy(q15Reason = "PRICE_PROOF_PENDING") },
            { it.copy(amountCents = 1) }, { it.copy(tipCents = 1) }, { it.copy(method = "CARD") },
            { it.copy(orderRequestJson = "{}") },
        )
        for (change in invalid) Harness().use { h ->
            val row = change(h.seed()); h.save(row); val ack = h.ack()
            assertNull(h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(row, h.row()); assertEquals(ack, h.ack()); assertTrue(h.sent.isEmpty()); assertEquals(0, h.casCalls)
        }
    }

    @Test fun `coherent capture and body still need complete unique original identities and reasons`() = runBlocking {
        for (kind in listOf("order-external", "empty-lines", "line-external", "duplicate-line", "reason", "flag", "product", "quantity")) Harness().use { h ->
            val source = syntheticSource(h.seed()) { body ->
                val lines = body.getJSONArray("items"); val line = lines.getJSONObject(0)
                when (kind) {
                    "order-external" -> body.remove("externalId")
                    "empty-lines" -> body.put("items", JSONArray())
                    "line-external" -> line.remove("externalId")
                    "duplicate-line" -> lines.put(JSONObject(line.toString()))
                    "reason" -> line.put("cortesiaReason", " ")
                    "flag" -> line.put("isCortesia", false)
                    "product" -> line.remove("productId")
                    "quantity" -> line.put("quantity", 0)
                }
            }
            h.save(source); val ack = h.ack()
            assertNull(kind, h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertTrue(h.sent.isEmpty()); assertEquals(0, h.casCalls)
        }
    }

    @Test fun `two original lines of the same product keep their own identities and reasons in preview`() = runBlocking {
        Harness().use { h ->
            val source = syntheticSource(h.seed()) { body ->
                val lines = body.getJSONArray("items")
                lines.put(JSONObject(lines.getJSONObject(0).toString()).put("externalId", LINE_TWO).put("cortesiaReason", "Amigos y familia"))
                body.put("subtotal", 22000)
            }
            h.save(source)
            val canonical = h.preview.getJSONArray("lines")
            canonical.put(JSONObject(canonical.getJSONObject(0).toString()).put("id", ITEM_TWO).put("externalId", LINE_TWO).also {
                it.getJSONArray("modifiers").getJSONObject(0).put("id", MOD_ROW_TWO_A)
                it.getJSONArray("modifiers").getJSONObject(1).put("id", MOD_ROW_TWO_B)
            })
            h.preview.put("subtotal", 220).put("total", 220).put("remainingBalance", 220)
            val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, proposal.original); assertEquals(source, h.row()); assertEquals(0, h.casCalls)
            val sent = h.sent.single().body.getJSONArray("lines")
            assertEquals(2, sent.length())
            val byId = (0 until sent.length()).map { sent.getJSONObject(it) }.associateBy { it.getString("externalId") }
            assertEquals(setOf(LINE, LINE_TWO), byId.keys)
            assertEquals(REASON, byId.getValue(LINE).getString("cortesiaReason"))
            assertEquals("Amigos y familia", byId.getValue(LINE_TWO).getString("cortesiaReason"))
        }
    }

    @Test fun `context or effective permission lost DURING source await cannot produce a preview`() = runBlocking {
        for (kind in listOf("venue", "actor", "role", "epoch", "permission")) Harness().use { h ->
            val source = h.seed(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.afterRead = { entered.complete(Unit); release.await() }
            val operation = async(Dispatchers.Unconfined) { h.f.service.prepararRevisionCortesia(KEY) }
            try {
                withTimeout(5000) { select<Unit> {
                    entered.onAwait { }
                    operation.onAwait { fail("The real source await was never reached"); Unit }
                } }
                revoke(h, kind, "orders:read"); release.complete(Unit)
                assertNull(kind, operation.await()); assertEquals(source, h.row()); assertTrue(h.sent.isEmpty()); assertEquals(0, h.casCalls)
            } finally { release.complete(Unit); operation.cancelAndJoin(); h.afterRead = null }
        }
    }

    @Test fun `read-only permission allows preview but manager role cannot substitute comp at confirm`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack()
            h.permissions = listOf("orders:read")
            val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, proposal.original)
            assertEquals(1, h.sent.size); assertReview(h.sent.single(), false)
            clearMocks(h.f.dao, answers = false, recordedCalls = true)
            assertFalse(h.f.service.confirmarRevisionCortesia(proposal))
            coVerify(exactly = 0) { h.f.dao.findForVenue(any(), any()) }
            assertEquals(source, h.row()); assertEquals(ack, h.ack())
            assertEquals(1, h.sent.size); assertEquals(0, h.casCalls)
        }
    }

    @Test fun `source from another selected venue is never adopted by its global payment key`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); h.venue = OTHER
            assertNull(h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertTrue(h.sent.isEmpty()); assertEquals(0, h.casCalls)
        }
    }

    @Test fun `context or permission lost during preview HTTP preserves the source and ACK`() = runBlocking {
        for (kind in listOf("venue", "actor", "role", "epoch", "permission")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); h.previewHook = { revoke(h, kind, "orders:read") }
            assertNull(kind, h.f.service.prepararRevisionCortesia(KEY))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(0, h.casCalls)
            assertEquals(1, h.sent.size); assertReview(h.sent.single(), false)
        }
    }

    @Test fun `APPLIED or exact ALREADY_APPLIED enables the SAME K through normal drain never CANCELLED retry`() = runBlocking {
        for (outcome in listOf("APPLIED", "ALREADY_APPLIED")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack()
            h.f.service.start(); h.f.service.esperarSincronizacionActualQ16()
            val proposal = required(h.f.service.prepararRevisionCortesia(KEY)); h.appliedOutcome = outcome
            assertTrue(h.f.service.confirmarRevisionCortesia(proposal)) // means enabled; only reconciliation proves SYNCED.
            h.finish(); val final = h.row()
            assertEquals("SYNCED", final.syncStatus); assertEquals(1, h.casCalls); assertEquals(1, h.rawCalls)
            assertEquals(ack, h.ack())
            assertEquals(source.copy(syncStatus = final.syncStatus, retryCount = 0, lastError = null, lastRetryAt = null,
                q15ResultJson = final.q15ResultJson), final)
            assertEquals(4, h.sent.size); assertReview(h.sent[0], false); assertReview(h.sent[1], true)
            assertEquals("Expected canonical tuple stays in PUBLIC PESOS",
                kotlinx.serialization.json.Json.parseToJsonElement(h.preview.toString()),
                kotlinx.serialization.json.Json.parseToJsonElement(h.sent[1].body.getJSONObject("expected").toString()))
            assertTrue(h.sent[2].path.endsWith("/orders")); assertEquals(KEY, h.sent[2].body.getString("externalId"))
            assertTrue(h.sent[3].path.endsWith("/orders/$ORDER/pay"))
            val pay = h.sent[3].body
            assertEquals(KEY, pay.getString("idempotencyKey")); assertEquals(0, pay.getInt("amount")); assertEquals(0, pay.getInt("tip"))
            assertEquals(HISTORICAL_ACTOR, pay.getString("staffId")); assertEquals(BOX, pay.getString("cashDrawerSessionId"))
            assertTrue(h.sent.none { it.path.contains("/sync/recovery") })
            coVerify(exactly = 0) { h.f.dao.retryFailed(any()) }
        }
    }

    @Test fun `context or permission lost during confirm HTTP never reaches local CAS or drain`() = runBlocking {
        for (kind in listOf("venue", "actor", "role", "epoch", "permission")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            h.confirmHook = { revoke(h, kind) }
            assertFalse(kind, h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(0, h.casCalls); assertEquals(2, h.sent.size)
        }
    }

    @Test fun `context or permission revoked BEFORE confirm blocks its first await and HTTP`() = runBlocking {
        for (kind in listOf("venue", "actor", "role", "epoch", "permission")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            revoke(h, kind)
            assertFalse(kind, h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(0, h.casCalls); assertEquals(1, h.sent.size)
        }
    }

    @Test fun `changed persisted source between preview and confirm is reread before the confirm request`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            val changed = source.copy(lastError = "Changed after preview")
            h.save(changed)
            assertFalse(h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(changed, h.row()); assertEquals(ack, h.ack()); assertEquals(1, h.sent.size); assertEquals(0, h.casCalls)
        }
    }

    @Test fun `context lost while the real SQLite transaction waits cannot pass the in-transaction guard`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            val holder = DriverManager.getConnection("jdbc:sqlite:" + h.f.directory.resolve("q16.sqlite").absolutePath,
                Properties().apply { setProperty("transaction_mode", "IMMEDIATE") })
            holder.autoCommit = false // Owns the write lock BEFORE the actual DAO transaction attempts entry.
            h.txAttempt = CompletableDeferred(); h.txEntered = CompletableDeferred()
            val operation = async(Dispatchers.Default) { h.f.service.confirmarRevisionCortesia(proposal) }
            try {
                withTimeout(5000) { h.txAttempt!!.await() }
                assertFalse("Transaction entry must still be blocked by the other SQLite connection", h.txEntered!!.isCompleted)
                h.epoch++ // Real service context predicate, NOT an injected financial confirmation.
                holder.commit(); holder.autoCommit = true
                assertFalse(operation.await())
                assertTrue(h.txEntered!!.isCompleted); assertEquals(0, h.rawCalls)
                assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(2, h.sent.size)
            } finally { if (!holder.autoCommit) holder.rollback(); holder.close(); operation.cancelAndJoin() }
        }
    }

    @Test fun `source changes after canonical confirm and just before CAS are refused by the real DAO`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            val changed = source.copy(rating = 4)
            h.beforeCas = { h.save(changed) }
            assertFalse(h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(changed, h.row()); assertEquals(ack, h.ack()); assertEquals(1, h.casCalls); assertEquals(0, h.rawCalls)
            assertEquals(2, h.sent.size)
        }
    }

    @Test fun `context lost AFTER the CAS await preserves the durable row but does not wake sending`() = runBlocking {
        Harness().use { h ->
            val source = h.seed(); val ack = h.ack()
            h.f.service.start(); h.f.service.esperarSincronizacionActualQ16()
            val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            h.afterCommit = { h.epoch++ } // COMMIT already happened; not a context change that the tx could still roll back.
            assertFalse(h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(source.copy(syncStatus = "PENDING", retryCount = 0, lastError = null, lastRetryAt = null), h.row())
            assertEquals(ack, h.ack()); assertEquals(2, h.sent.size); assertEquals(1, h.rawCalls)
        }
    }

    @Test fun `200 without matching canonical proof never enables the row`() = runBlocking {
        for (kind in listOf("outcome", "key", "order", "external", "positive-total", "comp-flag", "reason")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            h.corruptConfirm = { root ->
                val data = root.getJSONObject("data"); val snapshot = data.getJSONObject("snapshot")
                when (kind) {
                    "outcome" -> data.put("outcome", "PREVIEW")
                    "key" -> data.put("originalPaymentKey", OTHER)
                    "order" -> snapshot.put("orderId", OTHER)
                    "external" -> snapshot.put("externalId", OTHER)
                    "positive-total" -> snapshot.put("total", 110)
                    "comp-flag" -> snapshot.getJSONArray("lines").getJSONObject(0).put("isCortesia", false)
                    "reason" -> snapshot.getJSONArray("lines").getJSONObject(0).put("cortesiaReason", "Motivo ajeno")
                }; root
            }
            assertFalse(kind, h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(0, h.casCalls); assertEquals(2, h.sent.size)
        }
    }

    @Test fun `HTTP failure or CAS zero leaves original pending review without pretending it was enabled`() = runBlocking {
        for (kind in listOf("http", "cas")) Harness().use { h ->
            val source = h.seed(); val ack = h.ack(); val proposal = required(h.f.service.prepararRevisionCortesia(KEY))
            if (kind == "http") h.confirmCode = 503 else h.forceCasZero = true
            assertFalse(h.f.service.confirmarRevisionCortesia(proposal))
            assertEquals(source, h.row()); assertEquals(ack, h.ack()); assertEquals(2, h.sent.size)
        }
    }

    private fun <T : Any> required(value: T?): T {
        assertNotNull("The real preview must return a proposal before confirmation", value)
        return requireNotNull(value)
    }

    private fun assertReview(sent: Sent, confirm: Boolean) {
        assertTrue(sent.path.endsWith("/mobile/venues/${Q16PrecioCapturadoTest.VENUE}/orders/courtesy-review"))
        val body = sent.body
        assertEquals(KEY, body.getString("originalPaymentKey")); assertEquals(KEY, body.getString("externalId"))
        assertEquals(0, body.getInt("expectedAmount")); assertEquals(0, body.getInt("expectedTip"))
        assertFalse(body.has("expectedAmountCents")); assertFalse(body.has("expectedTipCents"))
        assertFalse(body.has("staffId")); assertFalse(body.has("orderRequestJson")); assertFalse(body.has("q15CaptureJson"))
        assertEquals(confirm, body.optBoolean("confirm", false))
        val lines = body.getJSONArray("lines"); assertEquals(1, lines.length())
        val line = lines.getJSONObject(0)
        assertEquals(LINE, line.getString("externalId")); assertEquals(PRODUCT, line.getString("productId")); assertEquals(1, line.getInt("quantity"))
        assertEquals(setOf(MOD_A, MOD_B), (0 until line.getJSONArray("modifierIds").length()).map { line.getJSONArray("modifierIds").getString(it) }.toSet())
        assertEquals(2, line.getJSONArray("modifierIds").length())
        assertTrue(line.getBoolean("isCortesia")); assertEquals(REASON, line.getString("cortesiaReason"))
    }

    private fun revoke(h: Harness, kind: String, permission: String = "orders:comp") { when (kind) {
        "venue" -> h.venue = OTHER; "actor" -> h.actor = OTHER; "role" -> h.role = "VIEWER"
        "epoch" -> h.epoch++; "permission" -> h.permissions = h.permissions.filterNot { it == permission }
    } }

    /** Constructs a DIFFERENT synthetic original for a negative/variant fixture.
     * Never used by the service or to rewrite a source during review/continuation. */
    private fun syntheticSource(row: PendingPaymentEntity, change: (JSONObject) -> Unit): PendingPaymentEntity {
        val body = JSONObject(requireNotNull(row.orderRequestJson)).also(change).toString()
        val capture = requireNotNull(ConciliacionDelCobro.leerCaptura(row.q15CaptureJson, row.venueId, row.id))
        return row.copy(orderRequestJson = body, q15CaptureJson = ConciliacionDelCobro.guardarCaptura(capture.copy(orderRequestJson = body)))
    }

    companion object {
        private const val KEY = "cjld2cjxh0010qzrmn831i7rn"
        private const val ACTOR = "cjld2cjxh0011qzrmn831i7rn"
        private const val HISTORICAL_ACTOR = "cjld2cjxh0012qzrmn831i7rn"
        private const val OLD_REVIEWER = "cjld2cjxh0013qzrmn831i7rn"
        private const val CUSTOMER = "cjld2cjxh0014qzrmn831i7rn"
        private const val ORDER = "cjld2cjxh0015qzrmn831i7rn"
        private const val ITEM = "cjld2cjxh0016qzrmn831i7rn"
        private const val LINE = "cjld2cjxh0017qzrmn831i7rn"
        private const val PRODUCT = "cjld2cjxh0018qzrmn831i7rn"
        private const val MOD_A = "cjld2cjxh0019qzrmn831i7rn"
        private const val MOD_B = "cjld2cjxh0020qzrmn831i7rn"
        private const val MOD_ROW_A = "cjld2cjxh0021qzrmn831i7rn"
        private const val MOD_ROW_B = "cjld2cjxh0022qzrmn831i7rn"
        private const val PAYMENT = "cjld2cjxh0023qzrmn831i7rn"
        private const val BOX = "cjld2cjxh0024qzrmn831i7rn"
        private const val OTHER = "cjld2cjxh0025qzrmn831i7rn"
        private const val LINE_TWO = "cjld2cjxh0026qzrmn831i7rn"
        private const val ITEM_TWO = "cjld2cjxh0027qzrmn831i7rn"
        private const val MOD_ROW_TWO_A = "cjld2cjxh0028qzrmn831i7rn"
        private const val MOD_ROW_TWO_B = "cjld2cjxh0029qzrmn831i7rn"
        private const val REASON = "Especial del administrador"
        private const val CAPTURED_AT = 1793040000000L

        private fun columns(p: PendingPaymentEntity) = linkedMapOf<String, Any?>(
            "id" to p.id, "venueId" to p.venueId, "staffId" to p.staffId, "amountCents" to p.amountCents, "tipCents" to p.tipCents,
            "method" to p.method, "paymentType" to p.paymentType, "orderId" to p.orderId, "orderNumber" to p.orderNumber,
            "cashTenderedCents" to p.cashTenderedCents, "changeCents" to p.changeCents, "rating" to p.rating, "itemsJson" to p.itemsJson,
            "orderRequestJson" to p.orderRequestJson, "syncStatus" to p.syncStatus, "retryCount" to p.retryCount, "lastError" to p.lastError,
            "createdAt" to p.createdAt, "lastRetryAt" to p.lastRetryAt, "tenderTypeId" to p.tenderTypeId, "tenderRevision" to p.tenderRevision,
            "customerId" to p.customerId, "splitType" to p.splitType, "q15CaptureJson" to p.q15CaptureJson,
            "q15InitialResultJson" to p.q15InitialResultJson, "q15ResultJson" to p.q15ResultJson, "q15Reason" to p.q15Reason,
            "q15DetectedAt" to p.q15DetectedAt, "q15ReviewedAt" to p.q15ReviewedAt, "q15ReviewedBy" to p.q15ReviewedBy,
            "q15ReviewRole" to p.q15ReviewRole, "capturedOrderRef" to p.capturedOrderRef, "cashDrawerSessionId" to p.cashDrawerSessionId,
        )
        private fun decode(r: Map<String, Any?>): PendingPaymentEntity {
            fun i(k: String) = (r[k] as? Number)?.toInt()
            fun l(k: String) = (r[k] as? Number)?.toLong()
            fun s(k: String) = r[k] as? String
            return PendingPaymentEntity(s("id")!!, s("venueId")!!, s("staffId")!!, i("amountCents")!!, i("tipCents")!!,
                s("method")!!, s("paymentType")!!, orderId = s("orderId"), orderNumber = s("orderNumber"),
                cashTenderedCents = i("cashTenderedCents"), changeCents = i("changeCents"), rating = i("rating"),
                itemsJson = s("itemsJson"), orderRequestJson = s("orderRequestJson"), syncStatus = s("syncStatus")!!,
                retryCount = i("retryCount")!!, lastError = s("lastError"), createdAt = l("createdAt")!!, lastRetryAt = l("lastRetryAt"),
                tenderTypeId = s("tenderTypeId"), tenderRevision = i("tenderRevision"), customerId = s("customerId"), splitType = s("splitType")!!,
                q15CaptureJson = s("q15CaptureJson"), q15InitialResultJson = s("q15InitialResultJson"), q15ResultJson = s("q15ResultJson"),
                q15Reason = s("q15Reason"), q15DetectedAt = l("q15DetectedAt"), q15ReviewedAt = l("q15ReviewedAt"),
                q15ReviewedBy = s("q15ReviewedBy"), q15ReviewRole = s("q15ReviewRole"), capturedOrderRef = s("capturedOrderRef"),
                cashDrawerSessionId = s("cashDrawerSessionId"))
        }
        private fun mutations(): List<Pair<String, (PendingPaymentEntity) -> PendingPaymentEntity>> = listOf(
            "id" to { it.copy(id = OTHER) }, "venueId" to { it.copy(venueId = OTHER) }, "staffId" to { it.copy(staffId = OTHER) },
            "amountCents" to { it.copy(amountCents = 1) }, "tipCents" to { it.copy(tipCents = 1) }, "method" to { it.copy(method = "CARD") },
            "paymentType" to { it.copy(paymentType = "FAST") }, "orderId" to { it.copy(orderId = ORDER) }, "orderNumber" to { it.copy(orderNumber = "Changed") },
            "cashTenderedCents" to { it.copy(cashTenderedCents = 1) }, "changeCents" to { it.copy(changeCents = 1) }, "rating" to { it.copy(rating = 4) },
            "itemsJson" to { it.copy(itemsJson = "[]") }, "orderRequestJson" to { it.copy(orderRequestJson = it.orderRequestJson + " ") },
            "syncStatus" to { it.copy(syncStatus = "PENDING") }, "retryCount" to { it.copy(retryCount = 9) }, "lastError" to { it.copy(lastError = "Changed") },
            "createdAt" to { it.copy(createdAt = it.createdAt + 1) }, "lastRetryAt" to { it.copy(lastRetryAt = CAPTURED_AT + 9) },
            "tenderTypeId" to { it.copy(tenderTypeId = OTHER) }, "tenderRevision" to { it.copy(tenderRevision = 2) },
            "customerId" to { it.copy(customerId = OTHER) }, "splitType" to { it.copy(splitType = "CUSTOM_AMOUNT") },
            "q15CaptureJson" to { it.copy(q15CaptureJson = it.q15CaptureJson + " ") }, "q15InitialResultJson" to { it.copy(q15InitialResultJson = "{}") },
            "q15ResultJson" to { it.copy(q15ResultJson = "{}") }, "q15Reason" to { it.copy(q15Reason = "PRICE_PROOF_PENDING") },
            "q15DetectedAt" to { it.copy(q15DetectedAt = CAPTURED_AT + 9) }, "q15ReviewedAt" to { it.copy(q15ReviewedAt = CAPTURED_AT + 9) },
            "q15ReviewedBy" to { it.copy(q15ReviewedBy = OTHER) }, "q15ReviewRole" to { it.copy(q15ReviewRole = "OWNER") },
            "capturedOrderRef" to { it.copy(capturedOrderRef = ORDER) }, "cashDrawerSessionId" to { it.copy(cashDrawerSessionId = OTHER) },
        )
    }
}
