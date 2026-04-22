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

    val canReceiveStock: Boolean
        get() = status == "SENT" || status == "PARTIALLY_RECEIVED"

    val canBeCancelled: Boolean
        get() = status != "RECEIVED" && status != "CANCELLED"

    val statusDisplay: String
        get() = when (status) {
            "DRAFT" -> "Borrador"
            "SENT" -> "Enviado"
            "PARTIALLY_RECEIVED" -> "Parcialmente recibido"
            "RECEIVED" -> "Recibido"
            "CANCELLED" -> "Cancelado"
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
