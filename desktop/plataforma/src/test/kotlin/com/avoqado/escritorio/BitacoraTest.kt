package com.avoqado.escritorio

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BitacoraTest {
    @Test fun `Log de Android escribe en la bitacora de la carpeta de datos`() {
        val carpeta = Files.createTempDirectory("bitácora")
        Bitacora.iniciar(carpeta)
        android.util.Log.w("📡", "Network lost", RuntimeException("sin cable"))
        val texto = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()
        assertTrue("W/📡: Network lost" in texto, texto)
        assertTrue("sin cable" in texto, texto)
    }
}
