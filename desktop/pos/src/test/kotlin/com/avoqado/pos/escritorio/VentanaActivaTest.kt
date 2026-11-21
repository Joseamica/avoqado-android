package com.avoqado.pos.escritorio

import com.avoqado.pos.onlineorders.data.VentanaActiva
import java.awt.DefaultKeyboardFocusManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D661 · Ruling 48: en WINDOWS la caja sólo le dice a la tienda «aquí avisan los pagados en línea» mientras una ventana suya es la
 * ACTIVA del sistema. Una caja sin minimizar pero tapada por otro programa (un navegador maximizado) no ve ni oye el aviso.
 * Las pruebas corren sin pantalla: el cambio de ventana activa se simula con el evento del administrador de foco.
 */
class VentanaActivaTest {
    private class Focos : DefaultKeyboardFocusManager() {
        fun activar(antes: Any?, ahora: Any?) = firePropertyChange("activeWindow", antes, ahora)
    }

    @AfterTest fun limpiar() = VentanaActiva.publicar(true)

    @Test fun `otro programa al frente apaga el aviso, la caja o uno de sus dialogos lo prende y cerrar vuelve a true`() {
        val focos = Focos()
        val soltar = escucharVentanaActiva(focos)
        assertFalse(VentanaActiva.activa.value, "sin ventana activa de la caja al arrancar")

        focos.activar(null, "caja")
        assertTrue(VentanaActiva.activa.value, "la caja es la ventana activa")

        focos.activar("caja", null)
        assertFalse(VentanaActiva.activa.value, "un navegador maximizado encima: la ventana activa ya no es de la caja")

        focos.activar(null, "dialogo de la caja")
        assertTrue(VentanaActiva.activa.value, "un diálogo de la caja cuenta como la caja")

        soltar()
        assertTrue(VentanaActiva.activa.value, "al cerrar, vuelve al true de ANDROID")
        focos.activar("dialogo de la caja", null)
        assertTrue(VentanaActiva.activa.value, "ya no escucha")
    }
}
