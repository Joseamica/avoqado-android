package com.avoqado.pos.inventory.waste.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Los estados de una merma en la cola del aparato.
 *
 * Vivos: `PENDING` (espera su turno) · `SENDING` (va en camino) · `NEEDS_REVIEW` (el servidor la
 * rechazó y una persona tiene que decidir) · `PLAN_BLOCKED` (se reintenta sola cuando vuelva el plan).
 * Terminales que se CONSERVAN: `VOIDED` (anulada, con quién y cuándo) · `APPLIED` (el servidor ya la
 * tenía: se le dice al cajero para que no la capture otra vez).
 */
object EstadoMerma {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val PLAN_BLOCKED = "PLAN_BLOCKED"
    const val VOIDED = "VOIDED"
    const val APPLIED = "APPLIED"
}

/**
 * Una merma declarada en este aparato, esperando a que el servidor la registre.
 *
 * 🔴 Se escribe ANTES de tocar la red (`todo-funciona-sin-red.md`, pregunta 2): si el proceso muere
 * entre el toque y el POST, la merma sigue aquí con su folio y sale sola después.
 *
 * El FOLIO (`idempotencyKey`, UUID v4) es la llave: el servidor deduplica por él, así que un reenvío
 * —tras un corte, un reinicio o un 5xx— nunca registra la merma dos veces. Por eso el cuerpo se
 * guarda ENTERO: un reenvío con otro cuerpo el servidor lo rechaza (`IDEMPOTENCY_KEY_REUSED`).
 *
 * NO es un `SyncIntentEntity` (spec §5): ese outbox es FIFO de órdenes, y una merma atorada detendría
 * las ventas, o al revés.
 */
@Entity(tableName = "pending_waste")
data class PendingWasteEntity(
    @PrimaryKey val idempotencyKey: String,
    /** El venue donde se CAPTURÓ. El envío usa éste, nunca el activo (Review Focus 3). */
    val venueId: String,
    /** Quién la capturó. Sólo sube con la sesión de esa misma persona (spec §5). */
    val staffId: String,
    val itemType: String,
    val itemId: String,
    val itemName: String,
    val unit: String,
    /** Ya normalizada (`normalizarCantidad`): punto decimal, sin separador de miles. */
    val quantity: String,
    val reasonCode: String,
    val note: String?,
    val clientOccurredAt: String,
    val estado: String,
    val intentos: Int = 0,
    val ultimoCodigo: String? = null,
    val creadaEn: Long,
    val proximoIntentoEn: Long = 0L,
    /** Quién la cerró y cuándo: spec §4.3, «marca durable de quién anuló». */
    val cerradaPorStaffId: String? = null,
    val cerradaEn: Long? = null,
)

/**
 * El SQL de la cola, en constantes, para que `PendingWasteSqlTest` lo EJECUTE contra SQLite de verdad
 * (patrón de [WasteSql]). Los candados de la cola viven aquí, no en Kotlin:
 *
 * - [RECLAMAR] es un CAS: sólo pasa a `SENDING` la fila que todavía estaba disponible.
 * - [MARCAR] y [BORRAR_SINCRONIZADA] sólo los aplica quien la tiene en `SENDING`: un resultado tardío
 *   no reabre ni borra una fila que otro camino ya cerró.
 * - [CERRAR] nunca reescribe lo terminal: quien anuló queda para siempre.
 */
internal object PendingWasteSql {

    /** 🔴 En el formato EXACTO que Room genera para [PendingWasteEntity] (lo vigila `WasteMigracionTest`). */
    const val CREAR_TABLA: String =
        "CREATE TABLE IF NOT EXISTS `pending_waste` (" +
            "`idempotencyKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `staffId` TEXT NOT NULL, " +
            "`itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, `itemName` TEXT NOT NULL, " +
            "`unit` TEXT NOT NULL, `quantity` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, " +
            "`note` TEXT, `clientOccurredAt` TEXT NOT NULL, `estado` TEXT NOT NULL, " +
            "`intentos` INTEGER NOT NULL, `ultimoCodigo` TEXT, `creadaEn` INTEGER NOT NULL, " +
            "`proximoIntentoEn` INTEGER NOT NULL, `cerradaPorStaffId` TEXT, `cerradaEn` INTEGER, " +
            "PRIMARY KEY(`idempotencyKey`))"

    /**
     * La más vieja de ESA persona lista para salir. El filtro por `staffId` va AQUÍ y no después:
     * reclamar la fila de otra persona para saltarla taparía para siempre las de la sesión actual.
     */
    const val CANDIDATO: String =
        "SELECT idempotencyKey FROM pending_waste " +
            "WHERE estado IN ('PENDING', 'PLAN_BLOCKED') AND staffId = :staffId " +
            "AND proximoIntentoEn <= :ahora " +
            "ORDER BY creadaEn ASC, idempotencyKey ASC LIMIT 1"

    const val RECLAMAR: String =
        "UPDATE pending_waste SET estado = 'SENDING' " +
            "WHERE idempotencyKey = :folio AND estado IN ('PENDING', 'PLAN_BLOCKED')"

    const val POR_FOLIO: String = "SELECT * FROM pending_waste WHERE idempotencyKey = :folio"

    const val MARCAR: String =
        "UPDATE pending_waste SET estado = :estado, ultimoCodigo = :codigo, " +
            "intentos = intentos + 1, proximoIntentoEn = :proximoIntentoEn " +
            "WHERE idempotencyKey = :folio AND estado = 'SENDING'"

    const val CERRAR: String =
        "UPDATE pending_waste SET estado = :estado, cerradaPorStaffId = :porStaffId, cerradaEn = :cuando " +
            "WHERE idempotencyKey = :folio AND estado NOT IN ('VOIDED', 'APPLIED')"

    const val BORRAR_SINCRONIZADA: String =
        "DELETE FROM pending_waste WHERE idempotencyKey = :folio AND estado = 'SENDING'"

    /**
     * Regresa a la cola una fila que iba en camino SIN contarla como intento: el envío no salió (la
     * sesión cambió a media vuelta) o se canceló antes de contestar. Sólo toca lo que va en camino.
     */
    const val DEVOLVER: String =
        "UPDATE pending_waste SET estado = 'PENDING' WHERE idempotencyKey = :folio AND estado = 'SENDING'"

    /** Al arrancar: lo que quedó en camino tiene desenlace desconocido y vuelve a la cola, con su folio. */
    const val SANAR_SENDING: String =
        "UPDATE pending_waste SET estado = 'PENDING' WHERE estado = 'SENDING'"

    const val DEL_VENUE: String =
        "SELECT * FROM pending_waste WHERE venueId = :venueId ORDER BY creadaEn ASC, idempotencyKey ASC"

    /** Lo que todavía espera algo; lo cerrado se conserva pero no cuenta. */
    const val CUENTA_VIVAS: String =
        "SELECT COUNT(*) FROM pending_waste WHERE venueId = :venueId AND estado NOT IN ('VOIDED', 'APPLIED')"

    const val TODAS: String = "SELECT * FROM pending_waste ORDER BY creadaEn ASC, idempotencyKey ASC"
}

@Dao
interface PendingWasteDao {

    /**
     * `IGNORE`: re-encolar el mismo folio no crea otra merma NI pisa la que ya está — ni su cuerpo
     * (que el servidor exige idéntico en un reenvío) ni su estado.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun encolar(fila: PendingWasteEntity): Long

    @Query(PendingWasteSql.CANDIDATO)
    suspend fun candidato(ahora: Long, staffId: String): String?

    @Query(PendingWasteSql.RECLAMAR)
    suspend fun reclamarFolio(folio: String): Int

    @Query(PendingWasteSql.POR_FOLIO)
    suspend fun porFolio(folio: String): PendingWasteEntity?

    /** La más vieja de esa persona, ya en `SENDING`; o `null` si no hay o si otro la ganó. */
    @Transaction
    suspend fun reclamar(ahora: Long, staffId: String): PendingWasteEntity? {
        val folio = candidato(ahora, staffId) ?: return null
        if (reclamarFolio(folio) == 0) return null
        return porFolio(folio)
    }

    /** Para los estados que siguen vivos (`PENDING`, `NEEDS_REVIEW`, `PLAN_BLOCKED`). */
    @Query(PendingWasteSql.MARCAR)
    suspend fun marcar(folio: String, estado: String, codigo: String?, proximoIntentoEn: Long): Int

    /** Para los terminales que conservan autoría (`VOIDED`, `APPLIED`). */
    @Query(PendingWasteSql.CERRAR)
    suspend fun cerrar(folio: String, estado: String, porStaffId: String?, cuando: Long): Int

    /** Sólo el 201 del drenado: la merma ya vive en el servidor. */
    @Query(PendingWasteSql.BORRAR_SINCRONIZADA)
    suspend fun borrarSincronizada(folio: String): Int

    @Query(PendingWasteSql.DEVOLVER)
    suspend fun devolver(folio: String): Int

    @Query(PendingWasteSql.SANAR_SENDING)
    suspend fun sanarSending(): Int

    @Query(PendingWasteSql.DEL_VENUE)
    fun delVenue(venueId: String): Flow<List<PendingWasteEntity>>

    @Query(PendingWasteSql.CUENTA_VIVAS)
    fun cuenta(venueId: String): Flow<Int>

    @Query(PendingWasteSql.TODAS)
    suspend fun todas(): List<PendingWasteEntity>
}
