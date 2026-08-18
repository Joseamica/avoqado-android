package com.avoqado.pos.orders

import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.RefundState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reembolsos — el carril ADITIVO que el server agregó el 2026-08-18
 * (`refundState` + `refundedAmount`). Una venta devuelta queda CERRADA y
 * MARCADA: el saldo NO se reabre, así que sin la marca se ve idéntica a una
 * cobrada. Espejo exacto de `OrderRefundStateTests.swift` en avoqado-ios.
 */
class OrderRefundStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Parseo (campo presente / ausente / basura)

    @Test
    fun `summary parses FULL refund from server payload`() {
        val order = json.decodeFromString<OrderSummary>(
            """{"id":"o1","orderNumber":"ORD-1","total":200.0,"refundState":"FULL","refundedAmount":200.0}""",
        )
        assertEquals(RefundState.FULL, order.refund)
        assertEquals(200.0, order.refundedAmount, 0.001)
    }

    @Test
    fun `summary parses PARTIAL refund from server payload`() {
        val order = json.decodeFromString<OrderSummary>(
            """{"id":"o1","orderNumber":"ORD-1","total":200.0,"refundState":"PARTIAL","refundedAmount":50.0}""",
        )
        assertEquals(RefundState.PARTIAL, order.refund)
        assertEquals(50.0, order.refundedAmount, 0.001)
    }

    /** Server viejo: el campo NO viene. Debe comportarse EXACTAMENTE como hoy. */
    @Test
    fun `summary without the refund fields falls back to NONE`() {
        val order = json.decodeFromString<OrderSummary>(
            """{"id":"o1","orderNumber":"ORD-1","total":200.0}""",
        )
        assertEquals(RefundState.NONE, order.refund)
        assertEquals(0.0, order.refundedAmount, 0.001)
        assertNull(order.refundBadgeLabel)
    }

    /** Un valor que este cliente no conoce NO puede pintar una marca inventada. */
    @Test
    fun `summary with an unknown refundState falls back to NONE`() {
        val order = json.decodeFromString<OrderSummary>(
            """{"id":"o1","orderNumber":"ORD-1","refundState":"CHARGED_BACK","refundedAmount":10.0}""",
        )
        assertEquals(RefundState.NONE, order.refund)
        assertNull(order.refundBadgeLabel)
    }

    @Test
    fun `detail parses the refund rail too`() {
        val detail = json.decodeFromString<OrderDetail>(
            """{"id":"o1","orderNumber":"ORD-1","refundState":"FULL","refundedAmount":120.0}""",
        )
        assertEquals(RefundState.FULL, detail.refund)
        assertEquals(120.0, detail.refundedAmount, 0.001)
    }

    @Test
    fun `detail without the refund fields falls back to NONE`() {
        val detail = json.decodeFromString<OrderDetail>("""{"id":"o1","orderNumber":"ORD-1"}""")
        assertEquals(RefundState.NONE, detail.refund)
        assertEquals(0.0, detail.refundedAmount, 0.001)
        assertNull(detail.refundBadgeLabel)
    }

    // MARK: - La regla de UI (texto de la marca)

    @Test
    fun `FULL prints Reembolsada`() {
        val order = OrderSummary(id = "1", refundState = "FULL", refundedAmount = 200.0)
        assertEquals("Reembolsada", order.refundBadgeLabel)
    }

    @Test
    fun `PARTIAL prints the amount given back`() {
        val order = OrderSummary(id = "1", refundState = "PARTIAL", refundedAmount = 50.5)
        assertEquals("Reembolso parcial: $50.50", order.refundBadgeLabel)
    }

    @Test
    fun `NONE prints no badge`() {
        val order = OrderSummary(id = "1", refundState = "NONE", refundedAmount = 0.0)
        assertNull(order.refundBadgeLabel)
    }

    @Test
    fun `detail badge text is identical to the summary badge text`() {
        val detail = OrderDetail(id = "1", refundState = "PARTIAL", refundedAmount = 50.5)
        val summary = OrderSummary(id = "1", refundState = "PARTIAL", refundedAmount = 50.5)
        assertEquals(summary.refundBadgeLabel, detail.refundBadgeLabel)
    }

    // MARK: - La regla de UI (cobrar)

    @Test
    fun `a fully refunded order can no longer be collected`() {
        assertFalse(OrderSummary(id = "1", refundState = "FULL").allowsCollection)
        assertFalse(OrderDetail(id = "1", refundState = "FULL").allowsCollection)
    }

    /**
     * PARCIAL también bloquea: el reembolso NO reabre el saldo (decisión del
     * founder, 2026-08-18 — Square/Toast/CFDI de Egreso), así que "cobrar el
     * saldo" sobre una cuenta devuelta cobraría de más.
     */
    @Test
    fun `a partially refunded order can no longer be collected either`() {
        assertFalse(OrderSummary(id = "1", refundState = "PARTIAL", refundedAmount = 50.0).allowsCollection)
        assertFalse(OrderDetail(id = "1", refundState = "PARTIAL", refundedAmount = 50.0).allowsCollection)
    }

    /** REGRESIÓN: sin reembolso, todo sigue exactamente como hoy. */
    @Test
    fun `an order with no refund is unaffected`() {
        val order = OrderSummary(id = "1", refundState = "NONE")
        assertTrue(order.allowsCollection)
        assertNull(order.refundBadgeLabel)
    }
}
