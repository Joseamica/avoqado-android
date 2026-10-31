package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.network.ConnectivityInterceptor
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real ConnectivityInterceptor reports into the Android-platform monitor double; HTTP/drain/SQL are real. */
class Q15RecoveryConnectivityTest {
    @get:Rule val main=MainDispatcherRule()
    @get:Rule val timeout=org.junit.rules.Timeout.seconds(60)

    private class Fixture:AutoCloseable {
        val h=Q15RecoveryByKeyTest.Harness()
        val reachable=MutableStateFlow(true)
        val connected=MutableStateFlow(true)
        val reports=CopyOnWriteArrayList<Boolean>()
        private val owner=SupervisorJob()
        val service:PaymentSyncService
        init {
            every {h.f.network.isConnected} returns connected
            every {h.f.network.isServerReachable} returns reachable
            // Only the Android monitor's platform boundary is doubled; the production interceptor chooses these reports.
            every {h.f.network.reportServerError()} answers {reachable.value=false;reports+=false;Unit}
            every {h.f.network.reportServerSuccess()} answers {reachable.value=true;reports+=true;Unit}
            val client=h.f.client.newBuilder().retryOnConnectionFailure(false)
                .addInterceptor(ConnectivityInterceptor(h.f.network)).build()
            service=PaymentSyncService(h.f.dao,h.f.storage,client,h.f.network,h.f.drawer,h.f.outbox,mockk(relaxed=true))
                .also {it.usarContextoDeSincronizacion(owner+Dispatchers.Unconfined)}
        }
        suspend fun first(){service.start();service.esperarSincronizacionActualQ16();service.stop()}
        override fun close(){service.stop();owner.cancel();runBlocking {owner.join()};h.close()}
    }

    @Test fun real_interceptor_503_only_retains_that_result_and_allows_other_order_lookup_and_money() = runBlocking {
        for(code in listOf(503,500,502)) Fixture().use { f ->
            val held=row(0);val positive=row(1);val normal=row(2,held=false)
            listOf(held,positive,normal).forEach(f.h::save)
            val positiveWasReportedOffline=CopyOnWriteArrayList<Boolean>()
            f.h.reply={request ->
                when {
                    request.method=="GET" && request.path.orEmpty().endsWith("/${held.id}") ->
                        MockResponse().setResponseCode(code).setBody("{\"success\":false}").apply {
                            if(code==502) setHeader("ngrok-error-code","ERR_NGROK_3200")
                        }
                    request.method=="GET" && request.path.orEmpty().endsWith("/${positive.id}") -> {
                        positiveWasReportedOffline+=!f.reachable.value
                        MockResponse().setBody(JSONObject(Q15RecoveryByKeyTest.proof()).apply {getJSONObject("data").apply {
                            put("idempotencyKey",positive.id);put("orderId",positive.orderId);put("orderExternalId",positive.id)
                        }}.toString())
                    }
                    request.method=="POST" && request.path.orEmpty().endsWith("/pay") -> {
                        val body=JSONObject(request.body.clone().readUtf8())
                        MockResponse().setBody(JSONObject().put("success",true).put("payment",JSONObject()
                            .put("paymentId","normal-payment").put("orderId",normal.orderId).put("venueId",normal.venueId)
                            .put("status","COMPLETED").put("method","CASH").put("splitType","FULLPAYMENT")
                            .put("amount",body.getInt("amount")).put("tipAmount",body.getInt("tip"))
                            .put("remainingBalanceCents",0).put("orderPaymentStatus","PAID")).toString())
                    }
                    else -> MockResponse().setResponseCode(500).setBody("{\"error\":\"unexpected\"}")
                }
            }
            f.first()
            assertEquals("A 5xx response is local to its retained result, not a global offline decision","SYNCED",f.h.read(normal.id)!!.syncStatus)
            assertEquals("SYNCED",f.h.read(positive.id)!!.syncStatus);assertEquals(held,f.h.read(held.id))
            assertEquals(listOf(true),positiveWasReportedOffline.toList())
            assertFalse(f.reports.first());assertTrue(f.reachable.value)
            assertEquals(2,f.h.sent.count {it.first=="GET"})
            assertEquals(listOf(normal.id),f.h.requests.filter {it.method=="POST"}.map {JSONObject(it.body.clone().readUtf8()).getString("idempotencyKey")})
        }
    }

    @Test fun real_transport_failure_or_401_still_pauses_the_whole_drain_without_reposting() = runBlocking {
        for(kind in listOf("transport","401")) Fixture().use { f ->
            val held=row(0);val positive=row(1);val normal=row(2,held=false)
            listOf(held,positive,normal).forEach(f.h::save)
            f.h.reply={if(kind=="transport") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else MockResponse().setResponseCode(401).setBody("{\"success\":false}")}
            f.first()
            assertEquals(held,f.h.read(held.id));assertEquals(positive,f.h.read(positive.id));assertEquals(normal,f.h.read(normal.id))
            assertEquals(listOf("GET"),f.h.sent.map {it.first})
            if(kind=="transport") assertEquals(listOf(false),f.reports.toList())
        }
    }

    private fun row(n:Int,held:Boolean=true):PendingPaymentEntity {
        val old=Q15RecoveryByKeyTest.source();val key="q15-network-$n";val order="network-order-$n"
        val pedido=JSONObject(old.orderRequestJson!!).put("externalId",key).toString()
        val c=ConciliacionDelCobro.leerCaptura(old.q15CaptureJson,old.venueId,old.id)!!
        return old.copy(id=key,orderId=order,capturedOrderRef=order,createdAt=System.currentTimeMillis()-60000+n,
            orderRequestJson=pedido,q15CaptureJson=ConciliacionDelCobro.guardarCaptura(c.copy(key=key,orderId=order,orderRequestJson=pedido)),
            q15Reason=if(held) old.q15Reason else null,q15DetectedAt=if(held) old.q15DetectedAt else null)
    }
}
