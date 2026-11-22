package com.avoqado.pos.escritorio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.onlineorders.data.VentanaActiva
import java.awt.DefaultKeyboardFocusManager
import java.nio.file.Files
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

    /**
     * Ruling 78: el arranque registra el vigilante antes de que exista la ventana; la señal sale de él en «no», así que su primera lectura
     * no se anuncia por una ventana que todavía no existe. La prende la ventana en cuanto es la activa.
     */
    @Test fun `al arrancar la caja no se anuncia hasta que su ventana sea la activa`() {
        VentanaActiva.publicar(true)   // como la deja ANDROID (y la prueba de arriba al soltar)
        val carpeta = Files.createTempDirectory("Avoqado POS ventana activa")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        val inyector = Inyector.crear(actividad)
        Escritorio.instalar(actividad, inyector)
        // Un ciclo sin arrancar: el vigilante queda registrado pero no lee (nada sale a la red en la prueba).
        val dueño = object : LifecycleOwner {
            val registro = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registro
        }
        Arranque.iniciar(inyector, dueño.lifecycle)
        assertFalse(VentanaActiva.activa.value, "sin ventana todavía: no se anuncia")

        val focos = Focos()
        val soltar = escucharVentanaActiva(focos)
        focos.activar(null, "caja")
        assertTrue(VentanaActiva.activa.value, "la ventana ya es la activa")
        soltar()
    }
}
