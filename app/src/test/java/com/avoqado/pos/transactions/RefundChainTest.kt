package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RefundChainTest {
    @Test fun `credit exposes only inherited label and omits explicit refund method`() {
        val rules = refundChainRules("EXCHANGE_CREDIT", "CASH", "Efectivo original", null)
        assertEquals(listOf(OpcionDeDevolucion(null, "Efectivo original")), rules.options)
        assertNull(rules.blockedReason)
        assertNull(refundMethodForSubmit("EXCHANGE_CREDIT", "CASH"))
        val body = jsonReembolsos.encodeToJsonElement(AssociatedRefundRequest.serializer(), AssociatedRefundRequest(amount=100, reason="RETURNED_GOODS", refundMethod=refundMethodForSubmit("EXCHANGE_CREDIT", "CASH"))).jsonObject
        assertFalse(body.containsKey("refundMethod"))
        assertEquals("CASH", refundMethodForSubmit(null, "CASH"))
    }
    @Test fun `credit blocks by code even without reason and never exposes processor root`() {
        for ((code, text) in listOf("EXCHANGE_CHAIN_NEEDS_TERMINAL" to TextosCambio.EXCHANGE_CHAIN_NEEDS_TERMINAL,
            "EXCHANGE_CHAIN_GIFT_CARD_PENDING" to TextosCambio.EXCHANGE_CHAIN_GIFT_CARD_PENDING,
            "EXCHANGE_SETTLEMENT_UNAVAILABLE" to TextosCambio.EXCHANGE_SETTLEMENT_UNAVAILABLE,
            "FUTURE" to TextosCambio.EXCHANGE_SETTLEMENT_UNAVAILABLE)) {
            assertEquals(text, refundChainRules("EXCHANGE_CREDIT", "CASH", "Efectivo", code).blockedReason)
        }
        assertNotNull(refundChainRules("EXCHANGE_CREDIT", null, null, null).blockedReason)
        assertFalse(refundChainRules(null, null, null, "FUTURE").isCredit)
    }
}
