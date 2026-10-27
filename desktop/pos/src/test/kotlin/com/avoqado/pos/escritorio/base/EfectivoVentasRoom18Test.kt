package com.avoqado.pos.escritorio.base

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.di.DatabaseModule
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Ambos JSON17 son copias byte a byte de las ramas originales, no del candidato. */
class EfectivoVentasRoom18Test {
    @Test fun `Room abre Ventas17 conserva fuentes completas y reconocimientos y reabre en18`() = migrar("ventas")
    @Test fun `Room abre D2_17 conserva captura y caja completas y reabre en18`() = migrar("d2")

    private fun migrar(variante: String) = runBlocking {
        val carpeta = Files.createTempDirectory("efectivo-ventas-room-")
        try {
            val archivo = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
            val esquemas = Path.of(requireNotNull(javaClass.getResource("/room17-originales/$variante")).toURI())
            val antes = ayudanteDeMigraciones(archivo, esquemas).createDatabase(17).use { db ->
                sembrar(db, variante)
                fotografiar(db)
            }
            assertEquals(8, antes.getValue("pending_payments").filas.size)
            assertEquals(2, antes.getValue("pos_sync_intents").filas.size)
            assertEquals(1, antes.getValue("cash_drawer_sessions").filas.size)
            assertEquals(1, antes.getValue("cash_drawer_events").filas.size)
            assertEquals(if (variante == "ventas") 1 else 0,
                antes["sales_notice_acknowledgements"]?.filas?.size ?: 0)

            // La lectura usa el DAO generado y fuerza la apertura/validación real de DatabaseModule.
            repeat(2) { apertura ->
                val room = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
                try {
                    val fallo = runCatching {
                        assertNotNull(room.pendingPaymentDao().findByIdQ15(id(1)))
                    }.exceptionOrNull()
                    assertNull(fallo, "$variante debe abrir sin borrar ni rechazar su identidad (apertura $apertura): $fallo")
                    room.salesNoticeDao().page(id(90), "", null, emptySet())
                } finally { room.close() }
                BundledSQLiteDriver().open(archivo.toString()).use { db ->
                    assertEquals(18L, db.leerLargo("PRAGMA user_version"), "$variante debe actualizar a Room18")
                    antes.forEach { (tabla, foto) ->
                        assertEquals(foto, fotografiar(db, tabla, foto.columnas), "$variante perdió datos de $tabla")
                    }
                    val columnas = columnas(db, "pending_payments")
                    assertEquals(33, columnas.size)
                    assertTrue("cashDrawerSessionId" in columnas)
                    assertEquals(if (variante == "d2") 8L else 0L,
                        db.leerLargo("SELECT COUNT(*) FROM pending_payments WHERE cashDrawerSessionId IS NOT NULL"))
                    assertEquals(if (variante == "ventas") 1L else 0L,
                        db.leerLargo("SELECT COUNT(*) FROM sales_notice_acknowledgements"))
                }
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    private fun sembrar(db: SQLiteConnection, variante: String) {
        val esquema = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/room17-originales/$variante/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/17.json"
        )).readText()).jsonObject.getValue("database").jsonObject
        fun insertar(tabla: String, n: Int, valores: Map<String, Any?>) {
            val campos = esquema.getValue("entities").jsonArray.single {
                it.jsonObject.getValue("tableName").jsonPrimitive.content == tabla
            }.jsonObject.getValue("fields").jsonArray
            val nombres = campos.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            db.prepare("INSERT INTO `$tabla` (${nombres.joinToString { "`$it`" }}) VALUES (${nombres.joinToString { "?" }})").use { s ->
                campos.forEachIndexed { i, campo ->
                    val nombre = nombres[i]
                    val valor = if (valores.containsKey(nombre)) valores[nombre]
                        else if (campo.jsonObject.getValue("affinity").jsonPrimitive.content == "INTEGER") 1700000000000L + n * 100 + i
                        else "original:$tabla:$nombre:$n"
                    when (valor) {
                        null -> s.bindNull(i + 1)
                        is Number -> s.bindLong(i + 1, valor.toLong())
                        else -> s.bindText(i + 1, valor.toString())
                    }
                }
                s.step()
            }
        }
        insertar("cash_drawer_sessions", 70, mapOf("id" to id(70), "venueId" to id(90), "status" to "CLOSED"))
        insertar("cash_drawer_events", 71, mapOf("id" to id(71), "sessionId" to id(70), "venueId" to id(90), "type" to "SALE"))
        repeat(8) { n ->
            insertar("pending_payments", n, mapOf("id" to id(n + 1), "venueId" to id(90), "staffId" to id(91),
                "method" to "CASH", "paymentType" to "ORDER", "syncStatus" to listOf("EN_VUELO", "PENDING", "FAILED", "SYNCED")[n % 4],
                "amountCents" to 11000, "tipCents" to 100, "cashTenderedCents" to 12000, "changeCents" to 900,
                "splitType" to "FULLPAYMENT", "orderId" to id(80), "capturedOrderRef" to id(80),
                "cashDrawerSessionId" to id(70), "itemsJson" to "[{\"id\":\"${id(81)}\",\"quantity\":1}]",
                "orderRequestJson" to "{\"externalId\":\"${id(n + 1)}\",\"total\":11100,\"tip\":100}",
                "q15CaptureJson" to "{\"version\":1,\"venueId\":\"${id(90)}\",\"key\":\"${id(n + 1)}\",\"amountCents\":11000,\"tipCents\":100,\"method\":\"CASH\"}",
                "q15InitialResultJson" to "{\"original\":$n}", "q15ResultJson" to "{\"result\":$n}",
                "q15Reason" to "DIFERENCIA_DE_COBRO", "q15ReviewedBy" to if (n % 2 == 0) id(92) else null))
        }
        repeat(2) { n -> insertar("pos_sync_intents", n, mapOf("id" to id(30 + n), "venue_id" to id(90),
            "staff_id" to id(91), "type" to if (n == 0) "PAY_CASH" else "CLOSE_SHIFT", "status" to "REJECTED",
            "payload_json" to "{\"orderId\":\"${id(80)}\",\"amountCents\":11000}",
            "q15CaptureJson" to "{\"original\":$n}", "q15ResultJson" to "{\"result\":$n}")) }
        if (variante == "ventas") insertar("sales_notice_acknowledgements", 1, mapOf("venueId" to id(90),
            "origin" to "PAYMENT", "id" to id(1), "actor" to id(92), "type" to "ORDER", "state" to "UNRESOLVED"))
    }

    private data class Foto(val columnas: List<String>, val filas: List<List<String?>>)
    private fun columnas(db: SQLiteConnection, tabla: String): List<String> = db.prepare("PRAGMA table_info(`$tabla`)").use { s ->
        buildList { while (s.step()) add(s.getText(1)) }
    }
    private fun fotografiar(db: SQLiteConnection): Map<String, Foto> =
        listOf("pending_payments", "pos_sync_intents", "cash_drawer_sessions", "cash_drawer_events", "sales_notice_acknowledgements")
            .mapNotNull { tabla -> columnas(db, tabla).takeIf { it.isNotEmpty() }?.let { tabla to fotografiar(db, tabla, it) } }.toMap()
    private fun fotografiar(db: SQLiteConnection, tabla: String, nombres: List<String>): Foto =
        db.prepare("SELECT ${nombres.joinToString { "`$it`" }} FROM `$tabla` ORDER BY id").use { s ->
            Foto(nombres, buildList { while (s.step()) add(nombres.indices.map { if (s.isNull(it)) null else s.getText(it) }) })
        }
    private fun id(n: Int) = "c" + n.toString().padStart(24, '0')
}
