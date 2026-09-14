package com.avoqado.pos.printing.receiptlayout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puerto de las pruebas del intérprete de referencia (avoqado-server
 * `tests/unit/services/shared/receiptLayout/{columns,sanitizeText,address}.test.ts`).
 * Cada valor esperado salió de correr el código del servidor: si cambia aquí y no allá, la app
 * imprime distinto de lo que el dueño vio en la vista previa del dashboard.
 */
class ReceiptTextTest {

    private fun cp(code: Int) = String(Character.toChars(code))

    @Test
    fun `P1 force borra controles, bidi y ancho cero, y lo no imprimible vale UN signo por code point`() {
        assertEquals("Café", ReceiptText.force("Cafe" + cp(0x301)))
        assertEquals("ab", ReceiptText.force("a" + cp(0x1B) + "b"))
        assertEquals("?x", ReceiptText.force(cp(0x1F600) + "x"))
        assertEquals("abc", ReceiptText.force(cp(0x202E) + "abc"))
        assertEquals("q?", ReceiptText.force("q" + cp(0x301)))
        assertEquals("Mesa 5 ? PRE", ReceiptText.force("Mesa 5 " + cp(0x2014) + " PRE"))
        assertEquals("??", ReceiptText.force(cp(0x2764) + cp(0xFE0F)))
    }

    @Test
    fun `hasForbiddenChars es el candado del servidor - ESC y el euro no pasan, la enie si`() {
        assertFalse(ReceiptText.hasForbiddenChars("Hola ñ"))
        assertTrue(ReceiptText.hasForbiddenChars(cp(0x1B)))
        assertTrue(ReceiptText.hasForbiddenChars(cp(0x20AC)))
    }

    @Test
    fun `wrap parte por palabras y corta la palabra que no cabe`() {
        assertEquals(
            listOf("Nápoles 47, Cuauhtémoc, Ciudad", "de México, CP 06600"),
            ReceiptText.wrap("Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600", 32),
        )
        assertEquals(listOf("abcd", "efgh", "ij"), ReceiptText.wrap("abcdefghij", 4))
        assertEquals(listOf(""), ReceiptText.wrap("   ", 10))
    }

    @Test
    fun `P1 twoColumnLines nunca se pasa del ancho`() {
        assertEquals(listOf("Subtotal:" + " ".repeat(30) + "\$1,234.50"), ReceiptText.twoColumnLines("Subtotal:", "\$1,234.50", 48))
        val r = ReceiptText.twoColumnLines("Atendió:", "María Guadalupe Fernández de la Garza Ortega", 32)
        assertEquals(listOf("Atendió:", " María Guadalupe Fernández de la", " ".repeat(20) + "Garza Ortega"), r)
        r.forEach { assertTrue(it.length <= 32) }
    }

    @Test
    fun `P1 itemLines envuelve el nombre bajo su columna y el encabezado ya no sale pegado`() {
        assertEquals(listOf("1    Café americano       \$45.00", "     grande con leche"), ReceiptText.itemLines("1", "Café americano grande con leche", "\$45.00", 32))
        assertEquals(listOf("Cant Artículo             Precio"), ReceiptText.itemLines("Cant", "Artículo", "Precio", 32))
        assertEquals(listOf("2    Servicio        \$123,456.78"), ReceiptText.itemLines("2", "Servicio", "\$123,456.78", 32))
        assertEquals(listOf("       1x Café" + " ".repeat(18)), ReceiptText.itemLines("", "1x Café", "", 32, 2))
    }

    @Test
    fun `P1 una cantidad de 5 o 6 digitos ensancha su columna - ni se pega al nombre ni se pasa del papel`() {
        assertEquals(listOf("10000 Artículo             \$1.00"), ReceiptText.itemLines("10000", "Artículo", "\$1.00", 32))
        assertEquals(listOf("123456 Artículo            \$1.00"), ReceiptText.itemLines("123456", "Artículo", "\$1.00", 32))
        val largo = ReceiptText.itemLines("123456", "Café americano grande", "\$1.00", 32)
        assertEquals(listOf("123456 Café americano      \$1.00", "       grande"), largo)
        largo.forEach { assertTrue(it.length <= 32) }
    }

    @Test
    fun `indentedLines sangra 2 y envuelve`() {
        assertEquals(
            listOf("  + Leche de almendra orgánica", "  sin azúcar añadida"),
            ReceiptText.indentedLines("+ Leche de almendra orgánica sin azúcar añadida", 32),
        )
    }

    @Test
    fun `P1 addressLine no repite ciudad, estado ni CP que la direccion ya dice`() {
        assertEquals(
            "Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600",
            ReceiptText.addressLine("Nápoles 47", "Cuauhtémoc", "Ciudad de México", "06600"),
        )
        val completa = "Monte Himalaya 408, Lomas de Chapultepec, Miguel Hidalgo, 11000 Ciudad de México, CDMX, México"
        assertEquals(completa, ReceiptText.addressLine(completa, "Ciudad de Mexico", "cdmx", "11000"))
        assertNull(ReceiptText.addressLine(null, "  ", null, null))
    }
}
