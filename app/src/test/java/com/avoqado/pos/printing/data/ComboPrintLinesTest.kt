package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.ComboPrintLines
import com.avoqado.pos.printing.data.model.ComboTag
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COMBOS en el papel — decisión del founder 2026-08-18 (Fudo/Square/Toast):
 * **el nombre del combo como renglón, y debajo cada producto asociado.**
 *
 * Hasta hoy el combo desaparecía del ticket y de la comanda: se imprimían sus
 * productos sueltos y un descuento anónimo, así que ni el cliente ni la cocina
 * sabían que iba junto.
 *
 * 🔴 NO cambia importes: el renglón del combo lleva la SUMA de sus componentes y
 * los componentes van en 0 — el total impreso es exactamente el mismo de antes.
 *
 * Espejo exacto de `ComboPrintLinesTests.swift` en avoqado-ios.
 */
class ComboPrintLinesTest {

    private fun receiptLine(name: String, cents: Int, qty: Int = 1) = ReceiptItem(
        name = name,
        quantity = qty,
        unitPrice = cents,
        totalPrice = cents * qty,
    )

    private fun kitchenLine(name: String, qty: Int = 1) = KitchenItem(name = name, quantity = qty)

    // MARK: - Ticket (recibo)

    @Test
    fun `receipt prints the combo name and its components below`() {
        val combo = ComboTag(key = "inst-1", name = "Combo del día")
        val out = ComboPrintLines.receipt(
            listOf(
                combo to receiptLine("Hamburguesa", 12000),
                combo to receiptLine("Refresco", 3000),
            ),
        )

        assertEquals(3, out.size)
        assertEquals("Combo del día", out[0].name)
        assertTrue(out[0].isComboHeader)
        assertEquals("Hamburguesa", out[1].name)
        assertEquals("Refresco", out[2].name)
        assertTrue(out[1].isComboComponent)
        assertTrue(out[2].isComboComponent)
    }

    @Test
    fun `receipt combo header carries the total of its components`() {
        val combo = ComboTag(key = "inst-1", name = "Combo del día")
        val out = ComboPrintLines.receipt(
            listOf(
                combo to receiptLine("Hamburguesa", 12000),
                combo to receiptLine("Refresco", 3000),
            ),
        )
        assertEquals(15000, out[0].totalPrice)
    }

    /** 🔴 DINERO: el ticket no puede cobrar dos veces lo mismo. */
    @Test
    fun `receipt components print no price so the lines still add up to the combo`() {
        val combo = ComboTag(key = "inst-1", name = "Combo del día")
        val out = ComboPrintLines.receipt(
            listOf(
                combo to receiptLine("Hamburguesa", 12000),
                combo to receiptLine("Refresco", 3000),
            ),
        )
        assertEquals(15000, out.sumOf { it.totalPrice })
        assertEquals(0, out[1].totalPrice)
        assertEquals(0, out[2].totalPrice)
        assertEquals("", out[1].formattedPrice)
        assertEquals("", out[2].formattedPrice)
    }

    @Test
    fun `receipt keeps loose lines exactly where they were`() {
        val combo = ComboTag(key = "inst-1", name = "Combo del día")
        val out = ComboPrintLines.receipt(
            listOf(
                null to receiptLine("Café", 5000),
                combo to receiptLine("Hamburguesa", 12000),
                combo to receiptLine("Refresco", 3000),
                null to receiptLine("Postre", 7000),
            ),
        )
        assertEquals(listOf("Café", "Combo del día", "Hamburguesa", "Refresco", "Postre"), out.map { it.name })
        assertEquals(5000, out.first().totalPrice)
        assertEquals(7000, out.last().totalPrice)
    }

    /** Dos combos vendidos = dos renglones de combo (una instancia = UN combo). */
    @Test
    fun `receipt prints one header per promotion instance`() {
        val a = ComboTag(key = "inst-a", name = "Combo del día")
        val b = ComboTag(key = "inst-b", name = "Combo del día")
        val out = ComboPrintLines.receipt(
            listOf(
                a to receiptLine("Hamburguesa", 12000),
                b to receiptLine("Hamburguesa", 12000),
                a to receiptLine("Refresco", 3000),
                b to receiptLine("Refresco", 3000),
            ),
        )
        assertEquals(
            listOf(
                "Combo del día", "Hamburguesa", "Refresco",
                "Combo del día", "Hamburguesa", "Refresco",
            ),
            out.map { it.name },
        )
        assertEquals(30000, out.sumOf { it.totalPrice })
    }

    /** REGRESIÓN: sin combo el ticket sale IDÉNTICO al de hoy. */
    @Test
    fun `receipt without combos is byte-identical to today`() {
        val lines = listOf(receiptLine("Café", 5000), receiptLine("Postre", 7000))
        val out = ComboPrintLines.receipt(lines.map { null to it })
        assertEquals(lines, out)
        assertFalse(out.any { it.isComboHeader || it.isComboComponent })
    }

    // MARK: - Comanda (cocina)

    @Test
    fun `kitchen prints the combo name and its products below`() {
        val combo = ComboTag(key = "Combo del día", name = "Combo del día")
        val out = ComboPrintLines.kitchen(
            listOf(
                combo to kitchenLine("Hamburguesa"),
                combo to kitchenLine("Refresco"),
            ),
        )
        assertEquals(listOf("Combo del día", "Hamburguesa", "Refresco"), out.map { it.name })
        assertTrue(out[0].isComboHeader)
        assertTrue(out[1].isComboComponent)
        assertTrue(out[2].isComboComponent)
    }

    /** La cocina necesita las CANTIDADES de los productos, no las del combo. */
    @Test
    fun `kitchen keeps each product quantity untouched`() {
        val combo = ComboTag(key = "Combo del día", name = "Combo del día")
        val out = ComboPrintLines.kitchen(
            listOf(
                combo to kitchenLine("Hamburguesa", qty = 3),
                combo to kitchenLine("Refresco", qty = 3),
            ),
        )
        assertEquals(3, out[1].quantity)
        assertEquals(3, out[2].quantity)
    }

    /**
     * En la comanda la llave es el NOMBRE, no la instancia: el motor de ruteo ya
     * consolidó líneas de instancias distintas ("3x Hamburguesa"), así que agrupar
     * por instancia partiría el combo en pedazos que no existen.
     */
    @Test
    fun `kitchen groups consolidated lines under a single combo header`() {
        val combo = ComboTag(key = "Combo del día", name = "Combo del día")
        val out = ComboPrintLines.kitchen(
            listOf(
                null to kitchenLine("Ensalada"),
                combo to kitchenLine("Hamburguesa", qty = 3),
                null to kitchenLine("Sopa"),
                combo to kitchenLine("Refresco", qty = 3),
            ),
        )
        assertEquals(
            listOf("Ensalada", "Combo del día", "Hamburguesa", "Refresco", "Sopa"),
            out.map { it.name },
        )
    }

    /** REGRESIÓN: sin combo la comanda sale IDÉNTICA a la de hoy. */
    @Test
    fun `kitchen without combos is byte-identical to today`() {
        val lines = listOf(kitchenLine("Ensalada"), kitchenLine("Sopa"))
        val out = ComboPrintLines.kitchen(lines.map { null to it })
        assertEquals(lines, out)
        assertFalse(out.any { it.isComboHeader || it.isComboComponent })
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(ComboPrintLines.receipt(emptyList()).isEmpty())
        assertTrue(ComboPrintLines.kitchen(emptyList()).isEmpty())
    }
}
