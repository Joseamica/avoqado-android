package com.avoqado.escritorio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarpetaDeDatosTest {
    private val casa: Path = Files.createTempDirectory("casa de José Ñandú")

    private fun props(vararg p: Pair<String, String>): (String) -> String? {
        val m = mapOf("user.home" to casa.toString(), *p)
        return { m[it] }
    }

    @Test fun `en Windows usa APPDATA`() {
        val appdata = casa.resolve("AppData/Roaming")
        val c = CarpetaDeDatos.resolver(props("os.name" to "Windows 11"), { if (it == "APPDATA") appdata.toString() else null })
        assertEquals(appdata.resolve("Avoqado POS"), c)
        assertTrue(c.isDirectory())
    }

    @Test fun `en Windows sin APPDATA cae a la carpeta Roaming del usuario`() {
        val c = CarpetaDeDatos.resolver(props("os.name" to "Windows 10"), { null })
        assertEquals(casa.resolve("AppData").resolve("Roaming").resolve("Avoqado POS"), c)
    }

    @Test fun `APPDATA vacio o avoqado_datos vacio caen al respaldo`() {
        val roaming = casa.resolve("AppData").resolve("Roaming").resolve("Avoqado POS")
        assertEquals(roaming, CarpetaDeDatos.resolver(props("os.name" to "Windows 11", "avoqado.datos" to ""), { "" }))
    }

    @Test fun `en Mac usa Application Support`() {
        val c = CarpetaDeDatos.resolver(props("os.name" to "Mac OS X"), { null })
        assertEquals(casa.resolve("Library/Application Support/Avoqado POS"), c)
        assertTrue(c.isDirectory())
    }

    @Test fun `la propiedad avoqado_datos gana siempre`() {
        val propia = casa.resolve("prueba con espacios/ñ")
        val c = CarpetaDeDatos.resolver(props("os.name" to "Windows 11", "avoqado.datos" to propia.toString()), { "C:/nope" })
        assertEquals(propia, c)
        assertTrue(c.isDirectory())
    }
}
