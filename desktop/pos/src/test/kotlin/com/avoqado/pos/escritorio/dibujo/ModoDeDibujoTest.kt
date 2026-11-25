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
        val a = A.copy(modo = Modo.RAPIDO, intentando = true, sinAviso = true)
        val esperado = AlArrancar(A.copy(modo = Modo.AUTO, fallo = true), false)
        assertEquals(esperado, ModoDeDibujo.alArrancar(a, aMano = false))
        assertEquals(esperado, ModoDeDibujo.alArrancar(a, aMano = true))
    }

    // MARK: - alPintar

    @Test fun `fijo y pinto con software - quita la marca`() {
        val a = A.copy(modo = Modo.RAPIDO, intentando = true)
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alPintar(a, fijo = true, aMano = false, motorReal = "SOFTWARE_FAST"))
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alPintar(a, fijo = true, aMano = false, motorReal = "SOFTWARE_COMPAT"))
    }

    @Test fun `fijo pero Skiko abrio con otro motor - regresa a automatico con fallo`() {
        val a = A.copy(modo = Modo.RAPIDO, intentando = true)
        assertEquals(A.copy(modo = Modo.AUTO, fallo = true), ModoDeDibujo.alPintar(a, fijo = true, aMano = false, motorReal = "OPENGL"))
    }

    @Test fun `sin fijar el archivo no cambia al pintar`() {
        assertNull(ModoDeDibujo.alPintar(A, fijo = false, aMano = false, motorReal = "OPENGL"))
    }

    // MARK: - La pista: la computadora cae sola a software

    @Test fun `sin fijar ni mano, la primera vez que pinta en software se anota la pista`() {
        assertEquals(Ajuste(caeSolaASoftware = true), ModoDeDibujo.alPintar(A, fijo = false, aMano = false, motorReal = "SOFTWARE_FAST"))
    }

    @Test fun `con la pista y software otra vez el archivo no cambia`() {
        assertNull(ModoDeDibujo.alPintar(A.copy(caeSolaASoftware = true), fijo = false, aMano = false, motorReal = "SOFTWARE_COMPAT"))
    }

    @Test fun `con la pista y la tarjeta de video la pista se quita`() {
        assertEquals(Ajuste(), ModoDeDibujo.alPintar(A.copy(caeSolaASoftware = true), fijo = false, aMano = false, motorReal = "DIRECT3D"))
    }

    @Test fun `a mano no se anota la pista`() {
        assertNull(ModoDeDibujo.alPintar(A, fijo = false, aMano = true, motorReal = "SOFTWARE_FAST"))
    }

    @Test fun `fijo quita la marca y conserva la pista`() {
        assertEquals(
            Ajuste(Modo.RAPIDO, caeSolaASoftware = true),
            ModoDeDibujo.alPintar(Ajuste(Modo.RAPIDO, intentando = true, caeSolaASoftware = true), fijo = true, aMano = false, motorReal = "SOFTWARE_FAST"),
        )
    }

    @Test fun `elegir, callar el aviso y el modo seguro conservan la pista`() {
        val p = A.copy(caeSolaASoftware = true)
        assertTrue(ModoDeDibujo.alElegir(p, Modo.RAPIDO).caeSolaASoftware)
        assertTrue(ModoDeDibujo.alCallarAviso(p).caeSolaASoftware)
        assertEquals(true, ModoDeDibujo.alArrancar(p.copy(modo = Modo.RAPIDO, intentando = true), aMano = false).guardar?.caeSolaASoftware)
    }

    // MARK: - alElegir

    @Test fun `elegir rapido limpia el fallo y el silencio`() {
        assertEquals(A.copy(modo = Modo.RAPIDO), ModoDeDibujo.alElegir(A.copy(fallo = true, sinAviso = true), Modo.RAPIDO))
    }

    @Test fun `elegir automatico con la marca de intento puesta la quita`() {
        // Pintó, pero falló el guardado que quitaba la marca: el archivo nunca queda con intentando y AUTO.
        assertEquals(A.copy(modo = Modo.AUTO), ModoDeDibujo.alElegir(A.copy(modo = Modo.RAPIDO, intentando = true), Modo.AUTO))
    }

    // MARK: - alCallarAviso

    @Test fun `callar el aviso lo silencia y quita la marca de intento`() {
        // Un aviso sólo existe con la ventana ya pintada: callarlo nunca debe dejar el archivo rumbo al modo seguro.
        assertEquals(A.copy(modo = Modo.RAPIDO, sinAviso = true), ModoDeDibujo.alCallarAviso(A.copy(modo = Modo.RAPIDO, intentando = true)))
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
