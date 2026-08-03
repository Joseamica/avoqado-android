package com.avoqado.pos.reservations.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReservationActionDao {
    @Insert
    suspend fun insert(action: PendingReservationActionEntity): Long

    @Query(
        """
        DELETE FROM pending_reservation_action
        WHERE reservationId = :reservationId AND action = :action
          AND ((payloadJson IS NULL AND :payloadJson IS NULL) OR payloadJson = :payloadJson)
        """,
    )
    suspend fun deleteEquivalent(reservationId: String, action: String, payloadJson: String?)

    /**
     * Encola descartando una idéntica anterior — misma reserva, misma acción y
     * mismo payload.
     *
     * Sin esto cada toque insertaba otra fila. Y se tocaba varias veces: la
     * pantalla mostraba la acción encolada como un error en inglés y la reserva
     * sin cambiar, así que el mesero volvía a intentar. Al reconectar el
     * reintentador reproducía TODAS: N reservas duplicadas con CREATE, y con
     * NO_SHOW un cargo por no presentarse aplicado más de una vez.
     *
     * El payload entra en la comparación a propósito: dos CREATE distintos son
     * dos reservas legítimas, dos CREATE idénticos son un doble toque.
     */
    @Transaction
    suspend fun enqueue(action: PendingReservationActionEntity): Long {
        deleteEquivalent(action.reservationId, action.action, action.payloadJson)
        return insert(action)
    }

    @Query("SELECT * FROM pending_reservation_action ORDER BY createdAt ASC")
    suspend fun all(): List<PendingReservationActionEntity>

    @Query("SELECT COUNT(*) FROM pending_reservation_action")
    fun pendingCount(): Flow<Int>

    /** Las que agotaron los reintentos y esperan a que alguien las resuelva. */
    @Query("SELECT * FROM pending_reservation_action WHERE attemptCount >= :maxAttempts ORDER BY createdAt ASC")
    suspend fun exhausted(maxAttempts: Int): List<PendingReservationActionEntity>

    @Query("SELECT COUNT(*) FROM pending_reservation_action WHERE attemptCount >= :maxAttempts")
    fun exhaustedCount(maxAttempts: Int): Flow<Int>

    @Query("DELETE FROM pending_reservation_action WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE pending_reservation_action SET attemptCount = attemptCount + 1, lastError = :error WHERE rowId = :rowId")
    suspend fun incrementAttempt(rowId: Long, error: String? = null)
}
