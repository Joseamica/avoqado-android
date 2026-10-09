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
    /**
     * Ronda 2 de la Task 6 (N1): UNA fila por PLAN — `<sourceKey>|<orderItemIds del plan, ordenados, con coma>`
     * (`entregaIdDe` en `EntregaPorWifi.kt`). Los cursos de una ronda comparten `sourceKey`; con él de llave, el `REPLACE`
     * del curso 2 pisaba la fila del curso 1 aún sin decidir.
     */
    @PrimaryKey val entregaId: String,
    /** El folio (para la marca `FALLBACK_PRINTED`). Ninguna consulta filtra por él: sin índice. */
    val sourceKey: String,
    val venueId: String,
    val stationId: String,
    /** La línea `comanda` tal cual se manda (`KdsLanProtocol.encode`). */
    val mensajeJson: String,
    val trabajoJson: String,
    val creadaEnMillis: Long,
    /**
     * Ronda 2 (N2): `null` = un despacho de este proceso la está decidiendo. Con hora = su despacho terminó y el papel NO
     * salió: el reloj de `ReplayDeEntregasKds` la toma sin esperar a que se reabra la app.
     */
    val soltadaEnMillis: Long? = null,
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
            "`entregaId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, " +
            "`mensajeJson` TEXT NOT NULL, `trabajoJson` TEXT NOT NULL, `creadaEnMillis` INTEGER NOT NULL, " +
            "`soltadaEnMillis` INTEGER, PRIMARY KEY(`entregaId`))"

    /** 🔴 En el formato EXACTO que Room genera para [KdsTicketLocalEntity]. */
    const val CREAR_TICKETS: String =
        "CREATE TABLE IF NOT EXISTS `kds_tickets_locales` (" +
            "`sourceKey` TEXT NOT NULL, `venueId` TEXT NOT NULL, `stationId` TEXT NOT NULL, " +
            "`orderNumber` TEXT NOT NULL, `orderType` TEXT NOT NULL, `itemsJson` TEXT NOT NULL, " +
            "`recibidaEnMillis` INTEGER NOT NULL, `listaEnMillis` INTEGER, " +
            "PRIMARY KEY(`sourceKey`))"

    const val ENTREGAS_DEL_VENUE: String =
        "SELECT * FROM entregas_kds_pendientes WHERE venueId = :venueId ORDER BY creadaEnMillis ASC, sourceKey ASC"

    const val BORRAR_ENTREGA: String = "DELETE FROM entregas_kds_pendientes WHERE entregaId = :entregaId"

    /** Ronda 2 (N2): el despacho vivo terminó sin papel para esta entrega; el reloj del replay ya la puede tomar. */
    const val SOLTAR_ENTREGA: String = "UPDATE entregas_kds_pendientes SET soltadaEnMillis = :ahora WHERE entregaId = :entregaId"

    const val TICKET_POR_FOLIO: String = "SELECT * FROM kds_tickets_locales WHERE sourceKey = :sourceKey"

    /** Pendientes Y listas: la mezcla por folio necesita las dos (D9). */
    const val TICKETS_DE_LA_ESTACION: String =
        "SELECT * FROM kds_tickets_locales WHERE venueId = :venueId AND stationId = :stationId " +
            "ORDER BY recibidaEnMillis ASC, sourceKey ASC"

    /** Pegajoso: sólo la primera vez. */
    const val MARCAR_LISTA: String =
        "UPDATE kds_tickets_locales SET listaEnMillis = :ahora WHERE sourceKey = :sourceKey AND listaEnMillis IS NULL"

    const val PURGAR_TICKETS: String =
        "DELETE FROM kds_tickets_locales WHERE venueId = :venueId AND recibidaEnMillis < :corte AND itemsJson NOT LIKE '%\"preparationVersion\":1%'"

    /**
     * D9 (ronda 1 de la Task 8): el servidor ya devolvió estos folios — su copia gana, y la local PENDIENTE sobra. Sin
     * esto, al marcarla LISTO en línea (aquí o en otra pantalla de la estación) el servidor deja de mandarla y la copia
     * local resucitaba como «sólo local». Las LISTAS se quedan: esconden la del servidor hasta que procese el BUMP.
     */
    const val RETIRAR_PENDIENTES: String =
        "DELETE FROM kds_tickets_locales WHERE sourceKey IN (:folios) AND listaEnMillis IS NULL"

    /** «Deshacer» en línea: la marca LISTO local deja de esconder la comanda que el servidor regresó. */
    const val QUITAR_LISTA: String = "UPDATE kds_tickets_locales SET listaEnMillis = NULL WHERE sourceKey = :sourceKey"
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
    val nuevos = b.filter { it.id !in ids }
    // Nada nuevo ⇒ la fila se queda tal cual (reescribirla le agregaba al texto campos nuevos en `null`, como `course`).
    if (nuevos.isEmpty()) return previos
    return jsonDeItems.encodeToString(serializer, a + nuevos)
}

/** Los ids de los renglones de una fila guardada; un JSON ilegible es «sin renglones» (por eso todo lo que llegue es nuevo). */
internal fun idsDeItemsJson(itemsJson: String): Set<String> =
    runCatching { jsonDeItems.decodeFromString(ListSerializer(KdsComandaItem.serializer()), itemsJson) }
        .getOrDefault(emptyList()).map { it.id }.toSet()

@Dao
interface EntregasKdsPendientesDao {
    /** `REPLACE`: la misma entrega (mismo folio y mismos renglones) refresca la fila; otro curso es otra fila. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(fila: EntregaKdsPendienteEntity)

    @Query(KdsLanSql.ENTREGAS_DEL_VENUE)
    suspend fun delVenue(venueId: String): List<EntregaKdsPendienteEntity>

    @Query(KdsLanSql.BORRAR_ENTREGA)
    suspend fun borrar(entregaId: String): Int

    @Query(KdsLanSql.SOLTAR_ENTREGA)
    suspend fun soltar(entregaId: String, ahora: Long): Int
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

    @Query(KdsLanSql.RETIRAR_PENDIENTES)
    suspend fun retirarPendientes(folios: List<String>): Int

    @Query(KdsLanSql.QUITAR_LISTA)
    suspend fun quitarLista(sourceKey: String): Int

    /**
     * Upsert que UNE renglones por `id` y conserva `listaEnMillis` (pegajoso). Devuelve `true` si la fila quedó guardada.
     *
     * Ronda 1 (I1): leer la fila, decidir y escribir van en ESTA transacción — leerla afuera y escribir aquí dejaba una
     * ventana en la que un LISTO (`marcarLista`) caía entre las dos, el curso se unía a una fila ya oculta y se acusaba
     * (pérdida). Si la fila previa ya está LISTA y [idsEntrantes] trae algún id que ella no tenía, devuelve `false` SIN
     * escribir: es un curso nuevo que la cocina nunca vería; sin acuse la caja lo saca en papel. Un reenvío de los mismos
     * renglones sobre una fila LISTA (nada nuevo) y cualquier curso sobre una PENDIENTE se guardan como siempre. Un JSON
     * previo ilegible cuenta como «sin renglones»: todo lo entrante es nuevo (el lado seguro). [idsEntrantes] son los ids
     * de los renglones del dominio — igual que el gemelo de iOS, sin volver a leer el JSON recién codificado.
     */
    @Transaction
    suspend fun unir(nueva: KdsTicketLocalEntity, idsEntrantes: Set<String>): Boolean {
        val previa = porFolio(nueva.sourceKey)
        if (previa == null) {
            guardar(nueva)
            return true
        }
        if (previa.listaEnMillis != null && (idsEntrantes - idsDeItemsJson(previa.itemsJson)).isNotEmpty()) return false
        guardar(previa.copy(itemsJson = unirItemsJson(previa.itemsJson, nueva.itemsJson)))
        return true
    }

    /**
     * LISTO sin red (D10) en UNA transacción (revisión final de la 3.5, I2): marca la fila si está PENDIENTE; si no hay
     * NINGUNA, inserta [sombra] (ya LISTA, para que un sondeo no resucite la copia del servidor). Una fila ya LISTA no
     * cambia, y una existente nunca se reemplaza. En tres transacciones, un `unir` que insertaba un curso entre la lectura y
     * el `REPLACE` quedaba pisado por la sombra: el curso ya acusado, escondido 12 h. Espejo de iOS (un solo `writer.write`).
     */
    @Transaction
    suspend fun marcarListaOCrear(sombra: KdsTicketLocalEntity, ahora: Long) {
        if (marcarLista(sombra.sourceKey, ahora) == 0 && porFolio(sombra.sourceKey) == null) guardar(sombra)
    }
}
