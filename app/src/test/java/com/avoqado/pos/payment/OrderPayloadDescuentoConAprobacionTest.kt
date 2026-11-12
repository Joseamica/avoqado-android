package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.CouponCode
import com.avoqado.pos.pos.data.model.DescuentoDelCupon
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.DESCUENTO_DEL_CLIENTE_SOURCE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4 (hz:f34960c5:D4): el descuento de CUENTA del catálogo que pide aprobación viaja como siempre, como importe (`discount`), MÁS la
 * etiqueta `descuentoDeCatalogoId` para que el SERVIDOR revise el permiso y guarde quién aprobó. El importe no cambia (Codex r1 #2 y
 * #3: con `orderDiscountId` el servidor recalculaba y rechazaba los combos). Lo que no pide aprobación sale byte a byte igual.
 */
class OrderPayloadDescuentoConAprobacionTest {

    private val playera = OrderItemRequest(productId = "playera", name = "Playera", quantity = 1, unitPrice = 10000)
    private val importe = OrderItemRequest(productId = null, name = "Otro importe", quantity = 1, unitPrice = 10000)

    private fun pedido(items: List<OrderItemRequest> = listOf(playera), descuento: Int = 1000, etiqueta: String? = "d-gerente") =
        CreateOrderRequest(
            items = items, subtotal = 10000, discount = descuento, total = 10000 - descuento, paymentMethod = "CASH",
            descuentoDeCatalogoId = etiqueta,
        )

    private fun cuerpo(request: CreateOrderRequest) =
        Json.parseToJsonElement(OrderRepository.buildCreateOrderPayload(request, staffId = "s1")).jsonObject

    @Test
    fun `la etiqueta viaja junto al importe, que no cambia`() {
        val c = cuerpo(pedido())
        assertEquals("d-gerente", c["descuentoDeCatalogoId"]?.jsonPrimitive?.content)
        assertEquals(1000, c["discount"]?.jsonPrimitive?.content?.toInt())
        assertNull(c["orderDiscountId"])
    }

    @Test
    fun `sin etiqueta o sin importe el cuerpo es el de siempre`() {
        assertNull(cuerpo(pedido(etiqueta = null))["descuentoDeCatalogoId"])
        assertNull(cuerpo(pedido(descuento = 0))["descuentoDeCatalogoId"])
    }

    @Test
    fun `una venta de puros importes con la etiqueta crea su orden (no va por FAST)`() {
        assertTrue(OrderRepository.aceptaCrearOrden(pedido(items = listOf(importe))))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(items = listOf(importe), etiqueta = null)))
    }

    // MARK: - El carrito decide qué viaja

    private val linea = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)
    private val gerencial = Discount(id = "d-gerente", name = "10% gerencial", value = 10.0, requiresApproval = true)

    @Test
    fun `un descuento de cuenta del catalogo que pide aprobacion lleva etiqueta y pide aprobacion`() {
        val carrito = CartState(items = listOf(linea), orderDiscount = gerencial)
        assertTrue(carrito.pideAprobacionDeGerente)
        assertEquals("d-gerente", carrito.descuentoDeCatalogoQueViaja)
    }

    @Test
    fun `una cortesia (COMP) con requiresApproval no pide esta aprobacion (la cubre orders comp)`() {
        val carrito = CartState(items = listOf(linea), orderDiscount = gerencial.copy(type = "COMP"))
        assertFalse(carrito.pideAprobacionDeGerente)
        assertNull(carrito.descuentoDeCatalogoQueViaja)
    }

    @Test
    fun `un cupon que pide aprobacion la pide pero sin etiqueta (el servidor lo busca por su codigo)`() {
        val cupon = CouponCode(id = "c1", code = "GERENTE", name = "10% gerencial", value = 10.0, requiresApproval = true).toDiscount()
        val carrito = CartState(items = listOf(linea), orderDiscount = cupon)
        assertTrue(carrito.pideAprobacionDeGerente)
        assertNull(carrito.descuentoDeCatalogoQueViaja)
    }

    @Test
    fun `el cupon toma requiresApproval del descuento que manda el servidor, salvo una cortesia`() {
        fun cupon(tipo: String) = com.avoqado.pos.pos.data.model.CuponDelServidor(
            id = "c", code = "X", discount = DescuentoDelCupon(id = "d", name = "x", type = tipo, value = 10.0, requiresApproval = true),
        ).aCoupon()
        assertTrue(cupon("PERCENTAGE").requiresApproval)
        assertFalse(cupon("COMP").requiresApproval)
    }

    @Test
    fun `el descuento del cliente nunca lleva esta etiqueta`() {
        val delCliente = gerencial.copy(source = DESCUENTO_DEL_CLIENTE_SOURCE)
        assertNull(CartState(items = listOf(linea), orderDiscount = delCliente).descuentoDeCatalogoQueViaja)
    }

    @Test
    fun `un descuento por producto que pide aprobacion pide aprobacion sin etiqueta de cuenta`() {
        val carrito = CartState(items = listOf(linea.copy(itemDiscountId = "d-r", itemDiscountType = "FIXED_AMOUNT", itemDiscountValue = 20.0, itemDiscountPideAprobacion = true)))
        assertTrue(carrito.pideAprobacionDeGerente)
        assertNull(carrito.descuentoDeCatalogoQueViaja)
    }

    @Test
    fun `regresion - un descuento sin aprobacion no pide nada ni lleva etiqueta`() {
        val carrito = CartState(items = listOf(linea), orderDiscount = gerencial.copy(requiresApproval = false))
        assertFalse(carrito.pideAprobacionDeGerente)
        assertNull(carrito.descuentoDeCatalogoQueViaja)
    }
}
