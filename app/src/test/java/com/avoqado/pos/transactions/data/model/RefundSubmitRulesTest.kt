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
}
