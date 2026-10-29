package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.exchange.*
import org.junit.Assert.*
import org.junit.Test

class ExchangeEntryTest {
    private val sale = Transaction(id = "payment", exchangeable = true,
        payoutOptions = emptyList(), exchange = ExchangeLinks(0, emptyList()))
    private fun decide(t: Transaction = sale, enabled: Boolean = true, online: Boolean = true,
        refund: Boolean = true, overrideEnabled: Boolean = false) =
        ExchangeEntry.decide(t, enabled, online, refund, overrideEnabled)

    @Test fun `entry is hidden when disabled or the row is a refund or the contract is absent`() {
        assertFalse(decide(enabled = false).visible)
        assertFalse(decide(sale.copy(status = "REFUNDED", amount = -5.0)).visible)
        assertFalse(decide(Transaction(id = "old-server")).visible)
        assertFalse(decide(sale.copy(payoutOptions = null)).visible)
    }

    @Test fun `entry is greyed with the exact offline reason`() {
        assertEquals("El cambio necesita conexión", decide(online = false).blockedReason)
        assertTrue(decide(online = false).visible)
    }

    @Test fun `entry translates server reasons without exposing codes`() {
        assertEquals(TextosCambio.NOT_ATTRIBUTABLE,
            decide(sale.copy(exchangeable = false, notExchangeableReason = "NOT_ATTRIBUTABLE")).blockedReason)
        assertEquals(TextosCambio.ORDER_LOCK_SCOPE_TOO_LARGE,
            decide(sale.copy(exchangeable = false, notExchangeableReason = "ORDER_LOCK_SCOPE_TOO_LARGE")).blockedReason)
        assertEquals(TextosCambio.NOT_A_SALE,
            decide(sale.copy(exchangeable = false, notExchangeableReason = "future-code")).blockedReason)
    }

    @Test fun `entry asks for manager PIN only when override is available`() {
        assertTrue(decide(refund = false, overrideEnabled = true).requiresPin)
        assertFalse(decide(refund = false, overrideEnabled = false).visible)
        assertFalse(decide(refund = true, overrideEnabled = true).requiresPin)
    }

    @Test fun `detail links survive decoding and reopening with translated states`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for ((status, label) in mapOf("COMPLETED" to "Hecho", "PENDING_PAYMENT" to "Cobrando la diferencia",
            "NEEDS_REVIEW" to "Por revisar", "CANCELLED" to "Cancelado", "future" to "Por revisar")) {
            val detail = sale.copy(exchange = ExchangeLinks(1,
                listOf(ExchangeAsOriginal("e", "new", "ORD-2", status)),
                ExchangeAsNew("e0", "old", "ORD-0", status)))
            val reopened = json.decodeFromString(Transaction.serializer(), json.encodeToString(Transaction.serializer(), detail))
            assertEquals(listOf("Cambiada en ORD-2 · $label", "Cambio de ORD-0 · $label"),
                ExchangeEntry.links(reopened).map { it.label })
            assertEquals(listOf("new", "old"), ExchangeEntry.links(reopened).map { it.orderId })
            // La venta nueva de un cambio apartado no tiene pago que abrir: el vínculo lo sabe sin ir a la red (F4, 28-oct).
            assertEquals(listOf(status == "PENDING_PAYMENT", false), ExchangeEntry.links(reopened).map { it.esperaCobro })
        }
    }
}
