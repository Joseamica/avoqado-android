package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.database.AvoqadoDatabaseMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 🔴 LAS MIGRACIONES DE `cobros_en_maquina` DEJAN LA TABLA EXACTAMENTE COMO ROOM LA ESPERA (patrón `KdsLanMigracionTest`): se
 * compara cada constante con el `createSql` del esquema que Room exporta (`app/schemas/…/<v>.json`, que se COMMITEA). Si
 * difieren, la app revienta al abrir la base en el aparato. La validación completa con Room de verdad vive en escritorio
 * (`CobrosEnMaquinaRoomTest`).
 */
class CobroEnMaquinaMigracionTest {

    private fun createSql(version: Int): Pair<String, JsonArray?> {
        val esquema = File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/$version.json")
        assertTrue("falta el esquema exportado por Room: ${esquema.absolutePath}", esquema.exists())
        val entidad = Json.parseToJsonElement(esquema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == "cobros_en_maquina" }
        return entidad["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "cobros_en_maquina") to
            (entidad["indices"] as? JsonArray)
    }

    @Test
    fun `la tabla que crea la migracion 19 a 20 es la que Room esperaba en la v20, sin indices`() {
        val (sql, indices) = createSql(20)
        assertEquals(sql, CobroEnMaquinaSql.CREAR_TABLA_V20)
        assertTrue(indices.isNullOrEmpty())
    }

    @Test
    fun `la tabla de la v21 es la que Room esperaba en la v21`() {
        val (sql, indices) = createSql(21)
        assertEquals(sql, CobroEnMaquinaSql.CREAR_TABLA_V21)
        assertTrue(indices.isNullOrEmpty())
    }

    @Test
    fun `la tabla de hoy es la que Room espera en la v22`() {
        val (sql, indices) = createSql(22)
        assertEquals(sql, CobroEnMaquinaSql.CREAR_TABLA)
        assertTrue(indices.isNullOrEmpty())
    }

    @Test
    fun `las migraciones 19 a 20, 20 a 21 y 21 a 22 existen y van en cadena`() {
        assertEquals(19 to 20, AvoqadoDatabaseMigrations.MIGRATION_19_20.let { it.startVersion to it.endVersion })
        assertEquals(20 to 21, AvoqadoDatabaseMigrations.MIGRATION_20_21.let { it.startVersion to it.endVersion })
        assertEquals(21 to 22, AvoqadoDatabaseMigrations.MIGRATION_21_22.let { it.startVersion to it.endVersion })
    }

    @Test
    fun `una base v21 llega a v22 con las columnas nuevas, lo que se debe calculado y los cobros intactos`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { v21 ->
            v21.createStatement().use { it.executeUpdate(CobroEnMaquinaSql.CREAR_TABLA_V21) }
            fun insertar(ref: String, estado: String, credit: Int, change: Int) = v21.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos, respuestaServidor) VALUES ('$ref', 'venue-1', 'venta-$ref', '{}', 8000, 0, 8000, 1, " +
                        "'$estado', $credit, $change, 1, 0, 0, 0, 'crudo', '{\"outcome\":\"$estado\"}')",
                )
            }
            insertar("AQ000000000001", "PAGADO", 10_000, 1_500)    // debe $5
            insertar("AQ000000000002", "CANCELADO", 5_000, 3_000)  // no devolvió $20
            insertar("AQ000000000003", "GUARDADO", 5_000, 0)
            insertar("AQ000000000004", "PAGADO", 10_000, 2_000)    // exacto

            CobroEnMaquinaSql.MIGRAR_21_22.forEach { sql -> v21.createStatement().use { it.executeUpdate(sql) } }

            val hoy = DriverManager.getConnection("jdbc:sqlite::memory:").use { nueva ->
                nueva.createStatement().use { it.executeUpdate(CobroEnMaquinaSql.CREAR_TABLA) }
                columnas(nueva)
            }
            assertEquals("las mismas columnas que crea Room hoy", hoy, columnas(v21))
            v21.createStatement().use { st ->
                val rs = st.executeQuery(
                    "SELECT referenceCC, aDeberCents, bindingId, cierreTardio, respuestaServidor FROM cobros_en_maquina ORDER BY referenceCC",
                )
                val filas = generateSequence { if (rs.next()) listOf<Any?>(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)) else null }.toList()
                assertEquals(
                    listOf(
                        listOf<Any?>("AQ000000000001", 500, null, null), listOf<Any?>("AQ000000000002", 2_000, null, null),
                        listOf<Any?>("AQ000000000003", 0, null, null), listOf<Any?>("AQ000000000004", 0, null, null),
                    ),
                    filas,
                )
            }
        }
    }

    private fun columnas(db: Connection): List<List<Any?>> = db.createStatement().use { st ->
        val rs = st.executeQuery("PRAGMA table_info(cobros_en_maquina)")
        generateSequence { if (rs.next()) listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getInt("pk")) else null }
            .toList()
    }

    @Test
    fun `una base v20 con un cobro llega a v21 con la columna nueva en NULL y el cobro intacto`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { v20 ->
            v20.createStatement().use { it.executeUpdate(CobroEnMaquinaSql.CREAR_TABLA_V20) }
            v20.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos, cancelPedido) VALUES ('AQ000000000001', 'venue-1', 'venta-1', '{}', 18000, 750, 18750, 1, " +
                        "'GUARDADO', 5000, 0, 1, 0, 0, 0, 'crudo', '2')",
                )
            }

            v20.createStatement().use { it.executeUpdate(CobroEnMaquinaSql.MIGRAR_20_21) }

            val v21 = DriverManager.getConnection("jdbc:sqlite::memory:").use { nueva ->
                nueva.createStatement().use { it.executeUpdate(CobroEnMaquinaSql.CREAR_TABLA_V21) }
                columnas(nueva)
            }
            assertEquals("las mismas columnas que creaba Room en la v21", v21, columnas(v20))
            v20.createStatement().use { st ->
                val rs = st.executeQuery("SELECT estado, creditCents, cancelPedido, respuestaServidor FROM cobros_en_maquina")
                assertTrue(rs.next())
                assertEquals(listOf<Any?>("GUARDADO", 5000, "2"), listOf(rs.getString(1), rs.getInt(2), rs.getString(3)))
                assertNull(rs.getString(4))
            }
        }
    }
}
