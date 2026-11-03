package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.presentation.enDosColumnas
import com.avoqado.pos.transactions.presentation.pieFijoEnDosColumnas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo «Emitir reembolso» va en dos columnas y cuándo su pie queda fijo.
 *
 * El corte es por ANCHO con los tamaños reales de la flota (Terminal en producción, 2-nov), nunca por
 * nombre de aparato: la Samsung de 8.7" acostada (1007 dp) va en dos y parada (601 dp) en una.
 */
class AcomodoDeReembolsoTest {

    @Test
    fun `el corte es por ancho, con los tamaños reales`() {
        assertFalse(enDosColumnas(839f, false))
        assertTrue(enDosColumnas(840f, false))
        assertTrue(enDosColumnas(1007f, false)) // Samsung 8.7" acostada
        assertFalse(enDosColumnas(601f, false)) // Samsung 8.7" parada
        assertTrue(enDosColumnas(1280f, false)) // Sunmi D3
    }

    @Test
    fun `la tarjeta de regalo siempre va en una columna`() {
        assertFalse(enDosColumnas(1280f, true))
    }

    @Test
    fun `con poco alto el pie deja de ser fijo`() {
        assertFalse(pieFijoEnDosColumnas(419f))
        assertTrue(pieFijoEnDosColumnas(420f))
    }
}
