package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExchangeDetailDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `detail without exchange fields decodes and exchangeable is null`() {
        val sale = json.decodeFromString<Transaction>("""{"id":"sale","amount":500,"items":[{"id":"line","quantity":3}]}""")
        assertEquals(500.0, sale.amount, 0.0)
        assertNull(sale.exchangeable)
        assertNull(sale.exchange)
        assertNull(sale.payoutOptions)
        assertNull(sale.items.single().chargedTotalCents)
    }

    @Test fun `all additive exchange fields accept explicit null without coercion`() {
        val sale = json.decodeFromString<Transaction>("""{
          "id":"sale", "exchangeable":null,"notExchangeableReason":null,"tenderSystemKind":null,
          "chainRefundMethod":null,"chainRefundLabel":null,"chainRefundBlockedCode":null,"chainRefundBlockedReason":null,
          "exchange":null,"payoutOptions":null,"items":[{"quantity":3,"productId":null,"chargedTotalCents":null,
          "returnedQtyOrder":null,"returnedCentsOrder":null,"remainingQtyOrder":null,"productGroupId":null,
          "variantOptions":null,"productType":null,"isPromotionLine":null,"orderPromotionId":null,
          "notExchangeableReason":null,"restockable":null,"currentListPriceCents":null}]}""")
        assertNull(sale.exchangeable)
        assertNull(sale.items.single().productId)
        assertNull(sale.items.single().isPromotionLine)
        assertNull(sale.items.single().restockable)
        assertEquals(3, sale.items.single().quantity)
        assertEquals(ExchangeDetailFields(), json.decodeFromString<ExchangeDetailFields>("{}"))
        assertEquals(ExchangeItemFields(), json.decodeFromString<ExchangeItemFields>("{}"))
        assertNull(json.decodeFromString<ExchangeItemFields>("""{"quantity":null}""").quantity)
    }

    @Test fun `real server fields decode including both link directions and promotion identity`() {
        val sale = json.decodeFromString<Transaction>("""{
          "id":"sale","exchangeable":true,"notExchangeableReason":null,"tenderSystemKind":"EXCHANGE_CREDIT",
          "chainRefundMethod":"CASH","chainRefundLabel":"Efectivo","chainRefundBlockedCode":null,"chainRefundBlockedReason":null,
          "payoutOptions":[{"method":"CASH","maxCents":2200000000,"maxSameCents":35000,"needsRefundToCash":true}],
          "exchange":{"asOriginalTotal":1,"asOriginal":[{"exchangeId":"ex-out","newOrderId":"new-order",
            "newOrderNumber":"ORD-2","status":"PENDING_PAYMENT","reviewedAt":null}],
            "asNew":{"exchangeId":"ex-in","originalOrderId":"old-order","originalOrderNumber":"ORD-1",
            "status":"COMPLETED","reviewedAt":"2026-10-26T18:00:00Z"}},
          "items":[{"id":"line","productId":"product","quantity":3,"chargedTotalCents":10000,
            "returnedQtyOrder":2,"returnedCentsOrder":6667,"remainingQtyOrder":1,"productGroupId":"family",
            "variantOptions":[{"name":"Talla","value":"M","namePosition":0,"valuePosition":0}],
            "productType":"RETAIL","isPromotionLine":true,"orderPromotionId":"promotion-1",
            "notExchangeableReason":"PROMOTION_PARTIAL","restockable":true,"currentListPriceCents":50000}]}""")
        assertEquals(true, sale.exchangeable)
        assertEquals("EXCHANGE_CREDIT", sale.tenderSystemKind)
        assertEquals("CASH", sale.chainRefundMethod)
        assertEquals("Efectivo", sale.chainRefundLabel)
        assertEquals(2_200_000_000L, sale.payoutOptions!!.single().maxCents)
        assertEquals(35000L, sale.payoutOptions!!.single().maxSameCents)
        assertEquals(true, sale.payoutOptions!!.single().needsRefundToCash)
        assertEquals(1, sale.exchange!!.asOriginalTotal)
        assertEquals("ORD-2", sale.exchange!!.asOriginal!!.single().newOrderNumber)
        assertEquals("ORD-1", sale.exchange!!.asNew!!.originalOrderNumber)
        val item = sale.items.single()
        assertEquals("product", item.productId)
        assertEquals(10000L, item.chargedTotalCents)
        assertEquals(2, item.returnedQtyOrder)
        assertEquals(6667L, item.returnedCentsOrder)
        assertEquals(1, item.remainingQtyOrder)
        assertEquals("family", item.productGroupId)
        assertEquals("M", item.variantOptions!!.single().value)
        assertEquals("RETAIL", item.productType)
        assertEquals("promotion-1", item.orderPromotionId)
        assertEquals(true, item.isPromotionLine)
        assertEquals("PROMOTION_PARTIAL", item.notExchangeableReason)
        assertEquals(true, item.restockable)
        assertEquals(50000L, item.currentListPriceCents)
    }

    @Test fun `a malformed exchange block decodes to null without losing the sale`() {
        listOf("42", "[]", "{}", """{"asOriginalTotal":"bad","asOriginal":[],"asNew":null}""",
            """{"asOriginalTotal":1,"asOriginal":[{}],"asNew":null}""",
            """{"asOriginalTotal":0,"asOriginal":[],"asNew":{}}""").forEach { block ->
            val sale = json.decodeFromString<Transaction>("""{"id":"sale","amount":500,"exchange":$block}""")
            assertEquals(500.0, sale.amount, 0.0)
            assertNull(block, sale.exchange)
        }
    }

    @Test fun `malformed payout options become null as a whole instead of inventing capacity`() {
        listOf("{}", "42", "[{}]", """[{"method":"CASH","maxCents":"bad","maxSameCents":0,"needsRefundToCash":false}]""",
            """[{"method":"CASH","maxCents":100,"maxSameCents":0,"needsRefundToCash":false},null]""").forEach { block ->
            val sale = json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":$block}""")
            assertEquals("sale", sale.id)
            assertNull(block, sale.payoutOptions)
        }
        assertEquals(emptyList<PayoutOption>(), json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":[]}""").payoutOptions)
    }

    @Test fun `unknown payout methods are discarded without losing valid cash`() {
        val sale = json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":[
            {"method":"CASH","maxCents":100,"maxSameCents":0,"needsRefundToCash":false},
            {"method":"CRYPTO","maxCents":100,"maxSameCents":0,"needsRefundToCash":false}
        ]}""")
        assertEquals(listOf(PayoutOption("CASH", 100, 0, false)), sale.payoutOptions)
    }

    @Test fun `a noninteger cash capacity makes payout options null`() {
        val sale = json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":[
            {"method":"CASH","maxCents":"x","maxSameCents":0,"needsRefundToCash":false}
        ]}""")
        assertNull(sale.payoutOptions)
    }

    @Test fun `unsupported payout methods and negative capacity cannot become refund options`() {
        listOf(
            """{"method":"CARD_TERMINAL","maxCents":100,"maxSameCents":100,"needsRefundToCash":false}""",
            """{"method":"UNKNOWN","maxCents":100,"maxSameCents":0,"needsRefundToCash":false}""",
        ).forEach { option ->
            assertEquals(emptyList<PayoutOption>(), json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":[$option]}""").payoutOptions)
        }
        listOf(
            """{"method":"CASH","maxCents":-1,"maxSameCents":0,"needsRefundToCash":false}""",
            """{"method":"CASH","maxCents":100,"maxSameCents":-1,"needsRefundToCash":true}""",
        ).forEach { option ->
            assertNull(json.decodeFromString<Transaction>("""{"id":"sale","payoutOptions":[$option]}""").payoutOptions)
        }
    }
}
