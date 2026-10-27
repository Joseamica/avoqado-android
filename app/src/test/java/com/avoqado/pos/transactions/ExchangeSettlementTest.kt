package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.model.PayoutOption
import com.avoqado.pos.transactions.presentation.exchange.*
import org.junit.Assert.*
import org.junit.Test

class ExchangeSettlementTest {
    @Test fun `cash from noncash is disabled only without permission and override`() {
        val options = listOf(PayoutOption("CASH", 1000, 500, true))
        for (canRefund in listOf(false, true)) for (overrideEnabled in listOf(false, true)) for (amount in listOf(500L, 501L)) {
            val option = ExchangeSettlement.payoutOptions(options, amount, canRefund, overrideEnabled).single()
            val blocked = amount == 501L && !canRefund && !overrideEnabled
            assertEquals(if (blocked) TextosCambio.EFECTIVO_SIN_PERMISO else null, option.disabledReason)
            assertEquals(amount == 501L && !canRefund && overrideEnabled, option.requiresPin)
            assertEquals(if (amount == 501L) true else null, option.cashFromNonCash)
        }
    }
    @Test fun `collect offers cash and card only`() {
        assertEquals(listOf("CASH", "CARD_TERMINAL"), ExchangeSettlement.collectOptions().map { it.method })
    }
    @Test fun `payout caps capacity and only cash above same money needs permission`() {
        val options = listOf(PayoutOption("CASH", 500, 200, true), PayoutOption("BANK_TRANSFER", 300, 300, false))
        assertFalse(ExchangeSettlement.payoutOptions(options, 200, false, true).first().requiresPin)
        val cash = ExchangeSettlement.payoutOptions(options, 201, false, true).first()
        assertTrue(cash.requiresPin)
        assertEquals(true, cash.cashFromNonCash)
        assertNull(ExchangeSettlement.payoutOptions(options, 200, false, true).first().cashFromNonCash)
        assertFalse(ExchangeSettlement.payoutOptions(options, 201, true, true).first().requiresPin)
        assertEquals(listOf("CASH"), ExchangeSettlement.payoutOptions(options, 400, false, true).map { it.method })
        assertTrue(ExchangeSettlement.payoutOptions(options, 501, true, true).isEmpty())
        assertTrue(ExchangeSettlement.payoutOptions(emptyList(), 1, true, true).isEmpty())
    }
    @Test fun `unknown methods and missing capacity are never offered`() {
        assertTrue(ExchangeSettlement.payoutOptions(listOf(PayoutOption("CARD_TERMINAL", 500, 500, false), PayoutOption("CASH")), 1, true, true).isEmpty())
    }
}
