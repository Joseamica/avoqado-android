package com.avoqado.pos.tables.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Recompensas» pinta «− $X en la cuenta» con lo que el canje del SERVIDOR descuenta de verdad.
 *
 * El SERVIDOR redondea `puntos × tasa` a centavos con `Math.round(x * 100) / 100` sobre el número binario; `String.format("%.2f")`
 * redondea a la mitad hacia arriba el decimal escrito. En un empate de medio centavo difieren: con 0.005 por punto, 29 puntos se
 * pintaban «$0.15» y el canje descontaba $0.14 (pizarrón, nube, 12-nov; encargo 1a3dbd del SERVIDOR). Los esperados de abajo los
 * calculó la misma fórmula del SERVIDOR (`puntosQueSeCanjean` en avoqado-server).
 */
class ValorDelCanjeTest {

    private data class Caso(val puntos: Int, val tasa: Double, val delServidor: Double)

    private val empates = listOf(
        Caso(50, 0.0201, 1.00), // %.2f directo pintaría 1.01
        Caso(250, 0.0059, 1.48), // %.2f directo pintaría 1.47
        Caso(29, 0.005, 0.14), // %.2f directo pintaría 0.15
        Caso(205, 0.005, 1.02), // %.2f directo pintaría 1.03
        Caso(207, 0.005, 1.03), // %.2f directo pintaría 1.04
        Caso(290, 0.0005, 0.14), // %.2f directo pintaría 0.15
        Caso(58, 0.0025, 0.14), // %.2f directo pintaría 0.15
        Caso(19, 0.015, 0.28), // %.2f directo pintaría 0.29
        Caso(27, 0.015, 0.41), // %.2f directo pintaría 0.40
        Caso(31, 0.015, 0.47), // %.2f directo pintaría 0.46
    )

    private fun lealtad(tasa: Double) = CustomerLoyalty(redemptionRate = tasa, balance = 100_000, maxRedeemablePoints = 100_000)

    @Test
    fun `en los empates de medio centavo, el valor es el que descuenta el servidor`() {
        for (c in empates) {
            val v = lealtad(c.tasa).valorDelCanje(c.puntos)
            assertEquals("${c.puntos} × ${c.tasa}", c.delServidor, v, 0.0)
            assertEquals("${c.puntos} × ${c.tasa}", String.format(java.util.Locale.US, "%.2f", c.delServidor), String.format(java.util.Locale.US, "%.2f", v))
        }
    }

    @Test
    fun `sin empate no cambia nada`() {
        assertEquals(223.0, lealtad(1.0).valorDelCanje(223), 0.0)
        assertEquals(0.98, lealtad(0.0201).valorDelCanje(49), 0.0)
        assertEquals(1.5, lealtad(0.0149).valorDelCanje(101), 0.0)
        assertEquals(0.0, lealtad(0.01).valorDelCanje(0), 0.0)
    }

    @Test
    fun `Canjear sólo con un número que el canje acepta - nunca 0 puntos ni un monto de $0 (Codex android r1 6)`() {
        val l = CustomerLoyalty(active = true, canRedeem = true, redemptionRate = 1.0, balance = 1, minPointsRedeem = 0, maxRedeemablePoints = 1)
        assertEquals(false, l.puedeCanjear(0))
        assertEquals(true, l.puedeCanjear(1))
        val chica = CustomerLoyalty(active = true, canRedeem = true, redemptionRate = 0.0001, balance = 100, minPointsRedeem = 10, maxRedeemablePoints = 100)
        assertEquals(false, chica.puedeCanjear(40)) // 40 × 0.0001 = $0.00: el canje lo rechaza
        assertEquals(true, chica.puedeCanjear(50)) // $0.01
        assertEquals(false, chica.puedeCanjear(101)) // arriba del máximo
        val conMinimo = CustomerLoyalty(active = true, canRedeem = true, redemptionRate = 1.0, balance = 500, minPointsRedeem = 10, maxRedeemablePoints = 90)
        assertEquals(false, conMinimo.puedeCanjear(9))
        assertEquals(true, conMinimo.puedeCanjear(10))
    }

    @Test
    fun `sin red «Canjear» se apaga aunque el número sea bueno - el diálogo abierto no ofrece lo que se va a rechazar (Codex android r2 4)`() {
        val l = CustomerLoyalty(active = true, canRedeem = true, redemptionRate = 1.0, balance = 500, minPointsRedeem = 10, maxRedeemablePoints = 90)
        assertEquals(true, l.puedeCanjear(50, enLinea = true))
        assertEquals(false, l.puedeCanjear(50, enLinea = false))
    }

    @Test
    fun `«Canjear» se apaga con el programa apagado o si el SERVIDOR dice que no se puede, aunque el rango sea bueno (Codex android r6 3)`() {
        // La GET real con el programa apagado: active=false, canRedeem=false, pero mínimo 100 y máximo 500.
        val apagado = CustomerLoyalty(active = false, canRedeem = false, redemptionRate = 0.10, balance = 500, minPointsRedeem = 100, maxRedeemablePoints = 500)
        assertEquals(false, apagado.puedeCanjear(500, enLinea = true))
        assertEquals(false, apagado.copy(active = true).puedeCanjear(500, enLinea = true))
        assertEquals(false, apagado.copy(canRedeem = true).puedeCanjear(500, enLinea = true))
        assertEquals(true, apagado.copy(active = true, canRedeem = true).puedeCanjear(500, enLinea = true))
    }

    @Test
    fun `máximo 0 que la cuenta contradice (Codex android r6 7)`() {
        val l = CustomerLoyalty(active = true, balance = 500, redemptionRate = 0.10, balanceValue = 50.0, minPointsRedeem = 10)
        val abierta = OrderDetail(id = "o1", subtotal = 100.0)
        assertEquals(true, maximoCeroContradiceLaCuenta(l, abierta))
        // Con pago, pagada, anulada o sin nada que descontar, el 0 es verdadero.
        assertEquals(false, maximoCeroContradiceLaCuenta(l, abierta.copy(paymentStatus = "PARTIAL")))
        assertEquals(false, maximoCeroContradiceLaCuenta(l, abierta.copy(paymentStatus = "PAID")))
        assertEquals(false, maximoCeroContradiceLaCuenta(l, abierta.copy(status = "CANCELLED")))
        assertEquals(false, maximoCeroContradiceLaCuenta(l, abierta.copy(subtotal = 50.0, discountAmount = 50.0)))
        assertEquals(false, maximoCeroContradiceLaCuenta(l, abierta.copy(payments = listOf(OrderDetailPayment()))))
        // El 0 lo explican los puntos (pocos, valen $0.00, programa apagado) o hay algo que descontar: nada que contradecir.
        assertEquals(false, maximoCeroContradiceLaCuenta(l.copy(balance = 5), abierta))
        assertEquals(false, maximoCeroContradiceLaCuenta(l.copy(balanceValue = 0.0), abierta))
        assertEquals(false, maximoCeroContradiceLaCuenta(l.copy(active = false), abierta))
        assertEquals(false, maximoCeroContradiceLaCuenta(l.copy(maxRedeemableValue = 0.01), abierta))
    }

    @Test
    fun `el rechazo del canje se lee una sola vez - código y mensaje del SERVIDOR (candado de la oferta)`() {
        val r = canjeRechazadoDe("""{"message":"La cuenta cambió: ahora se canjearían 10 puntos por ${'$'}1.00.","code":"LOYALTY_OFFER_CHANGED"}""")
        assertEquals("LOYALTY_OFFER_CHANGED", r.code)
        assertEquals(true, r.ofertaCambio)
        assertEquals("La cuenta cambió: ahora se canjearían 10 puntos por ${'$'}1.00.", r.message)
        val otro = canjeRechazadoDe("""{"message":"Puntos insuficientes: el cliente tiene 400"}""")
        assertEquals(false, otro.ofertaCambio)
        assertEquals("Puntos insuficientes: el cliente tiene 400", otro.message)
        assertEquals(false, canjeRechazadoDe(null).ofertaCambio)
        assertEquals(false, canjeRechazadoDe("<html>").ofertaCambio)
    }
}
