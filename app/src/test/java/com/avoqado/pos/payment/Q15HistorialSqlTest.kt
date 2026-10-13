package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.database.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** SQLite propia. Ejecuta las cadenas de producción usadas por Room; Room real queda para instrumentación. */
class Q15HistorialSqlTest {
    private lateinit var db: Connection
    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("CREATE TABLE pending_payments(id TEXT PRIMARY KEY, venueId TEXT NOT NULL, syncStatus TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        exec("CREATE TABLE pos_sync_intents(id TEXT PRIMARY KEY, venue_id TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL,type TEXT NOT NULL DEFAULT 'PAY_CASH')")
        for (sql in Q15_MIGRATION_SQL) exec(sql)
    }
    @After fun cleanup() { db.close() }
    private fun exec(sql: String, params: Map<String, Any?> = emptyMap()): Int {
        val ordered = mutableListOf<Any?>()
        val bound = Regex(":([a-zA-Z][a-zA-Z0-9]*)").replace(sql) { m -> ordered.add(params[m.groupValues[1]]); "?" }
        return db.prepareStatement(bound).use { stmt ->
            ordered.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeUpdate()
        }
    }
    private fun rows(sql: String, params: Map<String, Any?>): List<Map<String, Any?>> {
        val ordered = mutableListOf<Any?>()
        val bound = Regex(":([a-zA-Z][a-zA-Z0-9]*)").replace(sql) { m -> ordered.add(params[m.groupValues[1]]); "?" }
        return db.prepareStatement(bound).use { stmt ->
            ordered.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeQuery().use { rs -> buildList {
                while (rs.next()) add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) })
            } }
        }
    }
    private fun seed(id: String, venue: String = "FULLTEST-A", source: String = "PAYMENT", at: Long = 10) {
        if (source == "PAYMENT") exec("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt,q15CaptureJson) VALUES(:id,:venue,'PENDING',1,'original')", mapOf("id" to id,"venue" to venue))
        else exec("INSERT INTO pos_sync_intents(id,venue_id,status,created_at,q15CaptureJson) VALUES(:id,:venue,'ACKED',1,'original')", mapOf("id" to id,"venue" to venue))
        val sql = if (source == "PAYMENT") Q15_PAYMENT_RESULT_SQL else Q15_INTENT_RESULT_SQL
        exec(sql, mapOf("venueId" to venue, "id" to id, "aplicado" to 1, "motivo" to "DIFERENCIA_DE_COBRO", "resultadoJson" to "first", "ahora" to at))
    }
    private fun query(venue: String = "FULLTEST-A", reviewed: Int = 0, date: Long? = null, source: String? = null, id: String? = null) =
        mapOf("venueId" to venue,"revisadas" to reviewed,"cursorFecha" to date,"cursorOrigen" to source,"cursorId" to id,"busqueda" to "")
    @Test fun legacy_migrado_no_recibe_evidencia_autentica() {
        exec("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt) VALUES('old','FULLTEST-A','PENDING',1)")
        val row = rows("SELECT q15CaptureJson FROM pending_payments WHERE id='old'", emptyMap()).single()
        assertNull(row["q15CaptureJson"])
    }
    @Test fun resultado_y_motivo_se_guardan_sin_reencolar_el_pago_aplicado() {
        seed("p1")
        exec(Q15_PAYMENT_RESULT_SQL, mapOf("venueId" to "FULLTEST-A","id" to "p1","aplicado" to 0,"motivo" to "RESULTADO_NO_COMPROBADO","resultadoJson" to "later","ahora" to 20))
        val row = rows("SELECT syncStatus,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,q15DetectedAt FROM pending_payments WHERE id='p1'", emptyMap()).single()
        assertEquals("SYNCED", row["syncStatus"])
        assertEquals("original", row["q15CaptureJson"])
        assertEquals("first", row["q15InitialResultJson"])
        assertEquals("later", row["q15ResultJson"])
        assertEquals("DIFERENCIA_DE_COBRO", row["q15Reason"])
        assertEquals(10L, (row["q15DetectedAt"] as Number).toLong())
    }
    @Test fun tenant_no_puede_guardar_resultado_o_revisar_pago_de_otro() {
        seed("p1", "FULLTEST-A")
        assertEquals(0, exec(Q15_PAYMENT_RESULT_SQL, mapOf("venueId" to "FULLTEST-B","id" to "p1","aplicado" to 1,"motivo" to "x","resultadoJson" to "x","ahora" to 50)))
        assertEquals(0, exec(Q15_PAYMENT_REVIEW_SQL, mapOf("venueId" to "FULLTEST-B","id" to "p1","actor" to "manager","rol" to "MANAGER","ahora" to 50)))
        assertTrue(rows(Q15_HISTORY_PAGE_SQL, query("FULLTEST-B")).isEmpty())
    }
    @Test fun gerente_archiva_y_historia_sigue_paginada_mientras_cajero_no_puede() {
        seed("p1"); seed("i1", source = "INTENT")
        val manager = mapOf("venueId" to "FULLTEST-A","id" to "p1","actor" to "manager","rol" to "MANAGER","ahora" to 50)
        assertEquals(0, exec(Q15_PAYMENT_REVIEW_SQL, manager + ("rol" to "CASHIER")))
        assertEquals(1, exec(Q15_PAYMENT_REVIEW_SQL, manager))
        assertEquals(1, (rows(Q15_HISTORY_COUNT_SQL, query()).single()["total"] as Number).toInt())
        val history = rows(Q15_HISTORY_PAGE_SQL, query(reviewed = 1)).single()
        assertEquals("p1", history["id"])
        assertEquals("manager", history["q15ReviewedBy"])
        assertEquals("original", history["q15CaptureJson"])
    }
    @Test fun retencion_de_importe_desconocido_no_es_pago_aplicado_ni_revisable() {
        exec("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt) VALUES('old','FULLTEST-A','PENDING',1)")
        exec(Q15_PAYMENT_HOLD_SQL, mapOf("venueId" to "FULLTEST-A","id" to "old","motivo" to "CAPTURA_SIN_EVIDENCIA","ahora" to 2))
        assertEquals(0, exec(Q15_PAYMENT_REVIEW_SQL, mapOf("venueId" to "FULLTEST-A","id" to "old","actor" to "manager","rol" to "MANAGER","ahora" to 3)))
        assertEquals("PENDING", rows("SELECT syncStatus FROM pending_payments WHERE id='old'", emptyMap()).single()["syncStatus"])
    }
    @Test fun paginas_tienen_tope_total_exacto_y_cursor_no_pierde_empates() {
        for (n in 0..120) seed("p%03d".format(n), at = 10)
        seed("other", venue = "FULLTEST-B")
        val p1 = rows(Q15_HISTORY_PAGE_SQL, query())
        assertEquals(50, p1.size)
        val last = p1.last()
        val p2 = rows(Q15_HISTORY_PAGE_SQL, query(date=10, source=last["origen"] as String, id=last["id"] as String))
        assertEquals(50, p2.size)
        assertTrue(p1.map { it["id"] }.intersect(p2.map { it["id"] }.toSet()).isEmpty())
        assertEquals(121, (rows(Q15_HISTORY_COUNT_SQL, query()).single()["total"] as Number).toInt())
    }
    @Test fun ttl_y_borrado_no_eliminan_evidencia_ni_historial_revisado() {
        seed("p1"); seed("i1", source = "INTENT")
        assertEquals(0, exec(Q15_PAYMENT_TTL_SQL, mapOf("olderThan" to 9999)))
        assertEquals(0, exec(Q15_INTENT_TTL_SQL, mapOf("olderThan" to 9999)))
        assertEquals(0, exec(Q15_PAYMENT_DELETE_FAILED_SQL, mapOf("id" to "p1")))
        assertEquals(0, exec(Q15_INTENT_DISMISS_SQL, mapOf("id" to "i1")))
        assertEquals(2, (rows(Q15_HISTORY_COUNT_SQL, query()).single()["total"] as Number).toInt())
    }
    @Test fun referencia_original_es_scalar_por_venue_y_legacy_no_gana_proveniencia() {
        seed("p1"); seed("i1", source="INTENT")
        exec("UPDATE pending_payments SET capturedOrderRef='L1' WHERE id='p1'")
        exec("UPDATE pos_sync_intents SET capturedOrderRef='o2' WHERE id='i1'")
        val query = mapOf("venueId" to "FULLTEST-A", "orderRef" to "L1")
        assertEquals(1, (rows(Q15_CAPTURED_ORDER_EXISTS_SQL, query).single()["hayCaptura"] as Number).toInt())
        assertEquals(0, (rows(Q15_CAPTURED_ORDER_EXISTS_SQL, query + ("venueId" to "FULLTEST-B")).single()["hayCaptura"] as Number).toInt())
        assertEquals(0, (rows(Q15_CAPTURED_ORDER_EXISTS_SQL, query + ("orderRef" to "unknown")).single()["hayCaptura"] as Number).toInt())
    }
}
