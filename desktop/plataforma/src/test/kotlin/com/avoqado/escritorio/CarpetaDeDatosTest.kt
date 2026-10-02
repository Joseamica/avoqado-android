package com.avoqado.escritorio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CarpetaDeDatosTest {
    private val casa: Path = Files.createTempDirectory("casa de José Ñandú")

    private fun props(vararg p: Pair<String, String>): (String) -> String? {
        val m = mapOf("user.home" to casa.toString(), *p)
        return { m[it] }
    }

    @Test fun `en Windows usa APPDATA`() {
        val appdata = casa.resolve("AppData/Roaming")
        val c = CarpetaDeDatos.resolver(false, props("os.name" to "Windows 11"), { if (it == "APPDATA") appdata.toString() else null })
        assertEquals(appdata.resolve("Avoqado POS"), c)
        assertTrue(c.isDirectory())
    }

    @Test fun `en Windows sin APPDATA cae a la carpeta Roaming del usuario`() {
        val c = CarpetaDeDatos.resolver(false, props("os.name" to "Windows 10"), { null })
        assertEquals(casa.resolve("AppData").resolve("Roaming").resolve("Avoqado POS"), c)
    }

    @Test fun `APPDATA vacio o avoqado_datos vacio caen al respaldo`() {
        val roaming = casa.resolve("AppData").resolve("Roaming").resolve("Avoqado POS")
        assertEquals(roaming, CarpetaDeDatos.resolver(false, props("os.name" to "Windows 11", "avoqado.datos" to ""), { "" }))
    }

    @Test fun `en Mac usa Application Support`() {
        val c = CarpetaDeDatos.resolver(false, props("os.name" to "Mac OS X"), { null })
        assertEquals(casa.resolve("Library/Application Support/Avoqado POS"), c)
        assertTrue(c.isDirectory())
    }

    @Test fun `la propiedad avoqado_datos gana siempre`() {
        val propia = casa.resolve("prueba con espacios/ñ")
        val c = CarpetaDeDatos.resolver(false, props("os.name" to "Windows 11", "avoqado.datos" to propia.toString()), { "C:/nope" })
        assertEquals(propia, c)
        assertTrue(c.isDirectory())
    }

    // --- Build de producción: la carpeta segura, que la Store no borra al desinstalar ---

    @Test fun `en Windows produccion usa USERPROFILE punto avoqado-pos`() {
        val perfil = casa.resolve("perfil falso")
        val c = CarpetaDeDatos.resolver(true, props("os.name" to "Windows 11"), { if (it == "USERPROFILE") perfil.toString() else null })
        assertEquals(perfil.resolve(".avoqado-pos"), c)
        assertTrue(c.isDirectory())
    }

    @Test fun `en Windows produccion sin USERPROFILE cae a user_home`() {
        val c = CarpetaDeDatos.resolver(true, props("os.name" to "Windows 11"), { null })
        assertEquals(casa.resolve(".avoqado-pos"), c)
        val vacio = CarpetaDeDatos.resolver(true, props("os.name" to "Windows 11"), { "" })
        assertEquals(casa.resolve(".avoqado-pos"), vacio)
    }

    @Test fun `en Windows prueba sigue en APPDATA`() {
        val appdata = casa.resolve("otro/Roaming")
        val c = CarpetaDeDatos.resolver(false, props("os.name" to "Windows 11"), { if (it == "APPDATA") appdata.toString() else null })
        assertEquals(appdata.resolve("Avoqado POS"), c)
    }

    @Test fun `avoqado_datos gana tambien en produccion`() {
        val propia = casa.resolve("propia")
        assertEquals(propia, CarpetaDeDatos.resolver(true, props("os.name" to "Windows 11", "avoqado.datos" to propia.toString()), { null }))
    }

    @Test fun `Mac produccion y prueba`() {
        assertEquals(casa.resolve("Library/Application Support/Avoqado POS Produccion"), CarpetaDeDatos.resolver(true, props("os.name" to "Mac OS X"), { null }))
        assertEquals(casa.resolve("Library/Application Support/Avoqado POS"), CarpetaDeDatos.resolver(false, props("os.name" to "Mac OS X"), { null }))
    }

    @Test fun `Linux produccion y prueba`() {
        assertEquals(casa.resolve(".avoqado-pos-produccion"), CarpetaDeDatos.resolver(true, props("os.name" to "Linux"), { null }))
        assertEquals(casa.resolve(".avoqado-pos"), CarpetaDeDatos.resolver(false, props("os.name" to "Linux"), { null }))
    }

    @Test fun `produccion y prueba nunca dan la misma ruta`() {
        for (so in listOf("Windows 11", "Mac OS X", "Linux")) {
            val entorno: (String) -> String? = { when (it) { "APPDATA" -> casa.resolve("r").toString(); "USERPROFILE" -> casa.toString(); else -> null } }
            assertNotEquals(CarpetaDeDatos.resolver(true, props("os.name" to so), entorno), CarpetaDeDatos.resolver(false, props("os.name" to so), entorno), so)
        }
    }

    // --- Rutas relativas (USERPROFILE=perfil, "C:") no valen: caerían en la carpeta del paquete ---

    @Test fun `USERPROFILE relativo o de unidad se ignora y cae a user_home`() {
        for (malo in listOf("perfil", "C:", "..\\x")) {
            val c = CarpetaDeDatos.resolver(true, props("os.name" to "Windows 11"), { if (it == "USERPROFILE") malo else null })
            assertEquals(casa.resolve(".avoqado-pos"), c, malo)
        }
    }

    @Test fun `APPDATA relativo se ignora y cae a la carpeta Roaming`() {
        for (malo in listOf("rel/Roaming", "C:")) {
            val c = CarpetaDeDatos.resolver(false, props("os.name" to "Windows 11"), { if (it == "APPDATA") malo else null })
            assertEquals(casa.resolve("AppData").resolve("Roaming").resolve("Avoqado POS"), c, malo)
        }
    }

    @Test fun `si no hay ninguna ruta absoluta lanza en vez de usar la carpeta actual`() {
        val relativas: (String) -> String? = { when (it) { "user.home" -> "casa relativa"; "os.name" -> "Windows 11"; else -> null } }
        assertFailsWith<IllegalStateException> { CarpetaDeDatos.resolver(true, relativas, { if (it == "USERPROFILE") "C:" else null }) }
        assertFailsWith<IllegalStateException> { CarpetaDeDatos.resolver(false, relativas, { if (it == "APPDATA") "C:" else null }) }
        assertFailsWith<IllegalStateException> { CarpetaDeDatos.resolver(true, { if (it == "os.name") "Linux" else if (it == "user.home") "rel" else null }, { null }) }
    }
}
