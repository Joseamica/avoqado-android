package com.avoqado.pos.inventory.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PurchaseOrder(
    val id: String,
    val venueId: String,
    val supplierName: String,
    val status: String, // DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED
    val notes: String? = null,
    val expectedDate: String? = null,
    val items: List<PurchaseOrderItem> = emptyList(),
    val createdAt: String,
    val createdByName: String,
) {
    val canBeSent: Boolean
        get() = status == "DRAFT"

    /**
     * 🔴 Los estados se espejan por nombre EXACTO con `PurchaseOrderStatus` del
     * server (`prisma/schema.prisma`). Antes había DOS que no existen —
     * "PARTIALLY_RECEIVED" y "COMPLETED"— y faltaban seis que sí. Un nombre que
     * no coincide no falla: se cae al `else` y sigue de largo en silencio.
     *
     * Eso rompía la recepción de mercancía: el server marca las órdenes a medio
     * recibir como `PARTIAL`, nunca como "PARTIALLY_RECEIVED", así que
     * `canReceiveStock` daba false y no había forma de recibir lo que faltaba.
     */
    val canReceiveStock: Boolean
        get() = status in setOf("SENT", "CONFIRMED", "SHIPPED", "PARTIAL")

    val canBeCancelled: Boolean
        get() = status !in setOf("RECEIVED", "CANCELLED", "REJECTED")

    val statusDisplay: String
        get() = when (status) {
            "DRAFT" -> "Borrador"
            "PENDING_APPROVAL" -> "Por aprobar"
            "REJECTED" -> "Rechazada"
            "APPROVED" -> "Aprobada"
            "SENT" -> "Enviada al proveedor"
            "CONFIRMED" -> "Confirmada"
            "SHIPPED" -> "En camino"
            "PARTIAL" -> "Recibida en parte"
            "RECEIVED" -> "Recibida"
            "CANCELLED" -> "Cancelada"
            else -> status
        }

    val totalItems: Int
        get() = items.sumOf { it.orderedQuantity }

    val totalReceived: Int
        get() = items.sumOf { it.receivedQuantity }

    val isFullyReceived: Boolean
        get() = items.isNotEmpty() && items.all { it.receivedQuantity >= it.orderedQuantity }
}

@Serializable
data class PurchaseOrderItem(
    val id: String,
    @SerialName("rawMaterialId") val productId: String? = null,
    @SerialName("rawMaterialName") val productName: String? = null,
    @SerialName("quantityOrdered") val orderedQuantity: Int = 0,
    @SerialName("quantityReceived") val receivedQuantity: Int = 0,
    val unitCost: Double? = null,
) {
    val isComplete: Boolean
        get() = receivedQuantity >= orderedQuantity

    val displayCost: String
        get() = unitCost?.let { "$${String.format("%.2f", it)}" } ?: "—"
}

@Serializable
data class PurchaseOrdersResponse(
    val success: Boolean = true,
    val orders: List<PurchaseOrder> = emptyList(),
    val purchaseOrders: List<PurchaseOrder> = emptyList(),
    val data: List<PurchaseOrder> = emptyList(),
) {
    /** Resolve whichever field the API actually populates. */
    val resolved: List<PurchaseOrder>
        get() = orders.ifEmpty { purchaseOrders.ifEmpty { data } }
}
