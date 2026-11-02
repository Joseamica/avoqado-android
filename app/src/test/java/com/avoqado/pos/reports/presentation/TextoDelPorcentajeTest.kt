package com.avoqado.pos.reports.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El Informe decía «Tarjeta de Débito $35.00 (0%)»: con dinero de por medio, lo que no llega a 1 % no es cero (iPad, 31-oct).
 * Mismo caso en iOS (`TextoDelPorcentajeTests`).
 */
class TextoDelPorcentajeTest {

    @Test
    fun `dinero que no llega a uno por ciento no dice cero`() {
        assertEquals("<1%", textoDelPorcentaje(0.4))
        assertEquals("<1%", textoDelPorcentaje(0.99))
    }

    @Test
    fun `sin dinero dice cero`() {
        assertEquals("0%", textoDelPorcentaje(0.0))
    }

    @Test
    fun `casi todo no dice cien`() {
        assertEquals(">99%", textoDelPorcentaje(99.6))
        assertEquals("100%", textoDelPorcentaje(100.0))
    }

    @Test
    fun `lo demas se redondea`() {
        assertEquals("1%", textoDelPorcentaje(1.0))
        assertEquals("34%", textoDelPorcentaje(33.5))
        assertEquals("66%", textoDelPorcentaje(66.4))
    }
}
