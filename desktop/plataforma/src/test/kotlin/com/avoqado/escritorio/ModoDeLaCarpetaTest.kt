package com.avoqado.escritorio

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Producción y prueba nunca abren la carpeta del otro, ni con `-Davoqado.datos` (Codex, C3): una marca `.modo-de-datos`
 * dice de qué build es la carpeta, y se comprueba después del candado y antes de abrir preferencias o la base.
 */
class ModoDeLaCarpetaTest {
    private val carpeta: Path = Files.createTempDirectory("Avoqado POS modo José ñ")
    private val marca: Path = carpeta.resolve(".modo-de-datos")

    // --- La carpeta vacía (sin shared_prefs ni databases con archivos): se marca con el modo del build ---

    @Test fun `prueba con la carpeta vacia la marca prueba y sigue`() {
        CarpetaDeDatos.comprobarModo(carpeta, produccion = false)
        assertEquals("prueba", marca.readText().trim())
    }

    @Test fun `produccion con la carpeta vacia la marca produccion y sigue`() {
        CarpetaDeDatos.comprobarModo(carpeta, produccion = true)
        assertEquals("produccion", marca.readText().trim())
    }

    @Test fun `logs, el candado y carpetas vacias no son datos`() {
        Files.createDirectories(carpeta.resolve("logs")).resolve("avoqado-2026-10-02.log").writeText("arranque")
        carpeta.resolve(".instancia.lock").writeText("")
        Files.createDirectories(carpeta.resolve("shared_prefs"))
        Files.createDirectories(carpeta.resolve("databases"))
        CarpetaDeDatos.comprobarModo(carpeta, produccion = true)
        assertEquals("produccion", marca.readText().trim())
    }

    // --- Con datos y sin marca: son de un build de prueba anterior a la marca ---

    @Test fun `prueba con datos sin marca la marca prueba y sigue, sin tocar los datos`() {
        for (datos in listOf("shared_prefs/avoqado_secure_prefs.json", "databases/avoqado_db")) {
            val c = Files.createTempDirectory("Avoqado POS datos de prueba")
            Files.createDirectories(c.resolve(datos).parent)
            c.resolve(datos).writeText("cobro pendiente")
            CarpetaDeDatos.comprobarModo(c, produccion = false)
            assertEquals("prueba", c.resolve(".modo-de-datos").readText().trim(), datos)
            assertEquals("cobro pendiente", c.resolve(datos).readText(), datos)
        }
    }

    @Test fun `produccion con datos sin marca NO arranca y no toca nada`() {
        for (datos in listOf("shared_prefs/avoqado_secure_prefs.cifrado", "databases/avoqado_db")) {
            val c = Files.createTempDirectory("Avoqado POS datos sin marca")
            Files.createDirectories(c.resolve(datos).parent)
            c.resolve(datos).writeText("cola de prueba")
            val antes = foto(c)
            val e = assertFailsWith<CarpetaDeOtroModo>(datos) { CarpetaDeDatos.comprobarModo(c, produccion = true) }
            assertEquals("Esta carpeta es de una versión de prueba de Avoqado POS; no se abre con ésta.\nCarpeta: $c", e.motivo)
            assertEquals(antes, foto(c), "el rechazo tocó la carpeta")
        }
    }

    // --- Con marca ---

    @Test fun `con la marca de su mismo modo sigue y no la reescribe`() {
        for ((produccion, dice) in listOf(false to "prueba", true to "produccion")) {
            val c = Files.createTempDirectory("Avoqado POS marca igual")
            Files.createDirectories(c.resolve("shared_prefs")).resolve("avoqado_secure_prefs.json").writeText("sesión")
            c.resolve(".modo-de-datos").writeText("$dice\r\n")   // con fin de línea de Windows, como si se editara a mano
            val antes = foto(c)
            CarpetaDeDatos.comprobarModo(c, produccion)
            assertEquals(antes, foto(c), dice)
        }
    }

    @Test fun `con la marca del OTRO modo no arranca, lo dice con la ruta y no toca nada`() {
        val casos = listOf(
            Triple(true, "prueba", "Esta carpeta es de la versión de prueba de Avoqado POS; no se abre con ésta."),
            Triple(false, "produccion", "Esta carpeta es de la versión de producción de Avoqado POS; no se abre con ésta."),
        )
        for ((produccion, dice, aviso) in casos) {
            val c = Files.createTempDirectory("Avoqado POS marca distinta")
            Files.createDirectories(c.resolve("databases")).resolve("avoqado_db").writeText("ventas")
            c.resolve(".modo-de-datos").writeText(dice)
            val antes = foto(c)
            val e = assertFailsWith<CarpetaDeOtroModo>(dice) { CarpetaDeDatos.comprobarModo(c, produccion) }
            assertEquals("$aviso\nCarpeta: $c", e.motivo)
            assertEquals(antes, foto(c), "el rechazo tocó la carpeta")
        }
    }

    @Test fun `una marca que no se entiende no arranca en ningun modo y no se reescribe`() {
        for (produccion in listOf(false, true)) {
            val c = Files.createTempDirectory("Avoqado POS marca rara")
            c.resolve(".modo-de-datos").writeText("demo")
            val antes = foto(c)
            val e = assertFailsWith<CarpetaDeOtroModo> { CarpetaDeDatos.comprobarModo(c, produccion) }
            assertTrue("«demo»" in e.motivo && "Carpeta: $c" in e.motivo, e.motivo)
            assertEquals(antes, foto(c))
        }
    }

    // --- El caso de Codex: un -Davoqado.datos heredado ya no cruza de modo ---

    @Test fun `avoqado_datos apuntando a la carpeta de prueba no deja que produccion la abra (ni al reves)`() {
        val deCasa = Files.createTempDirectory("casa")
        fun resolver(produccion: Boolean, datos: Path) = CarpetaDeDatos.resolver(
            produccion,
            { mapOf("user.home" to deCasa.toString(), "os.name" to "Windows 11", "avoqado.datos" to datos.toString())[it] },
            { null },
        )
        val dePrueba = Files.createTempDirectory("Avoqado POS de prueba")
        CarpetaDeDatos.comprobarModo(resolver(false, dePrueba), produccion = false)
        Files.createDirectories(dePrueba.resolve("shared_prefs")).resolve("avoqado_secure_prefs.json").writeText("cola de prueba")
        assertFailsWith<CarpetaDeOtroModo> { CarpetaDeDatos.comprobarModo(resolver(true, dePrueba), produccion = true) }

        val deProduccion = Files.createTempDirectory("Avoqado POS de produccion")
        CarpetaDeDatos.comprobarModo(resolver(true, deProduccion), produccion = true)
        assertFailsWith<CarpetaDeOtroModo> { CarpetaDeDatos.comprobarModo(resolver(false, deProduccion), produccion = false) }
    }

    @Test fun `la marca se escribe atomica y no deja temporales`() {
        CarpetaDeDatos.comprobarModo(carpeta, produccion = false)
        val nombres = Files.list(carpeta).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(".modo-de-datos"), nombres)
    }

    /** Ruta relativa → bytes y fecha de TODO lo que hay (archivos y carpetas): si algo se crea, borra o cambia, cambia. */
    private fun foto(raiz: Path): Map<String, String> = Files.walk(raiz).use { s ->
        s.toList().associate { p ->
            val rel = raiz.relativize(p).toString()
            rel to (if (Files.isRegularFile(p)) Base64.getEncoder().encodeToString(Files.readAllBytes(p)) else "dir") +
                "@" + Files.getLastModifiedTime(p).toMillis()
        }
    }
}
