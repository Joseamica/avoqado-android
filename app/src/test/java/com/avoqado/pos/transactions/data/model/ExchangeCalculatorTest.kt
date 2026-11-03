package com.avoqado.pos.transactions.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExchangeCalculatorTest {
    private fun p(id: String, paid: Long, list: Long?, family: String? = "falda", product: String? = id) =
        ReturnedPiece(id, product, family, paid, list)

    private fun t(id: String, list: Long, from: String? = null, family: String? = "falda", quantity: Int = 1, unit: Long? = null) =
        TakenLine(id, family, list, quantity, from, unit)

    private fun vector(returned: List<ReturnedPiece>, taken: List<TakenLine>, r: Long, n: Long, d: Long, kind: ExchangeKind) {
        val result = calculateExchange(returned, taken)
        assertEquals(r, result.returnedCents)
        assertEquals(n, result.takenCents)
        assertEquals(d, result.differenceCents)
        assertEquals(kind, result.kind)
        assertEquals(taken.sumOf { it.quantity }, result.takenPieces.size)
    }

    @Test fun `V1 same list price is even`() =
        vector(listOf(p("M", 50000, 50000)), listOf(t("L", 50000, "M")), 50000, 50000, 0, ExchangeKind.EVEN)

    @Test fun `V2 same family keeps the discount`() =
        vector(listOf(p("M", 35000, 50000)), listOf(t("L", 50000, "M")), 35000, 35000, 0, ExchangeKind.EVEN)

    @Test fun `V3 higher list price inherits the discount ratio`() =
        vector(listOf(p("M", 35000, 50000)), listOf(t("XL", 55000, "M")), 35000, 38500, 3500, ExchangeKind.COLLECT)

    @Test fun `V4 another family pays list price`() =
        vector(listOf(p("M", 35000, 50000)), listOf(t("Blusa", 45000, null, "otra-familia")), 35000, 45000, 10000, ExchangeKind.COLLECT)

    @Test fun `V5 cheaper product pays out the difference`() =
        vector(listOf(p("M", 50000, 50000)), listOf(t("Top", 39000, null, "otra-familia")), 50000, 39000, -11000, ExchangeKind.PAYOUT)

    @Test fun `V6 two returns and mixed families`() =
        vector(listOf(p("M", 35000, 50000), p("M", 35000, 50000)), listOf(t("L", 50000, "M"), t("Blusa", 45000, null, "otra-familia")), 70000, 80000, 10000, ExchangeKind.COLLECT)

    @Test fun `V7 changed current list preserves the paid amount`() =
        vector(listOf(p("M", 50000, 60000)), listOf(t("L", 60000, "M")), 50000, 50000, 0, ExchangeKind.EVEN)

    @Test fun `V8 archived return without list uses paid amount`() =
        vector(listOf(p("M", 42000, null)), listOf(t("L", 50000, "M")), 42000, 42000, 0, ExchangeKind.EVEN)

    @Test fun `V9 fractional cent rounds half up`() =
        vector(listOf(p("M", 33333, 50000)), listOf(t("XL", 55000, "M")), 33333, 36666, 3333, ExchangeKind.COLLECT)

    @Test fun `reports which returned piece each taken piece inherited from`() {
        val calc = calculateExchange(
            listOf(p("M", 35000, 50000), p("S", 30000, 50000), p("S", 28000, 50000)),
            listOf(t("XL", 55000, "S"), t("L", 50000, "M"), t("L", 50000, "S")),
        )
        assertEquals(listOf(
            TakenPiecePrice(0, 33000, 55000, InheritedFrom("S", 30000)),
            TakenPiecePrice(1, 35000, 50000, InheritedFrom("M", 35000)),
            TakenPiecePrice(2, 28000, 50000, InheritedFrom("S", 28000)),
        ), calc.takenPieces)
    }

    @Test fun `calculateExchange creates no pieces for negative quantities`() {
        val result = calculateExchange(listOf(p("M", 35000, 50000)), listOf(t("L", 50000, "M", quantity = -2)))
        assertEquals(emptyList<TakenPiecePrice>(), result.takenPieces)
        assertEquals(35000L, result.returnedCents)
        assertEquals(0L, result.takenCents)
        assertEquals(-35000L, result.differenceCents)
        assertEquals(ExchangeKind.PAYOUT, result.kind)
    }

    @Test fun `pieceValuesCents returns an empty list for a negative count`() {
        assertEquals(emptyList<Int>(), pieceValuesCents(10000, 3, 0, 0, -2))
    }

    @Test fun `explicit pairs take priority over automatic family pairs`() {
        val result = calculateExchange(listOf(p("M", 35000, 50000), p("S", 30000, 50000)), listOf(t("XL", 55000), t("L", 50000, "M")))
        assertEquals(listOf(33000L, 35000L), result.takenPieces.map { it.priceCents })
        assertEquals(listOf("S", "M"), result.takenPieces.map { it.inheritedFrom?.orderItemId })
        assertEquals(listOf(0, 1), result.takenPieces.map { it.lineIndex })
    }

    @Test fun `wrong family pointer does not consume the return`() {
        val result = calculateExchange(listOf(p("M", 35000, 50000)), listOf(t("Blusa", 45000, "M", "otra-familia"), t("L", 50000, "M")))
        assertEquals(listOf(45000L, 35000L), result.takenPieces.map { it.priceCents })
        assertNull(result.takenPieces[0].inheritedFrom)
        assertEquals("M", result.takenPieces[1].inheritedFrom?.orderItemId)
    }

    @Test fun `unmatched units use the lower unit price without changing inherited units`() {
        val result = calculateExchange(listOf(p("M", 35000, 50000)), listOf(t("L", 50000, "M", quantity = 2, unit = 48000)))
        assertEquals(listOf(35000L, 48000L), result.takenPieces.map { it.priceCents })
        assertEquals(listOf(50000L, 50000L), result.takenPieces.map { it.listCents })
        assertEquals(83000L, result.takenCents)
    }

    @Test fun `family fallback is the product and missing product never inherits`() {
        val shampoo = p("oi-shampoo", 8000, 10000, null, "shampoo")
        assertEquals(8000L, calculateExchange(listOf(shampoo), listOf(t("shampoo", 10000, family = null))).takenCents)
        assertEquals(10000L, calculateExchange(listOf(shampoo), listOf(t("acondicionador", 10000, "oi-shampoo", null))).takenCents)
        assertEquals(10000L, calculateExchange(listOf(p("gone", 8000, 10000, null, null)), listOf(t("shampoo", 10000, "gone", null))).takenCents)
    }

    @Test fun `zero paid returns and zero takes are independent`() {
        vector(listOf(p("M", 0, 50000)), listOf(t("L", 50000, "M")), 0, 0, 0, ExchangeKind.EVEN)
        vector(listOf(p("M", 0, 50000)), listOf(t("Blusa", 45000, null, "otra-familia")), 0, 45000, 45000, ExchangeKind.COLLECT)
        vector(listOf(p("M", 0, 50000), p("B", 50000, 50000)), listOf(t("L", 50000, "M")), 50000, 0, -50000, ExchangeKind.PAYOUT)
    }

    @Test fun `inheritedPriceCents is half-up and exact past 2 to the power 31`() {
        assertEquals(1L, inheritedPriceCents(1, 2, 1))
        assertEquals(36666L, inheritedPriceCents(33333, 50000, 55000))
        assertEquals(100_000_001L, inheritedPriceCents(100_000_000, 99_999_999, 100_000_000))
        assertEquals(2_200_000_000L, inheritedPriceCents(2_200_000_000, 2_200_000_000, 2_200_000_000))
        assertEquals(42000L, inheritedPriceCents(42000, null, 50000))
        assertEquals(42000L, inheritedPriceCents(42000, 0, 50000))
        vector(listOf(p("M", 2_200_000_000, 2_200_000_000), p("S", 2_200_000_000, 2_200_000_000)), listOf(t("L", 2_200_000_000, quantity = 2)), 4_400_000_000, 4_400_000_000, 0, ExchangeKind.EVEN)
    }

    @Test fun `inheritedPriceCents rejects negative cents`() {
        assertThrows(IllegalArgumentException::class.java) { inheritedPriceCents(-1, 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { inheritedPriceCents(1, -2, 1) }
        assertThrows(IllegalArgumentException::class.java) { inheritedPriceCents(1, 2, -1) }
    }

    @Test fun `inheritedPriceCents handles full width products and rejects results outside Long`() {
        assertEquals(Long.MAX_VALUE, inheritedPriceCents(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertThrows(ArithmeticException::class.java) { inheritedPriceCents(Long.MAX_VALUE, 1, 2) }
    }

    @Test fun `pieceValuesCents splits and continues after prior returns`() {
        assertEquals(listOf(3334, 3333, 3333), pieceValuesCents(10000, 3, 0, 0, 3))
        assertEquals(listOf(3334, 3333), pieceValuesCents(10000, 3, 0, 0, 2))
        assertEquals(listOf(3333, 3333), pieceValuesCents(10000, 3, 1, 3334, 2))
        assertEquals(listOf(3333), pieceValuesCents(10000, 3, 2, 6667, 1))
    }

    @Test fun `pieceValuesCents trims the excess from the last piece when another refund took more than its share`() {
        assertEquals(listOf(1000, 0), pieceValuesCents(10000, 2, 0, 9000, 2))
        assertEquals(listOf(0, 0), pieceValuesCents(10000, 2, 0, 12000, 2))
        assertEquals(listOf(0, 0), pieceValuesCents(-100, 3, 0, 0, 2))
    }

    @Test fun `pieceValuesCents visits only requested pieces across the remainder boundary`() {
        assertEquals(listOf(124, 123), pieceValuesCents(123_456_789, 1_000_000, 456_788, 56_641_712, 2))
        assertEquals(listOf(1, 1), pieceValuesCents(10000, Int.MAX_VALUE, 0, 0, 2))
    }
}
