package com.avoqado.escritorio

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandadoDeInstanciaTest {
    @Test fun `una segunda instancia sobre la misma carpeta se niega, y al morir la primera se libera`() {
        val carpeta = Files.createTempDirectory("Avoqado POS candado ñ")
        val java = ProcessHandle.current().info().command().get()
        val otro = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.avoqado.escritorio.ProcesoQueTomaElCandadoKt", carpeta.toString())
            .redirectErrorStream(true).start()
        try {
            assertEquals("TOMADO", otro.inputStream.bufferedReader().readLine())
            assertFalse(CandadoDeInstancia.tomar(carpeta), "dos procesos con la misma carpeta reproducirían la cola dos veces")
        } finally {
            otro.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
        assertTrue(CandadoDeInstancia.tomar(carpeta), "el sistema suelta el candado aunque el proceso muera a la fuerza")
    }
}
