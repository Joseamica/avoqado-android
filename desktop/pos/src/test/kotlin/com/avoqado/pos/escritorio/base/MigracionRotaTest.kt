package com.avoqado.pos.escritorio.base

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigracionRotaTest {
    private class FallaDePrueba : RuntimeException("falla de prueba")

    @Test fun `si una migración truena a medias, Room revierte todo y el respaldo conserva los datos`() {
        val carpeta = Files.createTempDirectory("migracion-rota")
        try {
            val base = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
            val antes = ayudanteDeMigraciones(base).createDatabase(12).use { v12 ->
                SIEMBRA_V12.forEach { v12.execSQL(it) }
                v12.volcar("pending_payments")
            }
            val respaldo = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13))
            val rota = object : Migration(12, 13) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("UPDATE pending_payments SET amountCents = 1, syncStatus = 'SYNCED'")
                    connection.execSQL("CREATE TABLE testigo (x TEXT)")
                    throw FallaDePrueba()
                }
            }
            val room = Room.databaseBuilder<AvoqadoDatabase>(name = base.toString())
                .setDriver(BundledSQLiteDriver()).addMigrations(rota).build()
            val error = runCatching { runBlocking { room.pendingPaymentDao().getPendingPayments() } }.exceptionOrNull()
            assertTrue(generateSequence(error) { it.cause }.any { it is FallaDePrueba }, "la causa no es la migración de prueba: $error")
            runCatching { room.close() }

            BundledSQLiteDriver().open(base.toString()).use { c ->
                assertEquals(12L, c.leerLargo("PRAGMA user_version"))
                assertEquals(antes, c.volcar("pending_payments"))
                assertEquals(0L, c.leerLargo("SELECT COUNT(*) FROM sqlite_master WHERE name = 'testigo'"))
            }
            BundledSQLiteDriver().open(respaldo.path).use { c ->
                assertEquals(12L, c.leerLargo("PRAGMA user_version"))
                assertEquals(antes, c.volcar("pending_payments"))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }
}
