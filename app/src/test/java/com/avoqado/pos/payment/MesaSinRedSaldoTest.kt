package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CanalDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 19-oct (mesa sin red retenida, founder «a»): una mesa cobrada sin red ya no espera a que se compruebe el precio; el servidor
 * la valora con su precio de HOY. Si al confirmarse el cobro el saldo que dejó el servidor no es el que el aparato esperaba
 * (subió, bajó, o el cobro sobró), el gerente ve DIFERENCIA, como mostrador. Una captura vieja sin ese dato no se certifica.
 */
class MesaSinRedSaldoTest {
    private val venue = "FULLTEST-MESA"
    private fun captura(restanteEsperadoCents: Int?, toleranciaCents: Int? = null) = ConciliacionDelCobro.capturarIntent(venue, "pay",
        """{"localOrderId":"L","amountCents":3000,"tipCents":0}""", restanteEsperadoCents = restanteEsperadoCents, toleranciaCents = toleranciaCents)!!
    private fun ack(restante: Int?, total: Int = 7500, pagado: Int = 3000) = JSONObject()
        .put("paymentId", "p1").put("orderId", "R").put("amount", 3000).put("tipAmount", 0).put("method", "CASH")
        .apply { restante?.let { put("remainingBalanceCents", it) }; put("orderTotalCents", total); put("totalPaidCents", pagado) }.toString()

    @Test fun `la captura guarda el saldo que el aparato esperaba`() {
        assertEquals(4500, ConciliacionDelCobro.leerCaptura(captura(4500), venue, "pay")!!.restanteEsperadoCents)
    }

    @Test fun `saldo igual al esperado no avisa`() {
        val e = ConciliacionDelCobro.evaluar(captura(4500), venue, "pay", CanalDelCobro.ACK, ack(4500))
        assertTrue(e.aplicado); assertNull(e.motivo)
    }

    @Test fun `el servidor subio el precio y la mesa queda debiendo mas, el gerente lo ve`() {
        val e = ConciliacionDelCobro.evaluar(captura(4500), venue, "pay", CanalDelCobro.ACK, ack(5500, total = 8500))
        assertEquals(ConciliacionDelCobro.DIFERENCIA, e.motivo); assertTrue("saldoDeLaMesa" in e.camposDiferentes)
        val r = JSONObject(e.resultadoJson!!)
        assertEquals(4500, r.getInt("saldoEsperadoCents")); assertEquals(5500, r.getInt("saldoEnElServidorCents"))
    }

    @Test fun `el precio bajo y el cobro sobro, aunque el saldo sea 0 en los dos lados, el gerente lo ve`() {
        val e = ConciliacionDelCobro.evaluar(captura(0), venue, "pay", CanalDelCobro.ACK, ack(0, total = 2500, pagado = 3000))
        assertEquals(ConciliacionDelCobro.DIFERENCIA, e.motivo); assertTrue("saldoDeLaMesa" in e.camposDiferentes)
    }

    // Codex r2: el gerente necesita el importe del sobrante, no sólo «saldo 0 contra 0».
    @Test fun `el cobro que sobro guarda cuanto se cobro de mas`() {
        val e = ConciliacionDelCobro.evaluar(captura(0), venue, "pay", CanalDelCobro.ACK, ack(0, total = 2500, pagado = 3000))
        assertEquals(500, JSONObject(e.resultadoJson!!).getInt("cobradoDeMasCents"))
    }

    // Codex r2: un combo redondea distinto en el carrito y en el servidor (3×2 de $50 = $99.99 aquí, $100 allá): 1¢ por pieza.
    @Test fun `un redondeo dentro de la tolerancia por pieza no avisa, uno mayor si`() {
        assertNull(ConciliacionDelCobro.evaluar(captura(4500, toleranciaCents = 3), venue, "pay", CanalDelCobro.ACK, ack(4501)).motivo)
        assertEquals(ConciliacionDelCobro.DIFERENCIA,
            ConciliacionDelCobro.evaluar(captura(4500, toleranciaCents = 3), venue, "pay", CanalDelCobro.ACK, ack(4504)).motivo)
    }

    @Test fun `captura vieja sin saldo esperado no se certifica`() {
        val e = ConciliacionDelCobro.evaluar(captura(null), venue, "pay", CanalDelCobro.ACK, ack(4500))
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, e.motivo); assertTrue("saldoDeLaMesa" in e.camposNoComprobados)
    }

    @Test fun `sin saldo en el ACK (servidor que no lo resolvio) queda como antes`() {
        val e = ConciliacionDelCobro.evaluar(captura(4500), venue, "pay", CanalDelCobro.ACK, ack(null))
        assertNull(e.motivo); assertTrue("saldoDeLaMesa" in e.camposNoComprobados)
    }
}
