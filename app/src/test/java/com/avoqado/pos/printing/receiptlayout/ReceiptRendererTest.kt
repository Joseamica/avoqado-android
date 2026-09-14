package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.MonoRaster
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRendererTest {

    private val header = ESCPOSPrinter(PaperWidth.MM80).apply { reset() }.getData()

    private fun body(lines: List<LogicalLine>, raster: MonoRaster? = null): List<Int> =
        ESCPOSPrinter(PaperWidth.MM80).renderReceipt(lines) { _, _ -> raster }
            .drop(header.size).map { it.toInt() and 0xFF }

    @Test
    fun `P1 texto grande y en negritas - primero ESC bang, despues ESC E, y se apagan al reves`() {
        val bytes = body(listOf(LogicalLine.Text("TOTAL", Align.LEFT, bold = true, double = true)))
        val expected = listOf(0x1B, 0x61, 0x00, 0x1B, 0x21, 0x30, 0x1B, 0x45, 0x01) +
            "TOTAL".map { it.code } + listOf(0x0A, 0x1B, 0x45, 0x00, 0x1B, 0x21, 0x00)
        assertEquals(expected, bytes)
    }

    @Test
    fun `P1 un ESC guardado en el texto NUNCA llega a la impresora`() {
        val bytes = body(listOf(LogicalLine.Text("a" + Char(0x1B) + "b", Align.LEFT, bold = false, double = false)))
        assertEquals(listOf(0x1B, 0x61, 0x00, 'a'.code, 'b'.code, 0x0A), bytes)
    }

    @Test
    fun `sin raster no hay imagen ni alineacion suelta`() {
        assertEquals(emptyList<Int>(), body(listOf(LogicalLine.Image(ImageRef.LOGO, 60, Align.CENTER))))
        val conRaster = body(listOf(LogicalLine.Image(ImageRef.LOGO, 60, Align.CENTER)), MonoRaster(widthDots = 8, heightDots = 1, bits = byteArrayOf(0xAA.toByte())))
        assertEquals(listOf(0x1B, 0x61, 0x01, 0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, 0xAA), conRaster)
    }

    @Test
    fun `P1 la imagen se alinea con la linea - un logo a la derecha manda ESC a 2`() {
        val raster = MonoRaster(widthDots = 8, heightDots = 1, bits = byteArrayOf(0xAA.toByte()))
        assertEquals(
            listOf(0x1B, 0x61, 0x02, 0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, 0xAA),
            body(listOf(LogicalLine.Image(ImageRef.LOGO, 40, Align.RIGHT)), raster),
        )
    }

    @Test
    fun `P2 un raster mas ancho que el papel no escribe nada - ni la alineacion`() {
        // 80 mm = 576 puntos; uno de 584 no cabe y printRaster lo rechaza.
        val ancho = MonoRaster(widthDots = 584, heightDots = 1, bits = ByteArray(73))
        assertEquals(emptyList<Int>(), body(listOf(LogicalLine.Image(ImageRef.LOGO, 80, Align.LEFT)), ancho))
    }

    /** Los bytes de `GS ( k` para un contenido ya codificado. */
    private fun qr(payload: List<Int>): List<Int> {
        val len = payload.size + 3
        return listOf(0x1B, 0x61, 0x01) +
            listOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00) +
            listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x07) +
            listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31) +
            listOf(0x1D, 0x28, 0x6B, len and 0xFF, (len shr 8) and 0xFF, 0x31, 0x50, 0x30) +
            payload +
            listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)
    }

    @Test
    fun `P1 el QR viaja en UTF-8 - una enie son dos bytes y la longitud los cuenta`() {
        val ascii = "https://r/x/".map { it.code }
        // «ñ» = C3 B1: 12 + 2 = 14 bytes, longitud 17 (0x11). En Latin-1 serían 13 bytes con F1.
        assertEquals(qr(ascii + listOf(0xC3, 0xB1)), body(listOf(LogicalLine.Qr("https://r/x/" + Char(0x00F1)))))
        // «€» = E2 82 AC: fuera de Latin-1 no se pierde.
        assertEquals(qr(ascii + listOf(0xE2, 0x82, 0xAC)), body(listOf(LogicalLine.Qr("https://r/x/" + Char(0x20AC)))))
    }

    @Test
    fun `el codigo del vale usa las medidas del recibo y uno invalido no escribe barras`() {
        val bytes = body(listOf(LogicalLine.Barcode("9016719357")))
        assertEquals(listOf(0x1B, 0x61, 0x01, 0x1D, 0x68, 162, 0x1D, 0x77, 3, 0x1D, 0x48, 2, 0x1D, 0x6B, 73, 7, 0x7B, 0x43, 90, 16, 71, 93, 57), bytes)
        val invalido = body(listOf(LogicalLine.Barcode("ABC")))
        assertEquals(listOf(0x1B, 0x61, 0x01), invalido)
        assertFalse(invalido.contains(0x6B))
    }

    @Test
    fun `feed y corte`() {
        assertEquals(listOf(0x0A, 0x0A, 0x1B, 0x64, 0x05, 0x1D, 0x56, 0x01), body(listOf(LogicalLine.Feed(2), LogicalLine.Cut)))
        assertTrue(header.isNotEmpty())
    }
}
