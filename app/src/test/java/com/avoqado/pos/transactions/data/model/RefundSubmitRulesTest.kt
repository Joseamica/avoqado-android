package com.avoqado.pos.transactions.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 Las dos reglas que deciden CUÁNTO dinero se devuelve desde la hoja de reembolso.
 *
 * Origen: Testarudo, 2026-09-11. Al revisar esa pantalla salió que el importe se
 * convertía a centavos con `(pesos * 100).toInt()`, que TRUNCA. El dashboard web y iOS
 * redondean; Android era el único que cortaba, y siempre en contra del cliente.
 */
class RefundSubmitRulesTest {

    // ── El importe se redondea, nunca se trunca ──────────────────────────────────────

    /**
     * El barrido es el que de verdad guarda esto: un puñado de ejemplos elegidos a mano
     * se puede colar entre los valores que el truncamiento sí acierta. Con `toInt()`
     * caen 1 145 de estos 20 000.
     */
    @Test
    fun `todo importe de 1 centavo a 200 pesos vuelve a sus centavos exactos`() {
        val fallos = mutableListOf<String>()
        for (centavos in 1..20_000) {
            val pesos = centavos / 100.0
            val obtenido = centavosDelImporte(pesos)
            if (obtenido != centavos) {
                fallos += "$%.2f → %d (esperado %d)".format(pesos, obtenido, centavos)
            }
        }
        assertEquals(
            "El importe se está truncando en vez de redondear; el cliente recibe de menos en: " +
                fallos.take(5).joinToString("; ") + " … (${fallos.size} importes afectados)",
            emptyList<String>(),
            fallos,
        )
    }

    @Test
    fun `los casos que el truncamiento corta en un centavo`() {
        // Cada uno reproducido: 4.35 * 100 vale 434.99999999999994 en doble.
        assertEquals(29, centavosDelImporte(0.29))
        assertEquals(113, centavosDelImporte(1.13))
        assertEquals(201, centavosDelImporte(2.01))
        assertEquals(435, centavosDelImporte(4.35))
    }

    @Test
    fun `los importes redondos no cambian`() {
        assertEquals(0, centavosDelImporte(0.0))
        assertEquals(5_000, centavosDelImporte(50.0))
        assertEquals(6_500, centavosDelImporte(65.0))
        assertEquals(53_290, centavosDelImporte(532.90))
    }

    // ── La propina: el CERO es una decisión, no un vacío ─────────────────────────────

    @Test
    fun `desmarcar la propina manda 0 y deja intacta la del mesero`() {
        assertEquals(0, tipRefundCentsParaEnvio(paymentTipAmount = 68.45, includeTip = false))
    }

    @Test
    fun `con la propina marcada no se manda nada y reparte el servidor`() {
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 68.45, includeTip = true))
    }

    @Test
    fun `un cobro sin propina nunca manda el campo, marque lo que marque`() {
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 0.0, includeTip = false))
        assertNull(tipRefundCentsParaEnvio(paymentTipAmount = 0.0, includeTip = true))
    }

    // ── El TOPE depende de si la propina viaja ───────────────────────────────────────
    //
    // Testarudo, 17-sep-2026: cobro CASH de $200 + $20. El cajero desmarcó «Incluir
    // propina» y dejó el importe en $220 (el máximo que la hoja mostraba INCLUÍA la
    // propina). La app mandó `amount: 22000, tipRefundCents: 0` y el servidor rechazó,
    // con razón, «Sale portion of refund (22000) exceeds original sale amount (20000)».
    // Cinco 400 seguidos; el reembolso salió 4.7 h después por otro camino.

    @Test
    fun `con la propina incluida el tope es todo lo disponible`() {
        assertEquals(220.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = 200.0, paymentTipAmount = 20.0, includeTip = true), 0.0)
    }

    @Test
    fun `sin la propina el tope es la venta restante que manda el servidor`() {
        assertEquals(200.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = 200.0, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `con devoluciones previas manda la venta restante del servidor, no una resta local`() {
        // $200 + $20; ya se devolvieron $50 de venta + $5 de propina ⇒ disponible $165, venta restante $150.
        assertEquals(150.0, topeReembolsable(remainingRefundable = 165.0, remainingRefundableSale = 150.0, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `sin la propina y con un servidor viejo (sin saldo por componente) el tope es lo disponible menos la propina`() {
        assertEquals(200.0, topeReembolsable(remainingRefundable = 220.0, remainingRefundableSale = null, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `el tope nunca baja de cero`() {
        assertEquals(0.0, topeReembolsable(remainingRefundable = 15.0, remainingRefundableSale = null, paymentTipAmount = 20.0, includeTip = false), 0.0)
    }

    @Test
    fun `un cobro sin propina tiene el mismo tope marque lo que marque`() {
        assertEquals(80.0, topeReembolsable(remainingRefundable = 80.0, remainingRefundableSale = 80.0, paymentTipAmount = 0.0, includeTip = false), 0.0)
        assertEquals(80.0, topeReembolsable(remainingRefundable = 80.0, remainingRefundableSale = null, paymentTipAmount = 0.0, includeTip = true), 0.0)
    }

    // ── Al bajar el tope, el importe escrito se recorta; lo que cabe no se toca ──────
    //
    // Codex (20-sep-2026): «quien escribió $50 sin propina quiere devolver $50» — no se
    // resta la propina de cualquier importe manual; sólo se recorta lo que ya no cabe.

    @Test
    fun `un importe por encima del nuevo tope se recorta al tope`() {
        assertEquals("200.00", importeAjustadoAlTope(amountStr = "220", tope = 200.0))
        assertEquals("200.00", importeAjustadoAlTope(amountStr = "220,00", tope = 200.0))
    }

    @Test
    fun `un importe que cabe no se toca ni se reformatea`() {
        assertEquals("50", importeAjustadoAlTope(amountStr = "50", tope = 200.0))
        assertEquals("", importeAjustadoAlTope(amountStr = "", tope = 200.0))
        assertEquals("abc", importeAjustadoAlTope(amountStr = "abc", tope = 200.0))
    }
}
