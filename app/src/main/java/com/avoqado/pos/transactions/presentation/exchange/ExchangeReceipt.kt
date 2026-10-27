package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.receiptlayout.ReceiptExchange
import com.avoqado.pos.printing.receiptlayout.ReceiptExchangeLine
import com.avoqado.pos.transactions.data.ExchangeResult
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
            result.differenceCents, ExchangeSettlement.methodLabel(result.settlement.method)))
}
