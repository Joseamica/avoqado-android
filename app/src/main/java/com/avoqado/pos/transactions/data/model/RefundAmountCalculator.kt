package com.avoqado.pos.transactions.data.model

import kotlin.math.roundToInt

object RefundAmountCalculator {
    fun calculateSelectedAmount(
        items: List<TransactionItem>,
        selectedIds: Set<String>,
        refundQtyByItem: Map<String, Int>,
    ): Double {
        val totalCents = items
            .asSequence()
            .filter { item -> item.id != null && selectedIds.contains(item.id) }
            .sumOf { item ->
                val quantity = item.quantity.coerceAtLeast(1)
                val requestedQty = (refundQtyByItem[item.id] ?: quantity).coerceIn(1, quantity)
                val lineTotalCents = (item.amount * 100).roundToInt()
                unitRefundCents(
                    totalCents = lineTotalCents,
                    quantity = quantity,
                    offset = 0,
                    count = requestedQty,
                )
            }

        return totalCents / 100.0
    }

    private fun unitRefundCents(
        totalCents: Int,
        quantity: Int,
        offset: Int,
        count: Int,
    ): Int {
        if (quantity <= 0 || count <= 0) return 0
        val baseUnit = totalCents / quantity
        val remainder = totalCents % quantity
        val end = offset + count
        val bonusUnits = maxOf(0, minOf(remainder, end) - minOf(remainder, offset))
        return (baseUnit * count) + bonusUnits
    }
}
