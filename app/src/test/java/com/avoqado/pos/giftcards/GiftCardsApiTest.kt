package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object FijasDeTarjetas {
    val ajustes = """{"enabled":true,"suggestedAmounts":[500,1000],"minAmount":100,"maxAmount":10000,"llavesConfiguradas":true,"sale":{"sellable":true},"capacidades":["ENTREGA","PAGE_TOKEN","TELEFONO"],"tieneTarjetas":true}"""
    val cobro = """{"paymentId":"p","orderId":"o","orderNumber":"ORD-1","amount":80,"tipAmount":0,"redeemed":[{"giftCardId":"g","last4":"JKMN","amount":80,"balanceAfter":420}],"orderRemaining":0,"orderPaymentStatus":"PAID","digitalReceipt":null,"replay":false,"priceChanges":[{"productId":"producto","displayedUnitPriceCents":10000,"unitPriceCents":8000}]}"""
    val telefono = """{"proofToken":"secretoTelefono","expiresAt":"2026-10-20T18:15:00.000Z","usableBalance":500,"cards":[{"giftCardId":"g","last4":"JKMN","balance":500,"status":"ACTIVE","usable":true},{"giftCardId":"b","last4":"AAAA","balance":50,"status":"BLOCKED","usable":false,"notUsableReason":"BLOCKED"}],"hayMas":true}"""
}

class GiftCardsApiTest {
    @Test fun `ajustes con capacidades y server antiguo`() {
        val a = GiftCardsApi.parsearAjustes(FijasDeTarjetas.ajustes)
        assertTrue(a.vende && a.porLiga && a.porTelefono && a.filaDeCobro)
        val antiguo = GiftCardsApi.parsearAjustes("""{"enabled":false,"suggestedAmounts":[],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true}}""")
        assertFalse(antiguo.vende || antiguo.filaDeCobro)
        assertTrue(antiguo.capacidades.isEmpty())
    }
    @Test fun `una sola prueba en consulta y tres formas en cobro`() {
        val code = PruebaDeTarjeta.Codigo("secretoCodigo")
        val page = PruebaDeTarjeta.PageToken("secretoPagina")
        assertEquals("""{"kind":"CODE","code":"secretoCodigo"}""", code.aJson().toString())
        assertEquals("""{"kind":"PAGE_TOKEN","pageToken":"secretoPagina"}""", page.aJson().toString())
        assertEquals("""{"kind":"PHONE_OTP","proofToken":"secretoTelefono","giftCardId":"g"}""", PruebaDeTarjeta.Telefono("secretoTelefono", "g").aJson().toString())
        assertEquals("""{"code":"secretoCodigo"}""", GiftCardsApi.cuerpoDeConsulta(code))
        assertEquals("""{"pageToken":"secretoPagina"}""", GiftCardsApi.cuerpoDeConsulta(page))
    }
    @Test fun `P1 amount autoritativo y cambios opcionales no se inventan en replay`() {
        val r = GiftCardsApi.parsearCobro(FijasDeTarjetas.cobro)
        assertEquals(80.0, r.amount, 0.0)
        assertEquals(80.0, r.redeemed.single().amount, 0.0)
        assertEquals(0.0, r.orderRemaining, 0.0)
        assertNull(r.receiptUrl)
        assertEquals(8000, r.priceChanges!!.single().unitPriceCents)
        val replay = Json.parseToJsonElement(FijasDeTarjetas.cobro).jsonObject.toMutableMap().apply { remove("priceChanges"); put("replay", JsonPrimitive(true)) }
        assertNull(GiftCardsApi.parsearCobro(JsonObject(replay).toString()).priceChanges)
        assertNull(GiftCardsApi.parsearRecuperacion("""{"found":false}"""))
    }
    @Test fun `telefono conserva orden hayMas y no inventa total global`() {
        val r = GiftCardsApi.parsearTelefono(FijasDeTarjetas.telefono)
        assertTrue(r.hayMas)
        assertEquals(listOf("g", "b"), r.cards.map { it.giftCardId })
        assertEquals(500.0, r.usableBalance, 0.0)
        assertFalse(GiftCardsApi.parsearTelefono(FijasDeTarjetas.telefono.replace(",\"hayMas\":true", "")).hayMas)
    }
    @Test fun `extras arriba y en details y 429 propio distinto del intermediario`() {
        for (extras in listOf("\"retryAfter\":600,\"saldoDisponible\":120", "\"details\":{\"retryAfter\":600,\"saldoDisponible\":120}")) {
            val f = GiftCardsApi.leerFallo(429, """{"code":"GIFT_CARD_PHONE_RATE_LIMITED","message":"Espera",$extras}""")
            assertEquals(600, f.retryAfterSeg)
            assertEquals(120.0, f.saldoDisponible)
        }
        assertTrue(GiftCardsApi.clasificar(429, "application/json", null, """{"code":"GIFT_CARD_LOOKUP_RATE_LIMITED","retryAfter":60}""") is Respuesta.Rechazo)
        assertEquals(Respuesta.SinRespuesta, GiftCardsApi.clasificar(429, "application/json", null, "{}"))
        assertEquals(Respuesta.SinRespuesta, GiftCardsApi.clasificar(429, "text/html", null, "<html>"))
    }
    @Test fun `secretos no salen en toString de ninguna respuesta`() {
        val sale = GiftCardsApi.parsearVenta("""{"orderId":"o","orderNumber":"n","total":500,"sobres":[{"id":"g","ultimos4":"JKMN","monto":500,"codigo":"secretoCodigo","pageToken":"secretoPagina","pageUrl":"https://x/secretoPagina","deliveryChannel":"PRINTED"}]}""")
        val reveal = GiftCardsApi.parsearRevelado("""{"codigo":"secretoCodigo","pageToken":"secretoPagina","ultimos4":"JKMN","monto":500,"saldo":500}""")
        val values = listOf(PruebaDeTarjeta.Codigo("secretoCodigo"), PruebaDeTarjeta.PageToken("secretoPagina"), PruebaDeTarjeta.Telefono("secretoTelefono", "g"), sale, reveal, GiftCardsApi.parsearTelefono(FijasDeTarjetas.telefono))
        for (v in values) for (secret in listOf("secretoCodigo", "secretoPagina", "secretoTelefono")) assertFalse(v.toString().contains(secret))
    }
}
