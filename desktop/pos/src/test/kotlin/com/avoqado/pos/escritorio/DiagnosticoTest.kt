package com.avoqado.pos.escritorio

import com.avoqado.pos.escritorio.dibujo.Ajuste
import com.avoqado.pos.escritorio.dibujo.Apertura
import com.avoqado.pos.escritorio.dibujo.Estado
import com.avoqado.pos.escritorio.dibujo.Modo
import com.avoqado.pos.escritorio.dibujo.ModoDeDibujo
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
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

    // MARK: - Cómo dibuja de verdad (hz:a5fa2439:D2)

    private fun lineas(motor: String, dibujo: Estado?): List<String> {
        val carpeta = Files.createTempDirectory("diag-dibujo")
        try {
            Diagnostico.escribir(carpeta, motor, 1234, "omitido: no es Windows", dibujo)
            return carpeta.resolve("logs/diagnostico.txt").readText().lines()
        } finally {
            carpeta.toFile().deleteRecursively()
        }
    }

    private fun estado(a: Ajuste, aMano: Boolean = false, fijo: Boolean = false, motor: String?, ms: Long?) =
        ModoDeDibujo.estado(a, Apertura(aMano = aMano, fijo = fijo, pidioSoftware = fijo || aMano, motor = motor, pintarMs = ms))

    @Test fun `la linea Dibujo dice motor, tiempo, modo y modo ligero`() {
        val l = lineas("OPENGL", estado(Ajuste(), motor = "OPENGL", ms = 9_000))
        assertTrue("Dibujo: OPENGL · pintar al abrir 9000 ms · modo automático · modo ligero no" in l, l.joinToString("\n"))
        // El veredicto de arriba ya usa la medición: OpenGL lento deja de salir con ✅.
        assertTrue(l.any { it.startsWith("⚠️ Gráficos: OPENGL: esta computadora tardó ") }, l.joinToString("\n"))
        assertFalse(l.any { it.startsWith("✅ Gráficos:") }, l.joinToString("\n"))
    }

    @Test fun `la linea Dibujo dice el modo rapido y el modo ligero`() {
        val l = lineas("SOFTWARE_FAST", estado(Ajuste(modo = Modo.RAPIDO), fijo = true, motor = "SOFTWARE_FAST", ms = 800))
        assertTrue("Dibujo: SOFTWARE_FAST · pintar al abrir 800 ms · modo rápido · modo ligero sí" in l, l.joinToString("\n"))
    }

    @Test fun `la linea Dibujo agrega lo que aplica, en orden`() {
        // Eligió el rápido en esta apertura: se aplica al volver a abrir.
        assertTrue(
            "Dibujo: OPENGL · pintar al abrir 9000 ms · modo rápido · modo ligero no · se aplica al volver a abrir" in
                lineas("OPENGL", estado(Ajuste(modo = Modo.RAPIDO), motor = "OPENGL", ms = 9_000)),
        )
        assertTrue(
            "Dibujo: SOFTWARE · pintar al abrir 900 ms · modo automático · modo ligero sí · fijado a mano" in
                lineas("SOFTWARE", estado(Ajuste(), aMano = true, motor = "SOFTWARE", ms = 900)),
        )
        assertTrue(
            "Dibujo: DIRECT3D · pintar al abrir 700 ms · modo automático · modo ligero no · el modo rápido falló" in
                lineas("DIRECT3D", estado(Ajuste(fallo = true), motor = "DIRECT3D", ms = 700)),
        )
        val todo = estado(Ajuste(), motor = "OPENGL", ms = 5_000).copy(aplicaAlReabrir = true, aMano = true, fallo = true)
        assertTrue(
            "Dibujo: OPENGL · pintar al abrir 5000 ms · modo automático · modo ligero no · se aplica al volver a abrir · fijado a mano · el modo rápido falló" in
                lineas("OPENGL", todo),
        )
    }

    @Test fun `no sugiere el modo rapido y a la vez dice que fallo`() {
        val l = lineas("OPENGL", estado(Ajuste(fallo = true), motor = "OPENGL", ms = 9_000))
        assertTrue(l.any { it.endsWith("· el modo rápido falló") }, l.joinToString("\n"))
        assertTrue(l.any { it.startsWith("⚠️ Gráficos: OPENGL: esta computadora tardó ") && it.endsWith(" en dibujar la pantalla al abrir.") }, l.joinToString("\n"))
        assertFalse(l.any { "Prueba el modo rápido" in it }, l.joinToString("\n"))
    }

    @Test fun `sin pintar dice sin medir, y sin dueno no hay linea`() {
        assertTrue("Dibujo: sin medir · modo automático · modo ligero no" in lineas("DIRECT3D", estado(Ajuste(), motor = null, ms = null)))
        assertFalse(lineas("DIRECT3D", null).any { it.startsWith("Dibujo:") })
    }
}
