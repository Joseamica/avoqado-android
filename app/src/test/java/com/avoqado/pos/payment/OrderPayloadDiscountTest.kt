package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.PromotionRefRequest
import com.avoqado.pos.payment.data.model.PromotionSelectionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 EL guardrail del descuento por línea EN EL CABLE.
 *
 * El defecto que este archivo existe para impedir, MEDIDO EN HARDWARE
 * (D3, 2026-08-17): el POS cobró **$169.00** y el server registró la orden en
 * **$174.00**, con `discountAmount = 0` y sin `appliedDiscountId`. La orden quedó
 * `PARTIAL`, corta por $5.00, y el arqueo del día no cierra.
 *
 * La causa era esta función: `CartItem.itemDiscountId` sí llegaba hasta
 * `OrderItemRequest.discountId` (su propio comentario dice "sent as-is on the
 * /mobile payload"), pero el payload se arma A MANO con `buildJsonObject` y
 * simplemente **nunca escribía la llave**. El campo se llenaba y se tiraba en la
 * frontera HTTP — el mismo patrón que el bug original del upsell, una capa más
 * abajo, y por eso ningún test de las capas de arriba lo veía.
 *
 * Afecta por igual al descuento MANUAL que el cajero aplica a un artículo: es
 * preexistente, no lo introdujo el upsell.
 */
class OrderPayloadDiscountTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(vararg items: OrderItemRequest): kotlinx.serialization.json.JsonObject {
        val body = OrderRepository.buildCreateOrderPayload(
            request = CreateOrderRequest(
                items = items.toList(),
                subtotal = 0,
                discount = 0,
                tip = 0,
                total = 0,
                paymentMethod = "CASH",
            ),
            staffId = "staff-1",
        )
        return json.parseToJsonElement(body).jsonObject
    }

    private fun primerItem(obj: kotlinx.serialization.json.JsonObject) =
        obj["items"]!!.jsonArray[0].jsonObject

    private fun producto(discountId: String? = null) = OrderItemRequest(
        productId = "prod-1",
        name = "Coca-Cola 600ml",
        quantity = 1,
        unitPrice = 2500,
        discountId = discountId,
    )

    // ── 1. Lo nuevo ───────────────────────────────────────────────────────────

    @Test
    fun `🔴 una linea con descuento manda discountId al server`() {
        val item = primerItem(payload(producto(discountId = "disc-1")))

        assertEquals(
            "sin esta llave el server cobra precio de lista y la orden queda corta",
            "disc-1",
            item["discountId"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `un importe libre con descuento tambien lo manda`() {
        // El server aplica el descuento a las líneas de importe libre igual que a
        // las de producto (`customItemsData` en order.mobile.service.ts).
        val libre = OrderItemRequest(
            productId = null,
            name = "Otro importe",
            quantity = 1,
            unitPrice = 5000,
            discountId = "disc-1",
        )

        val item = primerItem(payload(libre))

        assertEquals("disc-1", item["discountId"]?.jsonPrimitive?.content)
        assertEquals("Otro importe", item["name"]?.jsonPrimitive?.content)
    }

    // ── 2. Lo que NO debe pasar ───────────────────────────────────────────────

    @Test
    fun `sin descuento la llave NO viaja`() {
        val item = primerItem(payload(producto(discountId = null)))

        assertNull("mandar null ensucia el payload sin necesidad", item["discountId"])
    }

    @Test
    fun `🔴 una linea de promocion NUNCA manda discountId`() {
        // Una promoción viaja SOLA: el server rechaza con 400 cualquier item que
        // traiga `promotionRef` junto con datos de producto, y su precio lo
        // resuelve el motor de promociones.
        val promo = OrderItemRequest(
            productId = null,
            name = "Combo",
            quantity = 1,
            unitPrice = 0,
            discountId = "disc-1",
            promotionRef = PromotionRefRequest(
                promotionId = "promo-1",
                promotionInstanceId = "inst-1",
                selections = listOf(PromotionSelectionRequest("g1", "o1")),
            ),
        )

        val item = primerItem(payload(promo))

        assertNull(item["discountId"])
        assertNull(item["productId"])
    }

    // ── 3. Regresión: el resto del payload no cambia ──────────────────────────

    @Test
    fun `la linea de producto sigue mandando lo de siempre`() {
        val item = primerItem(payload(producto(discountId = "disc-1")))

        assertEquals("prod-1", item["productId"]?.jsonPrimitive?.content)
        assertEquals(1, item["quantity"]?.jsonPrimitive?.content?.toInt())
        // El precio de una línea de producto NO viaja: el server usa su catálogo.
        assertNull(item["unitPrice"])
    }
}
