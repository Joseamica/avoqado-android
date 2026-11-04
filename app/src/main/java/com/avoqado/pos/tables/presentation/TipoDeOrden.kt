package com.avoqado.pos.tables.presentation

// Vive fuera de TableOrderScreen.kt a propósito (4-nov-2026): los recibos la usan, y cargar la clase
// de la pantalla de mesa sólo para leer esta etiqueta tumbó la 2.25.30 cuando R8 dejó esa clase rota.

internal val FULFILLMENT_OPTIONS = listOf(
    "DINE_IN" to "En tienda",
    "TAKEOUT" to "Para llevar",
    "DELIVERY" to "Entrega",
    "PICKUP" to "Pickup",
)

/** El «Tipo» del ticket (D4, mesas40): «Mesa N» si la venta es de una mesa; si no, la etiqueta del cumplimiento; si no, «En tienda». */
internal fun etiquetaDelTipoDeOrden(mesa: String?, orderType: String?): String =
    mesa?.takeIf { it.isNotBlank() }?.let { "Mesa $it" }
        ?: FULFILLMENT_OPTIONS.firstOrNull { it.first == orderType }?.second
        ?: "En tienda"
