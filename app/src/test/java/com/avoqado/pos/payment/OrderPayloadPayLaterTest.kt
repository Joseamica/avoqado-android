package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 🔴 «Pagar después» como Toast (founder 26-oct): el SERVIDOR sólo cuenta como deuda del cliente lo que el cajero mandó a
 * «Pagar después». Para saberlo necesita el método con el que se creó la cuenta: `PAY_LATER` desde el botón «Pagar
 * después», y el del cobro (CARD, CASH…) en una venta normal. Si ese cobro con tarjeta falla, la cuenta ya no aparece como
 * deuda en «Cuentas por cobrar» ni en el «Liquidar todo» del DASHBOARD (Amaena, $577.50 y $1,855).
 *
 * El modelo siempre tuvo `paymentMethod`, pero `buildCreateOrderPayload` arma el cuerpo a mano y nunca escribía la llave:
 * el mismo patrón que `OrderPayloadDiscountTest`. Este archivo fija que viaja.
 */
class OrderPayloadPayLaterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun metodoEnElCable(paymentMethod: String): String? {
        val body = OrderRepository.buildCreateOrderPayload(
            request = CreateOrderRequest(items = emptyList(), subtotal = 0, total = 0, paymentMethod = paymentMethod),
            staffId = "staff-1",
            customerId = "cliente-1",
        )
        return json.parseToJsonElement(body).jsonObject["paymentMethod"]?.jsonPrimitive?.content
    }

    @Test
    fun `Pagar despues manda PAY_LATER`() {
        assertEquals("PAY_LATER", metodoEnElCable("PAY_LATER"))
    }

    @Test
    fun `un cobro con tarjeta manda CARD, para que si falla no sea deuda del cliente`() {
        assertEquals("CARD", metodoEnElCable("CARD"))
    }

    @Test
    fun `un cobro en efectivo manda CASH`() {
        assertEquals("CASH", metodoEnElCable("CASH"))
    }
}
