package com.avoqado.escritorio

import org.junit.Assume
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferenciasEnArchivoTest {
    private val carpeta = Files.createTempDirectory("Avoqado POS José ñ")
    private fun abrir() = PreferenciasEnArchivo(carpeta.resolve("shared_prefs/avoqado prefs.json"))

    @Test fun `persiste todos los tipos entre instancias`() {
        abrir().edit().putString("s", "hola ñ").putBoolean("b", true).putInt("i", 7).putLong("l", 9_000_000_000L)
            .putFloat("f", 1.5f).putStringSet("set", setOf("a", "b")).commit()
        val p = abrir()
        assertEquals("hola ñ", p.getString("s", null))
        assertTrue(p.getBoolean("b", false))
        assertEquals(7, p.getInt("i", 0))
        assertEquals(9_000_000_000L, p.getLong("l", 0))
        assertEquals(1.5f, p.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), p.getStringSet("set", null))
        assertEquals(6, p.all.size)
    }

    @Test fun `apply tambien queda en disco y remove y clear funcionan`() {
        abrir().edit().putString("a", "1").putString("b", "2").apply()
        abrir().edit().remove("a").apply()
        assertNull(abrir().getString("a", null))
        assertEquals("2", abrir().getString("b", null))
        abrir().edit().clear().putString("c", "3").commit()   // Android: clear() se aplica ANTES que los put del mismo editor
        assertFalse(abrir().contains("b"))
        assertEquals("3", abrir().getString("c", null))
    }

    @Test fun `un default se devuelve cuando falta la llave`() {
        assertEquals("x", abrir().getString("nada", "x"))
        assertEquals(-1, abrir().getInt("nada", -1))
    }

    @Test fun `escrituras concurrentes no dejan el archivo roto`() {
        val p = abrir()
        (1..8).map { n -> thread { repeat(50) { p.edit().putInt("k$n", it).commit() } } }.forEach { it.join() }
        val q = abrir()
        (1..8).forEach { assertEquals(49, q.getInt("k$it", -1)) }
    }

    @Test fun `un fallo de disco devuelve false sin lanzar y sin temporales huerfanos`() {
        // 1) Carpeta sin permiso de escritura: ni el temporal se puede crear.
        val soloLectura = Files.createDirectories(carpeta.resolve("solo lectura"))
        val p = PreferenciasEnArchivo(soloLectura.resolve("prefs.json"))
        soloLectura.toFile().setWritable(false)
        try {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(soloLectura))
            assertFalse(p.edit().putString("token", "t").commit())
            p.edit().putString("token", "t2").apply()   // apply() tampoco lanza
        } finally {
            soloLectura.toFile().setWritable(true)
        }
        assertTrue(huerfanos(soloLectura).isEmpty())

        // 2) El temporal SÍ se escribió, pero el rename falla (el destino es una carpeta con algo dentro).
        val destino = carpeta.resolve("destino/prefs.json")
        val q = PreferenciasEnArchivo(destino)
        Files.createDirectories(destino.resolve("ocupado"))
        assertFalse(q.edit().putString("token", "t").commit())
        assertTrue(huerfanos(destino.parent).isEmpty(), "quedaron temporales: ${huerfanos(destino.parent)}")
    }

    @Test fun `un archivo truncado se aparta como corrupto y la app abre vacia`() {
        val archivo = carpeta.resolve("shared_prefs/avoqado prefs.json")
        Files.createDirectories(archivo.parent)
        Files.writeString(archivo, "{\"a\":")
        val p = abrir()
        assertTrue(p.all.isEmpty())
        val copias = Files.list(archivo.parent).use { s ->
            s.filter { it.fileName.toString().matches(Regex("avoqado prefs\\.corrupto-\\d{8}-\\d{6}\\.json")) }.toList()
        }
        assertEquals(1, copias.size, "copias: $copias")
        assertEquals("{\"a\":", Files.readString(copias.single()))
        assertTrue(p.edit().putString("b", "1").commit())
        assertEquals("1", abrir().getString("b", null))
    }

    @Test fun `un archivo sano que no se puede leer no se aparta ni se pisa, lanza`() {
        ContextoDeEscritorio(carpeta).getSharedPreferences("avoqado_secure_prefs", 0).edit().putString("token", "t-sano").commit()
        val archivo = carpeta.resolve("shared_prefs/avoqado_secure_prefs.json")
        val original = Files.readString(archivo)
        val otro = ContextoDeEscritorio(carpeta)   // contexto nuevo: todavía no lo tiene abierto
        Files.setPosixFilePermissions(archivo, PosixFilePermissions.fromString("-w-------"))
        try {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(archivo))
            assertFailsWith<IOException> { otro.getSharedPreferences("avoqado_secure_prefs", 0) }
        } finally {
            Files.setPosixFilePermissions(archivo, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(original, Files.readString(archivo))
        assertTrue(Files.list(archivo.parent).use { s -> s.noneMatch { ".corrupto-" in it.fileName.toString() } })
        assertEquals("t-sano", otro.getSharedPreferences("avoqado_secure_prefs", 0).getString("token", null))
    }

    @Test fun `si el contenido ilegible no se puede apartar, lanza y no toca nada`() {
        val sinEscritura = Files.createDirectories(carpeta.resolve("sin escritura"))
        val archivo = sinEscritura.resolve("prefs.json")
        Files.writeString(archivo, "{\"a\":")
        Files.setPosixFilePermissions(sinEscritura, PosixFilePermissions.fromString("r-x------"))   // ni move ni copy al .corrupto-
        try {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(sinEscritura))
            assertFailsWith<IOException> { PreferenciasEnArchivo(archivo) }
        } finally {
            Files.setPosixFilePermissions(sinEscritura, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals("{\"a\":", Files.readString(archivo))
        assertTrue(Files.list(sinEscritura).use { s -> s.noneMatch { ".corrupto-" in it.fileName.toString() } })
    }

    @Test fun `si no se puede saber si el archivo existe, lanza y no arranca vacio encima`() {
        val sinBusqueda = Files.createDirectories(carpeta.resolve("sin busqueda"))
        val archivo = sinBusqueda.resolve("prefs.json")
        PreferenciasEnArchivo(archivo).edit().putString("pendientes", "cobro-1").commit()
        val original = Files.readString(archivo)
        Files.setPosixFilePermissions(sinBusqueda, PosixFilePermissions.fromString("rw-------"))   // sin x: no se puede ni ver el archivo
        try {
            Assume.assumeFalse("corre como root: la existencia sí se puede saber", Files.exists(archivo) || Files.notExists(archivo))
            assertFailsWith<IOException> { PreferenciasEnArchivo(archivo) }
        } finally {
            Files.setPosixFilePermissions(sinBusqueda, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(original, Files.readString(archivo))
        assertEquals("cobro-1", PreferenciasEnArchivo(archivo).getString("pendientes", null))
    }

    @Test fun `reusar un Editor tras commit no repite sus cambios`() {
        val p = abrir()
        val e = p.edit()
        e.putInt("x", 1).clear().commit()
        p.edit().putInt("x", 2).putString("y", "vivo").commit()
        e.commit()
        assertEquals(2, abrir().getInt("x", -1))
        assertEquals("vivo", abrir().getString("y", null))
    }

    private fun huerfanos(dir: Path): List<Path> = Files.list(dir).use { s ->
        s.filter { it.fileName.toString().let { n -> n.startsWith(".prefs") && n.endsWith(".tmp") } }.toList()
    }
}
