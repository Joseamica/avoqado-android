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
}
