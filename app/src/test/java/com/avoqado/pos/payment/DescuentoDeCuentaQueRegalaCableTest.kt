package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hz:e0b12f00:D2: un «Descuento de la cuenta» que deja en $0 lo que queda de los productos es una cortesía. Aquí, lo que la app sabe
 * de él sin red (la misma base que el SERVIDOR) y lo que viaja en el cable: la marca de la app nueva y el token del PIN.
 */
class DescuentoDeCuentaQueRegalaCableTest {

    private val playera = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)

    private fun carrito(descuento: Discount?, vararg items: CartItem = arrayOf(playera)) =
        CartState(items = items.toList(), orderDiscount = descuento)

    private fun porcentaje(valor: Double) = Discount(id = "d", name = "Descuento", value = valor, type = "PERCENTAGE")
    private fun fijo(pesos: Double) = Discount(id = "d", name = "Descuento", value = pesos, type = "FIXED")

    @Test fun `100 por ciento regala`() = assertTrue(carrito(porcentaje(100.0)).descuentoDeCuentaRegala)

    @Test fun `99 por ciento deja un peso por cobrar y no regala`() = assertFalse(carrito(porcentaje(99.0)).descuentoDeCuentaRegala)

    @Test fun `un monto que cubre lo que queda regala, uno que deja un centavo no`() {
        assertTrue(carrito(fijo(100.0)).descuentoDeCuentaRegala)
        assertTrue("se topa a la base", carrito(fijo(250.0)).descuentoDeCuentaRegala)
        assertFalse(carrito(fijo(99.99)).descuentoDeCuentaRegala)
    }

    @Test fun `la base es lo que queda tras los descuentos de linea`() {
        val conDiezDeLinea = playera.copy(itemDiscountId = "art", itemDiscountType = "FIXED", itemDiscountValue = 10.0)
        assertTrue("$90 sobre $90", carrito(fijo(90.0), conDiezDeLinea).descuentoDeCuentaRegala)
        assertFalse("$89.99 sobre $90", carrito(fijo(89.99), conDiezDeLinea).descuentoDeCuentaRegala)
    }

    @Test fun `sin descuento, todo regalado por renglon o con cupon no aplica`() {
        assertFalse(carrito(null).descuentoDeCuentaRegala)
        assertFalse(carrito(porcentaje(100.0), playera.copy(isCortesia = true, cortesiaReason = "Casa")).descuentoDeCuentaRegala)
        assertFalse(
            "el cupón lo calcula el SERVIDOR",
            carrito(porcentaje(100.0).copy(couponCode = "GRATIS")).descuentoDeCuentaRegala,
        )
    }

    // MARK: - El cable

    private fun pedido(descuentoCents: Int) = CreateOrderRequest(
        items = listOf(OrderItemRequest(productId = "p1", name = "Playera", quantity = 1, unitPrice = 10000)),
        subtotal = 10000,
        discount = descuentoCents,
        total = 10000 - descuentoCents,
        paymentMethod = "CASH",
    )

    @Test fun `con descuento de cuenta, la app declara que pide el PIN (la marca de capacidad)`() {
        val body = JSONObject(OrderRepository.buildCreateOrderPayload(pedido(10000), staffId = "s1"))
        assertEquals(true, body.opt("cortesiaDeCuentaConPin"))
    }

    @Test fun `sin descuento de cuenta el cuerpo queda como siempre`() {
        val body = JSONObject(OrderRepository.buildCreateOrderPayload(pedido(0), staffId = "s1"))
        assertFalse(body.has("cortesiaDeCuentaConPin"))
    }

    @Test fun `el token del PIN viaja en el header de la venta, y sin token no viaja nada`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-A"
            every { storage.accessToken } returns "sesion"
            val repository = OrderRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            val ok = """{"success":true,"data":{"id":"orden-1","total":0}}"""
            server.enqueue(MockResponse().setResponseCode(201).setBody(ok))
            server.enqueue(MockResponse().setResponseCode(201).setBody(ok))

            repository.createOrder(pedido(10000), "s1", externalId = "venta-1", permisoDeGerente = "token-gerente")
            repository.createOrder(pedido(5000), "s1", externalId = "venta-2")

            assertEquals("token-gerente", server.takeRequest().getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
            assertNull(server.takeRequest().getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        } finally {
            server.shutdown()
        }
    }
}
