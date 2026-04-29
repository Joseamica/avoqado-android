package com.avoqado.pos.reservations.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_reservation_action")
data class PendingReservationActionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val reservationId: String,
    val action: String,           // "CONFIRM" | "CHECK_IN" | "COMPLETE" | "NO_SHOW" | "CANCEL" | "RESCHEDULE"
    val payloadJson: String? = null, // for CANCEL reason or RESCHEDULE times
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
