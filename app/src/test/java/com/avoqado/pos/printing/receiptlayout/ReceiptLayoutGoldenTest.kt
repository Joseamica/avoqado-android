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

    // m1 (revisión de la T2): dos casos de `footer.test.ts` del servidor que NINGÚN golden fija. Espejo literal en iOS (I1).
    private fun totales(taxCents: Long = 0, serviceChargeCents: Long? = null, subtotalCents: Long = 4500, totalCents: Long = 4500) =
        ReceiptLayoutInterpreter.interpret(
            listOf(ReceiptBlock.Totals(showSubtotal = true, showTax = true, showDiscount = true, showTip = true)),
            ReceiptInput(
                sale = ReceiptSale(
                    orderNumber = "1", orderType = "En tienda", occurredAt = "2026-10-09T18:00:00Z", timezone = "America/Mexico_City",
                    subtotalCents = subtotalCents, taxCents = taxCents, serviceChargeCents = serviceChargeCents, totalCents = totalCents,
                ),
                venue = ReceiptVenueInfo(name = "Testarudo Cafe"),
            ),
            32,
        ).filterIsInstance<LogicalLine.Text>().map { it.text }

    @Test
    fun `P1 un IVA negativo nunca se imprime - sale la leyenda`() {
        val textos = totales(taxCents = -50)
        assertTrue(textos.toString(), "IVA incluido cuando aplica" in textos)
        assertTrue(textos.toString(), textos.none { it.startsWith("IVA agregado") })
    }

    @Test
    fun `P1 un cargo de 12345 pesos 67 cabe en 32 columnas sin partirse`() {
        val textos = totales(serviceChargeCents = 1_234_567, totalCents = 1_239_067)
        assertTrue(textos.toString(), "Cargo por servicio:" + " ".repeat(3) + "\$12,345.67" in textos)
    }
}
