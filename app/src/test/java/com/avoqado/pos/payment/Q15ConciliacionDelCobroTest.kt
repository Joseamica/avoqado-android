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
                         original: CapturaDelCobro = capture, cola: Boolean = false) = ConciliacionDelCobro.evaluar(
        ConciliacionDelCobro.guardarCaptura(original), original.venueId, original.key, channel, body, ordenNacidaEnLaCola = cola)
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
    // Q16/C3 (2.24.1): la orden nació al sincronizar desde el pedido de ESTA fila (captura sin orderId). `orderTotalCents`
    // del servidor trae la propina; el pedido trae `total` con propina y `tip`. Se comparan los dos SIN propina.
    private fun sinRed(pedido: String = """{"externalId":"k1","subtotal":10005,"tip":500,"total":10505}""", amount: Int = 10005, tip: Int = 500) =
        capture.copy(orderId = null, amountCents = amount, tipCents = tip, orderRequestJson = pedido)
    private fun pagoDeOrdenNueva(amount: Int = 10005, tip: Int = 500, totalServidor: Int? = 10505) =
        """{"success":true,"payment":{"paymentId":"server-p1","orderId":"o-nueva","status":"COMPLETED","amount":$amount,"tipAmount":$tip,"method":"CASH"""" +
            (totalServidor?.let { ",\"orderTotalCents\":$it" } ?: "") + "}}"
    private fun totales(result: com.avoqado.pos.payment.data.EvaluacionDelCobro) =
        org.json.JSONObject(result.resultadoJson!!).let { it.optInt("totalAlCobrarCents", -1) to it.optInt("totalEnElServidorCents", -1) }

    @Test fun q16_precio_que_subio_sin_red_es_diferencia_con_los_dos_totales_y_el_pago_queda_aplicado() {
        val result = evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = sinRed(), cola = true)
        assertTrue(result.aplicado)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, result.motivo)
        assertEquals(listOf("totalDeLaOrden"), result.camposDiferentes)
        assertEquals(10005 to 12000, totales(result))
    }
    @Test fun q16_precio_que_bajo_sin_red_registra_menos_y_tambien_es_diferencia() {
        val result = evaluate(pagoDeOrdenNueva(amount = 9000, totalServidor = 9500), original = sinRed(), cola = true)
        assertTrue(result.aplicado)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, result.motivo)
        assertEquals(setOf("amountCents", "totalDeLaOrden"), result.camposDiferentes.toSet())
        assertEquals(10005 to 9000, totales(result))
    }
    @Test fun q16_mismo_precio_no_es_diferencia_ni_escribe_totales() {
        val result = evaluate(pagoDeOrdenNueva(), original = sinRed(), cola = true)
        assertNull(result.motivo); assertTrue(result.camposDiferentes.isEmpty())
        assertFalse(result.resultadoJson!!.contains("totalAlCobrarCents"))
    }
    @Test fun q16_sin_total_del_servidor_o_del_pedido_queda_como_antes() {
        assertNull(evaluate(pagoDeOrdenNueva(totalServidor = null), original = sinRed(), cola = true).motivo)
        assertNull(evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = sinRed(pedido = """{"externalId":"k1","items":[]}"""), cola = true).motivo)
        assertNull(evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = sinRed(pedido = """{"externalId":"k1","total":10505}"""), cola = true).motivo)
        assertNull(evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = capture.copy(orderId = null), cola = true).motivo)
    }
    @Test fun q16_orden_previa_mesas_o_cuenta_abierta_no_usa_la_regla_del_total() {
        val result = evaluate(order(extra = ",\"orderTotalCents\":20000,\"remainingBalanceCents\":9995,\"orderPaymentStatus\":\"PARTIAL\""), cola = true)
        assertNull(result.motivo); assertFalse(result.camposDiferentes.contains("totalDeLaOrden"))
    }
    @Test fun q16_abono_a_proposito_sobre_orden_nueva_no_es_diferencia() {
        val result = evaluate(pagoDeOrdenNueva(amount = 5000, totalServidor = 10505).replace("}}", ",\"remainingBalanceCents\":5005,\"orderPaymentStatus\":\"PARTIAL\"}}"),
            original = sinRed(amount = 5000).copy(splitType = "CUSTOMAMOUNT"), cola = true)
        assertNull(result.motivo); assertTrue(result.camposDiferentes.isEmpty())
    }
    @Test fun q16_descuento_de_cuenta_o_premio_de_lealtad_con_el_mismo_precio_no_es_diferencia() {
        val descuento = sinRed(pedido = """{"externalId":"k1","subtotal":12000,"discount":2000,"tip":500,"total":10500}""", amount = 10000)
        assertNull(evaluate(pagoDeOrdenNueva(amount = 10000, totalServidor = 10500), original = descuento, cola = true).motivo)
        val premio = sinRed(pedido = """{"externalId":"k1","subtotal":12000,"tip":0,"total":10000,"stampRewardId":"r1","stampRewardAware":true,"stampRewardExpectedDiscount":2000}""", amount = 10000, tip = 0)
        assertNull(evaluate(pagoDeOrdenNueva(amount = 10000, tip = 0, totalServidor = 10000), original = premio, cola = true).motivo)
    }
    // Q16 ronda 1: la regla es SÓLO de la cola sin red. Sin la bandera (cobro en línea, intents) queda como antes.
    @Test fun q16_el_mismo_precio_que_subio_fuera_de_la_cola_no_es_diferencia() {
        val enLinea = evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = sinRed())
        assertTrue(enLinea.aplicado); assertNull(enLinea.motivo); assertTrue(enLinea.camposDiferentes.isEmpty())
        assertFalse(enLinea.resultadoJson!!.contains("totalAlCobrarCents"))
        assertEquals(ConciliacionDelCobro.DIFERENCIA, evaluate(pagoDeOrdenNueva(totalServidor = 12500), original = sinRed(), cola = true).motivo)
    }
    // Ronda Codex código (P2-6): carrito y servidor redondean distinto sin que cambie el precio. Se tolera 1 centavo por
    // pieza del pedido capturado (mínimo 1 por renglón: una promoción y un renglón por peso cuentan 1; ilegible ⇒ 1¢).
    private val promo = """{"promotionRef":{"promotionId":"promo-3x2","promotionInstanceId":"i","selections":[]}}"""
    @Test fun q16_tres_promociones_3x2_redondeadas_por_pieza_no_son_diferencia() {
        val pedido = """{"externalId":"k1","items":[$promo,$promo,$promo],"subtotal":29997,"tip":0,"total":29997}"""
        val result = evaluate(pagoDeOrdenNueva(amount = 29997, tip = 0, totalServidor = 30000), original = sinRed(pedido, amount = 29997, tip = 0), cola = true)
        assertNull(result.motivo); assertTrue(result.camposDiferentes.isEmpty())
        assertFalse(result.resultadoJson!!.contains("totalAlCobrarCents"))
    }
    @Test fun q16_venta_por_peso_con_un_centavo_de_redondeo_no_es_diferencia_de_total() {
        val pedido = """{"externalId":"k1","items":[{"productId":"queso","quantity":1,"weightQuantity":0.5}],"subtotal":57,"tip":0,"total":57}"""
        val completo = evaluate(pagoDeOrdenNueva(amount = 57, tip = 0, totalServidor = 56), original = sinRed(pedido, amount = 57, tip = 0), cola = true)
        assertNull(completo.motivo); assertTrue(completo.camposDiferentes.isEmpty())
        // En efectivo el servidor registra a lo más el saldo ($0.56): el aviso Q15 por IMPORTE sigue (no se toca); el de total no.
        val efectivo = evaluate(pagoDeOrdenNueva(amount = 56, tip = 0, totalServidor = 56), original = sinRed(pedido, amount = 57, tip = 0), cola = true)
        assertEquals(listOf("amountCents"), efectivo.camposDiferentes)
    }
    // Equivalente de iOS `testTotalDeLaOrdenExactoAlCentavo`: avisa sólo si la diferencia PASA la tolerancia.
    @Test fun q16_total_avisa_solo_si_la_diferencia_pasa_la_tolerancia_de_un_centavo_por_pieza() {
        fun total(pedido: String, servidor: Int) =
            evaluate(pagoDeOrdenNueva(amount = 10000, totalServidor = servidor), original = sinRed(pedido, amount = 10000), cola = true)
        val uno = """{"externalId":"k1","items":[{"productId":"a","quantity":1}],"subtotal":10000,"tip":500,"total":10500}"""
        val subio = total(uno, 12500)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, subio.motivo); assertEquals(10000 to 12000, totales(subio))
        assertNull(total(uno, 10501).motivo); assertEquals(ConciliacionDelCobro.DIFERENCIA, total(uno, 10502).motivo)
        val cuatro = """{"externalId":"k1","items":[{"productId":"a","quantity":3},{"productId":"b","quantity":1}],"subtotal":10000,"tip":500,"total":10500}"""
        assertNull(total(cuatro, 10504).motivo); assertEquals(ConciliacionDelCobro.DIFERENCIA, total(cuatro, 10505).motivo)
        assertNull(total(cuatro, 10496).motivo); assertEquals(ConciliacionDelCobro.DIFERENCIA, total(cuatro, 10495).motivo)
        // Sin renglones que contar (pedido sin `items`) ⇒ 1¢.
        assertNull(evaluate(pagoDeOrdenNueva(totalServidor = 10506), original = sinRed(), cola = true).motivo)
        assertEquals(ConciliacionDelCobro.DIFERENCIA, evaluate(pagoDeOrdenNueva(totalServidor = 10507), original = sinRed(), cola = true).motivo)
    }
    @Test fun success_debe_ser_booleano_real_no_cadena_o_numero() {
        for (value in listOf("\"true\"", "1", "false")) {
            val result = evaluate(order().replace("\"success\":true", "\"success\":$value"))
            assertFalse(result.aplicado)
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO, result.motivo)
        }
    }
}
