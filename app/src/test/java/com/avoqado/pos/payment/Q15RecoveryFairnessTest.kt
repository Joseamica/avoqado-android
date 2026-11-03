package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real drain, HTTP and SQLite: lookup work has its own budget and cannot starve unrelated monetary FIFO. */
class Q15RecoveryFairnessTest {
    @get:Rule val main=MainDispatcherRule()
    @get:Rule val timeout=org.junit.rules.Timeout.seconds(90)

    @Test fun ten_missing_results_do_not_starve_the_eleventh_or_the_independent_payment() = runBlocking {
        for(absence in listOf(404,200)) Q15RecoveryByKeyTest.Harness().use { h ->
            val held=(0..10).map { row(it) }; val normal=row(11,held=false)
            (held+normal).forEach(h::save); serve(h,held.last(),absence)
            h.first()
            assertEquals("Lookup has a separate cap even when the next candidate has a valid result",10,gets(h))
            assertEquals("SYNCED",h.read(normal.id)!!.syncStatus)
            assertEquals(listOf(normal.id),postedKeys(h))
            held.forEach {assertEquals(it,h.read(it.id))}
            val before=gets(h); h.cycle()
            assertEquals("The candidate after the first ten must be visited on the next cycle","SYNCED",h.read(held.last().id)!!.syncStatus)
            assertTrue(gets(h)-before<=10); assertEquals(listOf(normal.id),postedKeys(h))
            held.dropLast(1).forEach {assertEquals(it,h.read(it.id))}
        }
    }

    @Test fun backoff_or_work_on_one_retained_order_does_not_stop_other_lookups_or_money() = runBlocking {
        for(block in listOf("backoff","work")) Q15RecoveryByKeyTest.Harness().use { h ->
            var first=row(0); val positive=row(1); val normal=row(2,held=false)
            if(block=="backoff") first=first.copy(lastRetryAt=System.currentTimeMillis())
            if(block=="work") coEvery {h.f.outbox.trabajoSinResolverDeLaOrden(any(),any())} answers {
                if(first.orderId in secondArg<Set<String>>()) TrabajoSinResolver(1,0,setOf("ADD_ITEMS"),0)
                else TrabajoSinResolver(0,0,emptySet(),0)
            }
            listOf(first,positive,normal).forEach(h::save); serve(h,positive,404)
            h.first()
            assertEquals(block,"SYNCED",h.read(normal.id)!!.syncStatus)
            assertEquals(block,"SYNCED",h.read(positive.id)!!.syncStatus)
            assertEquals(block,first,h.read(first.id)); assertEquals(listOf(normal.id),postedKeys(h))
            assertEquals(1,gets(h))
        }
    }

    @Test fun ten_busy_candidates_advance_fairness_even_without_a_network_lookup() = runBlocking {
        for(block in listOf("work","backoff")) Q15RecoveryByKeyTest.Harness().use { h ->
            val now=System.currentTimeMillis()
            val busy=(0..9).map {row(it).let {p -> if(block=="backoff") p.copy(lastRetryAt=now) else p}}
            val positive=row(10); val normal=row(11,held=false)
            val busyOrders=busy.map {it.orderId}.toSet()
            if(block=="work") coEvery {h.f.outbox.trabajoSinResolverDeLaOrden(any(),any())} answers {
                if(secondArg<Set<String>>().any {it in busyOrders}) TrabajoSinResolver(1,0,setOf("ADD_ITEMS"),0)
                else TrabajoSinResolver(0,0,emptySet(),0)
            }
            (busy+positive+normal).forEach(h::save); serve(h,positive,404)
            h.first()
            assertEquals("Blocked lookups cannot block independent money","SYNCED",h.read(normal.id)!!.syncStatus)
            assertEquals(0,gets(h)); assertEquals(positive,h.read(positive.id)); assertEquals(listOf(normal.id),postedKeys(h))
            h.cycle()
            assertEquals("Cursor must advance for visited busy candidates, not only completed GETs","SYNCED",h.read(positive.id)!!.syncStatus)
            assertEquals(1,gets(h)); assertEquals(listOf(normal.id),postedKeys(h))
            busy.forEach {assertEquals(it,h.read(it.id))}
        }
    }

    @Test fun lookup_cap_is_shared_across_monetary_batches_and_internal_close_passes() = runBlocking {
        Q15RecoveryByKeyTest.Harness().use { h ->
            val held=(0..10).map {row(it)}; val normal=(11..35).map {row(it,held=false)}
            (held+normal).forEach(h::save); serve(h,null,404)
            h.first()
            assertEquals(10,gets(h)); assertEquals(normal.map {it.id},postedKeys(h))
            normal.forEach {assertEquals("SYNCED",h.read(it.id)!!.syncStatus)}
            held.forEach {assertEquals(it,h.read(it.id))}
        }
    }

    @Test fun foreign_retained_prefix_never_queries_or_pauses_the_active_venue() = runBlocking {
        Q15RecoveryByKeyTest.Harness().use { h ->
            val old=row(0); val c=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
            val foreign=old.copy(venueId="foreign-venue",createdAt=100,
                q15CaptureJson=ConciliacionDelCobro.guardarCaptura(c.copy(venueId="foreign-venue")))
            val positive=row(1); val normal=row(2,held=false)
            listOf(foreign,positive,normal).forEach(h::save); serve(h,positive,404)
            h.first()
            assertTrue(h.sent.none {it.first=="GET" && it.second.contains("/foreign-venue/")})
            assertEquals("Foreign retained evidence cannot pause active-venue money","SYNCED",h.read(normal.id)!!.syncStatus)
            assertEquals("SYNCED",h.read(positive.id)!!.syncStatus)
            assertEquals(foreign,h.read(foreign.id)); assertEquals(listOf(normal.id),postedKeys(h)); assertEquals(1,gets(h))
        }
    }

    @Test fun foreign_external_references_cannot_contaminate_active_venue_identity_barriers() = runBlocking {
        Q15RecoveryByKeyTest.Harness().use { h ->
            val positive=row(2); val normal=row(3,held=false)
            coEvery {h.f.dao.insert(any())} coAnswers {callOriginal()}
            coEvery {h.f.dao.insertTransactionQ16(any())} coAnswers {callOriginal()}
            coEvery {h.f.dao.insertRaw(any())} coAnswers {callOriginal()}
            coEvery {h.f.dao.insertRawSqlQ16(any())} answers {h.save(firstArg());Unit}
            // Real queue writer accepts an explicit original external id. Order's server unique is [venueId,externalId].
            // This is a tenant-isolation contract, not evidence that repeated external ids are common in production.
            suspend fun foreign(key:String,venue:String,external:String,at:Long):PendingPaymentEntity {
                val order=CreateOrderRequest(items=listOf(OrderItemRequest(productId="original-product",name="Artículo",quantity=1,unitPrice=1999)),
                    subtotal=1999,tip=1,total=2000,paymentMethod="CASH")
                CashPaymentRepository(h.f.storage,h.f.dao).queueCashPayment(order,"historical-operator",3000,1000,null,
                    orderId="order-$key",orderExternalId=external,idempotencyKey=key,amountCents=1999,tipCents=1,
                    venueId=venue,capturadaEn=at)
                return h.read(key)!!.copy(q15Reason=ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,q15DetectedAt=102).also(h::save)
            }
            val first=foreign("foreign-first","foreign-venue",positive.id,100)
            val third=foreign("foreign-third","third-venue",normal.id,101)
            h.save(positive); h.save(normal); serve(h,positive,404)
            h.first()
            assertEquals("Foreign external ids must not block active-venue order identities","SYNCED",h.read(normal.id)!!.syncStatus)
            assertEquals("SYNCED",h.read(positive.id)!!.syncStatus)
            assertEquals(first,h.read(first.id)); assertEquals(third,h.read(third.id))
            assertEquals(listOf(normal.id),postedKeys(h)); assertEquals(1,gets(h))
            assertTrue(h.sent.none {it.second.contains("/foreign-venue/") || it.second.contains("/third-venue/")})
        }
    }

    private fun serve(h:Q15RecoveryByKeyTest.Harness,positive:PendingPaymentEntity?,absence:Int) {
        h.reply={ request ->
            when {
                request.method=="GET" -> if(positive!=null && request.path.orEmpty().endsWith("/${positive.id}"))
                    MockResponse().setBody(JSONObject(Q15RecoveryByKeyTest.proof()).apply {getJSONObject("data").apply {
                        put("idempotencyKey",positive.id);put("orderId",positive.orderId);put("orderExternalId",positive.id)
                    }}.toString()) else MockResponse().setResponseCode(absence).setBody("{\"success\":true,\"data\":null}")
                request.method=="POST" && request.path.orEmpty().endsWith("/pay") -> {
                    val body=JSONObject(request.body.clone().readUtf8())
                    val order=request.path.orEmpty().substringBeforeLast("/pay").substringAfterLast('/')
                    MockResponse().setBody(JSONObject().put("success",true).put("payment",JSONObject()
                        .put("paymentId","payment-${body.getString("idempotencyKey")}").put("orderId",order)
                        .put("venueId",Q16PrecioCapturadoTest.VENUE).put("status","COMPLETED").put("method","CASH")
                        .put("amount",body.getInt("amount")).put("tipAmount",body.getInt("tip"))
                        .put("splitType","FULLPAYMENT").put("remainingBalanceCents",0).put("orderPaymentStatus","PAID")).toString())
                }
                else -> MockResponse().setResponseCode(500).setBody("{\"error\":\"unexpected-write\"}")
            }
        }
    }
    private fun gets(h:Q15RecoveryByKeyTest.Harness)=h.sent.count {it.first=="GET"}
    private fun postedKeys(h:Q15RecoveryByKeyTest.Harness)=h.requests.filter {it.method=="POST"}
        .map {JSONObject(it.body.clone().readUtf8()).getString("idempotencyKey")}
    private fun row(n:Int,held:Boolean=true):PendingPaymentEntity {
        val old=Q15RecoveryByKeyTest.source();val key="q15-fair-%02d".format(n);val order="q15-order-%02d".format(n)
        val pedido=JSONObject(old.orderRequestJson!!).put("externalId",key).toString()
        val c=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
        return old.copy(id=key,orderId=order,capturedOrderRef=order,createdAt=System.currentTimeMillis()-60000+n,
            orderRequestJson=pedido,q15CaptureJson=ConciliacionDelCobro.guardarCaptura(c.copy(key=key,orderId=order,orderRequestJson=pedido)),
            q15Reason=if(held) old.q15Reason else null,q15DetectedAt=if(held) old.q15DetectedAt else null)
    }
}
