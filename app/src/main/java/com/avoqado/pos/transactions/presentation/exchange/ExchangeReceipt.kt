package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.receiptlayout.ReceiptExchange
import com.avoqado.pos.printing.receiptlayout.ReceiptExchangeLine
import com.avoqado.pos.transactions.data.ExchangeResult
import com.avoqado.pos.transactions.data.model.StoreCreditAbono
import java.util.Date

/** Only the paper document: the payment's digital URL does not describe the exchange. */
fun exchangeReceipt(result: ExchangeResult, venueName: String, actorName: String, date: Date): ReceiptData {
    require(result.status == "COMPLETED")
    return ReceiptData(orderNumber = result.newOrderNumber, orderType = TextosCambio.CAMBIO,
        items = emptyList(), subtotal = 0, taxAmount = 0, total = 0, venueName = venueName,
        cashierName = actorName, date = date, kind = "EXCHANGE", exchange = ReceiptExchange(
            result.originalOrderNumber,
            result.lines.returned.map { ReceiptExchangeLine(it.productName, it.quantity, -it.valueCents) },
            result.lines.taken.map { ReceiptExchangeLine(it.productName, it.quantity, it.priceCents) },
            result.differenceCents, ExchangeSettlement.methodLabel(result.settlement.method), result.settlement.storeCredit))
}

/** «Saldo a favor para {nombre}: {monto} · saldo nuevo {saldo}» (spec §4); `null` si el servidor no mandó los tres datos. */
fun storeCreditLine(storeCredit: StoreCreditAbono, money: (Long) -> String): String? {
    val name = storeCredit.customerName ?: return null
    val amount = storeCredit.amountCents ?: return null
    val balance = storeCredit.newBalanceCents ?: return null
    return TextosCambio.TICKET_SALDO_PARA.replace("{nombre}", name).replace("{monto}", money(amount)).replace("{saldo}", money(balance))
}

/** Los últimos 4 del teléfono con que se gasta, sólo cuando NO es el de la ficha (spec §5.2). */
fun storeCreditMismatchLast4(storeCredit: StoreCreditAbono): String? =
    storeCredit.phoneLast4?.takeIf { storeCredit.phoneMismatch == true && it.isNotBlank() }

/** Lo que la cajera ve al terminar un Cambio a saldo a favor: a quién y cuánto, y el aviso del teléfono que no coincide. */
fun exchangeStoreCreditNotice(storeCredit: StoreCreditAbono): List<String> = listOfNotNull(
    storeCreditLine(storeCredit, ::exchangeMoney),
    storeCreditMismatchLast4(storeCredit)?.let { last4 ->
        TextosCambio.SALDO_TELEFONO_NO_COINCIDE.replace("{nombre}", storeCredit.customerName.orEmpty()).replace("{4}", last4)
    },
)
