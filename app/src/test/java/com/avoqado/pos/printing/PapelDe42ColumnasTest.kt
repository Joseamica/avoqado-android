package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.SavedPrinter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La Galeterie (5-oct): su impresora de barra es de 80 mm pero sólo imprime 512 puntos (42 columnas), no 576 (48).
 * Con «80mm» cada línea de 48 columnas se pasaba del borde: el código del vale salía a medias («90219») y el «$73.00»
 * quedaba en «$». Un rollo de 80 mm con área de 72 mm a 180 dpi es lo normal en estas impresoras.
 */
class PapelDe42ColumnasTest {

    @Test
    fun `el papel de 42 columnas mide 42 columnas y 512 puntos`() {
        assertEquals(42, PaperWidth.MM72.charsPerLine)
        assertEquals(512, PaperWidth.MM72.dots)
    }

    @Test
    fun `los anchos de siempre no cambian`() {
        assertEquals(32, PaperWidth.MM58.charsPerLine)
        assertEquals(384, PaperWidth.MM58.dots)
        assertEquals(48, PaperWidth.MM80.charsPerLine)
        assertEquals(576, PaperWidth.MM80.dots)
    }

    @Test
    fun `una impresora guardada con 72 usa el papel de 42 columnas y las demas siguen igual`() {
        fun papel(mm: Int) = SavedPrinter(id = "p", name = "BARRA", connectionType = "USB", address = "x", paperWidthMm = mm).paperWidth
        assertEquals(PaperWidth.MM72, papel(72))
        assertEquals(PaperWidth.MM58, papel(58))
        assertEquals(PaperWidth.MM80, papel(80))
        assertEquals("Un valor raro cae al de 80 como antes", PaperWidth.MM80, papel(60))
    }

    @Test
    fun `una fila de dos columnas y un divisor miden justo el papel de 42 columnas`() {
        val impresora = ESCPOSPrinter(paperWidth = PaperWidth.MM72)
        impresora.printTwoColumns("Importe de referencia", "\$73.00")
        impresora.printDivider('=')

        val (fila, divisor) = String(impresora.getData(), Charsets.ISO_8859_1).lines()
        assertEquals(42, fila.length)
        assertEquals("El importe termina en su sitio", "\$73.00", fila.takeLast(6))
        assertEquals(42, divisor.length)
    }

    @Test
    fun `el reset fija el area de impresion en 512 puntos para este papel`() {
        val comando = ESCPOSPrinter(paperWidth = PaperWidth.MM72).apply { reset() }.getData()
        // GS W nL nH con 512 = 0x0200 → 1D 57 00 02
        val enHex = comando.joinToString(" ") { "%02X".format(it) }
        assert(enHex.contains("1D 57 00 02")) { "No fija 512 puntos: $enHex" }
    }
}
