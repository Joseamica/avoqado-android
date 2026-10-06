package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Vale de caja externa (spec 2026-09-30): el vale lo cobra OTRO POS, cuya pistola 1D lee un código
 * por pieza. Las dos doradas fijan que el vale NORMAL sale byte por byte igual que antes del cambio:
 * se sacaron del código SIN CAMBIOS (procedimiento en el reporte de la Task 9). Nunca se editan a mano.
 */
class AreaTicketExternalPrintTest {

    @Before fun zona() = com.avoqado.pos.core.util.VenueTimeZone.set("America/Mexico_City")

    private fun normal() = AreaTicketData(
        areaTicketCode = "9470000015",
        areaName = "Cafetería",
        items = listOf(
            ReceiptItem(name = "Latte", quantity = 2, unitPrice = 5500, totalPrice = 14000, note = "Sin espuma"),
            ReceiptItem(name = "LOMO CANADIENSE", quantity = 1, unitPrice = 16400, totalPrice = 3674, weightSummary = "0.224 kg × \$164.00/kg"),
        ),
        totalCents = 17674,
        venueName = "La Galeterie",
        staffName = "Rosa",
        timestamp = java.util.Date(1_790_000_000_000L),
        holdsProduct = true,
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `P1 el vale normal sale byte por byte igual que antes - 58 mm CODE128`() {
        assertEquals(DORADO_58_CODE128, hex(ESCPOSPrinter(paperWidth = PaperWidth.MM58).generateAreaTicket(normal())))
    }

    @Test
    fun `P1 el vale normal sale byte por byte igual que antes - 80 mm CODE39`() {
        assertEquals(DORADO_80_CODE39, hex(ESCPOSPrinter(paperWidth = PaperWidth.MM80).generateAreaTicket(normal(), BarcodeSymbology.CODE39)))
    }

    // MARK: - El papel de caja externa

    private fun externo() = normal().copy(
        items = listOf(
            ReceiptItem(
                name = "Latte", quantity = 2, unitPrice = 5500, totalPrice = 14000,
                modifiers = listOf("Shot de espresso"),
                externalCodes = listOf("P000500", "P000500", "P000672", "P000672"),
            ),
            ReceiptItem(name = "Galleta", quantity = 1, unitPrice = 2000, totalPrice = 2000, externalCodes = listOf("7501234567")),
        ),
        totalCents = 16000,
        externalRoute = true,
    )

    private fun render(t: AreaTicketData, paper: PaperWidth = PaperWidth.MM58) =
        ESCPOSPrinter(paperWidth = paper).generateAreaTicket(t)

    private fun ocurrencias(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).count { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    /** `GS k 73 n {B…` — CODE128-B con su payload exacto. */
    private fun code128B(code: String) =
        byteArrayOf(0x1D, 0x6B, 73, (code.length + 2).toByte(), 0x7B, 0x42) + code.toByteArray(Charsets.US_ASCII)

    @Test
    fun `cada codigo sale una vez por pieza, en CODE128-B si trae letras y C si es numerico par`() {
        val out = render(externo())
        assertEquals(2, ocurrencias(out, code128B("P000500")))
        assertEquals(2, ocurrencias(out, code128B("P000672")))
        // 7501234567: 10 dígitos ⇒ modo C ({C + 5 pares)
        assertEquals(1, ocurrencias(out, byteArrayOf(0x1D, 0x6B, 73, 7, 0x7B, 0x43, 75, 1, 23, 45, 67)))
    }

    @Test
    fun `el codigo del vale va en QR y NO como barras que lea la pistola`() {
        val out = render(externo())
        val qrData = byteArrayOf(0x1D, 0x28, 0x6B, 13, 0, 0x31, 0x50, 0x30) + "9470000015".toByteArray()
        assertEquals(1, ocurrencias(out, qrData))
        assertEquals(0, ocurrencias(out, byteArrayOf(0x1D, 0x6B, 73, 7, 0x7B, 0x43, 94, 70, 0, 0, 15)))
    }

    @Test
    fun `dice Importe de referencia, el extra y el pie de caja externa - nunca TOTAL ni pagado`() {
        val text = String(render(externo()), Charsets.ISO_8859_1)
        assertTrue(text.contains("Importe de referencia"))
        assertFalse(text.contains("TOTAL"))
        assertTrue(text.contains("   + Shot de espresso"))
        assertTrue(text.contains("No es comprobante de pago."))
        assertTrue(text.contains("Pásalo en la caja principal."))
        assertTrue(text.contains("Regresa con tu ticket de la caja"))
        assertFalse(text.lowercase().contains("pagado"))
        assertFalse(text.contains("Presenta este vale en caja"))
    }

    @Test
    fun `cabe en 58 mm - textos en 32 columnas y P000672 en 384 puntos`() {
        listOf("Importe de referencia", "No es comprobante de pago.", "Pásalo en la caja principal.", "Regresa con tu ticket de la caja")
            .forEach { assertTrue("$it no cabe", it.length <= PaperWidth.MM58.charsPerLine) }
        val modulos = ESCPOSPrinter.barcodeWidthInModules("P000672", BarcodeSymbology.CODE128_B)
        val ancho = ESCPOSPrinter.fittingModuleWidth("P000672", BarcodeSymbology.CODE128_B, ESCPOSPrinter.DEFAULT_MODULE_WIDTH, PaperWidth.MM58)
        assertTrue(modulos * ancho <= PaperWidth.MM58.dots)
    }

    @Test
    fun `un codigo que ninguna simbologia puede dibujar sale como texto`() {
        val t = externo().copy(items = listOf(ReceiptItem(name = "Raro", quantity = 1, unitPrice = 100, totalPrice = 100, externalCodes = listOf("ÑANDÚ"))))
        val text = String(render(t), Charsets.ISO_8859_1)
        assertTrue(text.contains("ÑANDÚ"))
    }

    @Test
    fun `P2 un SKU que no cabe en 58 mm sale como texto, no como barras cortadas`() {
        val largo = "ABCDEFGHIJKLMNOPQRST" // 20 letras en CODE128-B ≈ 550 puntos > 384
        val t = externo().copy(items = listOf(ReceiptItem(name = "Largo", quantity = 1, unitPrice = 100, totalPrice = 100, externalCodes = listOf(largo))))
        val out = render(t, PaperWidth.MM58)
        assertEquals(0, ocurrencias(out, code128B(largo)))
        assertTrue(String(out, Charsets.ISO_8859_1).contains(largo))
        // En 80 mm sí cabe y sale en barras.
        assertEquals(1, ocurrencias(render(t, PaperWidth.MM80), code128B(largo)))
    }

    @Test
    fun `la copia externa dice COPIA y conserva los mismos codigos`() {
        val out = render(externo().copy(isReprint = true))
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("*** COPIA ***"))
        assertTrue(text.contains("Sustituye al vale 9470000015"))
        assertEquals(2, ocurrencias(out, code128B("P000500")))
    }

    @Test
    fun `entre un codigo y el siguiente quedan 3 renglones en blanco para que la pistola no lea dos`() {
        // La Galeterie 6-oct: con UN renglón (≈4 mm) el café y su extra quedaban pegados.
        val out = render(externo())
        val codigo = code128B("P000672")
        val i = (0..out.size - codigo.size).first { s -> codigo.indices.all { out[s + it] == codigo[it] } }
        val despues = out.copyOfRange(i + codigo.size, i + codigo.size + 4)
        assertEquals("0a0a0a1d", hex(despues))
    }

    @Test
    fun `P1 la copia del vale normal sale igual que el original`() {
        assertEquals(DORADO_58_CODE128, hex(ESCPOSPrinter(paperWidth = PaperWidth.MM58).generateAreaTicket(normal().copy(isReprint = true))))
    }

    // MARK: - El QR del vale en el PDF (Task 11): la misma corrección M que el papel

    @Test
    fun `el QR del PDF lleva correccion M como el papel y se lee`() {
        val matrix = AreaTicketPdfGenerator().qrMatrix("9470000015")
        // La matriz trae su zona de silencio; el decodificador quiere sólo el símbolo.
        val (left, top, width, height) = matrix.enclosingRectangle
        val simbolo = BitMatrix(width, height)
        for (y in 0 until height) for (x in 0 until width) if (matrix[left + x, top + y]) simbolo.set(x, y)

        val leido = Decoder().decode(simbolo)

        assertEquals("M", leido.ecLevel)
        assertEquals("9470000015", leido.text)
    }

    private companion object {
        const val DORADO_58_CODE128 = "1b401b74101d4c00001d5780011b61014c612047616c6574657269650a1b21301b450143414645544552cd410a1b45001b21003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b610056616c6520233a202020202020202020202020202020393437303030303031350a486f72613a2020202020202020202020202020202020202020202030383a31330a4174656e6469f33a2020202020202020202020202020202020202020526f73610a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a322020204c6174746520202020202020202020202020202020243134302e30300a2020204e6f74613a2053696e20657370756d610a312020204c4f4d4f2043414e414449454e5345202020202020202433362e37340a202020302e323234206b6720d720243136342e30302f6b670a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a1b45011b2110544f54414c2020202020202020202020202020202020202020243137362e37340a1b21001b45003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b61011d68a21d77031d48021d6b49077b435e4600000f0a1b21301b4501393437303030303031350a1b45001b21000a50726573656e746120657374652076616c6520656e2063616a610a1b450154752070726f647563746f2074652065737065726120617175ed0a1b45005265677265736120636f6e20656c207469636b65742070616761646f0a3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b64051d5601"
        const val DORADO_80_CODE39 = "1b401b74101d4c00001d5740021b61014c612047616c6574657269650a1b21301b450143414645544552cd410a1b45001b21003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b610056616c6520233a20202020202020202020202020202020202020202020202020202020202020393437303030303031350a486f72613a202020202020202020202020202020202020202020202020202020202020202020202020202030383a31330a4174656e6469f33a202020202020202020202020202020202020202020202020202020202020202020202020526f73610a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a322020204c617474652020202020202020202020202020202020202020202020202020202020202020243134302e30300a2020204e6f74613a2053696e20657370756d610a312020204c4f4d4f2043414e414449454e534520202020202020202020202020202020202020202020202433362e37340a202020302e323234206b6720d720243136342e30302f6b670a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a1b45011b2110544f54414c202020202020202020202020202020202020202020202020202020202020202020202020243137362e37340a1b21001b45003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b61011d68a21d77021d48021d6b450a393437303030303031350a1b21301b4501393437303030303031350a1b45001b21000a50726573656e746120657374652076616c6520656e2063616a610a1b450154752070726f647563746f2074652065737065726120617175ed0a1b45005265677265736120636f6e20656c207469636b65742070616761646f0a3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b64051d5601"
    }
}
