package com.avoqado.pos.transactions.presentation

import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.TotalesDelTicket
import com.avoqado.pos.printing.receiptlayout.LogicalLine
import com.avoqado.pos.printing.receiptlayout.ReceiptBlock
import com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter
import com.avoqado.pos.transactions.data.model.TicketTotalsDto
import com.avoqado.pos.transactions.data.model.Transaction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class TransactionReprintReceiptTest {

    private val cuando = Date(1_788_380_000_000L)

    @Test
    fun `P1 la reimpresion lleva su hora y conserva la tarjeta como marca y ultimos 4`() {
        val tx = Transaction(id = "pay_123456", method = "CARD", amount = 45.0, cardBrand = "VISA", maskedPan = "411111******4242")
        val r = tx.toReceiptData(venueName = "Testarudo", reprintedAt = cuando)
        assertEquals(cuando, r.reprintedAt)
        assertEquals("Tarjeta", r.paymentMethod)
        assertEquals("VISA", r.cardBrand)
        assertEquals("4242", r.cardLastFour)
    }

    @Test
    fun `efectivo sigue siendo Efectivo y sin datos de tarjeta`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 10.0).toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals("Efectivo", r.paymentMethod)
        assertNull(r.cardLastFour)
        assertNull(r.cardBrand)
    }

    // MARK: - Reimpresión desde el comprobante COMPLETO (IVA por producto, plan 7a, Tarea 8; r2 R2-9, r3 R3-11; ruling A7-R6)

    private fun cuadra(r: ReceiptData) =
        r.total == r.subtotal - (r.discountAmount ?: 0) + (r.serviceChargeAmount ?: 0) + r.taxAmount + (r.tipAmount ?: 0)

    private fun comprobante(kind: String = "COBRO", desglosado: Boolean = true, subtotal: Double, serviceCharge: Double = 0.0,
                            addedTax: Double = 0.0, tip: Double = 0.0) =
        TicketTotalsDto(kind, desglosado, subtotal, serviceCharge, addedTax, tip, subtotal + serviceCharge + addedTax + tip)

    /** Los renglones de dinero tal como salen en el PAPEL (intérprete real, bloque de totales). */
    private fun renglonesDelPapel(r: ReceiptData): List<String> =
        ReceiptLayoutInterpreter.interpret(
            listOf(ReceiptBlock.Totals()),
            ReceiptInputMapper.map(r, info = null, hasLogo = false, timezone = "America/Mexico_City", appVersion = null),
            PaperWidth.MM80.charsPerLine,
        ).filterIsInstance<LogicalLine.Text>().map { it.text }

    @Test
    fun `P1 r3 R3-11 - emitido por 116 mas 10 de propina, propina corregida despues a 5 - el papel dice 10 y 126`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 116.0, tipAmount = 5.0, // el pago vivo, ya corregido
            ticketTotals = comprobante(subtotal = 100.0, addedTax = 16.0, tip = 10.0))
            .toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals(10000, r.subtotal)
        assertEquals(1600, r.taxAmount)
        assertEquals(1000, r.tipAmount)
        assertEquals(12600, r.total)
        assertEquals(true, cuadra(r))
    }

    @Test
    fun `P1 r3 R3-11 - importe corregido despues - subtotal, IVA y total del comprobante, nunca mezclados con el importe vivo`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 120.0, tipAmount = 0.0,
            ticketTotals = comprobante(subtotal = 100.0, addedTax = 16.0))
            .toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals(listOf(10000, 1600, 11600), listOf(r.subtotal, r.taxAmount, r.total))
    }

    @Test
    fun `P1 cargo por servicio en su renglon al reimprimir`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 115.0, ticketTotals = comprobante(subtotal = 100.0, serviceCharge = 15.0))
            .toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals(10000, r.subtotal)
        assertEquals(1500, r.serviceChargeAmount)
        assertEquals(11500, r.total)
        assertEquals(true, cuadra(r))
    }

    @Test
    fun `P1 devolucion total con propina - en positivo, marcada como devolucion, sin IVA agregado`() {
        val r = Transaction(id = "ref_1", method = "CARD", amount = -116.0, tipAmount = -10.0,
            ticketTotals = comprobante(kind = "DEVOLUCION", desglosado = false, subtotal = 116.0, tip = 10.0))
            .toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals(true, r.isRefund)
        assertEquals(11600, r.subtotal)
        assertEquals(1000, r.tipAmount)
        assertEquals(12600, r.total)
        assertEquals(0, r.taxAmount)
        assertEquals(true, cuadra(r))
    }

    @Test
    fun `P1 devolucion de SOLO propina - con el comprobante del servidor y con un servidor viejo (r2 R2-6)`() {
        for (tx in listOf(
            Transaction(id = "ref_3", method = "CARD", amount = 0.0, tipAmount = -10.0,
                ticketTotals = comprobante(kind = "DEVOLUCION", desglosado = false, subtotal = 0.0, tip = 10.0)),
            Transaction(id = "ref_3", method = "CARD", amount = 0.0, tipAmount = -10.0),
        )) {
            val r = tx.toReceiptData(venueName = "T", reprintedAt = cuando)
            assertEquals(true, r.isRefund)
            assertEquals(0, r.subtotal)
            assertEquals(1000, r.tipAmount)
            assertEquals(1000, r.total)
        }
    }

    @Test
    fun `servidor viejo - devolucion parcial sin propina por los signos`() {
        val r = Transaction(id = "ref_2", method = "CASH", amount = -50.0).toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals(true, r.isRefund)
        assertEquals(5000, r.subtotal)
        assertNull(r.tipAmount)
        assertEquals(5000, r.total)
    }

    @Test
    fun `servidor viejo o comprobante sin desglose - la reimpresion queda como hoy, con la leyenda`() {
        for (tx in listOf(
            Transaction(id = "pay_1", method = "CASH", amount = 290.0, tipAmount = 10.0),
            Transaction(id = "pay_1", method = "CASH", amount = 290.0, tipAmount = 10.0,
                ticketTotals = comprobante(desglosado = false, subtotal = 290.0, tip = 10.0)),
        )) {
            val r = tx.toReceiptData(venueName = "T", reprintedAt = cuando)
            assertEquals(listOf(29000, 0, 30000), listOf(r.subtotal, r.taxAmount, r.total))
            assertEquals(false, r.isRefund)
        }
    }

    @Test
    fun `el detalle del servidor trae el comprobante y un servidor viejo no rompe`() {
        val json = Json { ignoreUnknownKeys = true }
        val nuevo = json.decodeFromString<Transaction>(
            """{"id":"p","amount":116.0,"ticketTotals":{"kind":"COBRO","desglosado":true,"subtotal":100,"serviceCharge":0,"addedTax":16,"tip":0,"total":116}}""",
        )
        assertEquals(16.0, nuevo.ticketTotals?.addedTax)
        assertNull(json.decodeFromString<Transaction>("""{"id":"p","amount":116.0}""").ticketTotals)
    }

    @Test
    fun `P1 A7-R6 - venta de 100 con 10 de descuento cobrada en 90 - el papel reimpreso es el mismo que el del cobro`() {
        // El papel del cobro (A2): `buildReceiptSnapshot` arma sus renglones con `TotalesDelTicket.desdeLoCobrado` y los pone así.
        val papel = TotalesDelTicket.desdeLoCobrado(cobrado = 9000, propina = 0, subtotalBruto = 10000, cargo = 0, impuestoAgregado = 0)
        val delCobro = ReceiptData(
            orderNumber = "42", orderType = "En tienda", items = emptyList(), venueName = "T",
            subtotal = papel.subtotal, taxAmount = papel.impuesto, tipAmount = null,
            discountAmount = papel.descuento.takeIf { it > 0 }, total = papel.total,
            serviceChargeAmount = papel.cargo.takeIf { it > 0 },
        )
        // La reimpresión: el detalle de venta con el comprobante TAL COMO lo manda el servidor (Tarea 4): subtotal BRUTO + discount.
        // El pago vivo dice 85 (corregido después): no se lee.
        val tx = Json { ignoreUnknownKeys = true }.decodeFromString<Transaction>(
            """{"id":"pay_1","method":"CASH","amount":85.0,"ticketTotals":{"kind":"COBRO","desglosado":true,"subtotal":100,"discount":10,"serviceCharge":0,"addedTax":0,"tip":0,"total":90}}""",
        )
        val r = tx.toReceiptData(venueName = "T", reprintedAt = cuando)

        assertEquals(listOf(10000, 1000, 0, 9000), listOf(r.subtotal, r.discountAmount, r.taxAmount, r.total))
        assertNull(r.serviceChargeAmount)
        assertNull(r.tipAmount)
        assertEquals(true, cuadra(r))
        // Mismas cifras y mismo orden en el papel: Subtotal → Descuento → leyenda → TOTAL.
        assertEquals(renglonesDelPapel(delCobro), renglonesDelPapel(r))
    }

    @Test
    fun `control - comprobante desglosado sin discount - sin renglon de descuento y con la leyenda`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 100.0, ticketTotals = comprobante(subtotal = 100.0))
            .toReceiptData(venueName = "T", reprintedAt = cuando)
        assertNull(r.discountAmount)
        val papel = renglonesDelPapel(r)
        assertFalse(papel.toString(), papel.any { it.contains("Descuento") })
        assertTrue(papel.toString(), papel.any { it.contains("IVA incluido cuando aplica") })
        assertEquals(10000, r.total)
    }
}
