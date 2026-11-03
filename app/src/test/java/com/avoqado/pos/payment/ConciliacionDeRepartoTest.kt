package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CanalDelCobro
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rappi 2b (Codex plan r1 #2): la app manda $205 de importe y $0 de propina; el SERVIDOR reparte (venta 175, propina 20, Rappi 10)
 * y lo dice en `deliveryCashAllocation`. Eso NO es una diferencia de cobro: se compara lo que se quedó en el cajón. Sin ese campo
 * (un cobro normal o un servidor viejo), la comparación de siempre, parte por parte.
 */
class ConciliacionDeRepartoTest {

    private val captura = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("v1", "llave-1", "o1", amountCents = 20500, tipCents = 0, method = "CASH"))

    private fun respuesta(amount: Int, tip: Int, retenido: Int, conReparto: Boolean) = """
        {"success":true,"payment":{"paymentId":"pay-1","orderId":"o1","status":"COMPLETED","method":"CASH",
        "amount":$amount,"tipAmount":$tip,"cashRetainedCents":$retenido
        ${if (conReparto) ""","deliveryCashAllocation":{"saleCents":$amount,"tipCents":$tip,"thirdPartyCents":${retenido - amount - tip}}""" else ""}}}
    """.trimIndent()

    private fun evaluar(cuerpo: String) = ConciliacionDelCobro.evaluar(captura, "v1", "llave-1", CanalDelCobro.ORDER, cuerpo)

    @Test
    fun `the server split of 205 into 175, 20 and 10 is not a difference`() {
        val r = evaluar(respuesta(17500, 2000, 20500, conReparto = true))
        assertTrue(r.camposDiferentes.isEmpty())
        assertNotEquals(ConciliacionDelCobro.DIFERENCIA, r.motivo)
    }

    @Test
    fun `less cash kept than captured is still a difference`() {
        val r = evaluar(respuesta(7000, 2000, 10000, conReparto = true))
        assertEquals(listOf("cashRetainedCents"), r.camposDiferentes)
    }

    @Test
    fun `without the split field it compares part by part as today`() {
        val r = evaluar(respuesta(17500, 2000, 20500, conReparto = false))
        assertEquals(listOf("amountCents", "tipCents"), r.camposDiferentes)
    }
}
