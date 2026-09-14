package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.tpvsettings.data.ReceiptInfo

/**
 * `ReceiptData` → `ReceiptInput`. Espejo de avoqado-ios `ReceiptInputMapper.swift`.
 *
 * 🔴 Aquí NO hay aritmética de dinero: los centavos pasan tal cual. Y lo que la pantalla ya puso en
 * `ReceiptData` gana a lo del caché de settings (misma regla que tenía `ReceiptBranding.decorate`).
 */
object ReceiptInputMapper {

    fun map(receipt: ReceiptData, info: ReceiptInfo?, hasLogo: Boolean, timezone: String, appVersion: String?): ReceiptInput =
        ReceiptInput(sale = sale(receipt, timezone, appVersion), venue = venue(receipt, info, hasLogo))

    private fun sale(r: ReceiptData, timezone: String, appVersion: String?) = ReceiptSale(
        kind = "SALE",
        orderNumber = r.orderNumber,
        orderType = r.orderType,
        occurredAt = r.date.toInstant().toString(),
        timezone = timezone,
        items = r.items.map {
            ReceiptSaleItem(
                name = it.name,
                quantity = it.quantity,
                unitPriceCents = it.unitPrice.toLong(),
                totalPriceCents = it.totalPrice.toLong(),
                modifiers = it.modifiers,
                note = it.note,
                isCortesia = it.isCortesia,
                weightSummary = it.weightSummary,
                areaSourceLabel = it.areaSourceLabel,
                isComboHeader = it.isComboHeader,
                isComboComponent = it.isComboComponent,
            )
        },
        subtotalCents = r.subtotal.toLong(),
        taxCents = r.taxAmount.toLong(),
        discountCents = r.discountAmount?.toLong(),
        tipCents = r.tipAmount?.toLong(),
        totalCents = r.total.toLong(),
        tender = tender(r),
        staffName = r.cashierName,
        transactionId = r.transactionId,
        receiptUrl = r.receiptUrl,
        areaDeliveryCode = r.areaDeliveryCode,
        reprint = r.reprintedAt?.let { ReceiptReprint(it.toInstant().toString()) },
        appVersion = appVersion,
        autofacturaAvailable = r.autofacturaAvailable,
    )

    /**
     * Sin método = pre-cuenta (no hay pago). Autorización y referencia NO se mandan: el resultado del
     * cobro remoto no las trae (D6 del plan de la Fase 3).
     */
    private fun tender(r: ReceiptData): ReceiptTender? {
        val label = r.paymentMethod ?: return null
        return when {
            r.isCashPayment -> ReceiptTender(kind = "CASH", label = label, tenderedCents = r.cashTendered?.toLong(), changeCents = r.changeAmount?.toLong())
            r.cardLastFour != null || r.cardBrand != null || label == "Tarjeta" ->
                ReceiptTender(kind = "CARD", label = label, cardBrand = r.cardBrand, cardLastFour = r.cardLastFour)
            else -> ReceiptTender(kind = "OTHER", label = label)
        }
    }

    private fun venue(r: ReceiptData, info: ReceiptInfo?, hasLogo: Boolean): ReceiptVenueInfo {
        // 🔴 `callerEmisor`/`callerAddress` están MUERTAS en producción hoy (revisión de
        // conjunto, M10): cero pantallas escriben `venueLegalName`/`venueRfc`/`venueAddress`
        // en `ReceiptData` (verificado por grep). Sólo las ejercita `ReceiptInputMapperTest`.
        // Se conservan — no se retiran — porque quitarlas exige también tocar esa prueba, y
        // el camino real de hoy es `TpvSettingsRepository` → `ReceiptInfo.fiscalEmisors`.
        val callerEmisor = r.venueLegalName != null || r.venueRfc != null
        val emisors = when {
            callerEmisor -> listOf(ReceiptFiscalEmisor(id = "caller", legalName = r.venueLegalName, rfc = r.venueRfc, lugarExpedicion = r.venueLugarExpedicion))
            info?.fiscalEmisors != null -> info.fiscalEmisors
            info?.legalName != null || info?.rfc != null ->
                listOf(ReceiptFiscalEmisor(id = "principal", legalName = info?.legalName, rfc = info?.rfc, lugarExpedicion = info?.lugarExpedicion))
            else -> emptyList()
        }
        val callerAddress = r.venueAddress != null
        return ReceiptVenueInfo(
            name = r.venueName,
            address = if (callerAddress) r.venueAddress else info?.address,
            city = if (callerAddress) null else info?.city,
            state = if (callerAddress) null else info?.state,
            zipCode = if (callerAddress) null else info?.zipCode,
            phone = r.venuePhone ?: info?.phone,
            hasLogo = hasLogo,
            fiscalEmisors = emisors,
            principalEmisorId = when {
                callerEmisor -> "caller"
                info?.fiscalEmisors != null -> info.principalEmisorId
                else -> emisors.firstOrNull()?.id
            },
            legacy = info?.legacy ?: ReceiptLegacyFiscal(),
        )
    }
}
