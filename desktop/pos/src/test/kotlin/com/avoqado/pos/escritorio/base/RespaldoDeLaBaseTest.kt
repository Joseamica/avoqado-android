package com.avoqado.pos.escritorio.base

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RespaldoDeLaBaseTest {
    private fun conTemporal(bloque: (Path) -> Unit) {
        val carpeta = Files.createTempDirectory("respaldo")
        try { bloque(carpeta) } finally { carpeta.toFile().deleteRecursively() }
    }

    private fun abrir(archivo: Path): SQLiteConnection = BundledSQLiteDriver().open(archivo.toString())

    private fun filasDe(archivo: Path, tabla: String): List<String> = abrir(archivo).use { c ->
        c.prepare("SELECT v FROM $tabla ORDER BY v").use { s -> buildList { while (s.step()) add(s.getText(0)) } }
    }

    @Test fun `una base vieja se respalda idéntica antes de migrar`() = conTemporal { carpeta ->
        val base = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
        // La conexión sigue abierta: la fila vive todavía en el -wal, que es justo el caso de un aparato en uso.
        abrir(base).use { c ->
            c.execSQL("PRAGMA journal_mode = WAL")
            c.execSQL("CREATE TABLE cobros (v TEXT NOT NULL)")
            c.execSQL("INSERT INTO cobros VALUES ('pago-1')")
            c.execSQL("PRAGMA user_version = 12")
            val respaldo = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, LocalDateTime.of(2026, 9, 30, 10, 15, 0)))
            assertEquals(base.parent.resolve("respaldos"), respaldo.toPath().parent)
            assertEquals("avoqado_db-v12-000001-20260930-101500", respaldo.name)
            assertEquals(listOf("pago-1"), filasDe(respaldo.toPath(), "cobros"))
        }
    }

    @Test fun `sin migración pendiente no hay respaldo`() = conTemporal { carpeta ->
        val base = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
        assertNull(respaldarSiVaAMigrar(base.toFile(), 13))   // base inexistente
        assertTrue(Files.notExists(base), "no debe crear la base")
        abrir(base).use { it.execSQL("PRAGMA user_version = 13") }
        assertNull(respaldarSiVaAMigrar(base.toFile(), 13))
        assertTrue(Files.notExists(base.parent.resolve("respaldos")))
    }

    private fun baseV12(carpeta: Path): Path =
        carpeta.resolve("databases/avoqado_db").also {
            Files.createDirectories(it.parent)
            abrir(it).use { c -> c.execSQL("CREATE TABLE cobros (v TEXT NOT NULL)"); c.execSQL("INSERT INTO cobros VALUES ('pago-1')"); c.execSQL("PRAGMA user_version = 12") }
        }

    private fun publicados(base: Path): List<String> =
        Files.list(base.parent.resolve("respaldos")).use { l -> l.map { it.fileName.toString() }.filter { !it.endsWith(".parcial") }.sorted().toList() }

    private fun parciales(base: Path): List<String> =
        Files.list(base.parent.resolve("respaldos")).use { l -> l.map { it.fileName.toString() }.filter { it.endsWith(".parcial") }.toList() }

    @Test fun `se quedan los 3 respaldos de secuencia más alta`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        (1..5).forEach { respaldarSiVaAMigrar(base.toFile(), 13, LocalDateTime.of(2026, 9, it, 8, 0, 0)) }
        assertEquals(
            listOf("avoqado_db-v12-000003-20260903-080000", "avoqado_db-v12-000004-20260904-080000", "avoqado_db-v12-000005-20260905-080000"),
            publicados(base),
        )
    }

    @Test fun `con el reloj atrasado el respaldo nuevo sobrevive y quedan 3`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        (1..3).forEach { respaldarSiVaAMigrar(base.toFile(), 13, LocalDateTime.of(2026, 9, 30, it, 0, 0)) }
        val nuevo = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, LocalDateTime.of(2026, 9, 29, 8, 0, 0)))
        assertTrue(nuevo.isFile, "el respaldo recién creado fue borrado por la poda")
        assertEquals(3, publicados(base).size)
        assertTrue(nuevo.name in publicados(base))
    }

    @Test fun `sólo cuentan los nombres con formato válido y los parciales viejos se limpian`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        val dir = base.parent.resolve("respaldos").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("notas.txt"), "x")
        Files.writeString(dir.resolve("avoqado_db-v12-sin-secuencia"), "x")
        Files.writeString(dir.resolve("viejo.parcial"), "x")
        (1..4).forEach { respaldarSiVaAMigrar(base.toFile(), 13, LocalDateTime.of(2026, 9, it, 8, 0, 0)) }
        assertEquals(listOf("avoqado_db-v12-000002-20260902-080000", "avoqado_db-v12-000003-20260903-080000", "avoqado_db-v12-000004-20260904-080000", "avoqado_db-v12-sin-secuencia", "notas.txt"), publicados(base))
        assertEquals(emptyList(), parciales(base))
    }

    @Test fun `dos intentos en el mismo segundo dan dos respaldos distintos y legibles`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        val ahora = LocalDateTime.of(2026, 9, 30, 10, 15, 0)
        val a = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, ahora))
        val b = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, ahora))
        assertTrue(a != b)
        listOf(a, b).forEach { assertEquals(listOf("pago-1"), filasDe(it.toPath(), "cobros")) }
    }

    @Test fun `un fallo durante la segunda copia deja intacto el primer respaldo y ningún parcial`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        val ahora = LocalDateTime.of(2026, 9, 30, 10, 15, 0)
        val primero = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, ahora))
        assertFailsWith<java.io.IOException> {
            respaldarSiVaAMigrar(base.toFile(), 13, ahora) { _, destino ->
                Files.writeString(destino, "a medias")
                throw java.io.IOException("disco lleno")
            }
        }
        assertEquals(listOf(primero.name), publicados(base))
        assertEquals(listOf("pago-1"), filasDe(primero.toPath(), "cobros"))
        assertEquals(emptyList(), parciales(base))
    }

    /** Codex (ronda b): si no se puede listar, la secuencia volvía a 000001 y en Windows ATOMIC_MOVE reemplaza. */
    @Test fun `si no se puede listar la carpeta de respaldos, lanza y no pisa el respaldo existente`() = conTemporal { carpeta ->
        val base = baseV12(carpeta)
        val ahora = LocalDateTime.of(2026, 9, 30, 10, 15, 0)
        val primero = assertNotNull(respaldarSiVaAMigrar(base.toFile(), 13, ahora))
        Files.writeString(base.parent.resolve("marca"), "x")   // la base cambia: si se volviera a copiar encima, se notaría
        abrir(base).use { it.execSQL("INSERT INTO cobros VALUES ('pago-2')") }
        assertFailsWith<java.io.IOException> { respaldarSiVaAMigrar(base.toFile(), 13, ahora, listar = { null }) }
        assertEquals(listOf(primero.name), publicados(base))
        assertEquals(listOf("pago-1"), filasDe(primero.toPath(), "cobros"))
        assertEquals(emptyList(), parciales(base))
    }

    @Test fun `un checkpoint que no se completa lanza y no publica respaldo`() = conTemporal { carpeta ->
        val base = carpeta.resolve("databases/avoqado_db").also { Files.createDirectories(it.parent) }
        abrir(base).use { escritor ->
            escritor.execSQL("PRAGMA journal_mode = WAL")
            escritor.execSQL("CREATE TABLE cobros (v TEXT NOT NULL)")
            escritor.execSQL("INSERT INTO cobros VALUES ('pago-1')")
            escritor.execSQL("PRAGMA user_version = 12")
            abrir(base).use { lector ->
                lector.execSQL("BEGIN")
                lector.prepare("SELECT v FROM cobros").use { it.step() }   // la lectura sigue abierta: fija el WAL
                escritor.execSQL("INSERT INTO cobros VALUES ('pago-2')")
                assertFailsWith<IllegalStateException> { respaldarSiVaAMigrar(base.toFile(), 13) }
                val dir = base.parent.resolve("respaldos")
                assertTrue(Files.notExists(dir) || Files.list(dir).use { it.count() } == 0L, "no debía publicar nada")
                lector.execSQL("COMMIT")
            }
        }
    }
}
