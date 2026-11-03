package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The actual cart mapper, HTTP builder and durable writer; no test-only wire mapper. */
class CortesiaCreateOrderContractTest {
    private fun line(comp: Boolean = true, custom: Boolean = false) = CartItem(
        id = "linea-comp", type = if (custom) CartItemType.CustomAmount else CartItemType.ProductItem("bebida-A"),
        name = "Bebida A", unitPrice = 9000, quantity = 1,
        selectedModifiers = if (custom) emptyList() else listOf(
            SelectedModifier("extras", "Extras", "extra-A", "Extra A", 1000),
            SelectedModifier("extras", "Extras", "extra-B", "Extra B", 1000),
        ),
        isCortesia = comp, cortesiaReason = if (comp) "  Invitación de la casa  " else null,
        catalogAt = if (custom) null else "2026-10-23T12:00:00.000Z",
    )

    private fun order(lines: List<CartItem>) = CreateOrderRequest(
        items = buildOrderItemRequests(lines), subtotal = lines.sumOf { it.grossPrice },
        total = lines.sumOf { it.totalPrice }, paymentMethod = "CASH",
    )

    private fun payload(lines: List<CartItem>) = JSONObject(OrderRepository.buildCreateOrderPayload(
        order(lines), staffId = "staff-A", externalId = "venta-original",
    ))

    @Test fun `cortesia conserva identidad razon y todos los extras en el JSON real`() {
        val body = payload(listOf(line()))
        val items = body.getJSONArray("items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals("bebida-A", item.getString("productId"))
        assertEquals("linea-comp", item.getString("externalId"))
        assertEquals(listOf("extra-A", "extra-B"), (0 until item.getJSONArray("modifierIds").length()).map {
            item.getJSONArray("modifierIds").getString(it)
        })
        assertEquals(true, item.opt("isCortesia"))
        assertEquals("Invitación de la casa", item.opt("cortesiaReason"))
        assertEquals(0, body.getInt("total"))
    }

    @Test fun `cortesia sustituye descuento de linea sin descontar dos veces`() {
        val comp = line().copy(itemDiscountId = "descuento-A", itemDiscountType = "PERCENTAGE", itemDiscountValue = 20.0)
        val item = payload(listOf(comp)).getJSONArray("items").getJSONObject(0)
        assertFalse(item.toString(), item.has("discountId"))
        assertEquals(true, item.opt("isCortesia"))
        assertEquals(0, order(listOf(comp)).total)
    }

    @Test fun `importe libre de cortesia conserva la identidad local que ya tenia`() {
        val item = payload(listOf(line(custom = true))).getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("productId"))
        assertEquals("Bebida A", item.getString("name"))
        assertEquals(9000, item.getInt("unitPrice"))
        assertEquals("linea-comp", item.opt("externalId"))
        assertEquals(true, item.opt("isCortesia"))
        assertEquals("Invitación de la casa", item.opt("cortesiaReason"))
    }

    @Test fun `cortesia de producto pesado conserva peso cantidad y extras`() {
        val item = payload(listOf(line().copy(weightKg = 0.435))).getJSONArray("items").getJSONObject(0)
        assertEquals(1, item.getInt("quantity"))
        assertEquals(0.435, item.getDouble("weightQuantity"), 0.000001)
        assertEquals(2, item.getJSONArray("modifierIds").length())
        assertEquals(true, item.opt("isCortesia"))
        assertEquals("Invitación de la casa", item.opt("cortesiaReason"))
    }

    @Test fun `venta normal conserva el contrato sin campos de cortesia`() {
        val item = payload(listOf(line(comp = false))).getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("isCortesia"))
        assertFalse(item.has("cortesiaReason"))
        assertEquals("bebida-A", item.getString("productId"))
        assertEquals(11000, order(listOf(line(comp = false))).total)
    }

    @Test fun `la reserva y la cola persisten el mismo JSON completo antes de enviar`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val request = order(listOf(line(), line(comp = false).copy(id = "linea-normal")))
            val cash = CashPaymentRepository(f.storage, f.dao)
            cash.reservarCobro(request, "staff-A", 11000, 0, null, orderExternalId = "venta-original",
                idempotencyKey = "cobro-original", amountCents = 11000, tipCents = 0, venueId = Q16PrecioCapturadoTest.VENUE)
            val reserved = f.row("cobro-original")
            val saved = JSONObject(JSONObject(reserved["original"] as String).getString("orderRequest"))
            val comp = saved.getJSONArray("items").getJSONObject(0)
            assertEquals(true, comp.opt("isCortesia"))
            assertEquals("Invitación de la casa", comp.opt("cortesiaReason"))
            assertEquals(2, comp.getJSONArray("modifierIds").length())
            assertEquals(11000, saved.getInt("total"))
            val capture = ConciliacionDelCobro.leerCaptura(reserved["q15CaptureJson"] as String?, Q16PrecioCapturadoTest.VENUE, "cobro-original")
            assertEquals(saved.toString(), JSONObject(requireNotNull(capture?.orderRequestJson)).toString())
            cash.queueCashPayment(request, "staff-A", 11000, 0, null, orderExternalId = "venta-original",
                idempotencyKey = "cobro-original", amountCents = 11000, tipCents = 0, venueId = Q16PrecioCapturadoTest.VENUE)
            val queued = f.row("cobro-original")
            assertEquals(reserved["q15CaptureJson"], queued["q15CaptureJson"])
            assertEquals(saved.toString(), JSONObject(JSONObject(queued["original"] as String).getString("orderRequest")).toString())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `crear por HTTP conserva la cortesia y adopta el total canonico confirmado`() = runBlocking {
        withRepository { server, repository ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(response()))
            val result = repository.createOrder(order(listOf(line())), "staff-A", externalId = "venta-original")
            assertEquals(0, result.getOrThrow().data?.totalCents)
            val item = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("items").getJSONObject(0)
            assertEquals(true, item.opt("isCortesia"))
            assertEquals("Invitación de la casa", item.opt("cortesiaReason"))
        }
    }

    @Test fun `backend viejo sin flags o dedup de otra linea no autoriza seguir al primer pago`() = runBlocking {
        val good = JSONObject(response())
        val invalid = listOf(
            "backend viejo" to """{"success":true,"data":{"id":"orden-A","total":110}}""",
            "sin flag" to JSONObject(good.toString()).apply { getJSONObject("data").getJSONArray("items").getJSONObject(0).remove("isCortesia") }.toString(),
            "otra identidad" to JSONObject(good.toString()).apply { getJSONObject("data").getJSONArray("items").getJSONObject(0).put("externalId", "otra-linea") }.toString(),
            "sin cortesia" to JSONObject(good.toString()).apply { getJSONObject("data").getJSONArray("items").getJSONObject(0).put("isCortesia", false) }.toString(),
            "otra razon" to JSONObject(good.toString()).apply { getJSONObject("data").getJSONArray("items").getJSONObject(0).put("cortesiaReason", "Otro motivo") }.toString(),
        )
        for ((name, body) in invalid) withRepository { server, repository ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val result = repository.createOrder(order(listOf(line())), "staff-A", externalId = "venta-original")
            assertTrue("$name no confirmó la intención original: $result", result.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    private fun response() = """{"success":true,"data":{"id":"orden-A","total":0,"items":[{"id":"item-servidor-A","externalId":"linea-comp","isCortesia":true,"cortesiaReason":"Invitación de la casa"}]}}"""

    private suspend fun withRepository(block: suspend (MockWebServer, OrderRepository) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-A"
            every { storage.accessToken } returns "token-QA"
            val repository = OrderRepository(storage, OkHttpClient()).apply {
                baseUrl = server.url("/api/v1").toString().trimEnd('/')
            }
            block(server, repository)
        } finally { server.shutdown() }
    }
}
