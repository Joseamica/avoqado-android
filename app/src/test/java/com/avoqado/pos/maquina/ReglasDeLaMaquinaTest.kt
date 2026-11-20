package com.avoqado.pos.maquina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `con todo en 2 hay cambio suficiente`() {
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222222222"))
    }

    @Test
    fun `una moneda en 1 o en 0 es poco cambio`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("111111222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222220222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("022222222222"))
    }

    @Test
    fun `sin billetes de 20, 50 y 100 es poco cambio, con uno basta`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222222000222"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222100000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222002000"))
    }

    @Test
    fun `el ejemplo del manual (sin tubo de 50 centavos ni de 20 pesos) sale poco cambio`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("022210222000"))
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
