package com.avoqado.pos.scale

import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.ScaleProfile

/**
 * El proceso que consume la lectura. La báscula sólo aporta peso: nunca decide producto,
 * presentación, precio ni movimiento de inventario.
 */
enum class ScaleUsageContext(
    val wireValue: String,
    val operatorLabel: String,
) {
    AREA_TICKET_LINE("AREA_TICKET_LINE", "productos del vale"),
    INVENTORY_RECEIPT("INVENTORY_RECEIPT", "recepción de inventario"),
    INVENTORY_TRANSFER_DISPATCH("INVENTORY_TRANSFER_DISPATCH", "despacho de inventario"),
    STOCK_COUNT("STOCK_COUNT", "conteo de inventario"),
    STOCK_ADJUSTMENT("STOCK_ADJUSTMENT", "ajuste de inventario"),
}

fun ScaleIntegrationSettings.configuredProfileFor(
    context: ScaleUsageContext,
): ScaleProfile? = profile?.takeIf {
    entitled &&
        enabled &&
        it.active &&
        it.transport == "ANDROID_USB_SERIAL" &&
        context.wireValue in it.allowedContexts
}
