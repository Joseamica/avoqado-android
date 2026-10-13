package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CanalDelCobro
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import org.junit.Assert.*
import org.junit.Test

class Q15ConciliacionDelCobroTest {
    private val capture = CapturaDelCobro(
        venueId = "FULLTEST-A", key = "p1", orderId = "o1", amountCents = 10005,
        tipCents = 500, splitType = "AMOUNT", method = "CASH",
        cashTenderedCents = 11000, changeCents = 495,
    )
    private fun evaluate(body: String, channel: CanalDelCobro = CanalDelCobro.ORDER,
                         original: CapturaDelCobro = capture) = ConciliacionDelCobro.evaluar(
        ConciliacionDelCobro.guardarCaptura(original), original.venueId, original.key, channel, body)
    private fun order(amount: String = "10005", tip: String = "500", extra: String = "") =
        """{"success":true,"payment":{"paymentId":"server-p1","orderId":"o1","status":"COMPLETED","amount":$amount,"tipAmount":$tip,"method":"CASH"$extra}}"""

    @Test fun captura_nueva_se_conserva_y_legacy_no_se_autoriza_por_default() {
        val json = ConciliacionDelCobro.guardarCaptura(capture)
        assertEquals(capture, ConciliacionDelCobro.leerCaptura(json, "FULLTEST-A", "p1"))
        assertNull(ConciliacionDelCobro.leerCaptura(null, "FULLTEST-A", "p1"))
        assertNull(ConciliacionDelCobro.leerCaptura("""{"splitType":"FULLPAYMENT"}""", "FULLTEST-A", "p1"))
    }
    @Test fun captura_es_del_venue_y_de_la_llave_original() {
        val json = ConciliacionDelCobro.guardarCaptura(capture)
        assertNull(ConciliacionDelCobro.leerCaptura(json, "FULLTEST-B", "p1"))
        assertNull(ConciliacionDelCobro.leerCaptura(json, "FULLTEST-A", "p2"))
    }
    @Test fun order_habla_centavos_y_no_alerta_por_campos_opcionales_ausentes() {
        val result = evaluate(order())
        assertTrue(result.aplicado)
        assertNull(result.motivo)
        assertTrue(result.camposNoComprobados.contains("splitType"))
    }
    @Test fun fast_decimal_pesos_no_se_lee_como_centavos() {
        val result = evaluate("""{"success":true,"data":{"id":"server-p1","venueId":"FULLTEST-A","orderId":"o1","status":"COMPLETED","amount":"100.05","tipAmount":"5.00","method":"CASH","splitType":"AMOUNT"}}""", CanalDelCobro.FAST)
        assertTrue(result.aplicado)
        assertNull(result.motivo)
        assertTrue(result.resultadoJson!!.contains("10005"))
    }
    @Test fun misma_suma_con_venta_y_propina_distintas_sigue_siendo_diferencia() {
        val result = evaluate(order("9505", "1000"))
        assertEquals(ConciliacionDelCobro.DIFERENCIA, result.motivo)
        assertEquals(setOf("amountCents", "tipCents"), result.camposDiferentes.toSet())
        assertTrue(result.aplicado)
    }
    @Test fun saldo_actual_y_cambio_fisico_no_producen_mismatch() {
        val result = evaluate(order(extra = ",\"remainingBalanceCents\":6500,\"changeCents\":0,\"orderPaymentStatus\":\"PARTIAL\""))
        assertNull(result.motivo)
        assertTrue(result.aplicado)
    }
    @Test fun ids_de_otra_orden_no_acreditan_el_pago_original() {
        val result = evaluate(order().replace("o1", "o2"))
        assertFalse(result.aplicado)
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
    }
    @Test fun monto_ausente_no_es_cero_y_el_pago_identificado_no_se_reproduce() {
        val result = evaluate("""{"success":true,"payment":{"paymentId":"server-p1","orderId":"o1","status":"COMPLETED","tipAmount":500}}""")
        assertTrue(result.aplicado)
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
    }
    @Test fun dinero_invalido_nunca_se_redondea_o_satura_para_cuadrar() {
        for (amount in listOf("\"10005\"", "true", "-1", "10005.5", "2147483648")) {
            val result = evaluate(order(amount))
            assertEquals(amount, ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
            assertTrue(result.aplicado)
        }
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,
            evaluate("""{"success":true,"data":{"id":"server-p1","orderId":"o1","status":"COMPLETED","amount":"100.005","tipAmount":"5.00"}}""", CanalDelCobro.FAST).motivo)
    }
    @Test fun ack_identificado_usa_centavos_y_conserva_diferencia() {
        val result = evaluate("""{"paymentId":"server-p1","orderId":"o1","amount":9000,"tipAmount":500,"method":"CASH"}""", CanalDelCobro.ACK)
        assertTrue(result.aplicado)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, result.motivo)
    }
    @Test fun cuerpo_2xx_sin_pago_no_es_comprobante() {
        for (body in listOf("{}", "<html>gateway</html>", "{\"success\":true,\"data\":{}}")) {
            val result = evaluate(body)
            assertFalse(result.aplicado)
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
        }
    }
    @Test fun cero_conocido_es_valido_pero_captura_negativa_u_overflow_no() {
        val zero = capture.copy(amountCents = 0, tipCents = 0)
        assertNull(evaluate(order("0", "0"), original = zero).motivo)
        for (bad in listOf(capture.copy(amountCents = -1), capture.copy(amountCents = Int.MAX_VALUE, tipCents = 1))) {
            assertThrows(IllegalArgumentException::class.java) { ConciliacionDelCobro.guardarCaptura(bad) }
        }
    }
    @Test fun tender_y_split_solo_se_comparan_si_el_servidor_los_devuelve() {
        val selected = capture.copy(method = null, tenderTypeId = "t1", tenderRevision = 3)
        val absent = evaluate(order(), original = selected)
        assertNull(absent.motivo)
        assertTrue(absent.camposNoComprobados.contains("tenderTypeId"))
        val changed = evaluate(order(extra = ",\"tenderTypeId\":\"t2\",\"tenderRevision\":3,\"splitType\":\"AMOUNT\""), original = selected)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, changed.motivo)
        assertTrue(changed.camposDiferentes.contains("tenderTypeId"))
    }
    @Test fun prueba_original_exacta_no_se_reconstruye_del_catalogo() {
        val original = capture.copy(orderRequestJson = "{\"items\":[{\"productId\":\"sku1\",\"unitPrice\":110}]}",
            intentoPayloadJson = "{\"localOrderId\":\"L1\",\"amountCents\":10005}")
        val restored = ConciliacionDelCobro.leerCaptura(ConciliacionDelCobro.guardarCaptura(original), original.venueId, original.key)
        assertEquals(original, restored)
        assertNull(ConciliacionDelCobro.leerCaptura(ConciliacionDelCobro.guardarCaptura(capture), capture.venueId, capture.key)?.orderRequestJson)
    }
    @Test fun success_debe_ser_booleano_real_no_cadena_o_numero() {
        for (value in listOf("\"true\"", "1", "false")) {
            val result = evaluate(order().replace("\"success\":true", "\"success\":$value"))
            assertFalse(result.aplicado)
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
        }
    }
}
