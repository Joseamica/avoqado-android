package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D5: el cupón llega hasta el cable. Sin él, el SERVIDOR sólo veía un descuento libre y el cupón nunca contaba un uso. */
class CuponEnElPedidoTest {

    private val cafe = OrderItemRequest(productId = "p-1", name = "Café", quantity = 1, unitPrice = 10000)
    private val importe = OrderItemRequest(productId = null, name = "Otro importe", quantity = 1, unitPrice = 10000)

    private fun pedido(vararg items: OrderItemRequest, cupon: String? = null) = CreateOrderRequest(
        items = items.toList(),
        subtotal = 10000,
        discount = if (cupon != null) 1000 else 0,
        total = if (cupon != null) 9000 else 10000,
        paymentMethod = "CASH",
        couponCode = cupon,
    )

    private fun cuerpo(request: CreateOrderRequest) =
        Json.parseToJsonElement(OrderRepository.buildCreateOrderPayload(request = request, staffId = "s-1")).jsonObject

    @Test
    fun `P1 el cupon viaja al crear la venta`() {
        assertEquals("DESC10", cuerpo(pedido(cafe, cupon = "DESC10"))["couponCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sin cupon el cuerpo no trae la llave`() {
        assertNull(cuerpo(pedido(cafe))["couponCode"])
        assertNull(cuerpo(pedido(cafe, cupon = " "))["couponCode"])
    }

    @Test
    fun `P1 una venta rapida con cupon lleva orden, para tener donde ligarlo`() {
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(importe)))
        assertTrue(OrderRepository.aceptaCrearOrden(pedido(importe, cupon = "DESC10")))
    }

    @Test
    fun `el carrito sabe su cupon y de donde salio, y un descuento comun no tiene`() {
        val linea = CartItem(id = "l", type = CartItemType.ProductItem("p-1"), name = "Café", unitPrice = 10000)
        val comun = Discount(id = "d", name = "Diez", value = 10.0)
        val cupon = comun.copy(id = "coupon_c", couponCode = "DESC10")
        assertNull(CartState(items = listOf(linea), orderDiscount = comun).couponCode)
        assertEquals("DESC10", CartState(items = listOf(linea), orderDiscount = cupon).couponCode)
        // Quitarlo es quitar el descuento de la cuenta: no queda nada que viaje.
        assertNull(CartState(items = listOf(linea)).couponCode)
    }

    @Test
    fun `un carrito guardado con cupon lo conserva al leerlo`() {
        val json = Json { ignoreUnknownKeys = true }
        val cupon = Discount(id = "coupon_c", name = "Diez (DESC10)", value = 10.0, couponCode = "DESC10", maxDiscountAmount = 50.0)
        val leido = json.decodeFromString<Discount>(json.encodeToString(Discount.serializer(), cupon))
        assertEquals(cupon, leido)
        // Lo guardado antes de este campo se sigue leyendo.
        assertNull(json.decodeFromString<Discount>("""{"id":"d","name":"Diez","value":10.0}""").couponCode)
    }
}
