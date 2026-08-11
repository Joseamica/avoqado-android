package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elegir 58 mm sólo cambiaba cuántos caracteres arma la app. A la impresora
 * nunca se le decía cuál era su área, así que conservaba la de fábrica.
 *
 * Medido en una Epson con adaptadores físicos de 58 mm (2026-08-10): TODAS las
 * líneas alineadas a la izquierda perdían sus primeras 6 columnas, mientras las
 * centradas salían enteras. Esa asimetría es la firma exacta del defecto: el
 * `ESC a 1` centra sobre los 80 mm del CABEZAL, y el rollo angosto quedaba más o
 * menos centrado ahí, así que lo centrado caía bien de pura casualidad. Lo
 * alineado a la izquierda arrancaba en el punto 0 del cabezal, ~9 mm antes de
 * donde de verdad empieza el papel, y esas columnas caían sobre el rodillo.
 *
 * El arreglo son dos comandos que existen justo para esto: `GS L` (margen) y
 * `GS W` (ancho del área). Van después de `ESC @`, que es lo que los resetea.
 *
 * Estos tests miran los BYTES porque es lo único comprobable sin papel. La
 * lección de [project_ticket-blanco-sunmi] aplica igual aquí: el contenido del
 * ticket no cambia ni un carácter con este defecto presente, así que un test de
 * contenido lo dejaría pasar entero.
 */
class ESCPOSPrintAreaTest {

    private fun ByteArray.indexOfSeq(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun bytesTrasReset(paperWidth: PaperWidth, margen: Int = 0, interna: Boolean = false): ByteArray {
        val printer = ESCPOSPrinter(
            paperWidth = paperWidth,
            switchToSingleByteFirst = interna,
            leftMarginChars = margen,
        )
        printer.reset()
        return printer.getData()
    }

    @Test
    fun `el reset fija el area de impresion, no la deja en la de fabrica`() {
        val bytes = bytesTrasReset(PaperWidth.MM58)

        // GS L 0 y GS W 384 (32 columnas x 12 puntos).
        assertTrue("falta GS L", bytes.indexOfSeq(byteArrayOf(0x1D, 0x4C, 0x00, 0x00)) >= 0)
        assertTrue("falta GS W", bytes.indexOfSeq(byteArrayOf(0x1D, 0x57, 0x80.toByte(), 0x01)) >= 0)
    }

    @Test
    fun `el margen viaja en puntos, no en columnas`() {
        // 6 columnas es lo que se midió que faltaba en la Epson con adaptadores.
        // 6 x 12 = 72 puntos = 0x48. Mandar el 6 crudo correría el ticket 6
        // puntos (menos de un carácter) y parecería que el ajuste no sirve.
        val bytes = bytesTrasReset(PaperWidth.MM58, margen = 6)

        assertTrue("GS L debe llevar 72 puntos", bytes.indexOfSeq(byteArrayOf(0x1D, 0x4C, 0x48, 0x00)) >= 0)
    }

    @Test
    fun `un margen grande se parte bien en dos bytes`() {
        // 16 x 12 = 192 puntos = 0xC0. Cabe en el byte bajo, pero el alto tiene
        // que seguir yendo: GS L SIEMPRE lleva dos bytes.
        val bytes = bytesTrasReset(PaperWidth.MM80, margen = 16)

        assertTrue(bytes.indexOfSeq(byteArrayOf(0x1D, 0x4C, 0xC0.toByte(), 0x00)) >= 0)
        // Y el ancho de 80 mm son 576 puntos = 0x0240 → nL=0x40, nH=0x02.
        assertTrue(bytes.indexOfSeq(byteArrayOf(0x1D, 0x57, 0x40, 0x02)) >= 0)
    }

    @Test
    fun `un margen fuera de rango se recorta en vez de correr el ticket fuera del papel`() {
        // Pedir 40 columnas dejaría el ticket completamente fuera del rollo. Se
        // topa en 16, que es el desperdicio máximo real (48 columnas de un
        // cabezal de 80 menos las 32 de un rollo de 58).
        val bytes = bytesTrasReset(PaperWidth.MM58, margen = 40)

        assertTrue(bytes.indexOfSeq(byteArrayOf(0x1D, 0x4C, 0xC0.toByte(), 0x00)) >= 0)
    }

    @Test
    fun `el area se fija DESPUES del init, que es justo lo que la resetea`() {
        val bytes = bytesTrasReset(PaperWidth.MM58, margen = 6)

        val init = bytes.indexOfSeq(byteArrayOf(0x1B, 0x40))
        val gsL = bytes.indexOfSeq(byteArrayOf(0x1D, 0x4C, 0x48, 0x00))
        val gsW = bytes.indexOfSeq(byteArrayOf(0x1D, 0x57, 0x80.toByte(), 0x01))

        assertTrue("ESC @ debe ir primero", init in 0 until gsL)
        // GS W mide desde el margen, así que el margen tiene que estar puesto ya.
        assertTrue("GS L debe ir antes que GS W", gsL < gsW)
    }

    @Test
    fun `la integrada de Sunmi sigue arrancando con FS punto`() {
        // Regresión de [project_ticket-blanco-sunmi]: si el switch a single-byte
        // deja de ser lo primero, la integrada se traga el ticket entero y el log
        // canta éxito igual. El área de impresión no puede colarse antes.
        val bytes = bytesTrasReset(PaperWidth.MM58, margen = 6, interna = true)

        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals(0x40.toByte(), bytes[1])
        assertEquals(0x1C.toByte(), bytes[2])
        assertEquals(0x2E.toByte(), bytes[3])
    }

    @Test
    fun `la pagina de prueba trae la regla para poder calibrar`() {
        val data = ESCPOSPrinter(paperWidth = PaperWidth.MM58, leftMarginChars = 6).generateTestPrint()
        val texto = String(data, Charsets.ISO_8859_1)

        // La fila de unidades: 0..9 repetido hasta llenar la línea. Es lo que se
        // cuenta en el papel para saber cuánto corrimiento hace falta.
        assertTrue("falta la fila de unidades", texto.contains("01234567890123456789012345678901"))
        // La de decenas: diez espacios y luego los dieces.
        assertTrue("falta la fila de decenas", texto.contains("          1111111111222222222233"))
        // Dice CUÁNTO sumar, no sólo qué contar: el número crudo de la regla deja
        // el ticket pegado a la izquierda con todo el aire a la derecha. En 58 mm
        // sobran 80 puntos (464 del rollo menos 384 de contenido), o sea 3
        // columnas por lado. Verificado en papel: 6 medido + 3 = 9, y a 9 quedó
        // parejo.
        assertTrue("falta la instrucción", texto.contains("Cuenta el 1er numero y suma 3"))
        assertTrue("falta el margen actual", texto.contains("Margen actual: 6"))
    }

    @Test
    fun `un titulo que no cabe en doble ancho baja a tamano normal`() {
        // Verificado en papel el 2026-08-10: al fijar el área, la impresora ya
        // parte la línea, y "PRUEBA DE IMPRESIÓN" (19 caracteres = 38 columnas)
        // salía como "PRUEBA DE IMPRES" / "IÓN". Donde de verdad duele es en el
        // nombre de la sucursal del recibo y en el código del vale.
        val p = ESCPOSPrinter(paperWidth = PaperWidth.MM58)
        p.reset()
        p.printTitle("PRUEBA DE IMPRESIÓN")
        val bytes = p.getData()

        assertTrue(
            "un título de 19 caracteres NO cabe en 58 mm: debe ir en tamaño normal",
            bytes.indexOfSeq(byteArrayOf(0x1B, 0x21, 0x30)) < 0,
        )
    }

    @Test
    fun `un titulo que si cabe conserva la letra grande`() {
        // 16 caracteres son 32 columnas: cabe justo. No hay que castigar al que
        // sí cabe — la letra grande es lo que hace legible una comanda de cocina
        // a un metro de distancia.
        val p = ESCPOSPrinter(paperWidth = PaperWidth.MM58)
        p.reset()
        p.printTitle("COCINA")
        val bytes = p.getData()

        assertTrue(bytes.indexOfSeq(byteArrayOf(0x1B, 0x21, 0x30)) >= 0)
    }

    @Test
    fun `en 80 mm el mismo titulo sigue saliendo grande`() {
        // 19 caracteres son 38 columnas y en 80 mm caben 48. El ajuste es por
        // ancho de papel, no un downgrade general.
        val p = ESCPOSPrinter(paperWidth = PaperWidth.MM80)
        p.reset()
        p.printTitle("PRUEBA DE IMPRESIÓN")

        assertTrue(p.getData().indexOfSeq(byteArrayOf(0x1B, 0x21, 0x30)) >= 0)
    }

    @Test
    fun `la regla mide exactamente lo que cabe en la linea`() {
        // Si midiera de más, se cortaría sola y el número que se lee saldría mal:
        // la regla dejaría de ser un instrumento de medición.
        val data = ESCPOSPrinter(paperWidth = PaperWidth.MM80).generateTestPrint()
        val lineas = String(data, Charsets.ISO_8859_1).split("\n")

        val unidades = lineas.first { it.startsWith("012345678901234567890") }
        assertEquals(48, unidades.length)
    }
}
