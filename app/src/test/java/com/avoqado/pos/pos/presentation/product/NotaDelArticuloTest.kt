package com.avoqado.pos.pos.presentation.product

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** D20 (WINDOWS 2.25.57, 22-nov): la nota del artículo es de una línea y sus notas rápidas se prenden y se apagan. */
class NotaDelArticuloTest {

    @Test
    fun `P2 una nota rapida que ya esta se quita, no se repite`() {
        assertEquals("", notaConNotaRapida("Sin cebolla", "Sin cebolla", 200))
        assertEquals("Sin hielo", notaConNotaRapida("Sin cebolla, Sin hielo", "Sin cebolla", 200))
        assertEquals("bien cocida, Sin hielo", notaConNotaRapida("bien cocida, sin cebolla, Sin hielo", "Sin cebolla", 200))
        // Lo que la persona escribió con otras palabras no cuenta como la nota rápida: se agrega.
        assertEquals("sin cebolla morada, Sin cebolla", notaConNotaRapida("sin cebolla morada", "Sin cebolla", 200))
    }

    @Test
    fun `P2 Enter no mete un salto y el cursor se queda donde iba`() {
        // «ab|» + Enter = «ab\n|»: queda «ab|».
        assertEquals(TextFieldValue("ab", TextRange(2)), sinSaltos(TextFieldValue("ab\n", TextRange(3))))
        // Pegado en medio: «a\r\nb|c» → «ab|c».
        assertEquals(TextFieldValue("abc", TextRange(2)), sinSaltos(TextFieldValue("a\r\nbc", TextRange(4))))
        // Sin saltos pasa el mismo valor (con la composición del teclado en pantalla).
        val sinCambio = TextFieldValue("hola", TextRange(4), composition = TextRange(0, 4))
        assertSame(sinCambio, sinSaltos(sinCambio))
    }
}
