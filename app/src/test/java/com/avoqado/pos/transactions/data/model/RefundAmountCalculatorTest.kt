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
}
