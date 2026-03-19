package com.avoqado.pos.core.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PaymentSyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,
}

@Entity(tableName = "pending_payments")
data class PendingPaymentEntity(
    @PrimaryKey val id: String,           // UUID (idempotency key)
    val venueId: String,
    val staffId: String,
    val amountCents: Int,
    val tipCents: Int,
    val method: String,                    // "CASH"
    val paymentType: String,               // "FAST" or "ORDER"
    val orderId: String? = null,
    val orderNumber: String? = null,
    val cashTenderedCents: Int? = null,
    val changeCents: Int? = null,
    val rating: Int? = null,
    val itemsJson: String? = null,         // Serialized items
    val orderRequestJson: String? = null,  // Full CreateOrderRequest JSON for retry
    val syncStatus: String = PaymentSyncStatus.PENDING.name,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRetryAt: Long? = null,
)
