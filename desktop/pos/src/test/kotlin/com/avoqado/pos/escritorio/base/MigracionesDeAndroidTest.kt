package com.avoqado.pos.escritorio.base

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase_Impl
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Todas las columnas de las dos colas del aparato en v12 (22 y 11), con valores distintos de cero/vacío. */
internal val SIEMBRA_V12 = listOf(
    "INSERT INTO pending_payments (id, venueId, staffId, amountCents, tipCents, method, paymentType, orderId, orderNumber, cashTenderedCents, " +
        "changeCents, rating, itemsJson, orderRequestJson, syncStatus, retryCount, lastError, createdAt, lastRetryAt, tenderTypeId, tenderRevision, customerId) " +
        "VALUES ('pago-1', 'venue-1', 'staff-1', 8732, 450, 'CASH', 'FAST', 'orden-9', 'A-17', 10000, 1268, 5, '[{\"p\":1}]', '{\"o\":2}', " +
        "'PENDING', 3, 'sin red', 1700000000000, 1700000099999, 'tender-7', 4, 'cliente-3')",
    "INSERT INTO pos_sync_intents (id, venue_id, staff_id, seq, type, payload_json, status, error_code, message, result_json, created_at) " +
        "VALUES ('intento-1', 'venue-1', 'staff-2', 7, 'CLOSE_SHIFT', '{\"a\":1}', 'REJECTED', 'E42', 'ya cerrado', '{\"r\":true}', 1700000000001)",
)

/** Las filas completas de [tabla] como texto (NULL explícito), para comparar antes y después de migrar. */
internal fun SQLiteConnection.volcar(tabla: String): List<List<String>> = prepare("SELECT * FROM $tabla ORDER BY id").use { s ->
    buildList {
        while (s.step()) add((0 until s.getColumnCount()).map { if (s.isNull(it)) "NULL" else s.getText(it) })
    }
}

/** El ayudante de Room JVM sobre los JSON de esquema que exporta Android (app/schemas), en una base de [archivo]. */
internal fun ayudanteDeMigraciones(archivo: Path) = MigrationTestHelper(
    schemaDirectoryPath = Path.of(System.getProperty("avoqado.esquemas")),
    databasePath = archivo,
    driver = BundledSQLiteDriver(),
    databaseClass = AvoqadoDatabase::class,
    databaseFactory = { AvoqadoDatabase_Impl() },
)

class MigracionesDeAndroidTest {
    @Test fun `toma todas las migraciones de Android, en cadena del 1 a la versión de la base`() {
        val m = migracionesDeAndroid()
        assertEquals((1 until VERSION_DE_LA_BASE).toList(), m.map { it.startVersion })
        m.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
        assertEquals(VERSION_DE_LA_BASE, versionDeLaApp())
    }

    @Test fun `una base v3 llega a la versión de la app y Room la valida contra el esquema de Android`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            ayudante.createDatabase(3).close()
            val migrada = ayudante.runMigrationsAndValidate(versionDeLaApp(), migracionesDeAndroid().map { it.paraRoom() })
            assertEquals(versionDeLaApp().toLong(), migrada.leerLargo("PRAGMA user_version"))
            migrada.close()
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `un cobro sin internet guardado en v12 sigue ahí idéntico después de actualizar`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            val antes = ayudante.createDatabase(12).use { v12 ->
                SIEMBRA_V12.forEach { v12.execSQL(it) }
                listOf(v12.volcar("pending_payments"), v12.volcar("pos_sync_intents"))
            }
            assertEquals(22, antes[0].single().size)
            assertEquals(11, antes[1].single().size)
            ayudante.runMigrationsAndValidate(13, migracionesDeAndroid().map { it.paraRoom() }).use { v13 ->
                assertEquals(antes, listOf(v13.volcar("pending_payments"), v13.volcar("pos_sync_intents")))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `DatabaseModule abre una base v12, conserva las filas completas y deja un respaldo legible en v12`() = runBlocking {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val archivo = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
            val antes = ayudanteDeMigraciones(archivo).createDatabase(12).use { v12 ->
                SIEMBRA_V12.forEach { v12.execSQL(it) }
                listOf(v12.volcar("pending_payments"), v12.volcar("pos_sync_intents"))
            }
            val base = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
            assertEquals("pago-1", DatabaseModule.providePendingPaymentDao(base).getPendingPayments().single().id)
            base.close()

            BundledSQLiteDriver().open(archivo.toString()).use { c ->
                assertEquals(antes, listOf(c.volcar("pending_payments"), c.volcar("pos_sync_intents")))
            }
            val respaldo = Files.list(archivo.parent.resolve("respaldos")).use { l -> l.toList() }.single()
            BundledSQLiteDriver().open(respaldo.toString()).use { c ->
                assertEquals(12L, c.leerLargo("PRAGMA user_version"))
                assertEquals(antes, listOf(c.volcar("pending_payments"), c.volcar("pos_sync_intents")))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }
}

internal fun SQLiteConnection.leerLargo(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }
