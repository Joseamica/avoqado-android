package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.areatickets.data.AreaTicketLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que lee quien entrega la mercancía.
 *
 * En la prueba física (2026-08-08) “Entregas por área” mostraba tres vales del
 * MISMO producto — 0.1, 0.435 y 1.5 kg de jamón — distinguibles sólo por
 * importe. Quien entrega tenía que dividir $360 ÷ $240/kg de cabeza para saber
 * cuánto pesar. La pantalla cuyo único trabajo es entregar el producto correcto
 * no puede ocultar la cantidad.
 */
class AreaTicketDeliverySummaryTest {
    private fun line(name: String, weightKg: String?, quantity: String = "1") =
        AreaTicketLine(
            id = "l1",
            clientLineId = "c1",
            productNameSnapshot = name,
            quantity = quantity,
            weightKg = weightKg,
            unitPrice = "240.00",
            total = "104.40",
        )

    @Test
    fun `un producto por peso enseña los kilos junto al nombre`() {
        assertEquals(
            "QA Jamón por kg · 0.435 kg",
            areaTicketLinesSummary(listOf(line("QA Jamón por kg", "0.435"))),
        )
    }

    @Test
    fun `distingue dos vales del mismo producto con distinto peso`() {
        val chico = areaTicketLinesSummary(listOf(line("QA Jamón por kg", "0.100")))
        val grande = areaTicketLinesSummary(listOf(line("QA Jamón por kg", "1.500")))
        assertEquals("QA Jamón por kg · 0.100 kg", chico)
        assertEquals("QA Jamón por kg · 1.500 kg", grande)
    }

    @Test
    fun `un producto por pieza enseña la cantidad y no un peso inventado`() {
        assertEquals(
            "Queso manchego × 3",
            areaTicketLinesSummary(listOf(line("Queso manchego", null, quantity = "3"))),
        )
    }

    @Test
    fun `una sola pieza no ensucia con un por uno`() {
        assertEquals(
            "Queso manchego",
            areaTicketLinesSummary(listOf(line("Queso manchego", null))),
        )
    }

    @Test
    fun `varias lineas se separan de forma legible`() {
        assertEquals(
            "QA Jamón por kg · 0.435 kg  •  Queso manchego × 2",
            areaTicketLinesSummary(
                listOf(line("QA Jamón por kg", "0.435"), line("Queso manchego", null, quantity = "2")),
            ),
        )
    }

    @Test
    fun `un vale sin lineas no revienta`() {
        assertEquals("", areaTicketLinesSummary(emptyList()))
    }
}
