package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the independent replay parser with the original durable JSON and real HTTP/SQLite. */
@OptIn(ExperimentalCoroutinesApi::class)
class CortesiaReplayContractTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val request = """{"externalId":"venta-cortesia","staffId":"staff-A","subtotal":11000,"tip":0,"total":11000,"items":[{"productId":"bebida-A","externalId":"linea-comp","quantity":1,"modifierIds":["extra-A","extra-B"],"isCortesia":true,"cortesiaReason":"Invitación de la casa"},{"productId":"bebida-B","externalId":"linea-normal","quantity":1,"modifierIds":["extra-C"]}]}"""
    private val confirmed = """{"success":true,"data":{"id":"orden-servidor","total":110,"items":[{"id":"item-A","externalId":"linea-comp","isCortesia":true,"cortesiaReason":"Invitación de la casa"},{"id":"item-B","externalId":"linea-normal","isCortesia":false,"cortesiaReason":null}]}}"""

    private fun Q16PrecioCapturadoTest.Fixture.seedComp(existingOrderId: String? = null) {
        val capture = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            Q16PrecioCapturadoTest.VENUE, "cobro-cortesia", null, 11000, 0,
            method = "CASH", splitType = "FULLPAYMENT", orderRequestJson = request,
        ))
        insertCaptured(PendingPaymentEntity(
            "cobro-cortesia", Q16PrecioCapturadoTest.VENUE, "staff-A", 11000, 0, "CASH", "ORDER",
            orderId=existingOrderId, orderRequestJson = request, q15CaptureJson = capture, splitType = "FULLPAYMENT",
        ))
    }

    private suspend fun noFirstPayment(body: String, existingOrderId: String? = null) {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.seedComp(existingOrderId)
            val original = f.row("cobro-cortesia")
            val sent = mutableListOf<Pair<String, String>>()
            f.server.dispatcher = dispatcher(body, sent)
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            f.service.start()
            f.service.esperarSincronizacionActualQ16()
            val after = f.row("cobro-cortesia")
            assertEquals("Una respuesta sin acreditar cortesía no puede crear un abono", listOf("orders"), sent.map { it.first })
            assertNotEquals("SYNCED", after["syncStatus"])
            assertEquals(original["original"], after["original"])
            assertEquals(original["q15CaptureJson"], after["q15CaptureJson"])
            assertEquals("venta-cortesia", JSONObject(sent.single().second).getString("externalId"))
        }
    }

    @Test fun `backend viejo no convierte cortesia parcial en abono silencioso`() = runTest {
        noFirstPayment("""{"success":true,"data":{"id":"orden-servidor","total":220}}""")
    }

    @Test fun `orden ya resuelta tambien exige acreditacion fresh antes de pay`() = runTest {
        noFirstPayment("""{"success":true,"data":{"id":"orden-servidor","total":220}}""",existingOrderId="orden-servidor")
    }

    @Test fun `dedup no puede cambiar identidad de orden ya persistida`() = runTest {
        noFirstPayment(JSONObject(confirmed).apply { getJSONObject("data").put("id","otra-orden") }.toString(),existingOrderId="orden-servidor")
    }

    @Test fun `total cero no sustituye flags e identidad persistidos`() = runTest {
        noFirstPayment("""{"success":true,"data":{"id":"orden-servidor","total":0}}""")
    }

    @Test fun `dedup con otra identidad de linea conserva el cobro sin enviarlo`() = runTest {
        noFirstPayment(JSONObject(confirmed).apply {
            getJSONObject("data").getJSONArray("items").getJSONObject(0).put("externalId", "otra-linea")
        }.toString())
    }

    @Test fun `dedup sin la cortesia prometida conserva el cobro sin enviarlo`() = runTest {
        noFirstPayment(JSONObject(confirmed).apply {
            getJSONObject("data").getJSONArray("items").getJSONObject(0).put("isCortesia", false)
        }.toString())
    }

    @Test fun `dedup con otra razon no acredita la intencion original`() = runTest {
        noFirstPayment(JSONObject(confirmed).apply {
            getJSONObject("data").getJSONArray("items").getJSONObject(0).put("cortesiaReason", "Otro motivo")
        }.toString())
    }

    @Test fun `identidad de cortesia duplicada invalida la confirmacion`() = runTest {
        noFirstPayment(JSONObject(confirmed).apply {
            val items = getJSONObject("data").getJSONArray("items")
            items.put(JSONObject(items.getJSONObject(0).toString()))
        }.toString())
    }

    @Test fun `confirmacion exacta permite un pago del saldo previsto con misma llave y captura`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.seedComp()
            val original = f.row("cobro-cortesia")
            val sent = mutableListOf<Pair<String, String>>()
            f.server.dispatcher = dispatcher(confirmed, sent)
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            f.service.start()
            f.service.esperarSincronizacionActualQ16()
            assertEquals(listOf("orders", "pay"), sent.map { it.first })
            val payment = JSONObject(sent.last().second)
            assertEquals("cobro-cortesia", payment.getString("idempotencyKey"))
            assertEquals(11000, payment.getInt("amount"))
            assertEquals(0, payment.getInt("tip"))
            val after = f.row("cobro-cortesia")
            assertEquals("SYNCED", after["syncStatus"])
            assertEquals(original["original"], after["original"])
            assertEquals(original["q15CaptureJson"], after["q15CaptureJson"])
        }
    }

    private fun dispatcher(orderResponse: String, sent: MutableList<Pair<String, String>>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val kind = if (path.endsWith("/orders")) "orders" else if (path.endsWith("/pay")) "pay" else "unexpected"
            synchronized(sent) { sent += kind to request.body.readUtf8() }
            val body = if (kind == "orders") orderResponse else """{"success":true,"payment":{"paymentId":"pago-servidor","orderId":"orden-servidor","status":"COMPLETED","method":"CASH","amount":11000,"tipAmount":0,"changeCents":0,"orderTotalCents":11000,"remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}"""
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }
}
