package com.avoqado.pos.printing.receiptlayout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los casos dorados (spec § 6): la app produce EXACTAMENTE las líneas del intérprete de referencia.
 * Si una sola línea difiere, se arregla la APP — nunca el golden.
 */
class ReceiptLayoutGoldenTest {

    @Test
    fun `hay al menos 50 goldens - 25 casos por 2 anchos`() {
        assertTrue(GoldenFixtures.files().size >= 50)
    }

    @Test
    fun `P1 el interprete de Android coincide linea por linea con el del servidor`() {
        val fallas = mutableListOf<String>()
        for (file in GoldenFixtures.files()) {
            val golden = GoldenFixtures.read(file)
            val parsed = ReceiptLayoutParser.parseTolerant(golden.blocksJson)
            assertEquals("${golden.name}: el golden trae bloques que la app no entiende", 0, parsed.dropped)
            val actual = ReceiptLayoutInterpreter.interpret(parsed.blocks, golden.input, golden.width)
            if (actual != golden.lines) {
                val i = (0 until maxOf(actual.size, golden.lines.size)).first { actual.getOrNull(it) != golden.lines.getOrNull(it) }
                fallas += "${golden.name}[$i]: esperado ${golden.lines.getOrNull(i)} · obtenido ${actual.getOrNull(i)}"
            }
        }
        assertTrue(fallas.joinToString("\n"), fallas.isEmpty())
    }

    @Test
    fun `P1 ningun renglon de ningun golden se pasa del papel`() {
        for (file in GoldenFixtures.files()) {
            val golden = GoldenFixtures.read(file)
            val lines = ReceiptLayoutInterpreter.interpret(ReceiptLayoutParser.parseTolerant(golden.blocksJson).blocks, golden.input, golden.width)
            for (l in lines.filterIsInstance<LogicalLine.Text>()) {
                val cells = if (l.double) l.text.length * 2 else l.text.length
                assertTrue("${golden.name}: «${l.text}»", cells <= golden.width)
            }
        }
    }
}
