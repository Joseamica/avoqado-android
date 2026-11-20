package com.avoqado.pos.escritorio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.window.WindowState
import com.avoqado.pos.onlineorders.data.VentanaALaVista
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D14 (Flautas, 20-nov): en WINDOWS el ciclo de vida no baja al minimizar, así que la pantalla de cocina (y la bandeja de
 * «Pedidos en línea») sólo se enteran por [VentanaALaVista]. Si `Main.kt` dejara de publicarla, el tablero minimizado volvería
 * a leer y a acusar por WiFi sin nadie mirando, y el SERVIDOR lo daría por conectado.
 */
@OptIn(ExperimentalTestApi::class)
class VentanaMinimizadaTest {
    @AfterTest fun limpiar() = VentanaALaVista.publicar(true)

    @Test fun `minimizar publica que la ventana no se ve, restaurar y cerrar vuelven a true`() = runComposeUiTest {
        val estado = WindowState()
        var abierta by mutableStateOf(true)
        setContent { if (abierta) PublicarSiLaVentanaSeVe(estado) }
        waitForIdle()
        assertTrue(VentanaALaVista.aLaVista.value)

        runOnIdle { estado.isMinimized = true }
        waitForIdle()
        assertFalse(VentanaALaVista.aLaVista.value, "minimizada: el tablero de cocina tiene que ocultarse")

        runOnIdle { estado.isMinimized = false }
        waitForIdle()
        assertTrue(VentanaALaVista.aLaVista.value, "restaurada: el tablero vuelve a leer")

        runOnIdle { estado.isMinimized = true }
        waitForIdle()
        runOnIdle { abierta = false } // la ventana se cierra minimizada
        waitForIdle()
        assertTrue(VentanaALaVista.aLaVista.value, "sin ventana se regresa al default de Android")
    }
}
