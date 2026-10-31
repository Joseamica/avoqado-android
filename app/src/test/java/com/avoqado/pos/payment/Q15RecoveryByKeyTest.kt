package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.payment.data.*
import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.sql.DriverManager
import java.util.Properties
import kotlinx.coroutines.flow.*
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import kotlinx.coroutines.test.*

/** Real drain + HTTP + production Q15 SQL on an owned SQLite file; generated Room is a separate gate. */
class Q15RecoveryByKeyTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(60)

    internal class Harness : AutoCloseable {
        val f = Q16PrecioCapturadoTest.Fixture()
        private val owner = SupervisorJob()
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var code = 200
        var response = proof()
        var beforeReply: (() -> Unit)? = null
        var reply: ((RecordedRequest) -> MockResponse)? = null
        var beforeTransaction: (() -> Unit)? = null
        var failResultWrites = 0
        var forceCasZero = false
        private var inTransaction = false
        val live = MutableStateFlow<PendingPaymentEntity?>(null)
        @Volatile var venue: String? = Q16PrecioCapturadoTest.VENUE
        @Volatile var actor: String? = "current-operator"
        @Volatile var epoch = 0L
        init {
            val entities = JSONObject(File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/19.json").readText())
                .getJSONObject("database").getJSONArray("entities")
            val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == "pending_payments" }
            f.sql("DROP TABLE pending_payments")
            f.sql(entity.getString("createSql").replace("\${TABLE_NAME}", "pending_payments"))
            f.db.close()
            f.db = DriverManager.getConnection("jdbc:sqlite:" + f.directory.resolve("q16.sqlite").absolutePath,
                Properties().apply { setProperty("transaction_mode","IMMEDIATE"); setProperty("busy_timeout","5000") })
            f.service.usarContextoDeSincronizacion(owner + Dispatchers.Unconfined)
            every { f.storage.venueId } answers { venue }
            every { f.storage.userId } answers { actor }
            every { f.outbox.contextoDeSesionQ15() } answers { epoch }
            coEvery { f.outbox.trabajoSinResolverDeLaOrden(any(),any()) } returns TrabajoSinResolver(0,0,emptySet(),0)
            every { f.storage.userRole } returns "CASHIER"
            every { f.storage.venuePermissions } returns listOf("payments:read")
            coEvery { f.dao.findForVenue(any(), any()) } answers { read(secondArg()).takeIf { it?.venueId == firstArg<String>() } }
            coEvery { f.dao.findByIdQ15(any()) } answers { read(firstArg()) }
            coEvery { f.dao.getPendingPayments(any()) } answers {
                f.query("SELECT * FROM pending_payments WHERE syncStatus='PENDING' ORDER BY createdAt,id LIMIT :n", mapOf("n" to firstArg<Int>())).map(::decode)
            }
            every { f.dao.observarFila(any(),any()) } answers { live }
            coEvery { f.dao.recuperarResultadoPorLlaveQ15(any(),any(),any(),any(),any()) } coAnswers {
                beforeTransaction?.invoke()
                if (forceCasZero) 0 else callOriginal()
            }
            coEvery { f.dao.recuperarResultadoPorLlaveTxQ15(any(),any(),any(),any(),any()) } coAnswers {
                check(!inTransaction)
                inTransaction = true
                try {
                    f.db.autoCommit = false
                    try { val count = callOriginal(); f.db.commit(); live.value=read(); count }
                    catch (error: Throwable) { f.db.rollback(); throw error }
                } finally { f.db.autoCommit=true; inTransaction=false }
            }
            coEvery { f.dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } answers {
                if (failResultWrites > 0) { failResultWrites--; throw java.io.IOException("owned-local-disk-failure") }
                val count=f.sql(Q15_PAYMENT_RESULT_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),
                    "aplicado" to if(arg<Boolean>(2)) 1 else 0,"motivo" to arg<String?>(3),"resultadoJson" to arg<String?>(4),"ahora" to arg<Long>(5)))
                if (!inTransaction) live.value=read()
                count
            }
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    sent += request.method.orEmpty() to request.path.orEmpty()
                    requests += request
                    beforeReply?.invoke()
                    return reply?.invoke(request) ?: MockResponse().setResponseCode(code).setBody(response)
                }
            }
        }
        fun attachNotices(): com.avoqado.pos.sync.SalesNoticeSqlTest.Fixture {
            // Both real DAOs use this same owned JDBC connection; no copy of the recovered payment into another store.
            val notices=com.avoqado.pos.sync.SalesNoticeSqlTest.Fixture()
            f.db.close(); f.db=notices.db
            val entities=JSONObject(File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/19.json").readText())
                .getJSONObject("database").getJSONArray("entities")
            val entity=(0 until entities.length()).map {entities.getJSONObject(it)}.single {it.getString("tableName")=="pending_payments"}
            f.sql("DROP TABLE pending_payments"); f.sql(entity.getString("createSql").replace("\${TABLE_NAME}","pending_payments"))
            return notices
        }
        fun save(row: PendingPaymentEntity) {
            val values = PendingPaymentEntity::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .associate { it.isAccessible = true; it.name to it.get(row) }
            f.sql("INSERT OR REPLACE INTO pending_payments (${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { ":$it" }})", values)
            live.value=read()
        }
        fun read(id: String = KEY) = f.query("SELECT * FROM pending_payments WHERE id=:id", mapOf("id" to id)).singleOrNull()?.let(::decode)
        suspend fun first() { f.service.start(); f.service.esperarSincronizacionActualQ16() }
        suspend fun cycle() { f.service.syncNow(); f.service.esperarSincronizacionActualQ16() }
        fun exactGetOnly(count: Int = 1) {
            assertEquals(List(count) { "GET" to "/api/v1/mobile/venues/${Q16PrecioCapturadoTest.VENUE}/payments/by-key/$KEY" }, sent.toList())
            for(request in requests) {
                assertEquals("true",request.getHeader("X-Avoqado-Background"))
                assertNull(request.getHeader("X-Permission-Override")); assertEquals(0L,request.bodySize)
            }
        }
        override fun close() { f.service.stop(); owner.cancel(); runBlocking { owner.join() }; f.close() }
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
                cashDrawerSessionId = s("cashDrawerSessionId"), lineasDelTicketJson = s("lineasDelTicketJson"))
        }
    }

    // The current bug is caught by the durable status assertion, before any expected new API is referenced.
    @Test fun recovered_ORDER_payment_is_SYNCED_after_cold_start_without_reposting() = runBlocking {
        Harness().use { h ->
            val original = source(); h.save(original); h.f.reopen()
            h.first()
            val actual = requireNotNull(h.read())
            assertEquals("An exact server payment must release the retained local result", "SYNCED", actual.syncStatus)
            h.exactGetOnly()
            assertNotNull(actual.q15InitialResultJson); assertEquals(actual.q15InitialResultJson, actual.q15ResultJson)
            val result = JSONObject(actual.q15ResultJson!!)
            assertEquals(PAYMENT, result.getString("paymentId")); assertEquals(1999, result.getInt("amountCents")); assertEquals(1, result.getInt("tipCents"))
            assertEquals(0, result.getInt("saldoCents")); assertEquals("PAID", result.getString("estadoDeLaOrden"))
            assertEquals(original.copy(syncStatus="SYNCED", q15InitialResultJson=actual.q15InitialResultJson, q15ResultJson=actual.q15ResultJson), actual)
            h.f.reopen(); assertEquals(actual, h.read()); h.cycle(); h.exactGetOnly()
        }
    }

    @Test fun recovered_FAST_with_items_keeps_original_split_and_exact_1999_plus_one_cents() = runBlocking {
        Harness().use { h ->
            val original = source("FAST", split="CUSTOM_AMOUNT"); h.save(original)
            h.response = proof(type="FAST")
            h.first()
            val actual = requireNotNull(h.read())
            assertEquals("SYNCED", actual.syncStatus); h.exactGetOnly()
            val result = JSONObject(actual.q15ResultJson!!)
            assertEquals(1999, result.getInt("amountCents")); assertEquals(1, result.getInt("tipCents"))
            assertEquals(0, result.getJSONArray("camposDiferentes").length())
            assertTrue(result.getJSONArray("camposNoComprobados").toString().contains("splitType"))
            assertEquals(original.copy(syncStatus="SYNCED", q15InitialResultJson=actual.q15InitialResultJson, q15ResultJson=actual.q15ResultJson), actual)
        }
    }

    @Test fun negative_http_or_absent_payment_never_posts_or_spends_money_retries() = runBlocking {
        for ((code, response) in listOf(200 to "{\"success\":true,\"data\":null}", 404 to "{}", 401 to "{}", 403 to "{}", 503 to "{}")) Harness().use { h ->
            val original=source(); h.save(original); h.code=code; h.response=response
            h.first()
            assertEquals("HTTP $code", original, h.read()); h.exactGetOnly()
            h.cycle(); assertEquals(original, h.read()); h.exactGetOnly(2)
        }
    }

    @Test fun invalid_proof_never_releases_the_original_or_posts() = runBlocking {
        val invalid: List<Pair<String, Any?>> = listOf("idempotencyKey" to "other-key", "venueId" to "other-venue", "paymentId" to "",
            "orderId" to "other-order", "type" to "FAST", "status" to "PENDING", "method" to "CARD", "tenderTypeId" to "cash-like",
            "amountCents" to true, "amountCents" to 1999.5, "amountCents" to -1, "amountCents" to 2147483648L,
            "tipCents" to 2147483647, "cashRetainedCents" to 1999, "type" to "REFUND", "type" to "TEST",
            "splitType" to "", "orderExternalId" to true)
        for ((field, value) in invalid) Harness().use { h ->
            val original=source(); h.save(original)
            h.response=JSONObject(proof()).apply { getJSONObject("data").put(field,value) }.toString()
            h.first()
            assertEquals("$field=$value", original, h.read()); h.exactGetOnly()
        }
    }

    @Test fun result_or_different_hold_or_non_cash_is_not_automatically_adopted() = runBlocking {
        val invalid: List<(PendingPaymentEntity) -> PendingPaymentEntity> = listOf(
            { it.copy(q15ResultJson="{}") }, { it.copy(q15InitialResultJson="{}") },
            { it.copy(q15Reason=ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA) },
            { it.copy(q15CaptureJson=null) }, { it.copy(q15ReviewedAt=100) },
            { it.copy(method="CARD") }, { it.copy(tenderTypeId="custom-tender", tenderRevision=1) },
            { it.copy(tenderRevision=1) }, {it.copy(capturedOrderRef="other-reference")},
            {it.copy(q15CaptureJson=it.q15CaptureJson+" ")},
            {it.copy(customerId="different-customer")}, {it.copy(cashTenderedCents=4000)}, {it.copy(changeCents=2000)})
        for (change in invalid) Harness().use { h ->
            val original=change(source()); h.save(original); h.first()
            assertEquals(original,h.read()); assertTrue(h.sent.isEmpty())
        }
    }


    @Test fun two_failed_local_saves_then_cold_start_recover_using_only_GET() = runBlocking {
        Harness().use { h ->
            val original=source(); h.save(original); h.failResultWrites=2
            assertTrue(h.f.service.handleSyncResult(original,200,"""{"success":true,"payment":{"paymentId":"$PAYMENT","orderId":"$ORDER","amount":1999,"tipAmount":1,"status":"COMPLETED"}}"""))
            assertEquals(original,h.read()); assertEquals(0,h.failResultWrites)
            h.f.reopen(); h.first()
            assertEquals("SYNCED",h.read()!!.syncStatus); h.exactGetOnly()
        }
    }

    @Test fun a_second_disk_failure_keeps_original_and_next_trigger_only_queries_again() = runBlocking {
        Harness().use { h ->
            val original=source(); h.save(original); h.failResultWrites=2
            h.first(); assertEquals(original,h.read()); h.exactGetOnly()
            h.cycle(); assertEquals(original,h.read()); h.exactGetOnly(2)
            h.f.reopen(); h.cycle()
            assertEquals("SYNCED",h.read()!!.syncStatus); h.exactGetOnly(3)
            assertEquals(original.q15DetectedAt,h.read()!!.q15DetectedAt)
        }
    }

    @Test fun complete_source_CAS_rejects_a_change_to_every_persisted_field_during_lookup() = runBlocking {
        val fields=PendingPaymentEntity::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        assertEquals(fields,mutations().map { it.first }.toSet())
        for ((field,change) in mutations()) Harness().use { h ->
            val original=source(); h.save(original); val newer=change(original)
            h.beforeTransaction={ h.f.sql("DELETE FROM pending_payments WHERE id=:id",mapOf("id" to KEY)); h.save(newer) }
            h.first()
            assertEquals(field,newer,h.read(newer.id)); h.exactGetOnly()
        }
    }

    @Test fun context_lost_during_GET_cannot_persist_and_does_not_spend_retries() = runBlocking {
        for (field in listOf("venue","actor","epoch","offline")) Harness().use { h ->
            val original=source(); h.save(original)
            h.beforeReply={ when(field) {
                "venue" -> h.venue="another-venue"
                "actor" -> h.actor="another-operator"
                "epoch" -> h.epoch++
                else -> every { h.f.network.isConnected } returns MutableStateFlow(false)
            } }
            h.first(); assertEquals(field,original,h.read()); h.exactGetOnly()
        }
    }

    @Test fun CAS_zero_does_not_claim_recovered_or_erase_the_source() = runBlocking {
        Harness().use { h ->
            val original=source(); h.save(original); h.forceCasZero=true
            h.first(); assertEquals(original,h.read()); h.exactGetOnly()
            h.forceCasZero=false; h.cycle(); assertEquals("SYNCED",h.read()!!.syncStatus); h.exactGetOnly(2)
        }
    }

    @Test fun original_backoff_opening_and_order_dependencies_still_precede_lookup() = runBlocking {
        for (case in listOf("backoff","opening","intent")) Harness().use { h ->
            var original=source()
            if(case=="backoff") original=original.copy(lastRetryAt=System.currentTimeMillis())
            if(case=="opening") coEvery { h.f.drawer.cajasQueEsperanSuApertura() } returns setOf(BOX)
            if(case=="intent") coEvery { h.f.outbox.trabajoSinResolverDeLaOrden(any(),any()) } returns TrabajoSinResolver(1,0,setOf("ADD_ITEMS"),0)
            h.save(original); h.first(); assertEquals(case,original,h.read()); assertTrue(case,h.sent.isEmpty())
            coEvery { h.f.drawer.cajasQueEsperanSuApertura() } returns emptySet()
            coEvery { h.f.outbox.trabajoSinResolverDeLaOrden(any(),any()) } returns TrabajoSinResolver(0,0,emptySet(),0)
            h.save(original.copy(lastRetryAt=101)); h.cycle()
            assertEquals(case,"SYNCED",h.read()!!.syncStatus); h.exactGetOnly()
        }
    }

    @Test fun HTTP_failure_retains_dependents_but_not_independent_orders_and_queries_once_per_cycle() = runBlocking {
        for (code in listOf(404,503,401)) Harness().use { h ->
            h.code=code; val original=source(); h.save(original)
            fun another(key:String,order:String): PendingPaymentEntity {
                val request=original.orderRequestJson!!.replace(KEY,key)
                return original.copy(id=key,orderId=order,capturedOrderRef=order,orderRequestJson=request,
                    q15CaptureJson=ConciliacionDelCobro.guardarCaptura(ConciliacionDelCobro.leerCaptura(original.q15CaptureJson,original.venueId,original.id)!!.copy(key=key,orderId=order,orderRequestJson=request)))
            }
            val dependent=another("q15-dependent",ORDER).copy(createdAt=101)
            val independent=another("q15-independent","independent-order").copy(createdAt=102)
            h.save(dependent); h.save(independent); h.first()
            assertEquals(original,h.read()); assertEquals(dependent,h.read(dependent.id)); assertEquals(independent,h.read(independent.id))
            assertEquals(if(code==401) 1 else 2,h.sent.size)
            assertTrue(h.sent.all { it.first=="GET" && !it.second.endsWith("/q15-dependent") })
            assertEquals(1,h.sent.count { it.second.endsWith("/$KEY") })
        }
    }

    @Test fun canonical_materialized_ORDER_uses_original_external_id_and_durable_local_alias_without_rewrite() = runBlocking {
        for (materialized in listOf(false,true)) Harness().use { h ->
            val old=source(); val capture=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
            val original=old.copy(orderId=if(materialized) ORDER else "local-order",capturedOrderRef="local-order",
                q15CaptureJson=ConciliacionDelCobro.guardarCaptura(capture.copy(orderId=if(materialized) null else "local-order",localOrderId="local-order")))
            coEvery { h.f.outbox.aliasDurableQ16(original.venueId,"local-order") } returns ORDER
            h.save(original); h.first(); val actual=h.read()!!
            assertEquals("SYNCED",actual.syncStatus); h.exactGetOnly()
            assertEquals(ORDER,JSONObject(actual.q15ResultJson!!).getString("orderId"))
            assertEquals(original.orderId,actual.orderId); assertEquals(original.q15CaptureJson,actual.q15CaptureJson)
            assertEquals(original.orderRequestJson,actual.orderRequestJson)
        }
    }

    @Test fun real_offline_ORDER_producer_needs_no_alias_when_original_has_only_external_identity() = runBlocking {
        Harness().use { h ->
            coEvery { h.f.dao.insert(any()) } coAnswers {callOriginal()}
            coEvery { h.f.dao.insertTransactionQ16(any()) } coAnswers {callOriginal()}
            coEvery { h.f.dao.insertRaw(any()) } coAnswers {callOriginal()}
            coEvery { h.f.dao.insertRawSqlQ16(any()) } answers {h.save(firstArg());Unit}
            h.f.venta(KEY)
            val original=h.read()!!.copy(q15Reason=ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,q15DetectedAt=102)
            h.save(original)
            assertNull(original.orderId)
            assertNull(ConciliacionDelCobro.leerCaptura(original.q15CaptureJson,original.venueId,original.id)!!.orderId)
            h.response=JSONObject(proof()).apply { getJSONObject("data").put("amountCents",10000).put("tipCents",500).put("cashRetainedCents",10500) }.toString()
            h.first(); val actual=h.read()!!
            assertEquals("SYNCED",actual.syncStatus); h.exactGetOnly()
            assertNull(actual.orderId); assertEquals(original.q15CaptureJson,actual.q15CaptureJson)
            assertEquals(ORDER,JSONObject(actual.q15ResultJson!!).getString("orderId"))
        }
    }

    @Test fun alias_or_external_id_mismatch_does_not_adopt_an_unrelated_payment() = runBlocking {
        for(case in listOf("alias","external")) Harness().use { h ->
            val old=source(); val original=if(case=="alias") old.copy(orderId="unbound-local") else old
            h.save(original)
            if(case=="external") h.response=JSONObject(proof()).apply { getJSONObject("data").put("orderExternalId","another-sale") }.toString()
            h.first(); assertEquals(original,h.read()); h.exactGetOnly()
        }
    }

    @Test fun Rappi_keeps_205_from_300_with_95_change_and_real_remote_tender_without_false_difference() = runBlocking {
        for(third in listOf(1000,0)) Harness().use { h ->
            val old=source(); val retained=if(third==1000) 20500 else 19500
            val c=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
            val original=old.copy(amountCents=retained,tipCents=0,cashTenderedCents=30000,changeCents=30000-retained,
                q15CaptureJson=ConciliacionDelCobro.guardarCaptura(c.copy(amountCents=retained,tipCents=0,cashTenderedCents=30000,changeCents=30000-retained)))
            h.save(original)
            h.response=JSONObject(proof()).apply { getJSONObject("data").apply {
                put("amountCents",17500); put("tipCents",2000); put("cashRetainedCents",retained)
                put("tenderTypeId","rappi-channel"); put("tenderRevision",7)
                put("deliveryCashAllocation",JSONObject().put("saleCents",17500).put("tipCents",2000).put("thirdPartyCents",third))
            } }.toString()
            h.first(); val actual=h.read()!!
            assertEquals("SYNCED",actual.syncStatus); h.exactGetOnly()
            val result=JSONObject(actual.q15ResultJson!!)
            assertEquals(0,result.getJSONArray("camposDiferentes").length())
            assertEquals("rappi-channel",result.getString("tenderTypeId")); assertEquals(7,result.getInt("tenderRevision"))
            assertEquals(17500,result.getInt("amountCents")); assertEquals(2000,result.getInt("tipCents"))
            assertEquals(30000,actual.cashTenderedCents); assertEquals(30000-retained,actual.changeCents)
            assertEquals(original.q15CaptureJson,actual.q15CaptureJson); assertNull(actual.tenderTypeId); assertNull(actual.tenderRevision)
        }
    }

    @Test fun FAST_Int32_limit_is_converted_to_exact_pesos_text_without_rounding() = runBlocking {
        Harness().use { h ->
            val old=source("FAST"); val c=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
            val original=old.copy(amountCents=Int.MAX_VALUE,tipCents=0,cashTenderedCents=Int.MAX_VALUE,changeCents=0,
                q15CaptureJson=ConciliacionDelCobro.guardarCaptura(c.copy(amountCents=Int.MAX_VALUE,tipCents=0,cashTenderedCents=Int.MAX_VALUE,changeCents=0)))
            h.save(original)
            h.response=JSONObject(proof("FAST")).apply { getJSONObject("data").apply { put("amountCents",Int.MAX_VALUE); put("tipCents",0); put("cashRetainedCents",Int.MAX_VALUE) } }.toString()
            h.first(); val actual=h.read()!!
            assertEquals("SYNCED",actual.syncStatus); h.exactGetOnly()
            assertEquals(Int.MAX_VALUE,JSONObject(actual.q15ResultJson!!).getInt("amountCents"))
        }
    }

    @Test fun a_real_amount_difference_is_recorded_without_erasing_original_reason_or_capture() = runBlocking {
        Harness().use { h ->
            val original=source(); h.save(original)
            h.response=JSONObject(proof()).apply { getJSONObject("data").put("amountCents",1900).put("cashRetainedCents",1901) }.toString()
            h.first(); val actual=h.read()!!
            assertEquals("SYNCED",actual.syncStatus); h.exactGetOnly()
            assertTrue(JSONObject(actual.q15ResultJson!!).getJSONArray("camposDiferentes").toString().contains("amountCents"))
            assertEquals(original.q15Reason,actual.q15Reason); assertEquals(original.q15DetectedAt,actual.q15DetectedAt)
            assertEquals(original.q15CaptureJson,actual.q15CaptureJson)
        }
    }

    @Test fun no_balance_pair_or_non_drawer_flow_can_release_a_recovered_payment() = runBlocking {
        val invalid:List<(JSONObject)->Unit> = listOf(
            {it.remove("remainingBalanceCents")}, {it.remove("orderPaymentStatus")},
            {it.put("remainingBalanceCents",true)}, {it.put("remainingBalanceCents",0.5)}, {it.put("remainingBalanceCents",2147483648L)},
            {it.put("orderPaymentStatus","PENDING")},
            {it.put("fundsFlow","EXTERNAL_RECORDED")}, {it.put("tenderRevision",1)},
            {it.put("tenderTypeId","rappi").put("tenderRevision",1)},
            {it.put("deliveryCashAllocation",JSONObject().put("saleCents",1998).put("tipCents",1).put("thirdPartyCents",1))})
        for(change in invalid) Harness().use { h ->
            val original=source(); h.save(original); h.response=JSONObject(proof()).apply { change(getJSONObject("data")) }.toString()
            h.first(); assertEquals(original,h.read()); h.exactGetOnly()
        }
    }

    @Test fun recovered_current_order_snapshot_reaches_real_repository_part_observer() = runBlocking {
        for((saldo,estado) in listOf(0 to "PAID",9500 to "PARTIAL",9500 to "PAID",0 to "PARTIAL")) Harness().use { h ->
            val original=source(); h.save(original)
            h.response=JSONObject(proof()).apply { getJSONObject("data").put("remainingBalanceCents",saldo).put("orderPaymentStatus",estado)
                .put("orderTotalCents",990000).put("totalPaidCents",600000) }.toString()
            val repository=CashPaymentRepository(h.f.storage,h.f.dao)
            assertEquals(EstadoDeParte.PorRevisar,repository.observarParte(KEY).first())
            h.first()
            assertEquals(EstadoDeParte.Subida(ORDER,saldo,estado=="PAID"),repository.observarParte(KEY).first())
            val result=JSONObject(h.read()!!.q15ResultJson!!)
            assertEquals(0,result.getJSONArray("camposDiferentes").length())
            assertFalse(result.has("totalAlCobrarCents")); assertFalse(result.has("totalEnElServidorCents"))
            h.exactGetOnly()
        }
    }

    @Test fun GET_result_invalidates_old_ACK_and_new_Entendido_clears_banner_without_financial_review() = runBlocking {
        Harness().use { h ->
            val notices=h.attachNotices(); val original=source(); h.save(original)
            val old=notices.dao.page(original.venueId,"",null,emptySet()).rows.single()
            assertTrue(notices.write {notices.dao.acknowledge(old,"old-reader",10,emptySet())})
            assertEquals(0,notices.dao.summary(original.venueId,emptySet()).unseenCount)
            h.first(); val applied=h.read()!!
            assertEquals("SYNCED",applied.syncStatus); h.exactGetOnly()
            val current=notices.dao.page(original.venueId,"",null,emptySet()).rows.single()
            assertNull(current.acknowledgement); assertEquals(1,notices.dao.summary(original.venueId,emptySet()).unseenCount)
            assertFalse(notices.write {notices.dao.acknowledge(old,"current-reader",20,emptySet())})
            val economic=notices.economic()
            assertTrue(notices.write {notices.dao.acknowledge(current,"current-reader",20,emptySet())})
            val summary=notices.dao.summary(original.venueId,emptySet())
            assertEquals(0,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(summary,0,99))
            assertEquals(economic,notices.economic()); assertEquals(applied,h.read()); assertNull(h.read()!!.q15ReviewedAt)
            assertEquals(original.q15Reason,h.read()!!.q15Reason); assertEquals(original.q15DetectedAt,h.read()!!.q15DetectedAt)
            assertEquals(original.q15CaptureJson,h.read()!!.q15CaptureJson)
        }
    }

    companion object {
        private fun mutations(): List<Pair<String,(PendingPaymentEntity)->PendingPaymentEntity>> = listOf(
            "id" to {it.copy(id="new-key")}, "venueId" to {it.copy(venueId="new-venue")}, "staffId" to {it.copy(staffId="new-actor")},
            "amountCents" to {it.copy(amountCents=2000)}, "tipCents" to {it.copy(tipCents=2)}, "method" to {it.copy(method="CARD")},
            "paymentType" to {it.copy(paymentType="FAST")}, "orderId" to {it.copy(orderId="new-order")}, "orderNumber" to {it.copy(orderNumber="new-number")},
            "cashTenderedCents" to {it.copy(cashTenderedCents=4000)}, "changeCents" to {it.copy(changeCents=2000)}, "rating" to {it.copy(rating=5)},
            "itemsJson" to {it.copy(itemsJson="[]")}, "orderRequestJson" to {it.copy(orderRequestJson=it.orderRequestJson+" ")},
            "syncStatus" to {it.copy(syncStatus="FAILED")}, "retryCount" to {it.copy(retryCount=9)}, "lastError" to {it.copy(lastError="new-diagnosis")},
            "createdAt" to {it.copy(createdAt=103)}, "lastRetryAt" to {it.copy(lastRetryAt=104)}, "tenderTypeId" to {it.copy(tenderTypeId="local-tender")},
            "tenderRevision" to {it.copy(tenderRevision=1)}, "customerId" to {it.copy(customerId="new-customer")}, "splitType" to {it.copy(splitType="CUSTOM_AMOUNT")},
            "q15CaptureJson" to {it.copy(q15CaptureJson=it.q15CaptureJson+" ")}, "q15InitialResultJson" to {it.copy(q15InitialResultJson="{}")},
            "q15ResultJson" to {it.copy(q15ResultJson="{}")}, "q15Reason" to {it.copy(q15Reason="other-reason")}, "q15DetectedAt" to {it.copy(q15DetectedAt=105)},
            "q15ReviewedAt" to {it.copy(q15ReviewedAt=106)}, "q15ReviewedBy" to {it.copy(q15ReviewedBy="new-reviewer")}, "q15ReviewRole" to {it.copy(q15ReviewRole="OWNER")},
            "capturedOrderRef" to {it.copy(capturedOrderRef="new-reference")}, "cashDrawerSessionId" to {it.copy(cashDrawerSessionId="new-drawer")},
            "lineasDelTicketJson" to {it.copy(lineasDelTicketJson="{}")})

        internal const val KEY="q15-owned-recovery-key"
        internal const val ORDER="q15-owned-order"
        internal const val PAYMENT="q15-owned-payment"
        private const val BOX="q15-original-box"
        internal fun source(kind:String="ORDER", split:String="FULLPAYMENT"): PendingPaymentEntity {
            val request=if(kind=="ORDER") "{\"externalId\":\"$KEY\",\"items\":[{\"productId\":\"original-product\",\"quantity\":1}],\"total\":2000,\"tip\":1}" else null
            val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(Q16PrecioCapturadoTest.VENUE,KEY,if(kind=="ORDER") ORDER else null,1999,1,
                splitType=split,method="CASH",cashTenderedCents=3000,changeCents=1000,customerId="original-customer",orderRequestJson=request,
                daPorCobradaAlAbandonar=true,primeraCapturaEn=100,ventasYaEnLaCaja=listOf("older-payment")))
            return PendingPaymentEntity(KEY,Q16PrecioCapturadoTest.VENUE,"original-operator",1999,1,"CASH",kind,
                orderId=if(kind=="ORDER") ORDER else null,orderNumber="ORIGINAL-9",cashTenderedCents=3000,changeCents=1000,rating=4,
                itemsJson=if(kind=="FAST") "[{\"productId\":\"original-product\",\"quantity\":1}]" else null,orderRequestJson=request,
                retryCount=2,lastError="No se pudo conservar el resultado",createdAt=100,lastRetryAt=101,customerId="original-customer",splitType=split,
                q15CaptureJson=capture,q15Reason=ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,q15DetectedAt=102,capturedOrderRef=if(kind=="ORDER") ORDER else null,
                cashDrawerSessionId=BOX,lineasDelTicketJson="{\"version\":1,\"lineas\":[]}")
        }
        internal fun proof(type:String="REGULAR")="""{"success":true,"data":{"idempotencyKey":"$KEY","venueId":"${Q16PrecioCapturadoTest.VENUE}","paymentId":"$PAYMENT","orderId":"$ORDER","orderExternalId":"$KEY","type":"$type","status":"COMPLETED","method":"CASH","fundsFlow":"CASH_DRAWER","tenderTypeId":null,"tenderRevision":null,"amountCents":1999,"tipCents":1,"cashRetainedCents":2000,"splitType":"FULLPAYMENT","remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}"""
    }
}
