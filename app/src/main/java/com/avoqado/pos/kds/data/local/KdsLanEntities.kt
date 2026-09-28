package com.avoqado.pos.kds.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.avoqado.pos.core.data.lan.KdsComandaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Etapa 3 del KDS (3.5, D7) — una entrega por WiFi que la CAJA guardó ANTES de conectar. Sólo estaciones «sólo
 * pantalla» (su papel depende del acuse). Si el proceso muere entre el toque y el acuse, al abrir la app se reintenta
 * (< 10 min) o sale en papel con `trabajoJson` (un [com.avoqado.pos.printing.data.TrabajoPendiente] ya congelado con
 * la config de respaldo). NO es un intent del outbox: el outbox es FIFO de órdenes.
 */
@Entity(tableName = "entregas_kds_pendientes")
data class EntregaKdsPendienteEntity(
    @PrimaryKey val sourceKey: String,
    val venueId: String,
    val stationId: String,
    /** La línea `comanda` tal cual se manda (`KdsLanProtocol.encode`). */
    val mensajeJson: String,
    val trabajoJson: String,
    val creadaEnMillis: Long,
)

/**
 * Etapa 3 del KDS (3.5, D8) — una comanda guardada en la PANTALLA: llegó por el WiFi del local (se guarda ANTES de
 * acusar), o se marcó LISTO sin red (`listaEnMillis`, pegajoso, para que un sondeo del servidor no la resucite).
 * Vigencia 12 h.
 */
@Entity(tableName = "kds_tickets_locales")
data class KdsTicketLocalEntity(
    @PrimaryKey val sourceKey: String,
    val venueId: String,
    val stationId: String,
    val orderNumber: String,
    /** Texto para mostrar («En tienda», «Mesa 8 · Aperitivos»). */
    val orderType: String,
    /** `List<KdsComandaItem>` en JSON. */
    val itemsJson: String,
    val recibidaEnMillis: Long,
    /** `null` = pendiente; con valor = LISTO (marcado aquí, con o sin red). */
    val listaEnMillis: Long?,
)

/** El SQL de las dos tablas, en constantes, para que `KdsLanSqlTest` lo EJECUTE contra SQLite de verdad (patrón `PendingWasteSql`). */
internal object KdsLanSql {

    /** 🔴 En el formato EXACTO que Room genera para [EntregaKdsPendienteEntity] (lo vigila `KdsLanMigracionTest`). */
    const val CREAR_ENTREGAS: String =
        "CREATE TABLE IF NOT EXISTS `entregas_kds_pendientes` (" +
            "`sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, " +
            "`mensajeJson` TEXT NOT NULL, `trabajoJson` TEXT NOT NULL, `creadaEnMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`sourceKey`))"

    /** 🔴 En el formato EXACTO que Room genera para [KdsTicketLocalEntity]. */
    const val CREAR_TICKETS: String =
        "CREATE TABLE IF NOT EXISTS `kds_tickets_locales` (" +
            "`sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, " +
            "`orderNumber` TEXT NOT NULL, `orderType` TEXT NOT NULL, `itemsJson` TEXT NOT NULL, " +
            "`recibidaEnMillis` INTEGER NOT NULL, `listaEnMillis` INTEGER, " +
            "PRIMARY KEY(`sourceKey`))"

    const val ENTREGAS_DEL_VENUE: String =
        "SELECT * FROM entregas_kds_pendientes WHERE venueId = :venueId ORDER BY creadaEnMillis ASC, sourceKey ASC"

    const val BORRAR_ENTREGA: String = "DELETE FROM entregas_kds_pendientes WHERE sourceKey = :sourceKey"

    const val TICKET_POR_FOLIO: String = "SELECT * FROM kds_tickets_locales WHERE sourceKey = :sourceKey"

    /** Pendientes Y listas: la mezcla por folio necesita las dos (D9). */
    const val TICKETS_DE_LA_ESTACION: String =
        "SELECT * FROM kds_tickets_locales WHERE venueId = :venueId AND stationId = :stationId " +
            "ORDER BY recibidaEnMillis ASC, sourceKey ASC"

    /** Pegajoso: sólo la primera vez. */
    const val MARCAR_LISTA: String =
        "UPDATE kds_tickets_locales SET listaEnMillis = :ahora WHERE sourceKey = :sourceKey AND listaEnMillis IS NULL"

    const val PURGAR_TICKETS: String =
        "DELETE FROM kds_tickets_locales WHERE venueId = :venueId AND recibidaEnMillis < :corte"
}

/** `encodeDefaults = true` como `LeaseProtocol.json`: nulos y listas vacías van EXPLÍCITOS (`KdsLanMigracionTest` compara el texto). */
private val jsonDeItems = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * D8: el MISMO folio llega varias veces (una ronda con cursos manda `round:<key>:<estación>` una vez por curso). Se UNEN
 * renglones por `id` conservando el orden de llegada; un JSON ilegible se ignora en vez de perder lo que ya había.
 */
internal fun unirItemsJson(previos: String, nuevos: String): String {
    val serializer = ListSerializer(KdsComandaItem.serializer())
    val a = runCatching { jsonDeItems.decodeFromString(serializer, previos) }.getOrDefault(emptyList())
    val b = runCatching { jsonDeItems.decodeFromString(serializer, nuevos) }.getOrDefault(emptyList())
    val ids = a.map { it.id }.toSet()
    return jsonDeItems.encodeToString(serializer, a + b.filter { it.id !in ids })
}

@Dao
interface EntregasKdsPendientesDao {
    /** `REPLACE`: el mismo folio (un reintento del despacho) refresca la fila. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(fila: EntregaKdsPendienteEntity)

    @Query(KdsLanSql.ENTREGAS_DEL_VENUE)
    suspend fun delVenue(venueId: String): List<EntregaKdsPendienteEntity>

    @Query(KdsLanSql.BORRAR_ENTREGA)
    suspend fun borrar(sourceKey: String): Int
}

@Dao
interface KdsTicketsLocalesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(fila: KdsTicketLocalEntity)

    @Query(KdsLanSql.TICKET_POR_FOLIO)
    suspend fun porFolio(sourceKey: String): KdsTicketLocalEntity?

    @Query(KdsLanSql.TICKETS_DE_LA_ESTACION)
    fun deLaEstacion(venueId: String, stationId: String): Flow<List<KdsTicketLocalEntity>>

    @Query(KdsLanSql.MARCAR_LISTA)
    suspend fun marcarLista(sourceKey: String, ahora: Long): Int

    @Query(KdsLanSql.PURGAR_TICKETS)
    suspend fun purgar(venueId: String, corte: Long): Int

    /** Upsert que UNE renglones por `id` y conserva `listaEnMillis` (pegajoso). Atómico. */
    @Transaction
    suspend fun unir(nueva: KdsTicketLocalEntity) {
        val previa = porFolio(nueva.sourceKey)
        guardar(if (previa == null) nueva else previa.copy(itemsJson = unirItemsJson(previa.itemsJson, nueva.itemsJson)))
    }
}
