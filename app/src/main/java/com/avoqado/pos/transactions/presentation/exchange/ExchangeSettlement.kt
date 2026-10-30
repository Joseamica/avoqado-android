package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.transactions.data.model.PayoutOption

data class ExchangeSettlementOption(val method: String, val label: String, val requiresPin: Boolean = false,
    val cashFromNonCash: Boolean? = null, val disabledReason: String? = null)

object ExchangeSettlement {
    fun collectOptions() = listOf(ExchangeSettlementOption("CASH", TextosCambio.EFECTIVO),
        ExchangeSettlementOption("CARD_TERMINAL", TextosCambio.TARJETA))

    fun payoutOptions(options: List<PayoutOption>?, amountCents: Long, canRefundToCash: Boolean,
        managerPinOverrideEnabled: Boolean): List<ExchangeSettlementOption> =
        options.orEmpty().mapNotNull { option ->
            val label = methodLabel(option.method) ?: return@mapNotNull null
            if (option.method == "CARD_TERMINAL" || option.maxCents == null || option.maxSameCents == null ||
                amountCents <= 0 || amountCents > option.maxCents) return@mapNotNull null
            val otherMoney = option.method == "CASH" && amountCents > option.maxSameCents
            val needsPermission = otherMoney && !canRefundToCash
            ExchangeSettlementOption(option.method!!, label, needsPermission && managerPinOverrideEnabled, true.takeIf { otherMoney },
                TextosCambio.EFECTIVO_SIN_PERMISO.takeIf { needsPermission && !managerPinOverrideEnabled })
        }

    fun methodLabel(method: String?): String? = when (method) {
        "CASH" -> TextosCambio.EFECTIVO
        "CARD_TERMINAL" -> TextosCambio.TARJETA
        "BANK_TRANSFER" -> TextosCambio.TRANSFERENCIA
        "GIFT_CARD_BALANCE" -> TextosCambio.SALDO_TARJETA_REGALO
        else -> null
    }
}
