package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.transactions.data.model.PayoutOption

data class ExchangeSettlementOption(val method: String, val label: String, val requiresPin: Boolean = false,
    val cashFromNonCash: Boolean? = null, val disabledReason: String? = null)

/**
 * A quién se le abona el saldo a favor. El de la venta llega sin teléfono ([phoneChecked] falso: lo revisa el servidor); el que
 * la cajera escoge con «Cambiar» trae el de su ficha, y sin teléfono no se le puede dar (spec §4).
 */
data class ExchangeStoreCreditCustomer(val id: String, val name: String, val phone: String? = null, val phoneChecked: Boolean = false)

object ExchangeSettlement {
    fun collectOptions() = listOf(ExchangeSettlementOption("CASH", TextosCambio.EFECTIVO),
        ExchangeSettlementOption("CARD_TERMINAL", TextosCambio.TARJETA))

    fun payoutOptions(options: List<PayoutOption>?, amountCents: Long, canRefundToCash: Boolean,
        managerPinOverrideEnabled: Boolean, storeCreditOnly: Boolean = false, hasDefect: Boolean = false,
        storeCreditAvailable: Boolean = true, online: Boolean = true,
        customer: ExchangeStoreCreditCustomer? = null): List<ExchangeSettlementOption> {
        val offered = options.orEmpty().mapNotNull { option ->
            val label = methodLabel(option.method) ?: return@mapNotNull null
            if (option.method == "CARD_TERMINAL" || option.maxCents == null || option.maxSameCents == null ||
                amountCents <= 0 || amountCents > option.maxCents) return@mapNotNull null
            val otherMoney = option.method == "CASH" && amountCents > option.maxSameCents
            val needsPermission = otherMoney && !canRefundToCash
            ExchangeSettlementOption(option.method!!, label, needsPermission && managerPinOverrideEnabled, true.takeIf { otherMoney },
                if (option.method == "STORE_CREDIT") storeCreditBlock(online, customer)
                else TextosCambio.EFECTIVO_SIN_PERMISO.takeIf { needsPermission && !managerPinOverrideEnabled })
        }
        // «Sólo saldo a favor» sin pieza con Defecto: el saldo a favor y regresar al mismo sobre de regalo (spec §4, §5.4).
        val allowed = if (storeCreditOnly && !hasDefect) offered.filter { it.method == "STORE_CREDIT" || it.method == "GIFT_CARD_BALANCE" }
            else offered
        // Sin las llaves el servidor no lo ofrece: apagado se ve y se explica (spec §5.1).
        val shown = if (!storeCreditAvailable && allowed.none { it.method == "STORE_CREDIT" })
            listOf(ExchangeSettlementOption("STORE_CREDIT", TextosCambio.SALDO_A_FAVOR,
                disabledReason = TextosCambio.SALDO_PIDE_A_AVOQADO)) + allowed else allowed
        return shown.sortedBy { it.method != "STORE_CREDIT" }
    }

    private fun storeCreditBlock(online: Boolean, customer: ExchangeStoreCreditCustomer?): String? = when {
        !online -> TextosCambio.SIN_CONEXION
        customer == null -> TextosCambio.SALDO_ELIGE_CLIENTE
        customer.phoneChecked && customer.phone.isNullOrBlank() -> TextosCambio.SALDO_SIN_TELEFONO.replace("{nombre}", customer.name)
        else -> null
    }

    fun methodLabel(method: String?): String? = when (method) {
        "CASH" -> TextosCambio.EFECTIVO
        "CARD_TERMINAL" -> TextosCambio.TARJETA
        "BANK_TRANSFER" -> TextosCambio.TRANSFERENCIA
        "GIFT_CARD_BALANCE" -> TextosCambio.SALDO_TARJETA_REGALO
        "STORE_CREDIT" -> TextosCambio.SALDO_A_FAVOR
        else -> null
    }
}
