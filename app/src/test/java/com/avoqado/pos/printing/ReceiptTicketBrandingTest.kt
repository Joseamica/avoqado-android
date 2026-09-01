package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.MonoRaster
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ticket de venta con identidad del negocio (encabezado fiscal estilo
 * SoftRestaurant) y la firma "Powered by Avoqado" (founder, 2026-09-01).
 *
 * Lo que estas pruebas protegen: (1) las líneas fiscales salen y en el ORDEN
 * del referente; (2) un venue sin emisor imprime el ticket de SIEMPRE — nada
 * de "RFC:" vacíos; (3) el ráster emite `GS v 0` bien formado y una imagen que
 * no cabe NO escribe bytes (un logo mocho es peor que caer al texto).
 */
class ReceiptTicketBrandingTest {

    private fun receipt(
        legalName: String? = null,
        rfc: String? = null,
        lugarExpedicion: String? = null,
        address: String? = null,
        logo: MonoRaster? = null,
        mark: MonoRaster? = null,
    ) = ReceiptData(
        orderNumber = "42",
        orderType = "En tienda",
        items = listOf(ReceiptItem(name = "Galleta", quantity = 1, unitPrice = 4500, totalPrice = 4500)),
        subtotal = 4500,
        taxAmount = 0,
        total = 4500,
        venueName = "Testarudo Cafe",
        venueAddress = address,
        venueLegalName = legalName,
        venueRfc = rfc,
        venueLugarExpedicion = lugarExpedicion,
        venueLogoRaster = logo,
        poweredByAvoqadoRaster = mark,
    )

    private fun printedText(data: ReceiptData): String =
        String(ESCPOSPrinter(PaperWidth.MM80).generateReceipt(data), Charsets.ISO_8859_1)

    // MARK: - Encabezado fiscal

    @Test
    fun `el encabezado fiscal sale completo y en el orden de SoftRestaurant`() {
        val text = printedText(
            receipt(
                legalName = "TESTARUDO CAFE S.A.P.I. DE C.V.",
                rfc = "TCA2501231A6",
                lugarExpedicion = "06600",
                address = "Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600",
            ),
        )

        assertTrue(text.contains("TESTARUDO CAFE S.A.P.I. DE C.V."))
        assertTrue(text.contains("RFC: TCA2501231A6"))
        assertTrue(text.contains("Nápoles 47"))
        assertTrue(text.contains("Lugar de expedición: CP 06600"))

        // El orden: nombre → razón social → RFC → dirección → lugar de expedición.
        val name = text.indexOf("Testarudo Cafe")
        val legal = text.indexOf("TESTARUDO CAFE S.A.P.I.")
        val rfc = text.indexOf("RFC: ")
        val addr = text.indexOf("Nápoles 47")
        val lugar = text.indexOf("Lugar de expedición")
        assertTrue("orden del encabezado", name < legal && legal < rfc && rfc < addr && addr < lugar)
        // Y todo el encabezado va ANTES del primer renglón de la venta.
        assertTrue(lugar < text.indexOf("Galleta"))
    }

    @Test
    fun `sin emisor fiscal el ticket no imprime etiquetas vacias`() {
        val text = printedText(receipt())
        assertFalse(text.contains("RFC:"))
        assertFalse(text.contains("Lugar de expedición"))
        // El ticket de siempre sigue entero.
        assertTrue(text.contains("Testarudo Cafe"))
        assertTrue(text.contains("Galleta"))
    }

    // MARK: - Powered by Avoqado

    @Test
    fun `la firma Powered by Avoqado sale al final aunque no haya rastercillo`() {
        val text = printedText(receipt())
        val firma = text.indexOf("Powered by Avoqado")
        assertTrue(firma >= 0)
        assertTrue("va después del gracias", text.indexOf("Gracias por su compra!") < firma)
    }

    @Test
    fun `con isotipo la firma lleva el raster antes del texto`() {
        val mark = MonoRaster.threshold(8, 1, intArrayOf(0, 0, 0, 0, 255, 255, 255, 255))
        val bytes = ESCPOSPrinter(PaperWidth.MM80).generateReceipt(receipt(mark = mark))
        assertTrue(containsRasterHeader(bytes, widthBytes = 1, height = 1))
    }

    // MARK: - Ráster GS v 0

    @Test
    fun `el empaquetado es MSB primero y 1 = negro`() {
        // Blanco/negro alternado: negro en x pares → 0b10101010 = 0xAA.
        val raster = MonoRaster.threshold(8, 1, intArrayOf(0, 255, 0, 255, 0, 255, 0, 255))
        assertEquals(1, raster.widthBytes)
        assertEquals(0xAA.toByte(), raster.bits[0])
    }

    @Test
    fun `printRaster emite la cabecera GS v 0 con ancho en BYTES y alto en puntos`() {
        val printer = ESCPOSPrinter(PaperWidth.MM80)
        printer.reset()
        val raster = MonoRaster(widthDots = 16, heightDots = 2, bits = ByteArray(4) { 0xFF.toByte() })
        assertTrue(printer.printRaster(raster))
        assertTrue(containsRasterHeader(printer.getData(), widthBytes = 2, height = 2))
    }

    @Test
    fun `una imagen mas ancha que el papel no escribe un solo byte`() {
        val printer = ESCPOSPrinter(PaperWidth.MM58) // 384 puntos
        printer.reset()
        val before = printer.getData().size
        val tooWide = MonoRaster(widthDots = 400, heightDots = 1, bits = ByteArray(50))
        assertFalse(printer.printRaster(tooWide))
        assertEquals("no debe escribir bytes", before, printer.getData().size)
    }

    @Test
    fun `el dithering conserva negro puro y blanco puro`() {
        val raster = MonoRaster.dither(8, 1, intArrayOf(0, 0, 0, 0, 255, 255, 255, 255))
        assertEquals(0xF0.toByte(), raster.bits[0])
    }

    /** Busca `GS v 0 m xL xH yL yH` con las dimensiones dadas. */
    private fun containsRasterHeader(data: ByteArray, widthBytes: Int, height: Int): Boolean {
        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte(),
        )
        outer@ for (i in 0..data.size - header.size) {
            for (j in header.indices) {
                if (data[i + j] != header[j]) continue@outer
            }
            return true
        }
        return false
    }
}
