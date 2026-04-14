package com.avoqado.pos.inventory.data.model

import kotlinx.serialization.Serializable

@Serializable
data class InventoryTransfer(
    val id: String,
    val venueId: String,
    val fromLocationName: String,
    val toLocationName: String,
    val status: String, // DRAFT, IN_TRANSIT, COMPLETED, CANCELLED
    val notes: String? = null,
    val items: List<TransferItem> = emptyList(),
    val createdAt: String,
    val createdByName: String,
) {
    val statusDisplay: String
        get() = when (status) {
            "DRAFT" -> "Borrador"
            "IN_TRANSIT" -> "En tránsito"
            "COMPLETED" -> "Completado"
            "CANCELLED" -> "Cancelado"
            else -> status
        }

    val totalItems: Int
        get() = items.sumOf { it.quantity }
}

@Serializable
data class TransferItem(
    val id: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unit: String? = null,
)

@Serializable
data class TransfersResponse(
    val success: Boolean = true,
    val transfers: List<InventoryTransfer> = emptyList(),
    val data: List<InventoryTransfer> = emptyList(),
) {
    /** Resolve whichever field the API actually populates. */
    val resolved: List<InventoryTransfer>
        get() = transfers.ifEmpty { data }
}
