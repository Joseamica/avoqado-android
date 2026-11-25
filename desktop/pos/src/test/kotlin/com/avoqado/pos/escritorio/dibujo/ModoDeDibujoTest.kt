package com.avoqado.pos.escritorio.dibujo

import com.avoqado.pos.escritorio.dibujo.ModoDeDibujo.AlArrancar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La tabla de la tarea 1 (hz:a5fa2439:D2): una prueba por renglón. `A` = `Ajuste()`. */
class ModoDeDibujoTest {
    private val A = Ajuste()
    private fun estado(a: Ajuste, ap: Apertura) = ModoDeDibujo.estado(a, ap)

    // MARK: - alArrancar

    @Test fun `automatico no guarda ni fija`() {
        assertEquals(AlArrancar(null, false), ModoDeDibujo.alArrancar(A, aMano = false))
    }

    @Test fun `rapido guarda la marca de intento y fija`() {
        val a = A.copy(modo = Modo.RAPIDO)
        assertEquals(AlArrancar(a.copy(intentando = true), true), ModoDeDibujo.alArrancar(a, aMano = false))
    }

    @Test fun `rapido con motor a mano no guarda ni fija`() {
        assertEquals(AlArrancar(null, false), ModoDeDibujo.alArrancar(A.copy(modo = Modo.RAPIDO), aMano = true))
    }

    @Test fun `la apertura rapida anterior no pinto - regresa a automatico con fallo, tambien a mano`() {
        val a = A.copy(modo = Modo.RAPIDO, intentando = true, ordenAplicada = 3, sinAviso = true)
        val esperado = AlArrancar(A.copy(modo = Modo.AUTO, ordenAplicada = 3, fallo = true), false)
        assertEquals(esperado, ModoDeDibujo.alArrancar(a, aMano = false))
        assertEquals(esperado, ModoDeDibujo.alArrancar(a, aMano = true))
    }

    // MARK: - alPintar

    @Test fun `fijo y pinto con software - quita la marca`() {
        val a = A.copy(modo = Modo.RAPIDO, intentando = true)
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alPintar(a, fijo = true, motorReal = "SOFTWARE_FAST"))
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alPintar(a, fijo = true, motorReal = "SOFTWARE_COMPAT"))
    }

    @Test fun `fijo pero Skiko abrio con otro motor - regresa a automatico con fallo`() {
        val a = A.copy(modo = Modo.RAPIDO, intentando = true)
        assertEquals(A.copy(modo = Modo.AUTO, fallo = true), ModoDeDibujo.alPintar(a, fijo = true, motorReal = "OPENGL"))
    }

    @Test fun `sin fijar el archivo no cambia al pintar`() {
        assertNull(ModoDeDibujo.alPintar(A, fijo = false, motorReal = "OPENGL"))
    }

    // MARK: - alElegir y alRecibirOrden

    @Test fun `elegir rapido limpia el fallo y el silencio`() {
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alElegir(A.copy(fallo = true, sinAviso = true), Modo.RAPIDO))
    }

    @Test fun `una orden mas nueva se aplica y queda acusada`() {
        assertEquals(
            A.copy(modo = Modo.RAPIDO, ordenAplicada = 3),
            ModoDeDibujo.alRecibirOrden(A.copy(ordenAplicada = 2), Modo.RAPIDO, 3),
        )
    }

    @Test fun `una orden igual o mas vieja se ignora`() {
        val a = A.copy(modo = Modo.RAPIDO, ordenAplicada = 3)
        assertNull(ModoDeDibujo.alRecibirOrden(a, Modo.AUTO, 3))
        assertNull(ModoDeDibujo.alRecibirOrden(a, Modo.AUTO, 2))
    }

    @Test fun `una orden mas nueva con el mismo modo tambien queda acusada`() {
        assertEquals(
            A.copy(modo = Modo.RAPIDO, ordenAplicada = 4),
            ModoDeDibujo.alRecibirOrden(A.copy(modo = Modo.RAPIDO, ordenAplicada = 3), Modo.RAPIDO, 4),
        )
    }

    // MARK: - estado

    @Test fun `Direct3D rapido es fluida, sin modo ligero ni aviso`() {
        val e = estado(A, Apertura(false, false, false, "DIRECT3D", 600))
        assertEquals(Rendimiento.FLUIDA, e.rendimiento)
        assertFalse(e.modoLigero)
        assertNull(e.aviso)
        assertFalse(e.aplicaAlReabrir)
    }

    @Test fun `Direct3D lento no alcanza pero no ofrece el modo rapido`() {
        val e = estado(A, Apertura(false, false, false, "DIRECT3D", 9000))
        assertEquals(Rendimiento.NO_ALCANZA, e.rendimiento)
        assertNull(e.aviso)
    }

    @Test fun `OpenGL lento ofrece el modo rapido`() {
        val e = estado(A, Apertura(false, false, false, "OPENGL", 9400))
        assertEquals(Rendimiento.NO_ALCANZA, e.rendimiento)
        assertEquals(Aviso.OFRECER_RAPIDO, e.aviso)
    }

    @Test fun `aviso callado no se ofrece`() {
        assertNull(estado(A.copy(sinAviso = true), Apertura(false, false, false, "OPENGL", 9400)).aviso)
    }

    @Test fun `lo acaba de elegir - aplica al reabrir y no ofrece`() {
        val e = estado(A.copy(modo = Modo.RAPIDO), Apertura(false, false, false, "OPENGL", 9400))
        assertNull(e.aviso)
        assertTrue(e.aplicaAlReabrir)
        assertFalse(e.forzada)
    }

    @Test fun `abrio en rapido y pinta bien - ligera y forzada`() {
        val e = estado(A.copy(modo = Modo.RAPIDO), Apertura(false, true, true, "SOFTWARE_FAST", 700))
        assertEquals(Rendimiento.LIGERA, e.rendimiento)
        assertTrue(e.modoLigero)
        assertTrue(e.forzada)
        assertFalse(e.aplicaAlReabrir)
        assertNull(e.aviso)
    }

    @Test fun `abrio en rapido y aun asi no alcanza`() {
        val e = estado(A.copy(modo = Modo.RAPIDO), Apertura(false, true, true, "SOFTWARE_FAST", 7000))
        assertEquals(Rendimiento.NO_ALCANZA, e.rendimiento)
        assertEquals(Aviso.NO_ALCANZA, e.aviso)
    }

    @Test fun `regreso a automatico en esta sesion - aplica al reabrir`() {
        assertTrue(estado(A.copy(modo = Modo.AUTO), Apertura(false, true, true, "SOFTWARE_FAST", 700)).aplicaAlReabrir)
    }

    @Test fun `con fallo avisa que el modo rapido no funciono`() {
        assertEquals(Aviso.FALLO_RAPIDO, estado(A.copy(fallo = true), Apertura(false, false, false, "OPENGL", 9400)).aviso)
    }

    @Test fun `fijo pero Skiko no pudo - no es forzada y avisa el fallo`() {
        val e = estado(A.copy(modo = Modo.AUTO, fallo = true), Apertura(false, true, true, "OPENGL", 9400))
        assertFalse(e.forzada)
        assertFalse(e.aplicaAlReabrir)
        assertEquals(Aviso.FALLO_RAPIDO, e.aviso)
    }

    @Test fun `motor a mano en software - ligero, nada aplica al reabrir y sin aviso`() {
        val e = estado(A.copy(modo = Modo.RAPIDO), Apertura(aMano = true, false, true, "SOFTWARE_FAST", 700))
        assertTrue(e.aMano)
        assertTrue(e.modoLigero)
        assertFalse(e.aplicaAlReabrir)
        assertNull(e.aviso)
    }

    @Test fun `motor a mano no ofrece el modo rapido`() {
        assertNull(estado(A, Apertura(true, false, false, "OPENGL", 9400)).aviso)
    }

    @Test fun `Skiko cayo solo a software y no alcanza`() {
        val e = estado(A, Apertura(false, false, false, "SOFTWARE_FAST", 9000))
        assertTrue(e.modoLigero)
        assertEquals(Aviso.NO_ALCANZA, e.aviso)
    }

    @Test fun `antes de pintar - sin motor ni rendimiento, ligero por lo pedido y sin aviso`() {
        val e = estado(A.copy(modo = Modo.RAPIDO), Apertura(false, true, true, null, null))
        assertNull(e.motor)
        assertNull(e.rendimiento)
        assertTrue(e.modoLigero)
        assertNull(e.aviso)
    }

    @Test fun `el tiempo de pintar se topa`() {
        assertEquals(120_000L, estado(A, Apertura(false, false, false, "OPENGL", 999_999)).pintarMs)
    }

    // MARK: - pideSoftware

    @Test fun `pideSoftware reconoce los alias de software de Skiko`() {
        for (valor in listOf("software", " SOFTWARE_FAST ", "DIRECT_SOFTWARE", "SOFTWARE_COMPAT")) {
            assertTrue(ModoDeDibujo.pideSoftware(valor), valor)
        }
        for (valor in listOf("OPENGL", "", null)) {
            assertFalse(ModoDeDibujo.pideSoftware(valor), valor.toString())
        }
    }
}
