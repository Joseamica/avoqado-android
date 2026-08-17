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

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'FAILED' ORDER BY createdAt DESC")
    suspend fun getFailedPayments(): List<PendingPaymentEntity>

    /**
     * Los cobros encolados de UN local. Lo usa el cajón (`PendingCashSales`) para no
     * borrar de la pantalla una venta que sí está en el cajón pero que el server aún
     * no puede conocer.
     *
     * 🔴 Devuelve la fila ENTERA y no filtra por estado ni por método a propósito:
     * ésas son decisiones de DINERO y viven en `PendingCashSales`, en Kotlin, donde
     * los tests las fijan. Un filtro escondido en este `@Query` no lo ejercita ninguna
     * prueba de la suite unitaria — se podría aflojar y todo seguiría verde.
     */
    @Query("SELECT * FROM pending_payments WHERE venueId = :venueId")
    suspend fun forVenue(venueId: String): List<PendingPaymentEntity>

    @Query("UPDATE pending_payments SET syncStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE pending_payments SET syncStatus = :status, lastError = :error WHERE id = :id")
    suspend fun updateStatusWithError(id: String, status: String, error: String?)

    @Query("UPDATE pending_payments SET retryCount = retryCount + 1, lastRetryAt = :timestamp, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun incrementRetry(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pending_payments SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun resetSyncingToPending()

    @Query("UPDATE pending_payments SET syncStatus = 'PENDING', retryCount = 0, lastError = NULL, lastRetryAt = NULL WHERE id = :id AND syncStatus = 'FAILED'")
    suspend fun retryFailed(id: String)

    /** Estado actual de UN cobro. Lo usa el reintento manual para saber cómo le
     *  fue de verdad, en vez de cantar éxito a ciegas. */
    @Query("SELECT syncStatus FROM pending_payments WHERE id = :id")
    suspend fun syncStatusOf(id: String): String?

    @Query("SELECT lastError FROM pending_payments WHERE id = :id")
    suspend fun lastErrorOf(id: String): String?

    /**
     * "Marcar resuelta": el gerente decidió que este cobro ya no debe
     * reintentarse (típico: el server confirma "ya está pagada" — el dinero SÍ
     * entró y reintentar jamás va a prosperar). SOLO borra filas FAILED: una
     * PENDING/SYNCING sigue viva y borrarla perdería un cobro real.
     */
    @Query("DELETE FROM pending_payments WHERE id = :id AND syncStatus = 'FAILED'")
    suspend fun deleteFailed(id: String): Int

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
