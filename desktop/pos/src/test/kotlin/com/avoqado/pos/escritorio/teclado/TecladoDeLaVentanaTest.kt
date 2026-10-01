package com.avoqado.pos.escritorio.teclado

import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.im.InputMethodRequests
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TecladoDeLaVentanaTest {
    private fun solicitudNueva(): InputMethodRequests = java.lang.reflect.Proxy.newProxyInstance(
        InputMethodRequests::class.java.classLoader, arrayOf(InputMethodRequests::class.java),
    ) { _, _, _ -> null } as InputMethodRequests

    private var solicitud: InputMethodRequests? = null
    private var capa: Any? = null
    private val enviadas = mutableListOf<KeyEvent>()
    private val pendientes = mutableListOf<() -> Unit>()
    private val bitacora = mutableListOf<String>()

    private val lienzo = object : Component() {
        override fun getInputMethodRequests(): InputMethodRequests? = solicitud
    }

    @BeforeTest fun preparar() {
        TecladoDeLaVentana.reiniciar()
        TecladoDeLaVentana.programar = { _, f -> pendientes += f }
        TecladoDeLaVentana.anotar = { bitacora += it }
        TecladoDeLaVentana.capaEnfocada = { capa }
        // Como en producción: las teclas del panel van DIRECTO a la escena, no por el escucha de AWT.
        TecladoDeLaVentana.configurar(lienzo, null) { enviadas += it }
    }

    @AfterTest fun limpiar() = TecladoDeLaVentana.reiniciar()

    private fun correrPendientes() { val l = pendientes.toList(); pendientes.clear(); l.forEach { it() } }

    private fun tocarCampo() {
        TecladoDeLaVentana.alPuntero(Puntero.DEDO)
        solicitud = solicitudNueva()
        TecladoDeLaVentana.revisar()
    }

    private fun tecla(c: Char = 'a') = KeyEvent(lienzo, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, c)

    private fun toque() {
        TecladoDeLaVentana.alPuntero(Puntero.DEDO)
        TecladoDeLaVentana.alPuntero(Puntero.DEDO, soltando = true)
    }

    @Test fun `campo activo y ultimo puntero un dedo se ve`() {
        tocarCampo()
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `campo activo tras el mouse no se ve`() {
        TecladoDeLaVentana.alPuntero(Puntero.MOUSE)
        solicitud = solicitudNueva()
        TecladoDeLaVentana.revisar()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `las teclas del panel llegan al campo y no lo ocultan`() {
        tocarCampo()
        TecladoDeLaVentana.alPresionar(Tecla.Letra('a'))
        TecladoDeLaVentana.alPresionar(Tecla.Borrar)
        assertEquals(listOf(KeyEvent.KEY_TYPED, KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED), enviadas.map { it.id })
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `una tecla fisica de verdad lo oculta`() {
        tocarCampo()
        TecladoDeLaVentana.alEventoDeTecla(tecla())
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `tecla fisica y luego Tab a otro campo no lo muestra`() {
        tocarCampo()
        TecladoDeLaVentana.alEventoDeTecla(tecla())
        solicitud = solicitudNueva()   // el Tab pasó el foco a otro campo: sesión nueva
        TecladoDeLaVentana.revisar()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `soltar el dedo y un lector de codigos antes de 150 ms no lo reabre`() {
        tocarCampo()
        TecladoDeLaVentana.alEventoDeTecla(tecla())
        toque()
        TecladoDeLaVentana.alEventoDeTecla(tecla('1'))   // el lector llega antes de que venza la reapertura
        correrPendientes()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `soltar una tecla y tocar ocultar antes de que venza no lo reabre`() {
        tocarCampo()
        toque()                                          // el dedo sobre «ocultar» se levanta (reapertura pendiente)…
        TecladoDeLaVentana.alPresionar(Tecla.Ocultar)    // …y el toque llega a la tecla
        correrPendientes()
        assertFalse(TecladoDeLaVentana.visible.value)
        toque()                                          // un toque NUEVO sobre el campo sí lo reabre
        correrPendientes()
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `un clic de mouse siempre lo oculta`() {
        tocarCampo()
        TecladoDeLaVentana.alPuntero(Puntero.MOUSE)
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `un deslizamiento con el foco retenido no lo reabre, un toque sí`() {
        tocarCampo()
        TecladoDeLaVentana.alEventoDeTecla(tecla())
        TecladoDeLaVentana.alPuntero(Puntero.DEDO)
        TecladoDeLaVentana.alPuntero(Puntero.DEDO, soltando = true, fueToque = false)
        correrPendientes()
        assertFalse(TecladoDeLaVentana.visible.value)
        toque()
        correrPendientes()
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `al perder el foco la ventana se oculta y cancela la reapertura pendiente`() {
        tocarCampo()
        toque()
        TecladoDeLaVentana.alPerderFoco()
        correrPendientes()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `con la ventana inactiva no se muestra`() {
        TecladoDeLaVentana.ventanaActiva = { false }
        tocarCampo()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    @Test fun `una sesion nueva sube la generacion del popup, la misma no`() {
        tocarCampo()
        val g = TecladoDeLaVentana.generacion.value
        TecladoDeLaVentana.revisar()
        assertEquals(g, TecladoDeLaVentana.generacion.value)
        solicitud = solicitudNueva()
        TecladoDeLaVentana.revisar()
        assertNotEquals(g, TecladoDeLaVentana.generacion.value)
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `una capa nueva con la misma sesion lo oculta`() {
        tocarCampo()
        capa = Any()
        TecladoDeLaVentana.revisar()
        assertFalse(TecladoDeLaVentana.visible.value)
    }

    /** Codex (ronda b, punto 2): tras ocultarse por un diálogo, un toque en el diálogo reabría el teclado del campo de atrás. */
    @Test fun `con un dialogo encima un toque no reabre el teclado del campo de atras, al cerrarlo tocar el campo si`() {
        tocarCampo()
        val principal = capa
        capa = Any()                       // se abrió un diálogo: misma sesión, otra capa
        TecladoDeLaVentana.revisar()
        assertFalse(TecladoDeLaVentana.visible.value)
        toque()                            // un toque en un botón del diálogo
        correrPendientes()
        assertFalse(TecladoDeLaVentana.visible.value)
        capa = principal                   // se cerró el diálogo
        TecladoDeLaVentana.revisar()
        toque()                            // tocar otra vez el campo
        correrPendientes()
        assertTrue(TecladoDeLaVentana.visible.value)
    }

    @Test fun `la bitacora no guarda lo tecleado`() {
        tocarCampo()
        "secreto".forEach { TecladoDeLaVentana.alPresionar(Tecla.Letra(it)) }
        TecladoDeLaVentana.alPresionar(Tecla.Enter)
        assertTrue(bitacora.isNotEmpty())
        bitacora.forEach { l ->
            assertFalse(l.contains("secreto") || l.contains("Letra") || l.contains("Escribir") || l.contains("tecla "), "la línea «$l» revela lo tecleado")
        }
    }

    @Test fun `desinstalar es idempotente, suelta todo y reiniciar limpia el estado de las teclas`() {
        tocarCampo()
        toque()
        TecladoDeLaVentana.desinstalar()
        TecladoDeLaVentana.desinstalar()
        correrPendientes()
        TecladoDeLaVentana.alPresionar(Tecla.Letra('a'))   // sin escritor: no lanza ni envía
        assertTrue(enviadas.isEmpty())
        TecladoDeLaVentana.estado.presionar(Tecla.Mayus); TecladoDeLaVentana.estado.presionar(Tecla.Pagina)
        TecladoDeLaVentana.reiniciar()
        assertFalse(TecladoDeLaVentana.estado.mayus)
        assertEquals(0, TecladoDeLaVentana.estado.pagina)
    }
}
