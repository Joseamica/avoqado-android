package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.Q16PrecioCapturadoTest.Companion.VENUE
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * D16 (28-oct): la venta «En tienda» cobrada en efectivo SIN red subía como «Para llevar». En línea la orden lleva el tipo del
 * carrito; la fila de la cola armaba su pedido sin él y el default del cuerpo era TAKEOUT. Ahora el tipo viaja en el pedido
 * capturado y la fila lo conserva, en la cola y en la reserva (antes de tocar la red).
 */
class TipoDeOrdenSinRedTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun pedido(orderType: String?) = CreateOrderRequest(
        items = listOf(OrderItemRequest(productId = "prod-1", name = "Café", quantity = 1, unitPrice = 3000)),
        subtotal = 3000, total = 3000, paymentMethod = "CASH", orderType = orderType,
    )

    private fun tipoEnLaFila(row: Map<String, Any?>): String =
        JSONObject(JSONObject(row["original"] as String).getString("orderRequest")).getString("orderType")

    @Test fun `la venta En tienda encolada sin red conserva DINE_IN`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido("DINE_IN"), "staff-A", 5000, 2000, null,
                orderExternalId = "en-tienda", idempotencyKey = "cobro-1", venueId = VENUE)
            assertEquals("DINE_IN", tipoEnLaFila(f.row("cobro-1")))
        }
    }

    @Test fun `la venta Para llevar encolada sin red sigue siendo TAKEOUT`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido("TAKEOUT"), "staff-A", 5000, 2000, null,
                orderExternalId = "para-llevar", idempotencyKey = "cobro-2", venueId = VENUE)
            assertEquals("TAKEOUT", tipoEnLaFila(f.row("cobro-2")))
        }
    }

    @Test fun `la reserva antes de la red tambien conserva el tipo del carrito`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).reservarCobro(pedido("DINE_IN"), "staff-A", 5000, 2000, null,
                orderExternalId = "en-tienda", idempotencyKey = "cobro-3", venueId = VENUE)
            assertEquals("DINE_IN", tipoEnLaFila(f.row("cobro-3")))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `un pedido guardado por una version anterior sin tipo sube como antes`() = runBlocking {
        // Un apartado en disco de la versión anterior no trae el campo: se lee como null y la fila sale con el default de siempre.
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString(CreateOrderRequest.serializer(),
            """{"items":[{"productId":"prod-1","name":"Café","quantity":1,"unitPrice":3000}],"subtotal":3000,"total":3000,"paymentMethod":"CASH"}""")
        assertEquals(null, viejo.orderType)
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(viejo, "staff-A", 5000, 2000, null,
                orderExternalId = "viejo", idempotencyKey = "cobro-4", venueId = VENUE)
            assertEquals("TAKEOUT", tipoEnLaFila(f.row("cobro-4")))
        }
    }
}
