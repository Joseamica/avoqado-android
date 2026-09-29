package com.avoqado.pos.transactions.data.model

import org.junit.Assert.assertEquals
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
}
