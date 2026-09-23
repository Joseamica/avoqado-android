package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteSql
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
 * LA COLA DURABLE DE MERMAS, EJECUTADA DE VERDAD.
 *
 * Mismo patrón que `WasteCatalogSqlTest` y `CashDrawerDeleteSqlTest`: se corren las MISMAS
 * constantes que Room compila en `@Query` contra SQLite en memoria. Lo que se mide son los
 * candados de la cola, que viven en el SQL: quién puede reclamar una fila, quién puede escribir
 * su desenlace, y que nada terminal se reabra.
 *
 * La tabla se crea con [PendingWasteSql.CREAR_TABLA], la MISMA cadena que corre la migración.
 */
class PendingWasteSqlTest {

    private lateinit var db: Connection

    @Before
    fun abrirBase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(PendingWasteSql.CREAR_TABLA) }
    }

    @After
    fun cerrarBase() {
        db.close()
    }

    private companion object {
        const val FOLIO = "3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f"
        const val STAFF = "staff-1"
        const val VENUE = "venue-1"
        val PARAMETRO = Regex(":([A-Za-z]+)")
    }

    /**
     * Room resuelve cada `:nombre` por su valor; JDBC pide un `?` por aparición. Se sustituyen
     * EN ORDEN y se alimenta cada posición con el valor de su nombre — así la prueba corre la
     * cadena literal que Room compila, sin reescribirla.
     */
    private fun preparar(sql: String, valores: Map<String, Any?>): PreparedStatement {
        val nombres = PARAMETRO.findAll(sql).map { it.groupValues[1] }.toList()
        val st = db.prepareStatement(PARAMETRO.replace(sql, "?"))
        nombres.forEachIndexed { i, nombre -> st.setObject(i + 1, valores.getValue(nombre)) }
        return st
    }

    private fun actualizar(sql: String, vararg valores: Pair<String, Any?>): Int =
        preparar(sql, valores.toMap()).use { it.executeUpdate() }

    private fun textoUnico(sql: String, vararg valores: Pair<String, Any?>): String? =
        preparar(sql, valores.toMap()).use { st ->
            val rs = st.executeQuery()
            if (rs.next()) rs.getString(1) else null
        }

    private fun folios(sql: String, vararg valores: Pair<String, Any?>): List<String> =
        preparar(sql, valores.toMap()).use { st ->
            val rs = st.executeQuery()
            generateSequence { if (rs.next()) rs.getString("idempotencyKey") else null }.toList()
        }

    private fun fila(folio: String): Map<String, Any?>? =
        preparar(PendingWasteSql.POR_FOLIO, mapOf("folio" to folio)).use { st ->
            val rs = st.executeQuery()
            if (!rs.next()) null
            else (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getObject(it) }
        }

    private fun insertar(
        folio: String,
        estado: String = EstadoMerma.PENDING,
        staffId: String = STAFF,
        venueId: String = VENUE,
        creadaEn: Long = 0L,
        proximoIntentoEn: Long = 0L,
    ) {
        db.prepareStatement(
            "INSERT INTO pending_waste (idempotencyKey, venueId, staffId, itemType, itemId, itemName, unit, " +
                "quantity, reasonCode, note, clientOccurredAt, estado, intentos, ultimoCodigo, creadaEn, " +
                "proximoIntentoEn, cerradaPorStaffId, cerradaEn) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use {
            listOf<Any?>(
                folio, venueId, staffId, "RAW_MATERIAL", "rm-1", "Aguacate", "kg", "3.5", "SPOILED", null,
                "2026-09-22T12:00:00-06:00", estado, 0, null, creadaEn, proximoIntentoEn, null, null,
            ).forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.executeUpdate()
        }
    }

    private fun candidato(ahora: Long, staffId: String = STAFF): String? =
        textoUnico(PendingWasteSql.CANDIDATO, "ahora" to ahora, "staffId" to staffId)

    private fun reclamarFolio(folio: String): Int = actualizar(PendingWasteSql.RECLAMAR, "folio" to folio)

    // MARK: - La llave

    /**
     * 🔴 El folio es la LLAVE de la fila, igual que en el servidor. Un segundo alta con el mismo
     * folio no puede crear otra merma. (El DAO usa `INSERT OR IGNORE`, que además deja intacta la
     * primera; eso lo genera Room y se comprueba leyendo su `_Impl`.)
     */
    @Test
    fun `el folio es la llave, un segundo alta del mismo folio no crea otra merma`() {
        insertar(FOLIO)
        try {
            insertar(FOLIO)
            fail("se aceptó un segundo alta con el mismo folio")
        } catch (esperado: SQLException) {
            // la llave primaria lo rechazó
        }
        assertEquals(listOf(FOLIO), folios(PendingWasteSql.TODAS))
    }

    // MARK: - Arranque

    /**
     * 🔴 Review Focus 4 — el proceso murió entre el POST y la respuesta. Una fila en `SENDING` al
     * arrancar tiene desenlace DESCONOCIDO: borrarla perdería una merma que quizá no se aplicó, y
     * darla por buena mentiría. Vuelve a `PENDING` y se reenvía con el MISMO folio, que el
     * servidor deduplica. Las demás filas no se tocan.
     */
    @Test
    fun `al arrancar, una fila en SENDING vuelve a PENDING con su folio intacto`() {
        insertar(FOLIO, estado = EstadoMerma.SENDING)
        insertar("en-revision", estado = EstadoMerma.NEEDS_REVIEW)

        assertEquals(1, actualizar(PendingWasteSql.SANAR_SENDING))

        assertEquals(EstadoMerma.PENDING, fila(FOLIO)?.get("estado"))
        assertEquals(EstadoMerma.NEEDS_REVIEW, fila("en-revision")?.get("estado"))
    }

    // MARK: - Reclamar

    /**
     * 🔴 Reclamar es un CAS: la fila sólo pasa a `SENDING` si todavía estaba disponible. Si dos
     * drenados llegan a la vez, el segundo no se la lleva — el folio protegería del doble registro
     * en el servidor, pero el segundo envío gastaría red y pisaría el estado del primero.
     */
    @Test
    fun `el reclamo es un CAS, el segundo que llega no se la lleva`() {
        insertar(FOLIO)

        assertEquals(FOLIO, candidato(ahora = 0L))
        assertEquals(1, reclamarFolio(FOLIO))
        assertEquals(0, reclamarFolio(FOLIO))

        assertEquals(EstadoMerma.SENDING, fila(FOLIO)?.get("estado"))
        assertNull(candidato(ahora = 0L))
    }

    /** Una fila que espera su reintento no se reclama antes de tiempo. */
    @Test
    fun `no se reclama antes de su proximo intento`() {
        insertar(FOLIO, proximoIntentoEn = 1_000L)
        assertNull(candidato(ahora = 999L))
        assertEquals(FOLIO, candidato(ahora = 1_000L))
    }

    /**
     * Lo que pide revisión y lo terminal NO se vuelve a mandar, ni por el candidato ni por el CAS.
     *
     * 🔴 Y no TAPA lo de atrás: la pendiente más nueva sale aunque haya una en revisión más vieja.
     * Si el candidato devolviera la fila en revisión, el CAS la rechazaría —no se mandaría—, pero el
     * reclamo regresaría vacío y las pendientes de atrás no saldrían NUNCA.
     */
    @Test
    fun `NEEDS_REVIEW, VOIDED y APPLIED no se vuelven a mandar ni tapan a las pendientes`() {
        insertar("a", estado = EstadoMerma.NEEDS_REVIEW, creadaEn = 1L)
        insertar("b", estado = EstadoMerma.VOIDED, creadaEn = 2L)
        insertar("c", estado = EstadoMerma.APPLIED, creadaEn = 3L)
        insertar("pendiente", creadaEn = 4L)

        assertEquals("pendiente", candidato(ahora = Long.MAX_VALUE))
        assertEquals(0, reclamarFolio("a") + reclamarFolio("b") + reclamarFolio("c"))
    }

    /** Un bloqueo de plan se vuelve a intentar solo: cuando el plan vuelve, la merma sube. */
    @Test
    fun `un bloqueo de plan si se vuelve a intentar`() {
        insertar(FOLIO, estado = EstadoMerma.PLAN_BLOCKED)
        assertEquals(FOLIO, candidato(ahora = 0L))
        assertEquals(1, reclamarFolio(FOLIO))
    }

    /**
     * Se drena en el orden en que se capturaron (spec §5: «el de llegada»), no en el de su próximo
     * intento: una merma vieja que falló una vez no se queda detrás de todas las nuevas.
     */
    @Test
    fun `se reclama la mas vieja primero`() {
        insertar("nueva", creadaEn = 200L, proximoIntentoEn = 0L)
        insertar("vieja", creadaEn = 100L, proximoIntentoEn = 50L)
        assertEquals("vieja", candidato(ahora = Long.MAX_VALUE))
    }

    /**
     * 🔴 Spec §5: la merma sube con la sesión de QUIEN LA CAPTURÓ. El servidor toma el autor del
     * token, así que mandarla con la sesión de otra persona la dejaría a nombre de quien no la
     * registró. Y el filtro va en el reclamo, no después: si la fila más vieja fuera de otra
     * persona y se reclamara para luego saltarla, taparía para siempre las de la sesión actual.
     */
    @Test
    fun `solo se reclama lo de la persona de la sesion`() {
        insertar("de-a", staffId = "persona-a", creadaEn = 1L)
        insertar("de-b", staffId = "persona-b", creadaEn = 2L)

        assertEquals("de-b", candidato(ahora = Long.MAX_VALUE, staffId = "persona-b"))
        assertEquals("de-a", candidato(ahora = Long.MAX_VALUE, staffId = "persona-a"))
        assertNull(candidato(ahora = Long.MAX_VALUE, staffId = "persona-c"))
    }

    // MARK: - Desenlaces

    /** El desenlace de un envío (reintentar, revisión, plan) sólo lo escribe quien la tiene en `SENDING`. */
    @Test
    fun `el desenlace de un envio solo lo escribe quien la tiene en SENDING`() {
        insertar(FOLIO)
        val marcar = arrayOf(
            "folio" to FOLIO, "estado" to EstadoMerma.NEEDS_REVIEW,
            "codigo" to "QUANTITY_TOO_LARGE", "proximoIntentoEn" to 5_000L,
        )
        assertEquals(0, actualizar(PendingWasteSql.MARCAR, *marcar))

        reclamarFolio(FOLIO)
        assertEquals(1, actualizar(PendingWasteSql.MARCAR, *marcar))

        val f = fila(FOLIO)!!
        assertEquals(EstadoMerma.NEEDS_REVIEW, f["estado"])
        assertEquals("QUANTITY_TOO_LARGE", f["ultimoCodigo"])
        assertEquals(1L, (f["intentos"] as Number).toLong())
        assertEquals(5_000L, (f["proximoIntentoEn"] as Number).toLong())
    }

    /**
     * Devolver regresa a la cola una fila que iba en camino SIN contarla como intento (el envío no
     * salió: la sesión cambió, o se canceló antes de contestar). Y sólo toca lo que va en camino:
     * una fila ya cerrada no se reabre.
     */
    @Test
    fun `devolver regresa a la cola lo que iba en camino, sin contarlo como intento`() {
        insertar(FOLIO)
        reclamarFolio(FOLIO)

        assertEquals(1, actualizar(PendingWasteSql.DEVOLVER, "folio" to FOLIO))
        val f = fila(FOLIO)!!
        assertEquals(EstadoMerma.PENDING, f["estado"])
        assertEquals(0L, (f["intentos"] as Number).toLong())

        insertar("anulada", estado = EstadoMerma.VOIDED)
        assertEquals(0, actualizar(PendingWasteSql.DEVOLVER, "folio" to "anulada"))
    }

    /** El 201: la merma ya vive en el servidor y la fila que se estaba mandando se va. */
    @Test
    fun `el 201 borra la fila que se estaba mandando`() {
        insertar(FOLIO)
        reclamarFolio(FOLIO)
        assertEquals(1, actualizar(PendingWasteSql.BORRAR_SINCRONIZADA, "folio" to FOLIO))
        assertNull(fila(FOLIO))
    }

    /**
     * 🔴 Spec §4.3: «queda marca durable de quién anuló». Una fila anulada NO se borra — se
     * cierra CON autor y fecha. Si desapareciera, nadie podría explicar después por qué esa
     * merma no está.
     */
    @Test
    fun `una fila anulada conserva quien la anulo y cuando`() {
        insertar(FOLIO)
        assertEquals(
            1,
            actualizar(
                PendingWasteSql.CERRAR,
                "folio" to FOLIO, "estado" to EstadoMerma.VOIDED,
                "porStaffId" to "gerente-1", "cuando" to 1_700_000_000_000L,
            ),
        )
        val f = fila(FOLIO)
        assertEquals(EstadoMerma.VOIDED, f?.get("estado"))
        assertEquals("gerente-1", f?.get("cerradaPorStaffId"))
        assertEquals(1_700_000_000_000L, (f?.get("cerradaEn") as Number).toLong())
    }

    private fun anularComoGerente() {
        insertar(FOLIO)
        actualizar(
            PendingWasteSql.CERRAR,
            "folio" to FOLIO, "estado" to EstadoMerma.VOIDED, "porStaffId" to "gerente-1", "cuando" to 1L,
        )
    }

    /**
     * 🔴 Lo terminal no se reabre. Si un envío que ya estaba en el aire vuelve TARDE con un 5xx
     * después de que la fila se anuló, no puede devolverla a la cola: se volvería a mandar y la
     * marca de quién anuló se perdería.
     */
    @Test
    fun `un resultado tardio no reabre una fila ya cerrada`() {
        anularComoGerente()
        assertEquals(
            0,
            actualizar(
                PendingWasteSql.MARCAR,
                "folio" to FOLIO, "estado" to EstadoMerma.PENDING, "codigo" to "HTTP 503", "proximoIntentoEn" to 0L,
            ),
        )
        assertEquals(EstadoMerma.VOIDED, fila(FOLIO)?.get("estado"))
    }

    /** Un 201 que llega tarde tampoco borra una fila ya cerrada: la anulación y su autor se quedan. */
    @Test
    fun `un 201 tardio no borra una fila ya cerrada`() {
        anularComoGerente()
        assertEquals(0, actualizar(PendingWasteSql.BORRAR_SINCRONIZADA, "folio" to FOLIO))
        assertEquals("gerente-1", fila(FOLIO)?.get("cerradaPorStaffId"))
    }

    /** Un segundo cierre no le cambia el estado ni el autor a una fila ya cerrada. */
    @Test
    fun `un segundo cierre no reescribe a quien anulo`() {
        anularComoGerente()
        assertEquals(
            0,
            actualizar(
                PendingWasteSql.CERRAR,
                "folio" to FOLIO, "estado" to EstadoMerma.APPLIED, "porStaffId" to "otra-persona", "cuando" to 2L,
            ),
        )
        val f = fila(FOLIO)
        assertEquals(EstadoMerma.VOIDED, f?.get("estado"))
        assertEquals("gerente-1", f?.get("cerradaPorStaffId"))
    }

    // MARK: - Lo que ve el cajero

    /**
     * «Mermas por subir» de UNA sucursal: sólo lo suyo, en el orden en que se capturó, y la cuenta
     * del aviso no incluye lo cerrado (anulado o ya registrado), que se conserva pero ya no espera nada.
     */
    @Test
    fun `la lista de la sucursal trae solo lo suyo en orden, y la cuenta no incluye lo cerrado`() {
        insertar("pendiente", creadaEn = 2L)
        insertar("en-revision", estado = EstadoMerma.NEEDS_REVIEW, creadaEn = 1L)
        insertar("anulada", estado = EstadoMerma.VOIDED, creadaEn = 3L)
        insertar("otra-sucursal", venueId = "venue-2", creadaEn = 0L)

        assertEquals(
            listOf("en-revision", "pendiente", "anulada"),
            folios(PendingWasteSql.DEL_VENUE, "venueId" to VENUE),
        )
        assertEquals("2", textoUnico(PendingWasteSql.CUENTA_VIVAS, "venueId" to VENUE))
        assertEquals("1", textoUnico(PendingWasteSql.CUENTA_VIVAS, "venueId" to "venue-2"))
    }
}
