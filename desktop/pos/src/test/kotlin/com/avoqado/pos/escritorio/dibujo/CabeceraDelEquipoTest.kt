package com.avoqado.pos.escritorio.dibujo

import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lo que la PC reporta en su sondeo (hz:a5fa2439:D1), con el formato exacto que lee el servidor. */
class CabeceraDelEquipoTest {
    private val pintada = Estado(
        motor = "OPENGL", pintarMs = 6200, modoLigero = false, rendimiento = Rendimiento.NO_ALCANZA, modo = Modo.AUTO,
        forzada = false, fallo = false, aMano = false, aplicaAlReabrir = false, aviso = Aviso.OFRECER_RAPIDO,
    )
    private val equipo = DatosDelEquipo(
        windows = true, versionSO = "10.0", ramGb = 3.87, nucleos = 4, procesador = "Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz",
        discoLibreGb = 40.0, anchoPx = 1366, altoPx = 768, puntosTactiles = 0,
        motorDeDibujo = null, primerCuadroMs = null, toqueConElDedo = null,
    )
    private val sinDatos = DatosDelEquipo(true, null, null, null, null, null, null, null, null, null, null, null)
    private val obligatorios = "v=1;proc=0a1b2c3d;rev=3;api=OPENGL;paint=6200;light=0;perf=INSUFFICIENT;set=AUTO;forced=0;fail=0;hand=0"

    private fun cabecera(e: Estado = pintada, d: DatosDelEquipo? = equipo, rev: Long = 3, windows: Boolean = true) =
        cabeceraDelEquipo(e, d, revision = rev, esWindows = windows, proceso = { "0a1b2c3d" })

    @Test fun `el ejemplo completo`() {
        assertEquals("$obligatorios;cpu=Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz;cores=4;ram=3.9;screen=1366x768", cabecera())
    }

    @Test fun `el procesador lleva sólo ASCII imprimible, sin punto y coma ni igual`() {
        assertEquals("$obligatorios;cpu=Procesadorrpidos", cabecera(d = sinDatos.copy(procesador = "Procesador®;rápido=sí")))
    }

    @Test fun `el procesador se recorta a 80 y la cabecera nunca pasa de 512`() {
        val e = pintada.copy(motor = "SOFTWARE_COMPAT", pintarMs = 999_999, rendimiento = Rendimiento.NO_ALCANZA)
        val d = equipo.copy(procesador = "X".repeat(600), nucleos = 512, ramGb = 4096.0, anchoPx = 99_999, altoPx = 99_999)
        val c = cabecera(e = e, d = d, rev = 1_000_000_000)!!
        assertTrue(";paint=120000;" in c, c)
        assertTrue(";cpu=${"X".repeat(80)};cores=512;ram=4096.0;screen=99999x99999" in c, c)
        assertTrue(c.length <= 512, "${c.length}")
    }

    @Test fun `si el filtro deja espacios al principio, el procesador conserva sus primeros 80 caracteres reales`() {
        val c = cabecera(d = sinDatos.copy(procesador = "®™   " + "Z".repeat(100)))!!
        assertTrue(c.endsWith(";cpu=${"Z".repeat(80)}"), c)
    }

    @Test fun `sin los datos del equipo (todavia no se leen) trae solo los pares obligatorios`() {
        assertEquals(obligatorios, cabecera(d = null))
    }

    @Test fun `fuera de Windows no hay cabecera`() = assertNull(cabecera(windows = false))

    @Test fun `sin pintar no hay cabecera`() {
        assertNull(cabecera(e = pintada.copy(motor = null)))
        assertNull(cabecera(e = pintada.copy(pintarMs = null)))
    }

    @Test fun `un motor o una revisión que el servidor no acepta no mandan cabecera`() {
        assertNull(cabecera(e = pintada.copy(motor = "VULKAN")))
        assertNull(cabecera(rev = 0))
    }

    @Test fun `modo rápido activo`() {
        val e = pintada.copy(motor = "SOFTWARE_FAST", pintarMs = 1800, modoLigero = true, rendimiento = Rendimiento.LIGERA, modo = Modo.RAPIDO, forzada = true, aviso = null)
        assertTrue(";api=SOFTWARE_FAST;paint=1800;light=1;perf=LIGHT;set=FAST;forced=1;fail=0;hand=0;" in cabecera(e = e)!!)
    }

    @Test fun `lo que no se conoce del equipo, o que el servidor no aceptaría, no viaja`() {
        assertEquals(obligatorios, cabecera(d = sinDatos))
        assertEquals(obligatorios, cabecera(d = sinDatos.copy(procesador = "®™", nucleos = 0, ramGb = -1.0, anchoPx = 1366, altoPx = 50)))
    }

    @Test fun `el id del proceso son 8 hex en minúscula`() = assertTrue(Regex("[0-9a-f]{8}").matches(ID_DE_PROCESO), ID_DE_PROCESO)

    @Test fun `dos cabeceras del mismo proceso llevan el mismo proc`() {
        val proc = { c: String? -> Regex("proc=([^;]*);").find(c!!)!!.groupValues[1] }
        val uno = proc(cabeceraDelEquipo(pintada, equipo, revision = 1, esWindows = true))
        val dos = proc(cabeceraDelEquipo(pintada, equipo, revision = 2, esWindows = true))
        assertEquals(uno, dos)
        assertTrue(Regex("^[0-9a-f]{8}$").matches(uno), uno)
    }

    @Test fun `fuera de Windows o sin pintar el id del proceso no se pide`() {
        var pedidos = 0
        val contar = { pedidos++; "0a1b2c3d" }
        assertNull(cabeceraDelEquipo(pintada, equipo, revision = 1, esWindows = false, proceso = contar))
        assertNull(cabeceraDelEquipo(pintada.copy(motor = null), equipo, revision = 1, esWindows = true, proceso = contar))
        assertEquals(0, pedidos)
    }
}
