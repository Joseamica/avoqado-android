package com.avoqado.pos.core.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

internal const val INTENTS_SIN_RESOLVER_SQL = "SELECT type, status, payload_json FROM pos_sync_intents WHERE venue_id = :venueId AND status IN ('PENDING','HELD','REJECTED')"

data class IntentSinResolver(
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)

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
@Entity(tableName = "pos_sync_intents", indices = [Index(value = ["venue_id", "q15Reason", "q15ReviewedAt", "q15DetectedAt", "id"]), Index(value = ["venue_id", "capturedOrderRef"])])
data class SyncIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "venue_id")
    val venueId: String,
    /** Empleado que originó la mutación, no quien casualmente la reproduce. */
    @ColumnInfo(name = "staff_id")
    val staffId: String? = null,
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
    // Q15: NULL on legacy rows; never infer original financial evidence during migration.
    val q15CaptureJson: String? = null,
    val q15InitialResultJson: String? = null,
    val q15ResultJson: String? = null,
    val q15Reason: String? = null,
    val q15DetectedAt: Long? = null,
    val q15ReviewedAt: Long? = null,
    val q15ReviewedBy: String? = null,
    val q15ReviewRole: String? = null,
    val capturedOrderRef: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACKED = "ACKED"
        const val STATUS_REJECTED = "REJECTED"

        /**
         * Etapa 3 del KDS (spec 2026-09-27 §5): la red de seguridad de una ronda que va EN LÍNEA. Está escrita pero el
         * replay no la manda —y nada detrás de ella sale— hasta que alguien la suelte (falla de red) o la descarte
         * (éxito o rechazo). Si el proceso muere con ella retenida, se suelta en el primer arranque. Estado LOCAL: el
         * servidor nunca lo ve, y no cambia el esquema (`status` es texto).
         */
        const val STATUS_HELD = "HELD"
    }
}

/**
 * Un intent pendiente reducido a lo que el cajón necesita: el payload y CUÁNDO se
 * encoló. Proyección de Room — no una entidad.
 */
data class SyncIntentPayload(
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)


// Estado global de instalación; no se borra con operaciones ni caché de un venue.
internal const val MAX_SYNC_SEQUENCE = 2_147_483_647L
internal const val CREATE_SYNC_SEQUENCE_SQL = "CREATE TABLE IF NOT EXISTS pos_sync_sequence_state (id INTEGER NOT NULL, last_seq INTEGER NOT NULL, PRIMARY KEY(id))"
internal const val SEED_SYNC_SEQUENCE_SQL = "INSERT OR IGNORE INTO pos_sync_sequence_state (id, last_seq) SELECT 1, COALESCE(MAX(seq), 0) FROM pos_sync_intents"
internal const val READ_SYNC_SEQUENCE_SQL = "SELECT last_seq FROM pos_sync_sequence_state WHERE id = 1"
internal const val ADVANCE_SYNC_SEQUENCE_SQL = "UPDATE pos_sync_sequence_state SET last_seq = MAX(last_seq, :floor) WHERE id = 1"
internal const val FIND_SYNC_INTENT_SQL = "SELECT * FROM pos_sync_intents WHERE id = :id"
internal const val MAX_SYNC_INTENT_SQL = "SELECT COALESCE(MAX(seq), 0) FROM pos_sync_intents"
internal const val RESOLVE_SYNC_INTENT_SQL = "UPDATE pos_sync_intents SET status = :status, error_code = :errorCode, message = :message, result_json = :resultJson WHERE id = :id"

@Entity(tableName = "pos_sync_sequence_state")
data class SyncSequenceStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
)

/**
 * 19-oct (mesa sin red retenida): un PAY_CASH de ESTA cola, en espera normal (PENDING) y con su captura, ya no retiene a su
 * OPEN/ADD: va detrás por FIFO y la mesa se esperaba a sí misma para siempre. Siguen reteniendo el rechazado, el que está en
 * vuelo (HELD) y el historial sin captura (dudoso). La diferencia de precio se ve al confirmar el cobro (`ConciliacionDelCobro`).
 */
internal const val Q16_COBRO_QUE_RETIENE = "status!='ACKED' AND NOT (status='PENDING' AND q15CaptureJson IS NOT NULL)"

internal const val Q16_CAPTURED_UNRESOLVED_REFS_SQL = "SELECT capturedOrderRef FROM pending_payments WHERE venueId=:venueId AND capturedOrderRef IN (:refs) AND syncStatus!='SYNCED' UNION SELECT capturedOrderRef FROM pos_sync_intents WHERE venue_id=:venueId AND capturedOrderRef IN (:refs) AND type='PAY_CASH' AND $Q16_COBRO_QUE_RETIENE LIMIT 100"

@Dao
interface SyncIntentDao {
    @Query(Q16_VENUE_CAPTURE_UNRESOLVED_SQL)
    suspend fun hayCapturaSinResolverQ16(venueId: String): Boolean

    @Query(Q16_CAPTURED_UNRESOLVED_REFS_SQL)
    suspend fun referenciasSinResolverQ16(venueId: String, refs: List<String>): List<String>

    @Query(SEED_SYNC_SEQUENCE_SQL)
    suspend fun seedSequenceState()

    @Query(READ_SYNC_SEQUENCE_SQL)
    suspend fun sequenceState(): Long?

    @Query(ADVANCE_SYNC_SEQUENCE_SQL)
    suspend fun advanceSequenceState(floor: Long): Int

    @Query(FIND_SYNC_INTENT_SQL)
    suspend fun findById(id: String): SyncIntentEntity?

    /** Debe resolver y conservar el piso en el mismo commit; el replay notifica después. */
    @androidx.room.Transaction
    suspend fun resolveStaleSequence(
        id: String, venueId: String, sentSeq: Long, latestSeq: Long?,
        errorCode: String?, message: String?, resultJson: String?,
    ) {
        require(errorCode == "STALE_DEVICE_SEQUENCE")
        val row = requireNotNull(findById(id)) { "No se encontró el intent enviado" }
        require(row.venueId == venueId && row.seq == sentSeq && row.status == SyncIntentEntity.STATUS_PENDING) {
            "El rechazo no corresponde al intent pendiente enviado"
        }
        if (latestSeq != null) {
            require(latestSeq in 0L..MAX_SYNC_SEQUENCE && latestSeq >= sentSeq)
            seedSequenceState()
            check(advanceSequenceState(latestSeq) == 1) { "No se pudo guardar el piso de secuencia" }
        }
        resolve(id, SyncIntentEntity.STATUS_REJECTED, errorCode, message, resultJson)
        if(row.type == "PAY_CASH") {
            check(registrarResultadoQ15(venueId,id,com.avoqado.pos.payment.data.ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,null,System.currentTimeMillis())==1)
        }
    }

    @androidx.room.Transaction
    suspend fun resolverCobroQ15(id: String, venueId: String, sentSeq: Long, status: String,
        errorCode: String?, message: String?, resultJson: String?, motivo: String?, economicJson: String?, ahora: Long) {
        require(status == SyncIntentEntity.STATUS_ACKED || status == SyncIntentEntity.STATUS_REJECTED)
        val row = requireNotNull(findById(id)) { "No se encontró el cobro original" }
        require(row.venueId == venueId && row.seq == sentSeq && row.type == "PAY_CASH") { "El resultado no corresponde al cobro original" }
        if (row.status == SyncIntentEntity.STATUS_ACKED) return
        require(row.status == SyncIntentEntity.STATUS_PENDING || row.status == SyncIntentEntity.STATUS_HELD || row.status == SyncIntentEntity.STATUS_REJECTED)
        resolve(id,status,errorCode,message,resultJson)
        check(registrarResultadoQ15(venueId,id,motivo,economicJson,ahora) == 1) { "No se pudo conservar el resultado del cobro" }
    }

    @Query(Q15_INTENT_RESULT_SQL)
    suspend fun registrarResultadoQ15(venueId: String, id: String, motivo: String?, resultadoJson: String?, ahora: Long): Int
    @Query(Q15_INTENT_HOLD_SQL)
    suspend fun retenerIntentQ15(venueId: String, id: String, motivo: String, ahora: Long): Int

    @Query(Q16_INTENT_RELEASE_SQL)
    suspend fun soltarRetenidoSqlQ16(venueId: String, id: String): Int
    /** Bajo el mismo candado que las capturas (como `soltarVentaSinRedQ16` de mostrador). */
    suspend fun soltarRetenidoQ16(venueId: String, id: String): Int =
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(venueId) { soltarRetenidoSqlQ16(venueId, id) }

    @Query(INTENTS_SIN_RESOLVER_SQL)
    suspend fun sinResolver(venueId: String): List<IntentSinResolver>

    @Query("SELECT * FROM cached_payloads WHERE venue_id = :venueId AND cache_key IN (:keys) LIMIT 100")
    suspend fun preparationBefore(venueId: String, keys: List<String>): List<CachedPayloadEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreparationSnapshots(snapshots: List<CachedPayloadEntity>)

    /** Intent and its optimistic progress survive together, including a process killed after the commit. */
    @androidx.room.Transaction
    suspend fun insertPreparationWithNextSeq(entity: SyncIntentEntity, snapshots: List<CachedPayloadEntity>): Long {
        require(entity.type == "KDS_ITEM_PROGRESS" && entity.status == SyncIntentEntity.STATUS_PENDING)
        com.avoqado.pos.kds.domain.validatePreparationSnapshots(entity.id, entity.venueId, entity.staffId, snapshots)
        snapshots.singleOrNull { it.cacheKey == "preparation:${entity.venueId}:delivery:${entity.id}" }?.let { record ->
            val job = com.avoqado.pos.kds.domain.PreparationPeerProtocol.json.decodeFromString<com.avoqado.pos.kds.domain.PreparationDeliveryJob>(record.json)
            val keys = job.commands.flatMap { it.items }.map { "preparation:${entity.venueId}:${it.stableKey}" }
            if (keys.isNotEmpty()) com.avoqado.pos.kds.domain.validatePreparationPredecessors(job, snapshots, preparationBefore(entity.venueId, keys))
        }
        seedSequenceState()
        val saved = requireNotNull(sequenceState())
        val retained = maxSeq()
        check(saved in 0L..MAX_SYNC_SEQUENCE && retained in 0L..MAX_SYNC_SEQUENCE) { "Se corrompió la secuencia del dispositivo" }
        val floor = maxOf(saved, retained)
        check(floor in 0L until MAX_SYNC_SEQUENCE) { "Se agotó o corrompió la secuencia del dispositivo" }
        val seq = floor + 1
        check(insert(entity.copy(seq = seq)) != -1L) { "Esta acción ya fue guardada" }
        savePreparationSnapshots(snapshots)
        check(advanceSequenceState(seq) == 1) { "No se pudo guardar la secuencia del dispositivo" }
        return seq
    }

    @Query("SELECT * FROM pos_sync_intents WHERE venue_id = :venueId AND id IN (:ids)")
    suspend fun preparationStatuses(venueId: String, ids: List<String>): List<SyncIntentEntity>
    /** IGNORE: el mismo intent nunca se encola dos veces (dedup local). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSqlQ16(entity: SyncIntentEntity): Long
    suspend fun insert(entity: SyncIntentEntity): Long = if (entity.type == "PAY_CASH")
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(entity.venueId) { insertSqlQ16(entity) }
        else insertSqlQ16(entity)

    /**
     * Asigna seq desde el estado durable e inserta en UNA transacción, para
     * que dos enqueues casi simultáneos no lean el mismo max y colisionen el
     * seq (lo que desordenaría el replay: un ADD_ITEMS podría ordenarse antes
     * de su OPEN_TABLE). @Transaction serializa el par. Devuelve el seq usado.
     */
    suspend fun insertWithNextSeq(entity: SyncIntentEntity): Long = if (entity.type == "PAY_CASH")
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(entity.venueId) { insertWithNextSeqTransactionQ16(entity) }
        else insertWithNextSeqTransactionQ16(entity)
    @androidx.room.Transaction
    suspend fun insertWithNextSeqTransactionQ16(entity: SyncIntentEntity): Long {
        // El mismo UUID conserva todos sus datos y su secuencia.
        findById(entity.id)?.let { return it.seq }
        seedSequenceState()
        val saved = requireNotNull(sequenceState())
        val retained = maxSeq()
        check(saved in 0L..MAX_SYNC_SEQUENCE && retained in 0L..MAX_SYNC_SEQUENCE) { "Se corrompió la secuencia del dispositivo" }
        val floor = maxOf(saved, retained)
        check(floor in 0L until MAX_SYNC_SEQUENCE) { "Se agotó o corrompió la secuencia del dispositivo" }
        val seq = floor + 1
        check(insert(entity.copy(seq = seq)) != -1L) { "No se pudo insertar el intent" }
        check(advanceSequenceState(seq) == 1) { "No se pudo guardar la secuencia del dispositivo" }
        return seq
    }

    /** Lo que falta mandar, CON las rondas retenidas: el replay corta antes de la primera (barrera FIFO). */
    @Query("SELECT * FROM pos_sync_intents WHERE venue_id = :venueId AND status IN ('${SyncIntentEntity.STATUS_PENDING}', '${SyncIntentEntity.STATUS_HELD}') ORDER BY seq ASC LIMIT :limit")
    suspend fun pendingFifo(venueId: String, limit: Int = 50): List<SyncIntentEntity>

    /** Negotiated lanes share the global seq, but each keeps its own held/retry barrier. */
    @Query("SELECT * FROM pos_sync_intents WHERE venue_id = :venueId AND status IN ('PENDING', 'HELD') AND ((:preparationLane = 1 AND type = 'KDS_ITEM_PROGRESS') OR (:preparationLane = 0 AND type != 'KDS_ITEM_PROGRESS')) ORDER BY seq ASC LIMIT :limit")
    suspend fun pendingFifoLane(venueId: String, preparationLane: Boolean, limit: Int = 50): List<SyncIntentEntity>

    /** La ronda en vuelo falló por red: vuelve a la cola como un intent cualquiera. */
    @Query("UPDATE pos_sync_intents SET status = '${SyncIntentEntity.STATUS_PENDING}' WHERE id = :id AND status = '${SyncIntentEntity.STATUS_HELD}'")
    suspend fun soltar(id: String): Int

    /** La ronda llegó (o el servidor la rechazó): su red de seguridad sobra. Nunca borra un ACKED ni un REJECTED. */
    @Query("DELETE FROM pos_sync_intents WHERE id = :id AND type!='PAY_CASH' AND q15CaptureJson IS NULL AND q15Reason IS NULL AND status IN ('${SyncIntentEntity.STATUS_PENDING}', '${SyncIntentEntity.STATUS_HELD}')")
    suspend fun descartar(id: String): Int

    /** Primer arranque del proceso: lo que siga retenido es de un proceso que murió a medio envío. */
    @Query("UPDATE pos_sync_intents SET status = '${SyncIntentEntity.STATUS_PENDING}' WHERE status = '${SyncIntentEntity.STATUS_HELD}'")
    suspend fun soltarRetenidos(): Int

    @Query("SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'PENDING'")
    suspend fun pendingCount(venueId: String): Int

    /**
     * Rondas retenidas (Task 7 review, 2026-09-28): las guardas de logout/cambio de sucursal deben
     * ver una ronda en vuelo como trabajo pendiente — no cuenta en el badge visible ([pendingCount]),
     * pero SÍ debe bloquear salir de la sesión (`blockingWorkCount`).
     */
    @Query("SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id = :venueId AND status = '${SyncIntentEntity.STATUS_HELD}'")
    suspend fun heldCount(venueId: String): Int

    /**
     * Los payloads de un tipo que siguen esperando a reproducirse, **con la hora en
     * que se encolaron**. Lo usa el cajón para saber qué ventas en efectivo el server
     * todavía no puede conocer — ver `PendingCashSales`. Devuelve el JSON crudo: quien
     * pregunta sabe qué campo necesita, y así no hay que deserializar 14 formas
     * distintas de payload.
     *
     * 🔴 `created_at` viaja porque el cajón tiene que ACOTAR a la ventana de su caja: un
     * `PAY_CASH` atorado del turno anterior no puede protegerle una venta a la caja de
     * hoy. La consulta sólo TRAE el dato; quién queda dentro de la ventana lo decide
     * `PendingCashSales`, en Kotlin, que es donde los tests lo fijan.
     */
    @Query("SELECT payload_json, created_at FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'PENDING' AND type = :type")
    suspend fun pendingPayloads(venueId: String, type: String): List<SyncIntentPayload>

    @Query(Q15_HISTORY_COUNT_SQL)
    fun observarRevisionesQ15(venueId:String,revisadas:Boolean,busqueda:String):kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'REJECTED' AND q15Reason IS NULL")
    suspend fun rejectedCount(venueId: String): Int

    /** Cuarentena: intents rechazados, más recientes primero, para la pantalla de resolución. */
    @Query("SELECT * FROM pos_sync_intents WHERE venue_id = :venueId AND status = 'REJECTED' AND q15Reason IS NULL ORDER BY created_at DESC")
    suspend fun rejectedIntents(venueId: String): List<SyncIntentEntity>

    /** El gerente descartó un rechazo tras resolverlo manualmente. */
    @Query(Q15_INTENT_DISMISS_SQL)
    suspend fun dismiss(id: String)

    /** El estado de UN intent, en vivo. Lo mira la pantalla de resultado de un cobro encolado. */
    @Query("SELECT status FROM pos_sync_intents WHERE id = :id")
    fun observarEstado(id: String): kotlinx.coroutines.flow.Flow<String?>

    @Query(MAX_SYNC_INTENT_SQL)
    suspend fun maxSeq(): Long

    @Query(RESOLVE_SYNC_INTENT_SQL)
    suspend fun resolve(id: String, status: String, errorCode: String?, message: String?, resultJson: String?)

    /** ACKED viejos se limpian; PENDING/REJECTED jamás se borran automáticos. */
    @Query(Q15_INTENT_TTL_SQL)
    suspend fun deleteOldAcked(olderThan: Long)
}

internal const val Q16_VENUE_CAPTURE_UNRESOLVED_SQL = "SELECT (EXISTS(SELECT 1 FROM pending_payments WHERE venueId=:venueId AND syncStatus!='SYNCED' LIMIT 1) OR EXISTS(SELECT 1 FROM pos_sync_intents WHERE venue_id=:venueId AND type='PAY_CASH' AND $Q16_COBRO_QUE_RETIENE LIMIT 1))"
