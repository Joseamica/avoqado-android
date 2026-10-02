package com.avoqado.pos.escritorio

import com.avoqado.escritorio.CarpetaDeOtroModo
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** El arranque real (lo que llaman Main y FasesE2E) comprueba la marca ANTES de abrir preferencias o la base (C3). */
class ModoDeLaCarpetaEnElArranqueTest {
    @Test fun `el build de prueba no abre una carpeta de produccion - lo dice y no crea preferencias ni base`() {
        val carpeta = Files.createTempDirectory("Avoqado POS de produccion ñ")
        carpeta.resolve(".modo-de-datos").writeText("produccion")
        val error = assertFailsWith<CarpetaDeOtroModo> { Arranque.abrir(carpeta) }   // las pruebas son build de PRUEBA
        assertTrue(Files.notExists(carpeta.resolve("shared_prefs")) && Files.notExists(carpeta.resolve("databases")))
        assertEquals("produccion", carpeta.resolve(".modo-de-datos").readText())
        assertEquals(
            "No se pudo abrir Avoqado POS: Esta carpeta es de la versión de producción de Avoqado POS; no se abre con ésta.\n" +
                "Carpeta: $carpeta\nNo se borró nada. Detalle en ${carpeta.resolve("logs")}",
            mensajeDeArranqueFallido(error, carpeta),
        )
    }

    @Test fun `un build de produccion no abre la carpeta con datos de una prueba vieja sin marca`() {
        val carpeta = Files.createTempDirectory("Avoqado POS prueba vieja ñ")
        val prefs = Files.createDirectories(carpeta.resolve("shared_prefs")).resolve("avoqado_secure_prefs.json")
        prefs.writeText("""{"pendingDrawerOps.v1":{"t":"s","v":"retiro-1"}}""")
        val error = assertFailsWith<CarpetaDeOtroModo> { Arranque.abrir(carpeta, produccion = true) }
        assertTrue(error.motivo.startsWith("Esta carpeta es de una versión de prueba de Avoqado POS; no se abre con ésta."), error.motivo)
        assertEquals("""{"pendingDrawerOps.v1":{"t":"s","v":"retiro-1"}}""", prefs.readText())
        assertTrue(Files.notExists(carpeta.resolve(".modo-de-datos")) && Files.notExists(carpeta.resolve("databases")))
    }
}
