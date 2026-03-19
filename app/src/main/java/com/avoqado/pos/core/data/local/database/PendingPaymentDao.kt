package com.avoqado.pos.core.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPaymentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: PendingPaymentEntity)

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'PENDING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingPayments(limit: Int = 10): List<PendingPaymentEntity>

    @Query("SELECT * FROM pending_payments WHERE syncStatus IN ('PENDING', 'SYNCING') ORDER BY createdAt ASC")
    suspend fun getUnsyncedPayments(): List<PendingPaymentEntity>

    @Query("UPDATE pending_payments SET syncStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE pending_payments SET syncStatus = :status, lastError = :error WHERE id = :id")
    suspend fun updateStatusWithError(id: String, status: String, error: String?)

    @Query("UPDATE pending_payments SET retryCount = retryCount + 1, lastRetryAt = :timestamp, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun incrementRetry(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pending_payments SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun resetSyncingToPending()

    @Query("DELETE FROM pending_payments WHERE syncStatus = 'SYNCED' AND createdAt < :olderThan")
    suspend fun deleteSynced(olderThan: Long)

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    @Query("DELETE FROM pending_payments")
    suspend fun deleteAll()

    @Query("DELETE FROM pending_payments WHERE venueId = :venueId")
    suspend fun deleteForVenue(venueId: String)
}
