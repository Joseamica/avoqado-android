package com.avoqado.escritorio

import org.junit.Assume
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferenciasEnArchivoTest {
    private val carpeta = Files.createTempDirectory("Avoqado POS José ñ")
    private fun abrir() = PreferenciasEnArchivo(carpeta.resolve("shared_prefs/avoqado prefs.json"))

    @Test fun `los permisos del fixture se restauran aunque el bloque falle`() {
        val archivo = carpeta.resolve("restaurar.json")
        Files.writeString(archivo, "original")
        assertFailsWith<IllegalStateException> {
            conPermisosDePrueba(archivo, "-w-------") { error("fallo simulado") }
        }
        assertEquals("original", Files.readString(archivo))
        Files.writeString(archivo, "nuevo")
        assertEquals("nuevo", Files.readString(archivo))
    }

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
        conPermisosDePrueba(soloLectura, "r-x------") {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(soloLectura))
            assertFalse(p.edit().putString("token", "t").commit())
            p.edit().putString("token", "t2").apply()   // apply() tampoco lanza
        }
        assertTrue(huerfanos(soloLectura).isEmpty())

        // 2) El temporal SÍ se escribió, pero el rename falla (el destino es una carpeta con algo dentro).
        val destino = carpeta.resolve("destino/prefs.json")
        val q = PreferenciasEnArchivo(destino)
        Files.createDirectories(destino.resolve("ocupado"))
        assertFalse(q.edit().putString("token", "t").commit())
        assertTrue(huerfanos(destino.parent).isEmpty(), "quedaron temporales: ${huerfanos(destino.parent)}")
    }

    @Test fun `un surrogate suelto no se guarda cambiado por un signo de interrogacion - commit da false y el archivo anterior queda intacto`() {
        val archivo = carpeta.resolve("shared_prefs/avoqado prefs.json")
        val p = abrir()
        assertTrue(p.edit().putString("cliente", "bien").commit())
        val antes = Files.readAllBytes(archivo)
        assertFalse(p.edit().putString("cliente", "emoji roto \uD83D").commit(), "se guardó algo distinto de lo que hay en memoria")
        assertContentEquals(antes, Files.readAllBytes(archivo))
        assertEquals("bien", abrir().getString("cliente", null))
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
        conPermisosDePrueba(archivo, "-w-------") {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(archivo))
            assertFailsWith<IOException> { otro.getSharedPreferences("avoqado_secure_prefs", 0) }
        }
        assertEquals(original, Files.readString(archivo))
        assertTrue(Files.list(archivo.parent).use { s -> s.noneMatch { ".corrupto-" in it.fileName.toString() } })
        assertEquals("t-sano", otro.getSharedPreferences("avoqado_secure_prefs", 0).getString("token", null))
    }

    @Test fun `si el contenido ilegible no se puede apartar, lanza y no toca nada`() {
        val sinEscritura = Files.createDirectories(carpeta.resolve("sin escritura"))
        val archivo = sinEscritura.resolve("prefs.json")
        Files.writeString(archivo, "{\"a\":")
        conPermisosDePrueba(sinEscritura, "r-x------") {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(sinEscritura))
            assertFailsWith<IOException> { PreferenciasEnArchivo(archivo) }
        }
        assertEquals("{\"a\":", Files.readString(archivo))
        assertTrue(Files.list(sinEscritura).use { s -> s.noneMatch { ".corrupto-" in it.fileName.toString() } })
    }

    @Test fun `si no se puede saber si el archivo existe, lanza y no arranca vacio encima`() {
        val sinBusqueda = Files.createDirectories(carpeta.resolve("sin busqueda"))
        val archivo = sinBusqueda.resolve("prefs.json")
        PreferenciasEnArchivo(archivo).edit().putString("pendientes", "cobro-1").commit()
        val original = Files.readString(archivo)
        conPermisosDePrueba(sinBusqueda, "rw-------") {
            Assume.assumeFalse("corre como root: la existencia sí se puede saber", Files.exists(archivo) || Files.notExists(archivo))
            assertFailsWith<IOException> { PreferenciasEnArchivo(archivo) }
        }
        assertEquals(original, Files.readString(archivo))
        assertEquals("cobro-1", PreferenciasEnArchivo(archivo).getString("pendientes", null))
    }

    @Test fun `un guardado que falla no se queda en memoria - el valor anterior sigue, la llave nueva no aparece y no se avisa`() {
        val dir = Files.createDirectories(carpeta.resolve("falla al guardar"))
        val archivo = dir.resolve("prefs.json")
        val p = PreferenciasEnArchivo(archivo)
        assertTrue(p.edit().putString("cola", "[op-1]").putInt("n", 1).commit())
        val avisos = mutableListOf<String?>()
        p.registerOnSharedPreferenceChangeListener { _, llave -> avisos += llave }
        val (_, bitacora) = bitacoraDe {
            sinEscrituraEn(dir) {
                assertFalse(p.edit().putString("cola", "[op-1,op-2-SECRETO]").putString("nueva", "x").remove("n").commit())
                assertEquals("[op-1]", p.getString("cola", null))
                assertFalse(p.contains("nueva"))
                assertEquals(1, p.getInt("n", -1))
                assertEquals(mapOf<String, Any>("cola" to "[op-1]", "n" to 1), p.all)
                assertFalse(p.edit().clear().commit(), "un clear() que no quedó en disco tampoco vacía la memoria")
                assertEquals("[op-1]", p.getString("cola", null))
            }
        }
        assertTrue(avisos.isEmpty(), "avisó a los oyentes de un cambio que no quedó en disco: $avisos")
        assertTrue("No se pudo guardar" in bitacora && "prefs.json" in bitacora && "AccessDeniedException" in bitacora, bitacora)
        assertFalse("SECRETO" in bitacora, "el valor llegó a la bitácora: $bitacora")
        // Al volver los permisos, un editor nuevo de la MISMA instancia guarda bien.
        assertTrue(p.edit().putString("cola", "[op-1,op-2]").commit())
        assertEquals(listOf<String?>("cola"), avisos)
        val releida = PreferenciasEnArchivo(archivo)
        assertEquals("[op-1,op-2]", releida.getString("cola", null))
        assertEquals(1, releida.getInt("n", -1))
    }

    @Test fun `el patron de la cola del cajon - guardar ignorando el booleano y releer - ve el valor viejo si el disco falla`() {
        // CashDrawerRepository.encolar: guarda con commit() (SecureStorage descarta el booleano) y RELEE para saber si quedó.
        val dir = Files.createDirectories(carpeta.resolve("cola del cajon"))
        val archivo = dir.resolve("avoqado_secure_prefs.json")
        val p = PreferenciasEnArchivo(archivo)
        fun encolar(op: String): Boolean {
            val lista = p.getString("pendingDrawerOps.v1", null)?.split(",").orEmpty() + op
            p.edit().putString("pendingDrawerOps.v1", lista.joinToString(",")).commit()
            return p.getString("pendingDrawerOps.v1", null)?.split(",")?.contains(op) == true
        }
        assertTrue(encolar("retiro-1"))
        sinEscrituraEn(dir) { assertFalse(encolar("retiro-2"), "la relectura dijo «guardado» sin nada en disco") }
        assertEquals("retiro-1", PreferenciasEnArchivo(archivo).getString("pendingDrawerOps.v1", null))
        assertTrue(encolar("retiro-2"))
        assertEquals("retiro-1,retiro-2", PreferenciasEnArchivo(archivo).getString("pendingDrawerOps.v1", null))
    }

    @Test fun `cifrado - un guardado que falla tampoco se queda en memoria ni toca el disco`() {
        val c = Carpeta()
        val p = c.abrir()
        assertTrue(p.edit().putString("pendingDrawerOps.v1", "retiro-1").commit())
        val antes = c.foto()
        c.sinEscritura {
            assertFalse(p.edit().putString("pendingDrawerOps.v1", "retiro-1,retiro-2").commit())
            assertEquals("retiro-1", p.getString("pendingDrawerOps.v1", null))
        }
        assertEquals(antes, c.foto())
        assertTrue(p.edit().putString("pendingDrawerOps.v1", "retiro-1,retiro-2").commit())
        assertEquals("retiro-1,retiro-2", c.abrir().getString("pendingDrawerOps.v1", null))   // releído del disco
    }

    /** Corre [bloque] con [dir] sin permiso de escritura (no se puede crear ni reemplazar nada dentro). */
    private fun sinEscrituraEn(dir: Path, bloque: () -> Unit) {
        conPermisosDePrueba(dir, "r-x------") {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(dir))
            bloque()
        }
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
