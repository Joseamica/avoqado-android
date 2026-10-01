package com.avoqado.pos.areatickets.data

import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.ReceiptItem
import java.time.Instant
import java.util.Date
import java.util.Locale

/** El vale del servidor, listo para papel y PDF. Fuera de caja externa sale exactamente como antes. */
fun AreaTicket.toAreaTicketData(venueName: String?, isReprint: Boolean = false): AreaTicketData {
    val external = isExternalRoute
    return AreaTicketData(
        areaTicketCode = code,
        areaName = fulfillmentArea.name,
        items = lines.map { line ->
            ReceiptItem(
                name = if (external) line.externalName() else line.productNameSnapshot,
                quantity = line.quantity.toBigDecimalOrNull()?.toInt() ?: 1,
                unitPrice = line.unitPrice.moneyToCents(),
                totalPrice = line.total.moneyToCents(),
                note = line.notes,
                weightSummary = line.weightKg?.let {
                    "$it kg × $${String.format(Locale.US, "%.2f", line.unitPrice.toDoubleOrNull() ?: 0.0)}/kg"
                },
                modifiers = if (external) line.modifiersSnapshot.map { it.printLabel() }.takeIf { it.isNotEmpty() } else null,
                externalCodes = if (external) line.externalCodes() else emptyList(),
            )
        },
        totalCents = total.moneyToCents(),
        venueName = venueName,
        timestamp = runCatching { Date.from(Instant.parse(issuedAt)) }.getOrDefault(Date()),
        holdsProduct = fulfillmentArea.fulfillmentMode == "HOLD_UNTIL_PAID",
        externalRoute = external,
        isReprint = isReprint,
    )
}

/**
 * Caja externa: un renglón que la pistola no puede cobrar (por peso o con descuento, sólo en vales emitidos antes de la
 * guarda del servidor) sale sin códigos; el nombre dice qué hacer, como el extra viejo sin código de abajo.
 */
private fun AreaTicketLine.externalName(): String = when {
    weightKg != null -> "$productNameSnapshot (por peso: cóbralo a mano)"
    needsManualCapture -> "$productNameSnapshot (con descuento: cóbralo a mano)"
    else -> productNameSnapshot
}

/**
 * Un vale externo emitido antes de que el extra tuviera SKU no trae su código congelado. Si el
 * extra cobra algo, que se VEA en el papel en vez de faltar callado (Codex #4). Nunca se reconstruye
 * con el catálogo de hoy: el papel debe decir lo que se emitió.
 */
private fun AreaTicketModifierSnapshot.printLabel(): String {
    val cobra = (price.toBigDecimalOrNull()?.signum() ?: 0) > 0
    return if (cobra && sku.isNullOrBlank()) "$name (sin código: cóbralo a mano)" else name
}
