package com.avoqado.pos.inventory.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_transfers")
data class InventoryTransferEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val fromLocationName: String,
    val toLocationName: String,
    val status: String, // DRAFT, IN_TRANSIT, COMPLETED, CANCELLED
    val notes: String? = null,
    val itemsJson: String? = null, // Serialized TransferItem list
    val createdAt: String,
    val createdByName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
