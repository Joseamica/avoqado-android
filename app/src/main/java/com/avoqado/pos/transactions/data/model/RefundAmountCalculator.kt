package com.avoqado.pos.transactions.data.model

import kotlin.math.roundToInt

/**
 * Lo que devolverá el servidor, al centavo. Espejo EXACTO de `getUnitRefundCents`
 * (avoqado-server refund.dashboard.service.ts): reparte los centavos del renglón entre sus piezas
 * —$10 / 3 = [3.34, 3.33, 3.33]— y cobra desde la pieza que sigue a lo YA devuelto. Mismo
 * algoritmo en iOS (`RefundAmountCalculator.swift`) y en el dashboard (`refundAmount.ts`).
 */
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
                val remaining = item.refundableQty.coerceAtMost(quantity)
                if (remaining <= 0) return@sumOf 0
                // Sin tocar el contador se devuelve lo que QUEDA, no la cantidad original.
                val requestedQty = (refundQtyByItem[item.id] ?: remaining).coerceIn(1, remaining)
                val lineTotalCents = (item.amount * 100).roundToInt()
                unitRefundCents(
                    totalCents = lineTotalCents,
                    quantity = quantity,
                    offset = quantity - remaining,
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
