package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.ESCPOSPrinter.HriPosition
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bytes exactos del `GS k` para el vale de área.
 *
 * Estos tests son la única defensa antes del hardware: una trama mal armada no
 * revienta ni marca error — la impresora escupe barras y la pistola no pita. Por
 * eso cada byte esperado está calculado a mano desde la especificación ESC/POS y
 * documentado en su comentario, en vez de copiado de lo que hoy genera el código.
 */
class EscPosBarcodeTest {

    /**
     * Vale real con el formato de §5.1: `9` (espacio de nombres) + `47`
     * (partición del dispositivo) + `000001` (contador monótono) + `3`
     * (verificador). Trae "00" a propósito: es el par que se codifica como
     * `0x00` y el que rompería la variante de `GS k` terminada en NUL.
     */
    private val vale = "9470000013"

    private fun printer(paper: PaperWidth = PaperWidth.MM58) = ESCPOSPrinter(paperWidth = paper)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(hex(expected), hex(actual))
    }

    // MARK: - Trama exacta de CODE128 modo C

    @Test
    fun `CODE128-C emite la trama GS k exacta para un vale de 10 digitos`() {
        val printer = printer()

        assertTrue(printer.printBarcode(vale))

        val expected = byteArrayOf(
            // GS h 162 — altura. `GS h` = 0x1D 0x68; 162 puntos = 0xA2 (~20 mm
            // a 203 dpi), que es el default de ESC/POS.
            0x1D, 0x68, 0xA2.toByte(),
            // GS w 3 — ancho de módulo. `GS w` = 0x1D 0x77. Queda en el 3 pedido
            // porque 110 módulos × 3 = 330 puntos y el rollo de 58 mm imprime 384.
            0x1D, 0x77, 0x03,
            // GS H 2 — texto legible ABAJO. `GS H` = 0x1D 0x48; n=2 = below.
            0x1D, 0x48, 0x02,
            // GS k m n — función B (con longitud), `GS k` = 0x1D 0x6B.
            // m = 73 = 0x49 = CODE128.
            // n = 7 = 2 bytes del selector `{C` + 5 pares de dígitos.
            0x1D, 0x6B, 0x49, 0x07,
            // `{C` = 0x7B 0x43 — selecciona el juego C (dos dígitos por símbolo).
            0x7B, 0x43,
            // Los 10 dígitos como 5 BYTES CRUDOS de valor 0..99:
            // "94" = 94 = 0x5E
            0x5E,
            // "70" = 70 = 0x46
            0x46,
            // "00" = 0 = 0x00  ← el byte que obliga a usar la función B
            0x00,
            // "00" = 0 = 0x00
            0x00,
            // "13" = 13 = 0x0D
            0x0D,
        )

        assertBytes(expected, printer.getData())
    }

    @Test
    fun `el verificador de CODE128 no se manda porque lo calcula la impresora`() {
        val printer = printer()
        printer.printBarcode(vale)

        // 4 bytes de cabecera GS k + 2 del selector + 5 pares = 11 bytes tras
        // los tres comandos de formato (3 × 3 bytes). Si algún día alguien
        // "arregla" el código agregando el símbolo verificador a mano, la
        // impresora agregaría el suyo encima y el código escanearía otro número.
        assertEquals(9 + 4 + 2 + 5, printer.getData().size)
    }

    // MARK: - Rechazo de payloads que no son codificables

    @Test
    fun `un payload no numerico en modo C se rechaza sin escribir bytes`() {
        val printer = printer()

        assertFalse(printer.printBarcode("94A0000013"))
        assertEquals(0, printer.getData().size)
    }

    @Test
    fun `un payload de largo impar en modo C se rechaza sin escribir bytes`() {
        val printer = printer()

        // El modo C codifica de a pares: un dígito suelto no tiene representación.
        assertFalse(printer.printBarcode("947000001"))
        assertEquals(0, printer.getData().size)
    }

    @Test
    fun `un payload rechazado no corrompe lo que ya estaba en el buffer`() {
        val printer = printer()
        printer.printLine("VALE")
        val antes = printer.getData()

        assertFalse(printer.printBarcode(""))
        assertFalse(printer.printBarcode("94A0000013"))
        assertFalse(printer.printBarcode("947000001"))

        // Ninguno de los tres rechazos dejó un GS h/GS w/GS H huérfano: esos
        // comandos cambian el estado de la impresora para TODO lo que siga.
        assertBytes(antes, printer.getData())
    }

    @Test
    fun `un caracter fuera del juego de CODE39 se rechaza sin escribir bytes`() {
        val printer = printer(PaperWidth.MM80)

        // El guion bajo no está en el juego de CODE39 (0-9 A-Z espacio $%+-./).
        assertFalse(printer.printBarcode("VALE_1", symbology = BarcodeSymbology.CODE39))
        assertEquals(0, printer.getData().size)
    }

    // MARK: - Guarda de ancho contra el papel

    @Test
    fun `el vale en CODE128-C cabe en el area imprimible de 58 mm`() {
        // 11 (arranque) + 5×11 (datos) + 11 (verificador) + 13 (paro) = 90
        // + 10 módulos de zona muda a cada lado = 110.
        val modules = ESCPOSPrinter.barcodeWidthInModules(vale, BarcodeSymbology.CODE128_C)
        assertEquals(110, modules)

        // Al ancho de módulo por default: 110 × 3 = 330 puntos.
        assertEquals(330, modules * ESCPOSPrinter.DEFAULT_MODULE_WIDTH)
        assertTrue(modules * ESCPOSPrinter.DEFAULT_MODULE_WIDTH <= PaperWidth.MM58.dots)
        assertEquals(384, PaperWidth.MM58.dots)
    }

    @Test
    fun `un ancho de modulo que no cabe en 58 mm se baja en vez de salir cortado`() {
        val printer = printer(PaperWidth.MM58)

        // Se piden 4: 110 × 4 = 440 puntos > 384. Debe bajar a 3 (330).
        assertTrue(printer.printBarcode(vale, moduleWidth = 4))

        val gsW = printer.getData().copyOfRange(3, 6)
        assertBytes(byteArrayOf(0x1D, 0x77, 0x03), gsW)
    }

    @Test
    fun `en 80 mm el mismo codigo conserva el ancho de modulo pedido`() {
        val printer = printer(PaperWidth.MM80)

        // 110 × 4 = 440 ≤ 576: aquí sí cabe holgado, no hay por qué adelgazarlo.
        assertTrue(printer.printBarcode(vale, moduleWidth = 4))

        val gsW = printer.getData().copyOfRange(3, 6)
        assertBytes(byteArrayOf(0x1D, 0x77, 0x04), gsW)
    }

    @Test
    fun `el ancho de modulo nunca baja de 2 aunque no quepa`() {
        // CODE39 con 10 dígitos mide 211 módulos: ni a módulo 2 (422 puntos)
        // cabe en 58 mm. Aun así imprime al mínimo — negarse dejaría al cliente
        // sin vale y sin poder pagar, y el HRI abajo sigue siendo tecleable.
        val ancho = ESCPOSPrinter.fittingModuleWidth(
            data = vale,
            symbology = BarcodeSymbology.CODE39,
            requested = 6,
            paper = PaperWidth.MM58,
        )
        assertEquals(ESCPOSPrinter.MIN_MODULE_WIDTH, ancho)
        assertTrue(
            "CODE39 de 10 dígitos NO cabe en 58 mm — necesita rollo de 80 mm",
            ESCPOSPrinter.barcodeWidthInModules(vale, BarcodeSymbology.CODE39) * ancho >
                PaperWidth.MM58.dots,
        )
    }

    // MARK: - CODE39 como respaldo

    @Test
    fun `CODE39 emite una trama distinta y valida`() {
        val printer = printer(PaperWidth.MM80)

        assertTrue(printer.printBarcode(vale, symbology = BarcodeSymbology.CODE39))

        val expected = byteArrayOf(
            // GS h 162 — misma altura por default.
            0x1D, 0x68, 0xA2.toByte(),
            // GS w 2 — se pidió 3 (default) pero 211 × 3 = 633 > 576 puntos del
            // rollo de 80 mm, así que la guarda lo baja al mínimo.
            0x1D, 0x77, 0x02,
            // GS H 2 — texto legible abajo.
            0x1D, 0x48, 0x02,
            // GS k m n — m = 69 = 0x45 = CODE39 (distinto del 0x49 de CODE128).
            // n = 10: CODE39 manda un byte ASCII por dígito, sin selector de
            // juego y sin empaquetar pares. Los `*` los agrega la impresora.
            0x1D, 0x6B, 0x45, 0x0A,
            // "9470000013" en ASCII
            0x39, 0x34, 0x37, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31, 0x33,
        )

        assertBytes(expected, printer.getData())
    }

    @Test
    fun `CODE39 acepta letras que el modo C rechaza`() {
        val alfanumerico = "VALE47"

        val code39 = printer(PaperWidth.MM80)
        assertTrue(code39.printBarcode(alfanumerico, symbology = BarcodeSymbology.CODE39))
        assertTrue(code39.getData().isNotEmpty())

        val code128 = printer(PaperWidth.MM80)
        assertFalse(code128.printBarcode(alfanumerico, symbology = BarcodeSymbology.CODE128_C))
        assertEquals(0, code128.getData().size)
    }

    // MARK: - Posición del texto legible

    @Test
    fun `el HRI se puede apagar`() {
        val printer = printer()
        printer.printBarcode(vale, hriPosition = HriPosition.NONE)

        val gsH = printer.getData().copyOfRange(6, 9)
        assertBytes(byteArrayOf(0x1D, 0x48, 0x00), gsH)
    }

    @Test
    fun `la altura se recorta al rango que acepta GS h`() {
        val printer = printer()
        printer.printBarcode(vale, heightDots = 9999)

        // `GS h` sólo tiene un byte de altura: 255 es el techo real.
        val gsH = printer.getData().copyOfRange(0, 3)
        assertBytes(byteArrayOf(0x1D, 0x68, 0xFF.toByte()), gsH)
    }
}
