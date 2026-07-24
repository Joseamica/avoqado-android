package com.avoqado.pos.core.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Offline-first Corte B — el OUTBOX de intents (patrón "Libreta" de la TPV
 * generalizado a mutaciones de orden). Toda acción de mesas hecha sin red se
 * escribe aquí ANTES de cualquier efecto y se reproduce al reconectar contra
 * POST /mobile/venues/:id/sync/intents (FIFO por dispositivo, viejo→nuevo).
 *
 * `id` = UUID generado en el dispositivo = idempotencyKey del server: un
 * reintento devuelve el MISMO ack sin re-aplicar (cero duplicados por
 * construcción). Los REJECTED se conservan visibles (cuarentena) — NUNCA se
 * borra un intent en silencio.
 */
@Entity(tableName = "pos_sync_intents")
data class SyncIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "venue_id")
    val venueId: String,
    @ColumnInfo(name = "seq")
    val seq: Long,
    @ColumnInfo(name = "type")
    val type: String, // OPEN_TABLE | ADD_ITEMS | PAY_CASH (espejo EXACTO del server)
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "message")
    val message: String? = null,
    @ColumnInfo(name = "result_json")
    val resultJson: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACKED = "ACKED"
        const val STATUS_REJECTED = "REJECTED"
    }
}

@Dao
interface SyncIntentDao {
    /** IGNORE: el mismo intent nunca se encola dos veces (dedup local). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SyncIntentEntity): Long

    @Query("SELECT * FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'PENDING' ORDER BY seq ASC LIMIT :limit")
    suspend fun pendingFifo(venueId: String, limit: Int = 50): List<SyncIntentEntity>

    @Query("SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'PENDING'")
    suspend fun pendingCount(venueId: String): Int

    @Query("SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'REJECTED'")
    suspend fun rejectedCount(venueId: String): Int

    @Query("SELECT COALESCE(MAX(seq), 0) FROM pos_sync_intents")
    suspend fun maxSeq(): Long

    @Query("UPDATE pos_sync_intents SET status = :status, error_code = :errorCode, message = :message, result_json = :resultJson WHERE id = :id")
    suspend fun resolve(id: String, status: String, errorCode: String?, message: String?, resultJson: String?)

    /** ACKED viejos se limpian; PENDING/REJECTED jamás se borran automáticos. */
    @Query("DELETE FROM pos_sync_intents WHERE status = 'ACKED' AND created_at < :olderThan")
    suspend fun deleteOldAcked(olderThan: Long)
}
