package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Etiqueta de precio para el anaquel (Inventario → Descripción general → impresora, PRO).
 *
 * Lo que no puede fallar: que el código de barras de la etiqueta sea el MISMO que el POS busca al
 * escanear (GTIN, luego código de barras, luego SKU), y que un código que la impresora no sabe
 * dibujar salga como texto en vez de desaparecer — sin él, el cajero no tiene qué teclear.
 */
class EtiquetaDePrecioTest {

    private fun ByteArray.contiene(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun ByteArray.veces(needle: ByteArray): Int {
        var n = 0
        var i = 0
        while (i <= size - needle.size) {
            if (needle.indices.all { this[i + it] == needle[it] }) { n++; i += needle.size } else i++
        }
        return n
    }

    private fun etiqueta(codigo: String?, copias: Int = 1) = EtiquetaDePrecio(
        nombre = "Cafe de olla",
        precio = "$45.00",
        codigo = codigo,
        negocio = "Testarudo",
        copias = copias,
    )

    private fun imprimir(vararg etiquetas: EtiquetaDePrecio): ByteArray =
        ESCPOSPrinter(PaperWidth.MM58).generatePriceLabels(etiquetas.toList())

    // MARK: - Qué código lleva la etiqueta

    @Test
    fun `el codigo prefiere GTIN, luego codigo de barras, luego SKU`() {
        assertEquals("7501", EtiquetaDePrecio.codigoDe(gtin = "7501", barcode = "B1", sku = "S1"))
        assertEquals("B1", EtiquetaDePrecio.codigoDe(gtin = "  ", barcode = "B1", sku = "S1"))
        assertEquals("S1", EtiquetaDePrecio.codigoDe(gtin = null, barcode = null, sku = " S1 "))
        assertNull(EtiquetaDePrecio.codigoDe(gtin = null, barcode = "", sku = "  "))
    }

    // MARK: - CODE128 conjunto B (SKU con letras)

    @Test
    fun `CODE128 B codifica ASCII imprimible con el prefijo de conjunto B`() {
        val bytes = ESCPOSPrinter.encodeBarcodeData("AB-12", BarcodeSymbology.CODE128_B)
        assertArrayEquals(byteArrayOf(0x7B, 0x42) + "AB-12".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `CODE128 B rechaza lo que no puede dibujar sin mentir`() {
        assertNull(ESCPOSPrinter.encodeBarcodeData("", BarcodeSymbology.CODE128_B))
        assertNull(ESCPOSPrinter.encodeBarcodeData("PIÑA", BarcodeSymbology.CODE128_B))
        // `{` abre un comando de conjunto en ESC/POS: mandarlo crudo cambia lo que se escanea.
        assertNull(ESCPOSPrinter.encodeBarcodeData("A{B", BarcodeSymbology.CODE128_B))
    }

    // MARK: - Los bytes de la etiqueta

    @Test
    fun `la etiqueta lleva nombre, precio y negocio`() {
        val bytes = imprimir(etiqueta(codigo = null))
        assertTrue(bytes.contiene("Cafe de olla".toByteArray()))
        assertTrue(bytes.contiene("$45.00".toByteArray()))
        assertTrue(bytes.contiene("Testarudo".toByteArray()))
    }

    @Test
    fun `un codigo numerico par sale en CODE128 C`() {
        val bytes = imprimir(etiqueta(codigo = "750100"))
        assertTrue(bytes.contiene(byteArrayOf(0x1D, 0x6B, 73, 5, 0x7B, 0x43)))
    }

    @Test
    fun `un SKU con letras sale en CODE128 B`() {
        val bytes = imprimir(etiqueta(codigo = "CAF-01"))
        assertTrue(bytes.contiene(byteArrayOf(0x1D, 0x6B, 73, 8, 0x7B, 0x42)))
    }

    @Test
    fun `un codigo que no se puede dibujar sale como texto, no desaparece`() {
        val bytes = imprimir(etiqueta(codigo = "PIÑA{1"))
        assertTrue("sin GS k", !bytes.contiene(byteArrayOf(0x1D, 0x6B)))
        assertTrue(bytes.contiene("{1".toByteArray()))
    }

    @Test
    fun `sin codigo no hay barras`() {
        assertTrue(!imprimir(etiqueta(codigo = null)).contiene(byteArrayOf(0x1D, 0x6B)))
    }

    @Test
    fun `cada copia es una etiqueta propia, con su corte`() {
        val bytes = imprimir(etiqueta(codigo = "750100", copias = 3), etiqueta(codigo = null, copias = 2))
        assertEquals(5, bytes.veces("Cafe de olla".toByteArray()))
        assertEquals(3, bytes.veces(byteArrayOf(0x1D, 0x6B)))
        assertEquals(5, bytes.veces(ESCPOSPrinter.PARTIAL_CUT))
    }
}
