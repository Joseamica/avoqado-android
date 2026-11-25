package com.avoqado.pos.escritorio.diagnostico

import com.avoqado.pos.escritorio.dibujo.Ajuste
import com.avoqado.pos.escritorio.dibujo.Apertura
import com.avoqado.pos.escritorio.dibujo.Estado
import com.avoqado.pos.escritorio.dibujo.Modo
import com.avoqado.pos.escritorio.dibujo.ModoDeDibujo
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VeredictoTest {
    private val buena = DatosDelEquipo(
        windows = true, versionSO = "10.0", ramGb = 15.8, nucleos = 12, procesador = "Intel(R) Core(TM) i7-10750H",
        discoLibreGb = 200.0, anchoPx = 1920, altoPx = 1080, puntosTactiles = 10,
        motorDeDibujo = "DIRECT3D", primerCuadroMs = 3000, toqueConElDedo = "puente activo",
    )

    private fun punto(v: Veredicto, nombre: String) = v.puntos.first { it.nombre == nombre }

    @Test fun `una PC buena sirve`() {
        assertEquals(Nivel.BIEN, veredicto(buena).global)
    }

    @Test fun `con 4 GB va lenta y recomienda 8 GB`() {
        val v = veredicto(buena.copy(ramGb = 3.9))
        assertEquals(Nivel.LENTA, v.global)
        assertTrue("8 GB" in punto(v, "Memoria RAM").texto)
    }

    @Test fun `con 2 GB no sirve`() {
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(ramGb = 2.0)).global)
    }

    @Test fun `Windows 8 no sirve`() {
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(versionSO = "6.3")).global)
    }

    @Test fun `motor por software va lento`() {
        assertEquals(Nivel.LENTA, veredicto(buena.copy(motorDeDibujo = "SOFTWARE_FAST")).global)
    }

    @Test fun `primer cuadro de 25 s no sirve y de 12 s va lento`() {
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(primerCuadroMs = 25_000)).global)
        assertEquals(Nivel.LENTA, veredicto(buena.copy(primerCuadroMs = 12_000)).global)
    }

    @Test fun `nucleos disco y pantalla`() {
        assertEquals(Nivel.LENTA, veredicto(buena.copy(nucleos = 2)).global)
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(nucleos = 1)).global)
        assertEquals(Nivel.LENTA, veredicto(buena.copy(discoLibreGb = 1.0)).global)
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(discoLibreGb = 0.2)).global)
        assertEquals(Nivel.LENTA, veredicto(buena.copy(anchoPx = 1024, altoPx = 768)).global)
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(anchoPx = 800, altoPx = 600)).global)
    }

    @Test fun `lo que no se pudo medir no cuenta como falla`() {
        val v = veredicto(
            DatosDelEquipo(true, "10.0", null, null, null, null, null, null, null, null, null, null),
        )
        assertEquals(Nivel.BIEN, v.global)
        assertEquals(Nivel.SIN_MEDIR, punto(v, "Memoria RAM").nivel)
        assertTrue("no se pudo medir" in punto(v, "Memoria RAM").texto)
    }

    @Test fun `fuera de Windows los puntos de Windows dicen no aplica`() {
        val v = veredicto(buena.copy(windows = false, versionSO = "15.0", puntosTactiles = null))
        assertEquals(Nivel.BIEN, v.global)
        assertTrue("no aplica" in punto(v, "Windows").texto)
    }

    @Test fun `sin puntos tactiles es informacion y no cambia el global`() {
        val v = veredicto(buena.copy(puntosTactiles = 0))
        assertEquals(Nivel.BIEN, v.global)
        val t = punto(v, "Pantalla táctil")
        assertEquals(Nivel.INFO, t.nivel)
        assertTrue("mouse" in t.texto)
    }

    // MARK: - Gráficos con la medición del primer cuadro (hz:a5fa2439:D2)

    private fun dibujo(motor: String?, ms: Long?, modo: Modo = Modo.AUTO, aMano: Boolean = false, fijo: Boolean = false) =
        ModoDeDibujo.estado(Ajuste(modo = modo), Apertura(aMano = aMano, fijo = fijo, pidioSoftware = fijo, motor = motor, pintarMs = ms))

    private fun graficos(e: Estado?, motorDeDibujo: String? = e?.motor) =
        punto(veredicto(buena.copy(motorDeDibujo = motorDeDibujo, dibujo = e)), "Gráficos")

    /** Los segundos salen con el formato de números de la computadora: la prueba fija uno para no depender de esta Mac. */
    private fun <T> conPunto(bloque: () -> T): T {
        val antes = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT)
        return try { bloque() } finally { Locale.setDefault(Locale.Category.FORMAT, antes) }
    }

    @Test fun `sin medicion el punto Graficos es el de hoy, byte a byte`() {
        val deHoy = mapOf(
            null to Punto("Gráficos", Nivel.SIN_MEDIR, "no se pudo medir (no cuenta en contra)."),
            "SOFTWARE_FAST" to Punto("Gráficos", Nivel.LENTA, "SOFTWARE_FAST: dibuja sin la tarjeta de video, las animaciones van a ir lentas. Actualiza el controlador de video."),
            "DIRECT3D" to Punto("Gráficos", Nivel.BIEN, "usa la tarjeta de video (DIRECT3D)."),
            "OPENGL" to Punto("Gráficos", Nivel.BIEN, "usa la tarjeta de video (OPENGL)."),
            "METAL" to Punto("Gráficos", Nivel.INFO, "METAL"),
        )
        for ((motor, esperado) in deHoy) {
            assertEquals(esperado, graficos(null, motor), "sin dibujo, $motor")
            // Con el dueño arrancado pero sin pintar todavía (o apagado): tampoco hay medición.
            assertEquals(esperado, graficos(dibujo(motor = null, ms = null), motor), "sin pintar, $motor")
            assertEquals(esperado, graficos(dibujo(motor = motor, ms = null), motor), "sin tiempo, $motor")
        }
        assertEquals(veredicto(buena).comoTexto(), veredicto(buena.copy(dibujo = dibujo(null, null))).comoTexto())
    }

    @Test fun `fluida usa la tarjeta de video`() {
        assertEquals(Punto("Gráficos", Nivel.BIEN, "usa la tarjeta de video (DIRECT3D)."), graficos(dibujo("DIRECT3D", 700)))
        assertEquals(Punto("Gráficos", Nivel.BIEN, "usa la tarjeta de video (OPENGL)."), graficos(dibujo("OPENGL", 900)))
    }

    @Test fun `ligera es el modo rapido`() {
        assertEquals(
            Punto("Gráficos", Nivel.LENTA, "SOFTWARE_FAST: modo rápido. La app dibuja sin la tarjeta de video y con menos animaciones."),
            graficos(dibujo("SOFTWARE_FAST", 900, modo = Modo.RAPIDO, fijo = true)),
        )
    }

    @Test fun `no alcanza aun en software no sirve`() {
        val e = dibujo("SOFTWARE_FAST", 9_000, modo = Modo.RAPIDO, fijo = true)
        assertEquals(
            Punto("Gráficos", Nivel.NO_SIRVE, "SOFTWARE_FAST: esta computadora tarda demasiado en dibujar la pantalla, aun en modo rápido. La app va a responder lento."),
            graficos(e),
        )
        assertEquals(Nivel.NO_SIRVE, veredicto(buena.copy(motorDeDibujo = "SOFTWARE_FAST", dibujo = e)).global)
    }

    @Test fun `OpenGL que no alcanza ya no da bien y ofrece el modo rapido`() = conPunto {
        val e = dibujo("OPENGL", 9_000)
        val p = graficos(e)
        assertNotEquals(Nivel.BIEN, p.nivel)
        assertEquals(
            Punto("Gráficos", Nivel.LENTA, "OPENGL: esta computadora tardó 9.0 s en dibujar la pantalla al abrir. Prueba el modo rápido en Más › Modo de dibujo."),
            p,
        )
        assertEquals(Nivel.LENTA, veredicto(buena.copy(motorDeDibujo = "OPENGL", dibujo = e)).global)
    }

    @Test fun `no alcanza sin ofrecer el modo rapido con Direct3D, a mano o ya en rapido`() = conPunto {
        val sinOferta = { m: String, s: String -> Punto("Gráficos", Nivel.LENTA, "$m: esta computadora tardó $s s en dibujar la pantalla al abrir.") }
        assertEquals(sinOferta("DIRECT3D", "12.3"), graficos(dibujo("DIRECT3D", 12_340)))
        assertEquals(sinOferta("OPENGL", "9.0"), graficos(dibujo("OPENGL", 9_000, aMano = true)))
        // Recién eligió el rápido: se aplica al volver a abrir, ya no se le ofrece.
        assertEquals(sinOferta("OPENGL", "9.0"), graficos(dibujo("OPENGL", 9_000, modo = Modo.RAPIDO)))
    }

    @Test fun `el texto empieza con el encabezado y trae una linea por punto`() {
        val v = veredicto(buena)
        val lineas = v.comoTexto().lines()
        assertEquals("✅ Esta PC sirve para Avoqado POS.", lineas[0])
        v.puntos.forEach { p -> assertTrue(lineas.any { it.endsWith("${p.nombre}: ${p.texto}") }, p.nombre) }
        assertTrue(veredicto(buena.copy(ramGb = 3.9)).comoTexto().startsWith("⚠️ "))
        assertTrue(veredicto(buena.copy(ramGb = 1.0)).comoTexto().startsWith("❌ "))
    }
}
