package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefundAmountCalculatorTest {

    @Test
    fun `full quantity keeps exact line total`() {
        val item = TransactionItem(
            id = "oi_1",
            productName = "Shake",
            quantity = 3,
            amount = 10.0,
        )

        val totalRefund = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(item),
            selectedIds = setOf("oi_1"),
            refundQtyByItem = mapOf("oi_1" to 3),
        )

        assertEquals(10.0, totalRefund, 0.001)
    }

    @Test
    fun `selected quantity uses deterministic cents allocation`() {
        val item = TransactionItem(
            id = "oi_2",
            productName = "Hamburguesa",
            quantity = 3,
            amount = 10.0,
        )

        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(item),
            selectedIds = setOf("oi_2"),
            refundQtyByItem = mapOf("oi_2" to 2),
        )

        assertEquals(6.67, refundAmount, 0.001)
    }

    @Test
    fun `single unit on 10 dollar qty 3 line rounds to first cent remainder`() {
        val item = TransactionItem(
            id = "oi_3",
            productName = "Papas",
            quantity = 3,
            amount = 10.0,
        )

        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(item),
            selectedIds = setOf("oi_3"),
            refundQtyByItem = mapOf("oi_3" to 1),
        )

        assertEquals(3.34, refundAmount, 0.001)
    }

    // ── Espejo del servidor: el reparto de centavos sigue a las piezas YA devueltas ──
    // `getUnitRefundCents` (avoqado-server refund.dashboard.service.ts) reparte $10 / 3 como
    // [3.34, 3.33, 3.33] y empieza en la pieza que sigue a lo ya devuelto. Con offset fijo en 0
    // la app pedía $6.67 cuando sólo quedaban $6.66 y el botón «Reembolsar» se bloqueaba.

    private fun lineaConDevoluciones(refundedQty: Int) = TransactionItem(
        id = "oi_4",
        productName = "Pan",
        quantity = 3,
        amount = 10.0,
        refundedQty = refundedQty,
        remainingQty = 3 - refundedQty,
    )

    @Test
    fun `P1 after refunding one unit the remaining two cost what the server refunds`() {
        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(lineaConDevoluciones(refundedQty = 1)),
            selectedIds = setOf("oi_4"),
            refundQtyByItem = mapOf("oi_4" to 2),
        )

        assertEquals(6.66, refundAmount, 0.001)
    }

    @Test
    fun `P1 the last unit after two refunds is the remaining cent split`() {
        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(lineaConDevoluciones(refundedQty = 2)),
            selectedIds = setOf("oi_4"),
            refundQtyByItem = mapOf("oi_4" to 1),
        )

        assertEquals(3.33, refundAmount, 0.001)
    }

    @Test
    fun `P1 a partially refunded line selected without touching the stepper refunds what remains`() {
        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(lineaConDevoluciones(refundedQty = 1)),
            selectedIds = setOf("oi_4"),
            refundQtyByItem = emptyMap(),
        )

        assertEquals(6.66, refundAmount, 0.001)
    }

    @Test
    fun `a fully refunded line adds nothing`() {
        val refundAmount = RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(lineaConDevoluciones(refundedQty = 3)),
            selectedIds = setOf("oi_4"),
            refundQtyByItem = emptyMap(),
        )

        assertEquals(0.0, refundAmount, 0.001)
    }

    @Test
    fun `refundableQty is what remains of the line, never negative`() {
        assertEquals(2, lineaConDevoluciones(refundedQty = 1).refundableQty)
        assertEquals(0, lineaConDevoluciones(refundedQty = 3).refundableQty)
        assertEquals(3, TransactionItem(id = "oi_5", quantity = 3, amount = 10.0).refundableQty)
    }

    // ── Lo COBRADO, no el bruto (IVA C2, decisión A del founder, 9-oct-2026) ──
    // El servidor devuelve por artículo lo que el renglón COBRÓ (`chargedTotal`, sin su descuento
    // propio ni su parte del de la cuenta). La pantalla tiene que pedir ese mismo número: con el
    // bruto, una venta con descuento pedía más que lo que queda del cobro y «Reembolsar» se
    // bloqueaba, o enseñaba una cifra que el servidor no iba a devolver. Mismos casos en iOS
    // (`RefundAmountCalculatorTests.swift`).

    private fun monto(item: TransactionItem, qty: Int? = null) =
        RefundAmountCalculator.calculateSelectedAmount(
            items = listOf(item),
            selectedIds = setOf(item.id!!),
            refundQtyByItem = qty?.let { mapOf(item.id!! to it) } ?: emptyMap(),
        )

    @Test
    fun `P1 a line with total 100 that charged 90 refunds 90`() {
        val item = TransactionItem(id = "oi_a", productName = "A", quantity = 1, amount = 100.0, chargedTotal = 90.0)

        assertEquals(90.0, monto(item), 0.001)
    }

    @Test
    fun `P1 three units that charged 10 split the cents of what was charged`() {
        val item = TransactionItem(id = "oi_b", productName = "Pan", quantity = 3, amount = 12.0, chargedTotal = 10.0)

        assertEquals(3.34, monto(item, qty = 1), 0.001)
    }

    @Test
    fun `P1 after refunding one charged unit the remaining two are 6_66`() {
        val item = TransactionItem(
            id = "oi_b",
            productName = "Pan",
            quantity = 3,
            amount = 12.0,
            chargedTotal = 10.0,
            refundedQty = 1,
            remainingQty = 2,
        )

        assertEquals(6.66, monto(item), 0.001)
    }

    @Test
    fun `without chargedTotal the line total is used as always`() {
        val item = TransactionItem(id = "oi_c", productName = "C", quantity = 1, amount = 100.0)

        assertEquals(100.0, monto(item), 0.001)
    }

    @Test
    fun `P1 a courtesy line that charged 0 refunds nothing`() {
        val item = TransactionItem(id = "oi_d", productName = "Cortesía", quantity = 2, amount = 50.0, chargedTotal = 0.0)

        assertEquals(0.0, monto(item), 0.001)
    }

    // El detalle se decodifica con este mismo `Json` (`TransactionRepository.json`, sin coerción):
    // el campo nuevo no puede tumbar la venta ni con `null` ni ausente (el `name: null` del 11-sep).
    private val json = Json { ignoreUnknownKeys = true }

    private fun detalleConItem(chargedTotal: String?) = json.decodeFromString<Transaction>(
        """{ "id": "p1", "amount": 140, "tipAmount": 0, "method": "CASH", "status": "COMPLETED",
            "items": [ { "id": "oi_a", "productName": "A", "quantity": 1, "unitPrice": 100, "total": 100
            ${chargedTotal?.let { ", \"chargedTotal\": $it" } ?: ""} } ] }""",
    ).items.single()

    @Test
    fun `chargedTotal is decoded from the transaction detail`() {
        val item = detalleConItem("90.5")

        assertEquals(90.5, item.chargedTotal!!, 0.001)
        assertEquals(90.5, monto(item), 0.001)
    }

    @Test
    fun `chargedTotal null decodes as null and refunds the line total`() {
        val item = detalleConItem("null")

        assertNull(item.chargedTotal)
        assertEquals(100.0, monto(item), 0.001)
    }

    @Test
    fun `chargedTotal absent decodes as null and refunds the line total`() {
        val item = detalleConItem(null)

        assertNull(item.chargedTotal)
        assertEquals(100.0, monto(item), 0.001)
    }
}
