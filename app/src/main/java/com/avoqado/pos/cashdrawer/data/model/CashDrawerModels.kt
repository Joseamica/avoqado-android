package com.avoqado.pos.cashdrawer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// MARK: - Enums

enum class CashDrawerStatus {
    OPEN,
    CLOSED,
}

enum class CashDrawerEventType {
    OPEN,
    PAY_IN,
    PAY_OUT,
    CASH_SALE,
    CLOSE,
}

// MARK: - Room Entities

@Entity(tableName = "cash_drawer_sessions")
data class CashDrawerSessionEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val deviceName: String?,
    val openedByStaffId: String,
    val openedByName: String,
    val openedAt: Long,
    val startingAmountCents: Int,
    val closedByStaffId: String? = null,
    val closedByName: String? = null,
    val closedAt: Long? = null,
    val actualAmountCents: Int? = null,
    val overShortCents: Int? = null,
    val closingNote: String? = null,
    val status: String = CashDrawerStatus.OPEN.name,
)

@Entity(tableName = "cash_drawer_events")
data class CashDrawerEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val venueId: String,
    val type: String,
    val amountCents: Int,
    val note: String?,
    val staffId: String,
    val staffName: String,
    val orderId: String? = null,
    val createdAt: Long,
)
