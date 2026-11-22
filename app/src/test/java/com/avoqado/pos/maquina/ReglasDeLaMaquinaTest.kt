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
    fun `con todas las monedas en 2 y un billete de 20, 50 o 100 hay cambio suficiente`() {
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222222222"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222200000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222020000"))
        assertEquals(NivelDeCambio.SUFICIENTE, nivelDeCambio("222222100000"))
        assertEquals("el billete de 100 entra en la regla del plan", NivelDeCambio.SUFICIENTE, nivelDeCambio("222222002000"))
    }

    /** Codex código r2 H12: la regla del plan (Task C1) tal cual: cualquier moneda de $0.50 a $20 en `1` o en `0` es poco cambio. */
    @Test
    fun `P1 Codex r2 H12 cualquier moneda de 50 centavos a 20 pesos en 1 o en 0 es poco cambio`() {
        assertEquals("50 centavos en «poco» (el caso de los $10.50)", NivelDeCambio.POCO, nivelDeCambio("122222222222"))
        assertEquals("50 centavos vacía", NivelDeCambio.POCO, nivelDeCambio("022222222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("212222222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("220222222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222122222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222202222222"))
        assertEquals("la moneda de 20 pesos también cuenta", NivelDeCambio.POCO, nivelDeCambio("222221222222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222220222222"))
        assertEquals("la barra del simulador (sin tubos de 50 centavos ni de 20) avisa", NivelDeCambio.POCO, nivelDeCambio("022220222000"))
    }

    @Test
    fun `P1 sin billetes de 20, 50 ni 100 es poco cambio`() {
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222222000222"))
        assertEquals(NivelDeCambio.POCO, nivelDeCambio("222222000000"))
    }

    @Test
    fun `P1 Codex r2 H12 el aviso no depende del total - el mismo con el precio cotizado y la propina`() {
        assertEquals(TEXTO_POCO_CAMBIO, avisoDeCambio("122222222222"))
        assertEquals("sin barra no se sabe: poco cambio", TEXTO_POCO_CAMBIO, avisoDeCambio(null))
        assertNull(avisoDeCambio("222222222222"))
    }

    @Test
    fun `el ejemplo del manual sale poco cambio por la moneda de 10 en 1`() {
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
