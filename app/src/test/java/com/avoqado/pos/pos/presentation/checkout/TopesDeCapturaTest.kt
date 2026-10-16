package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.pos.presentation.product.notaConNotaRapida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 (mesas40, 14-oct): los topes se ponen en el cliente ANTES de confirmar, porque el servidor ya no rechaza importes
 * (registra dinero que YA se recibió) y la nota del artículo la recorta él sin avisar.
 */
class TopesDeCapturaTest {

    @Test
    fun `P1 el teclado compara el importe RESULTANTE con el tope, no el anterior`() {
        // $999,999.99 + otro dígito serían ~$10 millones: antes pasaba porque se comparaba el importe de ANTES.
        assertEquals(99_999_999, siguienteMontoDelTeclado(99_999_999, 9))
        assertEquals(10_000_001, siguienteMontoDelTeclado(10_000_001, 0))
        // Justo en el tope sí entra; un centavo más, no.
        assertEquals(MAX_MONTO_DEL_TECLADO_CENTS, siguienteMontoDelTeclado(10_000_000, 0))
        assertEquals(10_000_000, siguienteMontoDelTeclado(10_000_000, 1))
        // Lo normal no cambia: $1 → $15.
        assertEquals(1_505, siguienteMontoDelTeclado(150, 5))
    }

    @Test
    fun `P2 una nota rapida no deja la nota del articulo por encima de 200`() {
        val larga = "x".repeat(195)
        val nota = notaConNotaRapida(larga, "Extra picante", 200)
        assertEquals(200, nota.length)
        assertEquals("$larga, Ext", nota)
        // Lo normal no cambia.
        assertEquals("Sin hielo", notaConNotaRapida("", "Sin hielo", 200))
        assertEquals("Sin hielo, Sin azúcar", notaConNotaRapida("Sin hielo", "Sin azúcar", 200))
    }

    @Test
    fun `P2 el recorte nunca parte un emoji a la mitad`() {
        val nota = notaConNotaRapida("x".repeat(197), "🌶 picante", 200) // "xxx…, 🌶 picante": el chile cae en 199-200
        assertTrue(nota.length <= 200)
        assertFalse("no puede quedar media pareja suelta", nota.last().isHighSurrogate())
    }
}
