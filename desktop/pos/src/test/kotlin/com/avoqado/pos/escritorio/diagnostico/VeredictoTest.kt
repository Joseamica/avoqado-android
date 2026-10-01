package com.avoqado.pos.escritorio.diagnostico

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun `el texto empieza con el encabezado y trae una linea por punto`() {
        val v = veredicto(buena)
        val lineas = v.comoTexto().lines()
        assertEquals("✅ Esta PC sirve para Avoqado POS.", lineas[0])
        v.puntos.forEach { p -> assertTrue(lineas.any { it.endsWith("${p.nombre}: ${p.texto}") }, p.nombre) }
        assertTrue(veredicto(buena.copy(ramGb = 3.9)).comoTexto().startsWith("⚠️ "))
        assertTrue(veredicto(buena.copy(ramGb = 1.0)).comoTexto().startsWith("❌ "))
    }
}
