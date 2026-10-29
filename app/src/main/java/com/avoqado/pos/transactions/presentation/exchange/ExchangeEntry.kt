package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.transactions.data.model.Transaction

data class EntryDecision(val visible: Boolean, val blockedReason: String? = null, val requiresPin: Boolean = false)
/** [esperaCobro]: la venta nueva de un cambio apartado todavía no tiene pago que abrir (F4, 28-oct). */
data class ExchangeDetailLink(val orderId: String, val label: String, val esperaCobro: Boolean = false)

object ExchangeEntry {
    fun decide(transaction: Transaction, exchangesEnabled: Boolean, online: Boolean,
        canRefund: Boolean, overrideEnabled: Boolean): EntryDecision {
        if (!exchangesEnabled || transaction.status == "REFUNDED" || transaction.amount < 0 ||
            transaction.exchangeable == null || transaction.exchange == null || transaction.payoutOptions == null ||
            (!canRefund && !overrideEnabled)) return EntryDecision(false)
        val reason = when {
            !online -> TextosCambio.SIN_CONEXION
            transaction.exchangeable == false -> reason(transaction.notExchangeableReason)
            else -> null
        }
        return EntryDecision(true, reason, !canRefund)
    }

    fun reason(code: String?): String = when (code) {
        "NOT_A_SALE" -> TextosCambio.NOT_A_SALE
        "ORDER_LOCK_SCOPE_TOO_LARGE" -> TextosCambio.ORDER_LOCK_SCOPE_TOO_LARGE
        "ALREADY_RETURNED" -> TextosCambio.ALREADY_RETURNED
        "PROMOTION_PARTIAL" -> TextosCambio.PROMOTION_PARTIAL
        "NOT_MERCHANDISE" -> TextosCambio.NOT_MERCHANDISE
        "GIFT_CARD_SALE" -> TextosCambio.GIFT_CARD_SALE
        "DELIVERY_PLATFORM" -> TextosCambio.DELIVERY_PLATFORM
        "NOT_ATTRIBUTABLE" -> TextosCambio.NOT_ATTRIBUTABLE
        "SOLD_BY_WEIGHT" -> TextosCambio.SOLD_BY_WEIGHT
        "NOT_PAID" -> TextosCambio.NOT_PAID
        "NOTHING_LEFT_TO_REFUND" -> TextosCambio.NOTHING_LEFT_TO_REFUND
        "SERIALIZED_ITEM" -> TextosCambio.SERIALIZED_ITEM
        else -> TextosCambio.NOT_A_SALE
    }

    private fun status(value: String?): String = when (value) {
        "COMPLETED" -> TextosCambio.ESTADO_HECHO
        "PENDING_PAYMENT" -> TextosCambio.COBRANDO_DIFERENCIA
        "CANCELLED" -> TextosCambio.ESTADO_CANCELADO
        else -> TextosCambio.ESTADO_POR_REVISAR
    }

    fun links(transaction: Transaction): List<ExchangeDetailLink> = buildList {
        transaction.exchange?.asOriginal.orEmpty().forEach { link ->
            link.newOrderId?.let { id -> add(ExchangeDetailLink(id,
                TextosCambio.CAMBIADA_EN.replace("{folio}", link.newOrderNumber.orEmpty()) + " · " + status(link.status),
                esperaCobro = link.status == "PENDING_PAYMENT")) }
        }
        transaction.exchange?.asNew?.let { link ->
            link.originalOrderId?.let { id -> add(ExchangeDetailLink(id,
                TextosCambio.CAMBIO_DE.replace("{folio}", link.originalOrderNumber.orEmpty()) + " · " + status(link.status))) }
        }
    }
}
