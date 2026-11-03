package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.Q16PrecioCapturadoTest.Companion.VENUE
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * D2 etapa 2 (27-oct): una venta SIN productos cobrada en partes es UNA orden con renglón «Otro importe». Aquí, la regla
 * «¿lleva orden?» en sus dos escritores reales (crear la orden por HTTP y la fila de la cola) y la reproducción de esa fila.
 * Una sola parte (FULLPAYMENT) sigue siendo cobro rápido.
 */
class VentaRapidaDivididaTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun pedido(split: String? = "EQUALPARTS", cents: Int = 1000) = CreateOrderRequest(
        items = listOf(OrderItemRequest(productId = null, name = "Otro importe", quantity = 1, unitPrice = cents)),
        subtotal = cents, total = cents, paymentMethod = "CASH", splitType = split,
    )

    @Test fun `la regla acepta una venta rapida dividida y no la de una sola parte`() {
        assertTrue(OrderRepository.aceptaCrearOrden(pedido()))
        assertTrue(OrderRepository.aceptaCrearOrden(pedido("CUSTOMAMOUNT")))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido("FULLPAYMENT")))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(null)))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido().copy(items = emptyList())))
        assertFalse(OrderRepository.hasProductItems(pedido()))
    }

    @Test fun `createOrder acepta una venta rapida dividida y manda el renglon Otro importe`() = runBlocking {
        withRepository { server, repository ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"id":"orden-1","orderNumber":"ORD-7","total":10}}"""))
            val result = repository.createOrder(pedido(), "staff-A", externalId = "venta-rapida")
            assertTrue(result.toString(), result.isSuccess)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertTrue(request.path, request.path!!.endsWith("/venues/venue-A/orders"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("venta-rapida", body.getString("externalId"))
            val item = body.getJSONArray("items").getJSONObject(0)
            assertFalse(item.has("productId"))
            assertEquals("Otro importe", item.getString("name"))
            assertEquals(1000, item.getInt("unitPrice"))
        }
    }

    @Test fun `createOrder sigue rechazando una venta rapida de una sola parte sin tocar la red`() = runBlocking {
        withRepository { server, repository ->
            val result = repository.createOrder(pedido("FULLPAYMENT"), "staff-A", externalId = "venta-rapida")
            assertTrue(result.isFailure)
            assertEquals("No hay productos válidos para crear la orden", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `la venta rapida dividida encolada es ORDER y lleva su pedido con el externalId`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido(), "staff-A", 2000, 1500, null,
                orderExternalId = "venta-rapida", idempotencyKey = "parte-1", amountCents = 500, tipCents = 0,
                splitType = "EQUALPARTS", venueId = VENUE)
            val row = f.row("parte-1")
            assertEquals("ORDER", row["paymentType"])
            val saved = JSONObject(JSONObject(row["original"] as String).getString("orderRequest"))
            assertEquals("venta-rapida", saved.getString("externalId"))
            val item = saved.getJSONArray("items").getJSONObject(0)
            assertFalse(item.has("productId"))
            assertEquals("Otro importe", item.getString("name"))
            assertEquals(1000, item.getInt("unitPrice"))
        }
    }

    @Test fun `la reserva de la venta rapida dividida tambien es ORDER antes de tocar la red`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).reservarCobro(pedido(), "staff-A", 2000, 1500, null,
                orderExternalId = "venta-rapida", idempotencyKey = "parte-1", amountCents = 500, tipCents = 0,
                splitType = "EQUALPARTS", venueId = VENUE)
            val row = f.row("parte-1")
            assertEquals("ORDER", row["paymentType"])
            assertEquals("venta-rapida", JSONObject(JSONObject(row["original"] as String).getString("orderRequest")).getString("externalId"))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `la venta rapida de una sola parte encolada sigue siendo cobro rapido`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido("FULLPAYMENT"), "staff-A", 1000, 0, null,
                orderExternalId = "venta-rapida", idempotencyKey = "entera", amountCents = 1000, tipCents = 0,
                splitType = "FULLPAYMENT", venueId = VENUE)
            val row = f.row("entera")
            assertEquals("FAST", row["paymentType"])
            assertTrue(JSONObject(row["original"] as String).isNull("orderRequest"))
        }
    }

    @Test fun `la fila de la venta rapida dividida se reproduce creando su orden y abonandole la parte`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = ServidorFalso(); f.server.dispatcher = falso
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido(), "staff-A", null, null, null,
                orderExternalId = "venta-rapida", idempotencyKey = "parte-1", amountCents = 500, tipCents = 0,
                splitType = "EQUALPARTS", venueId = VENUE)
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.service.esperarSincronizacionActualQ16()
            f.reopen(); repeat(3) { f.service.syncNow(); f.service.esperarSincronizacionActualQ16() }
            val after = f.row("parte-1")
            assertEquals("SYNCED", after["syncStatus"]); assertNull(after["q15Reason"])
            assertEquals(listOf("orders", "pay"), falso.peticiones.map { it.first })
            val orden = falso.de("orders").single()
            assertEquals("venta-rapida", orden.getString("externalId"))
            assertEquals("Otro importe", orden.getJSONArray("items").getJSONObject(0).getString("name"))
            val pago = falso.de("pay").single()
            assertEquals(500, pago.getInt("amount")); assertEquals("EQUALPARTS", pago.getString("splitType"))
            assertEquals(1, falso.ordenes.size); assertEquals(1, falso.pagos.size)
            // Y entra a la espera de D1-B: subida, con la orden que le dio el servidor (una FAST daría `SinOrden` y el resto
            // saldría como otra venta).
            val estado = com.avoqado.pos.payment.data.estadoDeParte(f.dao.findForVenue(VENUE, "parte-1"))
            assertTrue(estado.toString(), estado is com.avoqado.pos.payment.data.EstadoDeParte.Subida)
            assertEquals(falso.ordenes.getValue("venta-rapida"), (estado as com.avoqado.pos.payment.data.EstadoDeParte.Subida).orderId)
        }
    }

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
