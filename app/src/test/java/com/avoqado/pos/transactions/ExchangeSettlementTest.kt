package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.model.PayoutOption
import com.avoqado.pos.transactions.presentation.exchange.*
import org.junit.Assert.*
import org.junit.Test

class ExchangeSettlementTest {
    @Test fun `collect offers cash and card only`() {
        assertEquals(listOf("CASH", "CARD_TERMINAL"), ExchangeSettlement.collectOptions().map { it.method })
    }
    @Test fun `payout caps capacity and only cash above same money needs permission`() {
        val options = listOf(PayoutOption("CASH", 500, 200, true), PayoutOption("BANK_TRANSFER", 300, 300, false))
        assertFalse(ExchangeSettlement.payoutOptions(options, 200, false).first().requiresPin)
        val cash = ExchangeSettlement.payoutOptions(options, 201, false).first()
        assertTrue(cash.requiresPin)
        assertEquals(true, cash.cashFromNonCash)
        assertNull(ExchangeSettlement.payoutOptions(options, 200, false).first().cashFromNonCash)
        assertFalse(ExchangeSettlement.payoutOptions(options, 201, true).first().requiresPin)
        assertEquals(listOf("CASH"), ExchangeSettlement.payoutOptions(options, 400, false).map { it.method })
        assertTrue(ExchangeSettlement.payoutOptions(options, 501, true).isEmpty())
        assertTrue(ExchangeSettlement.payoutOptions(emptyList(), 1, true).isEmpty())
    }
    @Test fun `unknown methods and missing capacity are never offered`() {
        assertTrue(ExchangeSettlement.payoutOptions(listOf(PayoutOption("CARD_TERMINAL", 500, 500, false), PayoutOption("CASH")), 1, true).isEmpty())
    }
}
