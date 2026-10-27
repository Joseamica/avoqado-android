package com.avoqado.pos.transactions.data.model

import java.math.BigInteger
import kotlin.math.max

data class ReturnedPiece(
    val orderItemId: String,
    val productId: String?,
    val productGroupId: String?,
    val paidCents: Long,
    val listCents: Long?,
)

data class TakenLine(
    val productId: String,
    val productGroupId: String?,
    val listCents: Long,
    val quantity: Int,
    val inheritsFromOrderItemId: String?,
    val unitPriceCents: Long? = null,
)

data class InheritedFrom(val orderItemId: String, val paidCents: Long)

data class TakenPiecePrice(
    val lineIndex: Int,
    val priceCents: Long,
    val listCents: Long,
    val inheritedFrom: InheritedFrom?,
)

enum class ExchangeKind { EVEN, COLLECT, PAYOUT }

data class ExchangeCalculation(
    val returnedCents: Long,
    val takenCents: Long,
    val differenceCents: Long,
    val kind: ExchangeKind,
    val takenPieces: List<TakenPiecePrice>,
)

private fun familyOf(productGroupId: String?, productId: String?): String? =
    productGroupId ?: productId?.let { "producto:$it" }

/** Exact half-up mirror of the server's BigInt formula; never multiplies money in floating point. */
fun inheritedPriceCents(paidCents: Long, returnedListCents: Long?, newListCents: Long): Long {
    require(paidCents >= 0 && newListCents >= 0 && (returnedListCents == null || returnedListCents >= 0)) {
        "EXCHANGE_INVALID_CENTS"
    }
    if (returnedListCents == null || returnedListCents == 0L) return paidCents
    val list = BigInteger.valueOf(returnedListCents)
    val numerator = BigInteger.valueOf(paidCents).multiply(BigInteger.valueOf(newListCents)).shiftLeft(1).add(list)
    val result = numerator.divide(list.shiftLeft(1))
    // BigInteger.longValueExact requires API 31; the POS supports API 26.
    if (result.bitLength() > 63) throw ArithmeticException("EXCHANGE_CENTS_OVERFLOW")
    return result.toLong()
}

/** Explicit same-family pointers pair first; remaining pieces pair FIFO by family, one to one. */
fun calculateExchange(returned: List<ReturnedPiece>, taken: List<TakenLine>): ExchangeCalculation {
    data class Slot(val line: TakenLine, val lineIndex: Int, var from: Int = -1)
    val slots = taken.flatMapIndexed { lineIndex, line -> List(max(0, line.quantity)) { Slot(line, lineIndex) } }
    val paired = BooleanArray(returned.size)
    fun queuesBy(keyOf: (ReturnedPiece) -> String?): Map<String, ArrayDeque<Int>> {
        val queues = mutableMapOf<String, ArrayDeque<Int>>()
        returned.forEachIndexed { i, piece ->
            val key = keyOf(piece)
            if (key != null && !paired[i]) queues.getOrPut(key) { ArrayDeque() }.addLast(i)
        }
        return queues
    }
    val byOrderItem = queuesBy { it.orderItemId }
    for (slot in slots) {
        val queue = byOrderItem[slot.line.inheritsFromOrderItemId ?: ""] ?: continue
        val i = queue.firstOrNull() ?: continue
        val family = familyOf(returned[i].productGroupId, returned[i].productId)
        if (family != null && family == familyOf(slot.line.productGroupId, slot.line.productId)) {
            queue.removeFirst()
            paired[i] = true
            slot.from = i
        }
    }
    val byFamily = queuesBy { familyOf(it.productGroupId, it.productId) }
    for (slot in slots) {
        val family = familyOf(slot.line.productGroupId, slot.line.productId)
        val queue = if (slot.from == -1 && family != null) byFamily[family] else null
        if (queue != null && queue.isNotEmpty()) slot.from = queue.removeFirst()
    }
    val takenPieces = slots.map { slot ->
        val line = slot.line
        if (slot.from == -1) {
            TakenPiecePrice(slot.lineIndex, line.unitPriceCents ?: line.listCents, line.listCents, null)
        } else {
            val piece = returned[slot.from]
            TakenPiecePrice(
                slot.lineIndex,
                inheritedPriceCents(piece.paidCents, piece.listCents, line.listCents),
                line.listCents,
                InheritedFrom(piece.orderItemId, piece.paidCents),
            )
        }
    }
    val returnedCents = returned.sumOf { it.paidCents }
    val takenCents = takenPieces.sumOf { it.priceCents }
    val differenceCents = takenCents - returnedCents
    val kind = when {
        differenceCents == 0L -> ExchangeKind.EVEN
        differenceCents > 0 -> ExchangeKind.COLLECT
        else -> ExchangeKind.PAYOUT
    }
    return ExchangeCalculation(returnedCents, takenCents, differenceCents, kind, takenPieces)
}

/** Reuses the refund unit allocation; excess already refunded is trimmed from the last piece. */
fun pieceValuesCents(
    lineChargedCents: Int,
    lineQuantity: Int,
    alreadyReturnedQty: Int,
    alreadyReturnedCents: Int,
    count: Int,
): List<Int> {
    val values = MutableList(max(0, count)) { j ->
        maxOf(0, RefundAmountCalculator.unitRefundCents(lineChargedCents, lineQuantity, alreadyReturnedQty + j, 1))
    }
    var excess = values.sumOf { it.toLong() } - maxOf(0L, lineChargedCents.toLong() - alreadyReturnedCents)
    for (j in values.indices.reversed()) {
        if (excess <= 0) break
        val cut = minOf(values[j].toLong(), excess).toInt()
        values[j] -= cut
        excess -= cut
    }
    return values
}
