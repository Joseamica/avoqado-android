package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.giftcards.domain.aCartState
import com.avoqado.pos.giftcards.domain.aFoto
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.domain.MotivoElegido
import com.avoqado.pos.pos.domain.MotivosDeDescuento
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encargo f998c8 (founder «A», 15-nov, como Toast): el motivo obligatorio en los descuentos. Qué pide motivo, qué falta antes de
 * cobrar, qué viaja en el cuerpo de la venta (también la cola sin red, que arma por el mismo camino) y que no se pierda en la foto
 * del cobro con saldo. El SERVIDOR nunca rechaza sin motivo: sin él, el cuerpo queda byte a byte como hoy.
 */
class MotivoDeDescuentoTest {

    private val prendido = MotivosDeDescuento(requerido = true, lista = listOf("Queja del cliente", "Error de etiqueta"))

    private fun linea(id: String = "l1", extra: CartItem.() -> CartItem = { this }) =
        CartItem(id = id, type = CartItemType.ProductItem("p-$id"), name = "Playera", unitPrice = 10000).extra()

    private val diezPorCiento = Discount(id = "cmn3a6xr8000kn227eg8zeh50", name = "10% frecuente", value = 10.0)

    // MARK: - Qué pide motivo

    @Test
    fun `se pide solo prendido y con lista`() {
        assertTrue(prendido.sePide)
        assertFalse(prendido.copy(requerido = false).sePide)
        assertFalse(prendido.copy(lista = emptyList()).sePide)
        assertFalse(MotivosDeDescuento.APAGADO.sePide)
    }

    @Test
    fun `pide motivo el descuento de catalogo o el ajuste puesto a mano, no la cortesia, la sugerencia ni un renglon sin descuento`() {
        assertTrue(linea { copy(itemDiscountId = "d1") }.descuentoPideMotivo)
        assertTrue(linea { copy(priceAdjustment = 8000) }.descuentoPideMotivo)
        assertFalse(linea().descuentoPideMotivo)
        assertFalse(linea { copy(itemDiscountId = "d1", isCortesia = true) }.descuentoPideMotivo)
        assertFalse(linea { copy(itemDiscountId = "d1", descuentoSugerido = true) }.descuentoPideMotivo)
    }

    @Test
    fun `la cuenta pide motivo con un descuento de la lista, manual o cortesia, no con cupon, del cliente ni referido`() {
        val base = CartState(items = listOf(linea()))
        assertTrue(base.copy(orderDiscount = diezPorCiento).cuentaPideMotivo)
        assertTrue(base.copy(orderDiscount = diezPorCiento.copy(id = "manual_1")).cuentaPideMotivo)
        assertFalse(base.copy(orderDiscount = diezPorCiento.copy(couponCode = "HOLA10")).cuentaPideMotivo)
        assertFalse(base.copy(orderDiscount = diezPorCiento.copy(source = DESCUENTO_DEL_CLIENTE_SOURCE)).cuentaPideMotivo)
        assertFalse(base.copy(orderDiscount = diezPorCiento.copy(source = "REFERRAL_NEW_CUSTOMER")).cuentaPideMotivo)
        assertFalse(base.cuentaPideMotivo)
        // Codex r2 #2: la cortesía de toda la cuenta ya trae su propia razón.
        assertFalse(base.copy(orderDiscount = diezPorCiento.copy(id = "${CORTESIA_DE_CUENTA_PREFIJO}1", value = 100.0)).cuentaPideMotivo)
    }

    // MARK: - La última puerta antes de cobrar (Codex r1 #4: carrito restaurado)

    @Test
    fun `apagado no falta nada aunque haya descuentos sin motivo (lo de hoy)`() {
        val venta = CartState(items = listOf(linea { copy(priceAdjustment = 8000) }), orderDiscount = diezPorCiento)
        assertTrue(venta.descuentosSinMotivo(MotivosDeDescuento.APAGADO).isEmpty())
    }

    @Test
    fun `prendido dice que falta y ponerlo llena solo lo que faltaba`() {
        val venta = CartState(
            items = listOf(
                linea("a") { copy(priceAdjustment = 8000) },
                linea("b") { copy(itemDiscountId = "d1", itemDiscountName = "2x", discountReason = "Error de etiqueta") },
                linea("c"),
            ),
            orderDiscount = diezPorCiento,
        )
        assertEquals(listOf("Playera · Ajuste de precio", "Cuenta · 10% frecuente"), venta.descuentosSinMotivo(prendido))

        val llena = venta.conMotivoEnLoQueFalta(MotivoElegido.de("Queja del cliente", "  tardó  "))
        assertTrue(llena.descuentosSinMotivo(prendido).isEmpty())
        assertEquals("Queja del cliente", llena.items[0].discountReason)
        assertEquals("tardó", llena.items[0].discountReasonComment)
        assertEquals("Error de etiqueta", llena.items[1].discountReason) // lo que ya traía motivo no se toca
        assertNull(llena.items[2].discountReason) // sin descuento no se inventa
        assertEquals("Queja del cliente", llena.orderDiscountReason)
    }

    @Test
    fun `el comentario vacio no viaja y se recorta al tope`() {
        assertNull(MotivoElegido.de("A", "   ").comentario)
        assertEquals(MotivosDeDescuento.MAX_COMENTARIO, MotivoElegido.de("A", "x".repeat(500)).comentario?.length)
    }

    // MARK: - Lo que viaja (en línea y en la cola: el mismo cuerpo)

    private fun cuerpo(request: CreateOrderRequest) =
        Json.parseToJsonElement(OrderRepository.buildCreateOrderPayload(request, staffId = "s1")).jsonObject

    @Test
    fun `el renglon lleva su motivo y comentario, sin motivo el renglon es el de siempre`() {
        val items = buildOrderItemRequests(
            listOf(
                linea("a") { copy(priceAdjustment = 8000, discountReason = "Error de etiqueta", discountReasonComment = "etiqueta vieja") },
                linea("b") { copy(itemDiscountId = "d1") },
            ),
        )
        assertEquals("Error de etiqueta", items[0].discountReason)
        val c = cuerpo(CreateOrderRequest(items = items, subtotal = 20000, total = 18000, paymentMethod = "CASH"))
        val renglones = c["items"]!!.jsonArray
        assertEquals("Error de etiqueta", renglones[0].jsonObject["discountReason"]?.jsonPrimitive?.content)
        assertEquals("etiqueta vieja", renglones[0].jsonObject["discountReasonComment"]?.jsonPrimitive?.content)
        assertNull(renglones[1].jsonObject["discountReason"])
        assertNull(renglones[1].jsonObject["discountReasonComment"])
    }

    @Test
    fun `un motivo en una cortesia o en un renglon sin descuento no viaja`() {
        val items = buildOrderItemRequests(
            listOf(
                linea("a") { copy(isCortesia = true, cortesiaReason = "Amigos", discountReason = "X") },
                linea("b") { copy(discountReason = "X") },
            ),
        )
        assertNull(items[0].discountReason)
        assertNull(items[1].discountReason)
    }

    private val importe = OrderItemRequest(productId = null, name = "Otro importe", quantity = 1, unitPrice = 10000)

    @Test
    fun `el motivo de la cuenta viaja solo con importe, sin el, el cuerpo no cambia`() {
        val conMotivo = CreateOrderRequest(
            items = listOf(importe), subtotal = 10000, discount = 1000, total = 9000, paymentMethod = "CASH",
            orderDiscountReason = "Queja del cliente", orderDiscountReasonComment = "tardó",
        )
        assertEquals("Queja del cliente", cuerpo(conMotivo)["orderDiscountReason"]?.jsonPrimitive?.content)
        assertEquals("tardó", cuerpo(conMotivo)["orderDiscountReasonComment"]?.jsonPrimitive?.content)
        assertNull(cuerpo(conMotivo.copy(discount = 0))["orderDiscountReason"])
        assertNull(cuerpo(conMotivo.copy(orderDiscountReason = null))["orderDiscountReason"])
        assertNull(cuerpo(conMotivo.copy(orderDiscountReason = null))["orderDiscountReasonComment"])
    }

    @Test
    fun `Codex r1 #1 - puros importes con motivo en la cuenta crean su orden (FAST no lleva descuento ni motivo)`() {
        val conMotivo = CreateOrderRequest(
            items = listOf(importe), subtotal = 10000, discount = 1000, total = 9000, paymentMethod = "CASH",
            orderDiscountReason = "Queja del cliente",
        )
        assertTrue(OrderRepository.aceptaCrearOrden(conMotivo))
        // Regresión: sin motivo, sigue por el cobro rápido como hoy.
        assertFalse(OrderRepository.aceptaCrearOrden(conMotivo.copy(orderDiscountReason = null)))
        assertFalse(OrderRepository.aceptaCrearOrden(conMotivo.copy(discount = 0)))
    }

    // MARK: - La foto del cobro con saldo (Codex r1 #3)

    @Test
    fun `la foto de la venta conserva el motivo de la cuenta y de los renglones`() {
        val venta = CartState(
            items = listOf(linea { copy(priceAdjustment = 8000, discountReason = "Error de etiqueta", discountReasonComment = "x") }),
            orderDiscount = diezPorCiento,
            orderDiscountReason = "Queja del cliente",
            orderDiscountReasonComment = "tardó",
        )
        val foto = Json.decodeFromString(
            com.avoqado.pos.giftcards.domain.FotoDeLaVenta.serializer(),
            Json.encodeToString(com.avoqado.pos.giftcards.domain.FotoDeLaVenta.serializer(), venta.aFoto(null)),
        )
        val vuelta = foto.aCartState()
        assertEquals("Queja del cliente", vuelta.orderDiscountReason)
        assertEquals("tardó", vuelta.orderDiscountReasonComment)
        assertEquals("Error de etiqueta", vuelta.items[0].discountReason)
        assertEquals("x", vuelta.items[0].discountReasonComment)
        assertTrue(vuelta.descuentosSinMotivo(prendido).isEmpty())
    }

    @Test
    fun `una foto vieja sin motivo se lee y al cobrar pide el motivo`() {
        val vieja = """{"items":[],"orderDiscount":{"id":"cmn3a6xr8000kn227eg8zeh50","name":"10%","value":10.0}}"""
        val foto = Json { ignoreUnknownKeys = true }.decodeFromString(com.avoqado.pos.giftcards.domain.FotoDeLaVenta.serializer(), vieja)
        assertNull(foto.orderDiscountReason)
    }
}
