package com.avoqado.pos.kds.data

import com.avoqado.pos.kds.data.local.KdsLanSql
import com.avoqado.pos.kds.data.local.KdsTicketLocalEntity
import com.avoqado.pos.kds.data.local.KdsTicketsLocalesDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * Las dos tablas de la red local (etapa 3 del KDS, 3.5, D7/D8), EJECUTADAS DE VERDAD: las MISMAS constantes que Room
 * compila, contra SQLite en memoria (patrón `PendingWasteSqlTest`). La tabla se crea con las cadenas que corre la
 * migración 12→13.
 */
class KdsLanSqlTest {

    private lateinit var db: Connection

    @Before
    fun abrirBase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(KdsLanSql.CREAR_ENTREGAS); it.executeUpdate(KdsLanSql.CREAR_TICKETS) }
    }

    @After
    fun cerrarBase() { db.close() }

    private companion object { val PARAMETRO = Regex(":([A-Za-z]+)") }

    /** Una lista se expande a `?,?,?` como hace Room con `IN (:folios)`. */
    private fun preparar(sql: String, valores: Map<String, Any?>): PreparedStatement {
        val args = mutableListOf<Any?>()
        val expandido = PARAMETRO.replace(sql) { m ->
            when (val v = valores.getValue(m.groupValues[1])) {
                is List<*> -> { args.addAll(v); v.joinToString(",") { "?" } }
                else -> { args.add(v); "?" }
            }
        }
        val st = db.prepareStatement(expandido)
        args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        return st
    }

    private fun actualizar(sql: String, vararg valores: Pair<String, Any?>): Int = preparar(sql, valores.toMap()).use { it.executeUpdate() }

    private fun folios(sql: String, vararg valores: Pair<String, Any?>): List<String> = preparar(sql, valores.toMap()).use { st ->
        val rs = st.executeQuery()
        generateSequence { if (rs.next()) rs.getString("sourceKey") else null }.toList()
    }

    /**
     * Ronda 2 (N1): la llave es la ENTREGA (`<folio>|<renglones del plan>`), no el folio. `INSERT OR REPLACE` es lo que
     * corre Room con `@Insert(REPLACE)`.
     */
    private fun entrega(folio: String, venue: String = "venue-1", creada: Long = 0L, renglones: String = "") {
        db.prepareStatement("INSERT OR REPLACE INTO entregas_kds_pendientes (entregaId, sourceKey, venueId, stationId, mensajeJson, trabajoJson, creadaEnMillis) VALUES (?,?,?,?,?,?,?)").use {
            listOf<Any?>("$folio|$renglones", folio, venue, "st_barra", "{}", "{}", creada).forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.executeUpdate()
        }
    }

    private fun ticket(folio: String, venue: String = "venue-1", station: String = "st_barra", recibida: Long = 0L, lista: Long? = null) {
        db.prepareStatement("INSERT INTO kds_tickets_locales (sourceKey, venueId, stationId, orderNumber, orderType, itemsJson, recibidaEnMillis, listaEnMillis) VALUES (?,?,?,?,?,?,?,?)").use {
            listOf<Any?>(folio, venue, station, "1", "En tienda", "[]", recibida, lista).forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.executeUpdate()
        }
    }

    /**
     * N1 de la revisión: los cursos de una ronda comparten folio (`round:<llave>:<estación>`). Con el folio de llave, el
     * `REPLACE` del curso 2 pisaba la fila del curso 1 aún sin decidir — y si los dos papeles fallaban, el curso 1 no
     * quedaba en ningún lado.
     */
    @Test
    fun `P1 dos cursos de la misma ronda que fallan dejan DOS filas - y el mismo plan otra vez sigue siendo una`() {
        entrega("round:r1:st_cocina", creada = 1, renglones = "oi_1")
        entrega("round:r1:st_cocina", creada = 2, renglones = "oi_2,oi_3")
        entrega("round:r1:st_cocina", creada = 3, renglones = "oi_2,oi_3")
        assertEquals(listOf("round:r1:st_cocina", "round:r1:st_cocina"), folios(KdsLanSql.ENTREGAS_DEL_VENUE, "venueId" to "venue-1"))
        assertEquals(1, actualizar(KdsLanSql.BORRAR_ENTREGA, "entregaId" to "round:r1:st_cocina|oi_2,oi_3"))
        assertEquals(listOf("round:r1:st_cocina"), folios(KdsLanSql.ENTREGAS_DEL_VENUE, "venueId" to "venue-1"))
    }

    /** N2: el despacho vivo SUELTA la fila cuyo papel no salió; el reloj del replay la toma sin esperar a reabrir. */
    @Test
    fun `soltar pone la hora solo en esa entrega`() {
        entrega("round:r1:st_cocina", renglones = "oi_1"); entrega("round:r1:st_cocina", renglones = "oi_2")
        assertEquals(1, actualizar(KdsLanSql.SOLTAR_ENTREGA, "entregaId" to "round:r1:st_cocina|oi_2", "ahora" to 77L))
        val soltadas = preparar("SELECT entregaId, soltadaEnMillis FROM entregas_kds_pendientes ORDER BY entregaId", emptyMap()).use { st ->
            val rs = st.executeQuery()
            generateSequence { if (rs.next()) rs.getString("entregaId") to (rs.getObject("soltadaEnMillis") as Number?)?.toLong() else null }.toList()
        }
        assertEquals(listOf("round:r1:st_cocina|oi_1" to null, "round:r1:st_cocina|oi_2" to 77L), soltadas)
    }

    @Test
    fun `las entregas de la sucursal salen en orden de creacion y sin las de otra sucursal`() {
        entrega("b", creada = 2); entrega("a", creada = 1); entrega("ajena", venue = "venue-2", creada = 0)
        assertEquals(listOf("a", "b"), folios(KdsLanSql.ENTREGAS_DEL_VENUE, "venueId" to "venue-1"))
        assertEquals(1, actualizar(KdsLanSql.BORRAR_ENTREGA, "entregaId" to "a|"))
        assertEquals(listOf("b"), folios(KdsLanSql.ENTREGAS_DEL_VENUE, "venueId" to "venue-1"))
    }

    @Test
    fun `P1 marcar lista es pegajoso - solo la primera vez y solo si estaba pendiente`() {
        ticket("sale:ext-1:st_barra")
        assertEquals(1, actualizar(KdsLanSql.MARCAR_LISTA, "sourceKey" to "sale:ext-1:st_barra", "ahora" to 100L))
        assertEquals(0, actualizar(KdsLanSql.MARCAR_LISTA, "sourceKey" to "sale:ext-1:st_barra", "ahora" to 200L))
        assertEquals(0, actualizar(KdsLanSql.MARCAR_LISTA, "sourceKey" to "no-existe", "ahora" to 200L))
    }

    @Test
    fun `los tickets de la estacion salen en orden de llegada, pendientes y listos, sin los de otra estacion o sucursal`() {
        ticket("c", recibida = 3, lista = 9); ticket("a", recibida = 1); ticket("otra", station = "st_cocina"); ticket("ajena", venue = "venue-2")
        assertEquals(listOf("a", "c"), folios(KdsLanSql.TICKETS_DE_LA_ESTACION, "venueId" to "venue-1", "stationId" to "st_barra"))
    }

    @Test
    fun `purgar se lleva solo lo viejo de esa sucursal`() {
        ticket("vieja", recibida = 10); ticket("nueva", recibida = 100); ticket("ajena-vieja", venue = "venue-2", recibida = 10)
        assertEquals(1, actualizar(KdsLanSql.PURGAR_TICKETS, "venueId" to "venue-1", "corte" to 50L))
        assertEquals(listOf("nueva"), folios(KdsLanSql.TICKETS_DE_LA_ESTACION, "venueId" to "venue-1", "stationId" to "st_barra"))
        assertNull(preparar(KdsLanSql.TICKET_POR_FOLIO, mapOf("sourceKey" to "vieja")).use { st -> st.executeQuery().let { if (it.next()) it.getString("sourceKey") else null } })
    }

    @Test
    fun `P1 retirar pendientes borra solo las PENDIENTES que el servidor ya tiene - las listas se quedan`() {
        ticket("sale:a:st_barra", recibida = 1); ticket("sale:b:st_barra", recibida = 2, lista = 9); ticket("sale:c:st_barra", recibida = 3)
        assertEquals(1, actualizar(KdsLanSql.RETIRAR_PENDIENTES, "folios" to listOf("sale:a:st_barra", "sale:b:st_barra", "no-existe")))
        assertEquals(listOf("sale:b:st_barra", "sale:c:st_barra"), folios(KdsLanSql.TICKETS_DE_LA_ESTACION, "venueId" to "venue-1", "stationId" to "st_barra"))
    }

    @Test
    fun `quitar la marca LISTO la deja pendiente otra vez`() {
        ticket("sale:a:st_barra", lista = 9)
        assertEquals(1, actualizar(KdsLanSql.QUITAR_LISTA, "sourceKey" to "sale:a:st_barra"))
        // Pendiente otra vez: se puede volver a marcar, y retirar la borra.
        assertEquals(1, actualizar(KdsLanSql.MARCAR_LISTA, "sourceKey" to "sale:a:st_barra", "ahora" to 100L))
        assertEquals(1, actualizar(KdsLanSql.QUITAR_LISTA, "sourceKey" to "sale:a:st_barra"))
        assertEquals(1, actualizar(KdsLanSql.RETIRAR_PENDIENTES, "folios" to listOf("sale:a:st_barra")))
    }

    // MARK: - Ronda 1 (I1): la regla del curso nuevo sobre una fila LISTA, DENTRO de `unir`, ejecutada de verdad

    /**
     * El DAO REAL sobre SQLite en memoria: `porFolio` y `guardar` corren con las MISMAS constantes de SQL que Room
     * compila, y `unir` es el `@Transaction` de la interfaz, HEREDADO sin tocar — la lógica que se prueba es la de
     * producción. Lo que esto fija es que la lectura, la decisión y la escritura viven en ese único método (la
     * atomicidad la pone Room al envolverlo); un mock del DAO jamás podía ver la regla ni que se conserve la marca.
     */
    private inner class DaoSobreSqlite : KdsTicketsLocalesDao {
        override suspend fun guardar(fila: KdsTicketLocalEntity) {
            db.prepareStatement("INSERT OR REPLACE INTO kds_tickets_locales (sourceKey, venueId, stationId, orderNumber, orderType, itemsJson, recibidaEnMillis, listaEnMillis) VALUES (?,?,?,?,?,?,?,?)").use {
                listOf<Any?>(fila.sourceKey, fila.venueId, fila.stationId, fila.orderNumber, fila.orderType, fila.itemsJson, fila.recibidaEnMillis, fila.listaEnMillis)
                    .forEachIndexed { i, v -> it.setObject(i + 1, v) }
                it.executeUpdate()
            }
        }

        override suspend fun porFolio(sourceKey: String): KdsTicketLocalEntity? =
            preparar(KdsLanSql.TICKET_POR_FOLIO, mapOf("sourceKey" to sourceKey)).use { st ->
                val rs = st.executeQuery()
                if (!rs.next()) null else KdsTicketLocalEntity(
                    sourceKey = rs.getString("sourceKey"), venueId = rs.getString("venueId"), stationId = rs.getString("stationId"),
                    orderNumber = rs.getString("orderNumber"), orderType = rs.getString("orderType"), itemsJson = rs.getString("itemsJson"),
                    recibidaEnMillis = rs.getLong("recibidaEnMillis"), listaEnMillis = rs.getLong("listaEnMillis").takeUnless { rs.wasNull() },
                )
            }

        override fun deLaEstacion(venueId: String, stationId: String): Flow<List<KdsTicketLocalEntity>> = throw UnsupportedOperationException()
        override suspend fun marcarLista(sourceKey: String, ahora: Long): Int = actualizar(KdsLanSql.MARCAR_LISTA, "sourceKey" to sourceKey, "ahora" to ahora)
        override suspend fun purgar(venueId: String, corte: Long): Int = actualizar(KdsLanSql.PURGAR_TICKETS, "venueId" to venueId, "corte" to corte)
        override suspend fun retirarPendientes(folios: List<String>): Int = actualizar(KdsLanSql.RETIRAR_PENDIENTES, "folios" to folios)
        override suspend fun quitarLista(sourceKey: String): Int = actualizar(KdsLanSql.QUITAR_LISTA, "sourceKey" to sourceKey)
    }

    private val renglonA = """{"id":"a","productName":"Café","quantity":1,"modifiers":[],"notes":null}"""
    private val renglonB = """{"id":"b","productName":"Pan","quantity":2,"modifiers":[],"notes":null}"""
    private val folio = "round:r1:st_barra"

    private fun fila(items: String, lista: Long? = null, recibida: Long = 10L, folio: String = this.folio) =
        KdsTicketLocalEntity(folio, "venue-1", "st_barra", "77", "Mesa 8", items, recibida, lista)

    private fun ids(itemsJson: String): List<String> =
        Json.parseToJsonElement(itemsJson).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `P1 unir un curso con un renglon NUEVO sobre una fila YA LISTA devuelve false y la fila queda intacta`() = runBlocking {
        val dao = DaoSobreSqlite()
        val lista = fila("[$renglonA]", lista = 20L)
        dao.guardar(lista)

        val guardada = dao.unir(fila("[$renglonA,$renglonB]", recibida = 99L), idsEntrantes = setOf("a", "b"))

        assertFalse("sin acuse: el curso nuevo no se puede esconder detras de la marca LISTO", guardada)
        // Ni los renglones ni la marca cambian: todo o nada.
        assertEquals(lista, dao.porFolio(folio))
    }

    @Test
    fun `unir un curso nuevo sobre una fila PENDIENTE lo mezcla, la deja pendiente y devuelve true`() = runBlocking {
        val dao = DaoSobreSqlite()
        dao.guardar(fila("[$renglonA]"))

        assertTrue(dao.unir(fila("[$renglonB]", recibida = 99L), idsEntrantes = setOf("b")))

        val guardada = dao.porFolio(folio)!!
        assertEquals(listOf("a", "b"), ids(guardada.itemsJson))
        assertNull(guardada.listaEnMillis)
        assertEquals("la hora de llegada es la de la primera entrega", 10L, guardada.recibidaEnMillis)
    }

    @Test
    fun `reenviar los MISMOS renglones sobre una fila YA LISTA devuelve true y conserva la marca (sin cambios)`() = runBlocking {
        val dao = DaoSobreSqlite()
        val lista = fila("[$renglonA]", lista = 20L)
        dao.guardar(lista)

        assertTrue(dao.unir(fila("[$renglonA]", recibida = 99L), idsEntrantes = setOf("a")))

        assertEquals(lista, dao.porFolio(folio))
    }

    @Test
    fun `sin fila previa unir la inserta tal cual y devuelve true`() = runBlocking {
        val dao = DaoSobreSqlite()
        val nueva = fila("[$renglonA]", recibida = 99L)

        assertTrue(dao.unir(nueva, idsEntrantes = setOf("a")))

        assertEquals(nueva, dao.porFolio(folio))
    }

    /** Lado seguro: si no se puede leer lo que había, TODO lo entrante es «nuevo» y una fila LISTA no acusa. */
    @Test
    fun `P1 un JSON previo ilegible sobre una fila YA LISTA no acusa - y sobre una PENDIENTE sigue mezclando`() = runBlocking {
        val dao = DaoSobreSqlite()
        val ilegible = fila("no es json", lista = 20L)
        dao.guardar(ilegible)
        assertFalse(dao.unir(fila("[$renglonA]"), idsEntrantes = setOf("a")))
        assertEquals(ilegible, dao.porFolio(folio))

        dao.guardar(fila("no es json"))
        assertTrue(dao.unir(fila("[$renglonA]"), idsEntrantes = setOf("a")))
        assertEquals(listOf("a"), ids(dao.porFolio(folio)!!.itemsJson))
    }

    // MARK: - Revisión final (I2): el LISTO sin red es UNA transacción del DAO, ejecutada de verdad

    /**
     * Antes eran tres transacciones (marcar, leer, `REPLACE`): un `unir` que insertaba entre la lectura y el `REPLACE`
     * quedaba pisado por la sombra, con el curso ya acusado escondido 12 h. Ahora todo vive en `marcarListaOCrear`, y la
     * sombra sólo se INSERTA donde no había fila — nunca reemplaza una.
     */
    @Test
    fun `P1 marcar lista sobre una fila PENDIENTE con mas renglones solo pone la hora y conserva los renglones`() = runBlocking {
        val dao = DaoSobreSqlite()
        dao.guardar(fila("[$renglonA,$renglonB]"))

        dao.marcarListaOCrear(fila("[$renglonA]", lista = 50L, recibida = 50L), ahora = 50L)

        val guardada = dao.porFolio(folio)!!
        assertEquals("los renglones que llegaron por el WiFi no se pisan con los de la sombra", listOf("a", "b"), ids(guardada.itemsJson))
        assertEquals(50L, guardada.listaEnMillis)
        assertEquals("la hora de llegada no cambia", 10L, guardada.recibidaEnMillis)
    }

    @Test
    fun `marcar lista sin fila inserta la sombra LISTA`() = runBlocking {
        val dao = DaoSobreSqlite()
        val sombra = fila("[$renglonA]", lista = 50L, recibida = 50L)

        dao.marcarListaOCrear(sombra, ahora = 50L)

        assertEquals(sombra, dao.porFolio(folio))
    }

    @Test
    fun `P1 marcar lista sobre una fila LISTA no cambia la hora ni los renglones`() = runBlocking {
        val dao = DaoSobreSqlite()
        val lista = fila("[$renglonA,$renglonB]", lista = 20L)
        dao.guardar(lista)

        dao.marcarListaOCrear(fila("[$renglonA]", lista = 50L, recibida = 50L), ahora = 50L)

        assertEquals(lista, dao.porFolio(folio))
    }
}
