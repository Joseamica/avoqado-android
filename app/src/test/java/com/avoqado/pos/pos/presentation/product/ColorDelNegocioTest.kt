package com.avoqado.pos.pos.presentation.product

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Revisión visual (30-oct): blanco sobre «Postres» en amarillo no se leía. Mismos casos que `PulidoVisualTests` de iOS. */
class ColorDelNegocioTest {

    @Test
    fun `colores claros llevan letra oscura`() {
        assertTrue(esColorClaro("#FFEB3B")) // amarillo de «Postres»
        assertTrue(esColorClaro("#CDDC39")) // verde limón
        assertTrue(esColorClaro("#FFC107")) // ámbar
        assertTrue(esColorClaro("#FFFFFF"))
        assertTrue(esColorClaro("FFFFEB3B")) // con opacidad delante (orden de Android) y sin «#»
    }

    @Test
    fun `colores fuertes siguen con letra blanca`() {
        assertFalse(esColorClaro("#FF5722")) // naranja de «Hamburguesas»
        assertFalse(esColorClaro("#4CAF50")) // verde
        assertFalse(esColorClaro("#2196F3")) // azul
        assertFalse(esColorClaro("#E91E63")) // rosa
        assertFalse(esColorClaro("#9C27B0")) // morado
        assertFalse(esColorClaro("#000000"))
    }

    @Test
    fun `un color casi transparente conserva la letra blanca`() {
        // Lo que se ve es el fondo de atrás (en oscuro, negro): letra negra ahí sería 1:1 (Codex r1 #3).
        assertFalse(esColorClaro("#00FFFFFF"))
        assertFalse(esColorClaro("#40FFEB3B"))
        assertTrue(esColorClaro("#CCFFEB3B")) // casi opaco: sí cuenta
    }

    @Test
    fun `la letra que sale sobre cada color`() {
        // Se prueba el color de LETRA que resulta, no sólo `esColorClaro` (Codex r2 #1).
        val delTema = Color.Red
        assertEquals(Color.Black.copy(alpha = 0.85f), letraSobreColorDelNegocio("#FFEB3B", delTema)) // amarillo: oscura
        assertEquals(Color.White, letraSobreColorDelNegocio("#FF5722", delTema)) // naranja: blanca
        assertEquals(delTema, letraSobreColorDelNegocio("#00FFFFFF", delTema)) // transparente: la del tema
        assertEquals(delTema, letraSobreColorDelNegocio("#40FFEB3B", delTema))
    }

    @Test
    fun `un color invalido no se trata como claro`() {
        assertFalse(esColorClaro("amarillo"))
        assertFalse(esColorClaro(""))
        assertFalse(esColorClaro("#FFF"))
    }
}
