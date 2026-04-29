package com.avoqado.pos.reservations.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReservationActionDao {
    @Insert
    suspend fun enqueue(action: PendingReservationActionEntity): Long

    @Query("SELECT * FROM pending_reservation_action ORDER BY createdAt ASC")
    suspend fun all(): List<PendingReservationActionEntity>

    @Query("SELECT COUNT(*) FROM pending_reservation_action")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM pending_reservation_action WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE pending_reservation_action SET attemptCount = attemptCount + 1 WHERE rowId = :rowId")
    suspend fun incrementAttempt(rowId: Long)
}
