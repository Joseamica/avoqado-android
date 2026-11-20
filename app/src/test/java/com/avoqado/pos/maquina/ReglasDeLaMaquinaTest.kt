package com.avoqado.pos.maquina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasDeLaMaquinaTest {

    @Test
    fun `P1 la máquina sólo cobra montos positivos múltiplos de 50 centavos`() {
        assertTrue(cobrableEnMaquina(18_750))
        assertTrue(cobrableEnMaquina(50))
        assertTrue(cobrableEnMaquina(100_000))
        assertFalse(cobrableEnMaquina(18_735))
        assertFalse(cobrableEnMaquina(0))
        assertFalse(cobrableEnMaquina(-50))
        assertFalse(cobrableEnMaquina(25))
    }

    @Test
    fun `con las monedas de 1 a 10 pesos en 2 y un billete de 20 o de 50 hay cambio suficiente`() {
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222222222"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("022220200000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("022220020000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("022220100000"))
    }

    @Test
    fun `P1 una moneda de 1, 2, 5 o 10 pesos en 1 o en 0 es poco cambio`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("212222222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("220222222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222122222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222202222222"))
    }

    @Test
    fun `P1 sin billetes de 20 ni de 50 es poco cambio aunque haya de 100 o mas`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222222002222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("022220002000"))
    }

    /**
     * La máquina real sólo trae tubos de $1, $2, $5 y $10: en los ejemplos de APS las posiciones de $0.50 (0) y de la moneda
     * de $20 (5) siempre vienen en `0`. Antes cualquiera de las dos en `0` daba POCO, así que la máquina nunca se veía con
     * cambio suficiente. Ya no cuentan para el nivel.
     */
    @Test
    fun `P1 las posiciones de 50 centavos y de la moneda de 20 ya no bajan el nivel`() {
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("022220222000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("122221220000"))
    }

    @Test
    fun `el ejemplo del manual sale poco cambio por la moneda de 10 en 1`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("022210222000"))
    }

    @Test
    fun `las monedas de 50 centavos se informan aparte`() {
        assertEquals(false, hayMonedasDe50("022222222222"))
        assertEquals(true, hayMonedasDe50("122222222222"))
        assertEquals(true, hayMonedasDe50("200000000000"))
        assertNull(hayMonedasDe50(null))
        assertNull(hayMonedasDe50("2222"))
        assertNull(hayMonedasDe50("32222222222X"))
    }

    @Test
    fun `sin barra o con una barra rara no sabemos`() {
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio(null))
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio(""))
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio("22222222222"))
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio("2222222222222"))
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio("22222222222X"))
        assertEquals(NivelDeCambio.DESCONOCIDO, nivelDeCambio("222222322222"))
    }
}
