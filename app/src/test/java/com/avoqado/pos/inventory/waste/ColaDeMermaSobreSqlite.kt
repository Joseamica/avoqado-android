package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.PendingWasteDao
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.PendingWasteSql
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Un [PendingWasteDao] que corre el SQL REAL de la cola ([PendingWasteSql]) sobre SQLite en
 * memoria. Las pruebas del motor lo usan en vez de un doble escrito a mano: así los candados de la
 * cola (el CAS del reclamo, «sólo quien la tiene en SENDING escribe su desenlace») son los mismos
 * que en el aparato, no una réplica que podría pasar sin ellos.
 *
 * `reclamar` NO se reimplementa: es el método por defecto de la interfaz, el mismo que Room envuelve
 * en su transacción. El `INSERT OR IGNORE` sí se escribe aquí, porque en el aparato lo genera Room
 * a partir de `@Insert(onConflict = IGNORE)`.
 */
class ColaDeMermaSobreSqlite : PendingWasteDao {

    /** Se corre ANTES de escribir: una prueba puede detener aquí la escritura, como Room, que suspende. */
    var antesDeEncolar: suspend () -> Unit = {}

    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.executeUpdate(PendingWasteSql.CREAR_TABLA) }
    }

    private val parametro = Regex(":([A-Za-z]+)")

    private fun preparar(sql: String, valores: Map<String, Any?>): PreparedStatement {
        val nombres = parametro.findAll(sql).map { it.groupValues[1] }.toList()
        val st = db.prepareStatement(parametro.replace(sql, "?"))
        nombres.forEachIndexed { i, nombre -> st.setObject(i + 1, valores.getValue(nombre)) }
        return st
    }

    /** Una sola conexión, un solo hilo a la vez: como el escritor único de Room. */
    @Synchronized
    private fun <T> conBase(bloque: () -> T): T = bloque()

    private fun actualizar(sql: String, vararg valores: Pair<String, Any?>): Int =
        conBase { preparar(sql, valores.toMap()).use { it.executeUpdate() } }

    private fun filas(sql: String, vararg valores: Pair<String, Any?>): List<PendingWasteEntity> =
        conBase {
            preparar(sql, valores.toMap()).use { st ->
                val rs = st.executeQuery()
                generateSequence { if (rs.next()) rs.aFila() else null }.toList()
            }
        }

    private fun entero(sql: String, vararg valores: Pair<String, Any?>): Int =
        conBase { preparar(sql, valores.toMap()).use { st -> st.executeQuery().let { rs -> rs.next(); rs.getInt(1) } } }

    private fun ResultSet.aFila() = PendingWasteEntity(
        idempotencyKey = getString("idempotencyKey"),
        venueId = getString("venueId"),
        staffId = getString("staffId"),
        itemType = getString("itemType"),
        itemId = getString("itemId"),
        itemName = getString("itemName"),
        unit = getString("unit"),
        quantity = getString("quantity"),
        reasonCode = getString("reasonCode"),
        note = getString("note"),
        clientOccurredAt = getString("clientOccurredAt"),
        estado = getString("estado"),
        intentos = getInt("intentos"),
        ultimoCodigo = getString("ultimoCodigo"),
        creadaEn = getLong("creadaEn"),
        proximoIntentoEn = getLong("proximoIntentoEn"),
        cerradaPorStaffId = getString("cerradaPorStaffId"),
        cerradaEn = getObject("cerradaEn")?.let { (it as Number).toLong() },
    )

    override suspend fun encolar(fila: PendingWasteEntity): Long {
        antesDeEncolar()
        return escribir(fila)
    }

    private fun escribir(fila: PendingWasteEntity): Long = conBase {
        db.prepareStatement(
            "INSERT OR IGNORE INTO pending_waste (idempotencyKey, venueId, staffId, itemType, itemId, " +
                "itemName, unit, quantity, reasonCode, note, clientOccurredAt, estado, intentos, ultimoCodigo, " +
                "creadaEn, proximoIntentoEn, cerradaPorStaffId, cerradaEn) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { st ->
            listOf<Any?>(
                fila.idempotencyKey, fila.venueId, fila.staffId, fila.itemType, fila.itemId, fila.itemName,
                fila.unit, fila.quantity, fila.reasonCode, fila.note, fila.clientOccurredAt, fila.estado,
                fila.intentos, fila.ultimoCodigo, fila.creadaEn, fila.proximoIntentoEn,
                fila.cerradaPorStaffId, fila.cerradaEn,
            ).forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate().toLong()
        }
    }

    override suspend fun candidato(ahora: Long, staffId: String): String? = conBase {
        preparar(PendingWasteSql.CANDIDATO, mapOf("ahora" to ahora, "staffId" to staffId)).use { st ->
            val rs = st.executeQuery()
            if (rs.next()) rs.getString(1) else null
        }
    }

    override suspend fun reclamarFolio(folio: String): Int = actualizar(PendingWasteSql.RECLAMAR, "folio" to folio)

    override suspend fun porFolio(folio: String): PendingWasteEntity? =
        filas(PendingWasteSql.POR_FOLIO, "folio" to folio).singleOrNull()

    override suspend fun marcar(folio: String, estado: String, codigo: String?, proximoIntentoEn: Long): Int =
        actualizar(
            PendingWasteSql.MARCAR,
            "folio" to folio, "estado" to estado, "codigo" to codigo, "proximoIntentoEn" to proximoIntentoEn,
        )

    override suspend fun cerrar(folio: String, estado: String, porStaffId: String?, cuando: Long): Int =
        actualizar(PendingWasteSql.CERRAR, "folio" to folio, "estado" to estado, "porStaffId" to porStaffId, "cuando" to cuando)

    override suspend fun borrarSincronizada(folio: String): Int =
        actualizar(PendingWasteSql.BORRAR_SINCRONIZADA, "folio" to folio)

    override suspend fun devolver(folio: String): Int = actualizar(PendingWasteSql.DEVOLVER, "folio" to folio)

    override suspend fun sanarSending(): Int = actualizar(PendingWasteSql.SANAR_SENDING)

    override fun delVenue(venueId: String): Flow<List<PendingWasteEntity>> =
        flow { emit(filas(PendingWasteSql.DEL_VENUE, "venueId" to venueId)) }

    override fun cuenta(venueId: String): Flow<Int> =
        flow { emit(entero(PendingWasteSql.CUENTA_VIVAS, "venueId" to venueId)) }

    override suspend fun todas(): List<PendingWasteEntity> = filas(PendingWasteSql.TODAS)
}
