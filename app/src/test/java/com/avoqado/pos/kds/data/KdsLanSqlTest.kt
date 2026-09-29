package com.avoqado.pos.kds.data

import com.avoqado.pos.kds.data.local.KdsLanSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun entrega(folio: String, venue: String = "venue-1", creada: Long = 0L) {
        db.prepareStatement("INSERT INTO entregas_kds_pendientes (sourceKey, venueId, stationId, mensajeJson, trabajoJson, creadaEnMillis) VALUES (?,?,?,?,?,?)").use {
            listOf<Any?>(folio, venue, "st_barra", "{}", "{}", creada).forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.executeUpdate()
        }
    }

    private fun ticket(folio: String, venue: String = "venue-1", station: String = "st_barra", recibida: Long = 0L, lista: Long? = null) {
        db.prepareStatement("INSERT INTO kds_tickets_locales (sourceKey, venueId, stationId, orderNumber, orderType, itemsJson, recibidaEnMillis, listaEnMillis) VALUES (?,?,?,?,?,?,?,?)").use {
            listOf<Any?>(folio, venue, station, "1", "En tienda", "[]", recibida, lista).forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.executeUpdate()
        }
    }

    @Test
    fun `el folio es la llave de la entrega - un segundo alta del mismo folio no crea otra`() {
        entrega("sale:ext-1:st_barra")
        try { entrega("sale:ext-1:st_barra"); fail("se aceptó un segundo alta") } catch (esperado: SQLException) { }
    }

    @Test
    fun `las entregas de la sucursal salen en orden de creacion y sin las de otra sucursal`() {
        entrega("b", creada = 2); entrega("a", creada = 1); entrega("ajena", venue = "venue-2", creada = 0)
        assertEquals(listOf("a", "b"), folios(KdsLanSql.ENTREGAS_DEL_VENUE, "venueId" to "venue-1"))
        assertEquals(1, actualizar(KdsLanSql.BORRAR_ENTREGA, "sourceKey" to "a"))
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
}
