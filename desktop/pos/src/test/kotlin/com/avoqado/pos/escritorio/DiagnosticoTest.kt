package com.avoqado.pos.escritorio

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class DiagnosticoTest {
    @Test fun `el diagnóstico dice si el dedo va por el puente, y si se apagó después`() {
        val carpeta = Files.createTempDirectory("diag")
        try {
            Diagnostico.escribir(carpeta, "DIRECT3D", 1234, "omitido: no es Windows")
            Diagnostico.anotar(carpeta, "Toque con el dedo: apagado: falla al entregar a Compose")
            val texto = carpeta.resolve("logs/diagnostico.txt").readText()
            assertTrue("Toque con el dedo: omitido: no es Windows" in texto, texto)
            assertTrue(texto.lines().contains("Toque con el dedo: apagado: falla al entregar a Compose"), texto)
            assertTrue("Motor de dibujo (resuelto): DIRECT3D" in texto, "anotar no debe borrar lo anterior: $texto")
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }
}
