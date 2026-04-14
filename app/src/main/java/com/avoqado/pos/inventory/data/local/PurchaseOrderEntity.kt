package com.avoqado.pos.inventory.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "purchase_orders")
data class PurchaseOrderEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val supplierName: String,
    val status: String, // DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED
    val notes: String? = null,
    val expectedDate: String? = null,
    val itemsJson: String? = null, // Serialized PurchaseOrderItem list
    val createdAt: String,
    val createdByName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
