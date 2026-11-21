package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * C7 (R3#11, R5#9): el ticket de un cobro de la máquina de efectivo y su comprobante de diferencia, por el MISMO camino que
 * cualquier ticket (`ReceiptData` → `ReceiptInputMapper` → `ReceiptLayoutInterpreter`).
 */
class ReceiptLayoutInterpreterMaquinaTest {

    private val folio = "AQ0MHN2X5KABCD"

    private fun ticket(maquina: ReceiptCashMachine?, tendered: Int = 20_000, change: Int = 1_250) = ReceiptData(
        orderNumber = folio,
        orderType = "En tienda",
        items = listOf(ReceiptItem(name = "Café", quantity = 1, unitPrice = 18_750, totalPrice = 18_750)),
        subtotal = 18_750,
        taxAmount = 0,
        total = 18_750,
        paymentMethod = "Efectivo",
        venueName = "La Galeterie",
        date = Date(0),
        cashTendered = tendered,
        changeAmount = change,
        cashMachine = maquina,
    )

    private fun lineas(r: ReceiptData, bloques: List<ReceiptBlock> = CanonicalLayout.BLOCKS): List<LogicalLine.Text> =
        ReceiptLayoutInterpreter.interpret(bloques, ReceiptInputMapper.map(r, null, false, "America/Mexico_City", null), 32)
            .filterIsInstance<LogicalLine.Text>()

    private fun texto(r: ReceiptData) = lineas(r).joinToString("\n") { it.text }

    @Test
    fun `P1 el ticket de la maquina dice Efectivo, lo recibido, el cambio y que se cobro en la maquina con su referencia`() {
        val t = texto(ticket(ReceiptCashMachine(referenceCC = folio)))

        assertTrue(t, Regex("Pago:\\s+Efectivo").containsMatchIn(t))
        assertTrue(t, Regex("Recibido:\\s+\\$200\\.00").containsMatchIn(t))
        assertTrue(t, Regex("Cambio:\\s+\\$12\\.50").containsMatchIn(t))
        assertTrue(t, "Cobrado en la máquina · Ref. $folio".replace(" ", "") in t.replace(Regex("\\s"), ""))
        assertFalse("sin deuda no se promete nada", "Te debemos" in t)
        assertFalse("con cotización el precio ya está confirmado", "Precio por confirmar" in t)
    }

    @Test
    fun `P1 con deuda dice cuanto le debemos de cambio y el folio, en negritas`() {
        val l = lineas(ticket(ReceiptCashMachine(referenceCC = folio, owedCents = 500), tendered = 20_000, change = 750))
        val deuda = l.dropWhile { !it.text.startsWith("Te debemos") }.takeWhile { it.bold }
        assertTrue(l.joinToString("\n") { it.text }, deuda.isNotEmpty())
        assertEquals(
            "Te debemos \$5.00 de cambio: pasa a caja a cobrarlo · Folio $folio",
            deuda.joinToString(" ") { it.text },
        )
    }

    @Test
    fun `R5#9 el ticket hecho sin red dice Precio por confirmar`() {
        assertTrue(texto(ticket(ReceiptCashMachine(referenceCC = folio, priceToConfirm = true))).contains("Precio por confirmar"))
    }

    @Test
    fun `un ticket de efectivo normal no cambia`() {
        val t = texto(ticket(null))
        assertFalse(t, "máquina" in t || "Te debemos" in t || "Precio por confirmar" in t)
    }

    @Test
    fun `R5#9 el comprobante de diferencia lleva el mismo folio, lo que le debemos y nada de la venta`() {
        val comprobante = ReceiptData(
            orderNumber = folio,
            orderType = "",
            items = emptyList(),
            subtotal = 0,
            taxAmount = 0,
            total = 500,
            venueName = "La Galeterie",
            date = Date(0),
            kind = KIND_COMPROBANTE_DE_LA_MAQUINA,
            cashMachine = ReceiptCashMachine(referenceCC = folio, owedCents = 500, title = "COMPROBANTE DE DIFERENCIA"),
        )

        val l = lineas(comprobante)
        val t = l.joinToString("\n") { it.text }
        assertEquals(LogicalLine.Text("COMPROBANTE DE DIFERENCIA", Align.CENTER, true, false), l.first { it.text.startsWith("COMPROBANTE") })
        assertTrue(t, "La Galeterie" in t)
        assertTrue(t, Regex("Folio:\\s+$folio").containsMatchIn(t))
        assertTrue(t, "Te debemos \$5.00: pasa a caja a cobrarlo".replace(" ", "") in t.replace(Regex("\\s"), ""))
        assertFalse("no es una venta: ni renglones ni total ni QR", "Café" in t || "TOTAL" in t)
        assertFalse("el original no dice reimpresión", "Reimpresión" in t)
    }

    /** Decisión C (Codex código POS r6): lo que dijo la máquina no cuadra: el papel lo dice, sin deuda ni cambio. */
    @Test
    fun `P1 Codex POS r6 Revisar en el panel sale en el ticket y en el comprobante, sin deuda ni cambio`() {
        val revisar = ReceiptCashMachine(referenceCC = folio, revisarEnElPanel = true)
        val t = texto(ticket(revisar).copy(cashTendered = null, changeAmount = null))
        assertTrue(t, "Revisar en el panel" in t)
        assertFalse(t, "Cambio:" in t || "Recibido:" in t || "Te debemos" in t)

        val comprobante = ReceiptData(
            orderNumber = folio, orderType = "", items = emptyList(), subtotal = 0, taxAmount = 0, total = 0, venueName = "La Galeterie",
            date = Date(0), kind = KIND_COMPROBANTE_DE_LA_MAQUINA, cashMachine = revisar,
        )
        val c = texto(comprobante)
        assertTrue(c, "Revisar en el panel" in c)
        assertFalse(c, "Te debemos" in c)
        assertFalse("sin la marca no sale", "Revisar en el panel" in texto(ticket(ReceiptCashMachine(referenceCC = folio))))
    }

    @Test
    fun `P1 Codex r4 #10 el comprobante reimpreso dice REIMPRESION, como cualquier ticket`() {
        val copia = ReceiptData(
            orderNumber = folio, orderType = "", items = emptyList(), subtotal = 0, taxAmount = 0, total = 500, venueName = "La Galeterie",
            date = Date(0), kind = KIND_COMPROBANTE_DE_LA_MAQUINA, reprintedAt = Date(60_000),
            cashMachine = ReceiptCashMachine(referenceCC = folio, owedCents = 500, title = "COMPROBANTE DE DIFERENCIA"),
        )

        val t = texto(copia)

        assertTrue(t, Regex("Reimpresión:\\s+\\S+").containsMatchIn(t))
    }
}
