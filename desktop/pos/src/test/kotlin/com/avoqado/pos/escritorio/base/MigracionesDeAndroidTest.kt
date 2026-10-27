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
internal fun ayudanteDeMigraciones(archivo: Path, esquemas: Path = Path.of(System.getProperty("avoqado.esquemas"))) = MigrationTestHelper(
    schemaDirectoryPath = esquemas,
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
                assertEquals(antes, listOf(c.volcar("pending_payments").map { it.take(22) }, c.volcar("pos_sync_intents").map { it.take(11) }))
                c.prepare("SELECT capturedOrderRef,q15CaptureJson,q15ResultJson FROM pending_payments WHERE id='pago-1'").use { q ->
                    q.step();assertEquals("orden-9",q.getText(0));assertEquals(true,q.isNull(1));assertEquals(true,q.isNull(2))
                }
            }
            val respaldo = Files.list(archivo.parent.resolve("respaldos")).use { l -> l.toList() }.single()
            BundledSQLiteDriver().open(respaldo.toString()).use { c ->
                assertEquals(12L, c.leerLargo("PRAGMA user_version"))
                assertEquals(antes, listOf(c.volcar("pending_payments"), c.volcar("pos_sync_intents")))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }
}

/**
 * Las 32 columnas de `pending_payments` en v16, todas con valor distinto de cero/vacío, de un cobro sin red con su
 * evidencia Q15. Es la fila que tiene que llegar IDÉNTICA a v17, con la caja de la venta en nulo.
 */
internal val SIEMBRA_V16 =
    "INSERT INTO pending_payments (id, venueId, staffId, amountCents, tipCents, method, paymentType, orderId, orderNumber, cashTenderedCents, " +
        "changeCents, rating, itemsJson, orderRequestJson, syncStatus, retryCount, lastError, createdAt, lastRetryAt, tenderTypeId, tenderRevision, " +
        "customerId, splitType, q15CaptureJson, q15InitialResultJson, q15ResultJson, q15Reason, q15DetectedAt, q15ReviewedAt, q15ReviewedBy, " +
        "q15ReviewRole, capturedOrderRef) " +
        "VALUES ('pago-16', 'venue-1', 'staff-1', 8732, 450, 'CASH', 'ORDER', 'orden-9', 'A-17', 10000, 1268, 5, '[{\"p\":1}]', '{\"o\":2}', " +
        "'PENDING', 3, 'sin red', 1700000000000, 1700000099999, 'tender-7', 4, 'cliente-3', 'CUSTOMAMOUNT', '{\"version\":1}', '{\"i\":1}', " +
        "'{\"r\":1}', 'DIFERENCIA', 1700000000500, 1700000000600, 'gerente-1', 'MANAGER', 'orden-9')"

class MigracionCajaDeLaVentaTest {
    /** v17 (efectivo sin red en el turno correcto): una columna nula, nada que migrar. La fila vieja NO recibe una caja inventada. */
    @Test fun `un cobro sin internet guardado en v16 llega idéntico a v17 con la caja de la venta en nulo`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            val antes = ayudante.createDatabase(16).use { v16 ->
                v16.execSQL(SIEMBRA_V16)
                v16.volcar("pending_payments")
            }
            assertEquals(32, antes.single().size)
            ayudante.runMigrationsAndValidate(18, migracionesDeAndroid().map { it.paraRoom() }).use { v17 ->
                val despues = v17.volcar("pending_payments")
                assertEquals(33, despues.single().size)
                assertEquals(antes, despues.map { it.take(32) }, "Las 32 columnas de antes no cambian")
                v17.prepare("SELECT cashDrawerSessionId FROM pending_payments WHERE id='pago-16'").use { q ->
                    q.step(); assertEquals(true, q.isNull(0))
                }
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    /**
     * La primera captura manda, por el DAO real de Room (no un doble): la reserva guarda la caja; el encolado tras el fallo
     * vuelve a escribir la fila con la misma llave y otra caja (o ninguna), y en disco queda la de la reserva.
     */
    @Test fun `el reemplazo de la reserva conserva la caja de la reserva en el DAO real`() = runBlocking {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val base = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
            try {
                val dao = DatabaseModule.providePendingPaymentDao(base)
                val reserva = com.avoqado.pos.core.data.local.database.PendingPaymentEntity(
                    id = "llave-A", venueId = "venue-1", staffId = "staff-1", amountCents = 10_000, tipCents = 0,
                    method = "CASH", paymentType = "FAST", syncStatus = "EN_VUELO", createdAt = 1L,
                    cashDrawerSessionId = "caja-A-local",
                )
                dao.insert(reserva)
                dao.insert(reserva.copy(syncStatus = "PENDING", cashDrawerSessionId = "caja-B"))
                assertEquals("caja-A-local", dao.findForVenue("venue-1", "llave-A")?.cashDrawerSessionId)
                assertEquals("PENDING", dao.findForVenue("venue-1", "llave-A")?.syncStatus)
                dao.insert(reserva.copy(syncStatus = "PENDING", cashDrawerSessionId = null))
                assertEquals("caja-A-local", dao.findForVenue("venue-1", "llave-A")?.cashDrawerSessionId)
            } finally { base.close() }
        } finally { carpeta.toFile().deleteRecursively() }
    }
}

internal fun SQLiteConnection.leerLargo(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }
