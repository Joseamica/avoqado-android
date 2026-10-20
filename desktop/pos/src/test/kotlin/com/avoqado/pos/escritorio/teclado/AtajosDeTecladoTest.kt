package com.avoqado.pos.escritorio.teclado

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.pos.designsystem.components.AtajoDeTeclado
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.BarreraDeAtajos
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import java.awt.event.InputEvent
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.KEY_RELEASED
import java.awt.event.KeyEvent.VK_F4
import java.awt.event.KeyEvent.VK_F5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Atajos de teclado del POS de Windows (founder, 19-oct): quién gana la tecla, y qué deja pasar la ventana.
 * Las pantallas del cobro con sus teclas: `AtajosDelCobroEnLaEscenaTest`.
 */
@OptIn(ExperimentalTestApi::class)
class AtajosDeTecladoTest {
    private val componente = object : java.awt.Component() {}
    private fun awt(tipo: Int, codigo: Int, mods: Int = 0): KeyEvent =
        teclaDeCompose(java.awt.event.KeyEvent(componente, tipo, 0L, mods, codigo, java.awt.event.KeyEvent.CHAR_UNDEFINED))

    // MARK: - La ventana

    @Test fun `sostener una tecla la cuenta una sola vez`() {
        var reloj = 0L
        val vistas = mutableListOf<Key>()
        val ventana = AtajosDeLaVentana(atender = { vistas += it; true }, reloj = { reloj })
        // Lo que `antes` se come (la repetición de F5) ya no llega ni a la pantalla ni a `despues`, como en la ventana.
        fun pasa(e: KeyEvent): Boolean = !ventana.antes(e) && ventana.despues(e)

        assertTrue(pasa(awt(KEY_PRESSED, VK_F5)))
        reloj += 500; assertFalse(pasa(awt(KEY_PRESSED, VK_F5)), "la repetición de Windows no vuelve a cobrar")
        reloj += 30; assertFalse(pasa(awt(KEY_PRESSED, VK_F5)))
        pasa(awt(KEY_RELEASED, VK_F5))
        reloj += 80; assertTrue(pasa(awt(KEY_PRESSED, VK_F5)), "soltar y volver a apretar sí cuenta")
        assertEquals(listOf(Key.F5, Key.F5), vistas)
    }

    @Test fun `P1 Windows tarda hasta un segundo en empezar a repetir - esa repeticion tampoco cuenta`() {
        var reloj = 0L
        var vistas = 0
        val ventana = AtajosDeLaVentana(atender = { vistas++; true }, reloj = { reloj })
        val esc = awt(KEY_PRESSED, java.awt.event.KeyEvent.VK_ESCAPE)
        ventana.antes(esc); ventana.despues(esc)
        reloj += 1_300   // retardo máximo del teclado de Windows + un despacho lento (Codex r1 #6)
        ventana.antes(esc); ventana.despues(esc)
        assertEquals(1, vistas)
    }

    @Test fun `P1 un Enter sostenido no llega a la pantalla - no confirma el efectivo que F5 abrio con el total`() {
        val ventana = AtajosDeLaVentana(atender = { true })
        val enter = awt(KEY_PRESSED, java.awt.event.KeyEvent.VK_ENTER)
        assertFalse(ventana.antes(enter), "el primer Enter sí llega a la hoja")
        assertTrue(ventana.antes(enter), "su repetición se la come la ventana (Codex r1 #1)")
        ventana.antes(awt(KEY_RELEASED, java.awt.event.KeyEvent.VK_ENTER))
        assertFalse(ventana.antes(enter), "soltar y volver a apretar sí llega")
    }

    @Test fun `P1 sostener F5 no abre la hoja y cobra exacto con su propia repeticion`() {
        val ventana = AtajosDeLaVentana(atender = { true })
        val f5 = awt(KEY_PRESSED, VK_F5)
        assertFalse(ventana.antes(f5))
        assertTrue(ventana.antes(f5), "la repetición de F5 no llega a «Monto recibido»")
    }

    @Test fun `si el soltar se perdió (la ventana perdió el foco), la tecla vuelve a contar al rato`() {
        var reloj = 0L
        var vistas = 0
        val ventana = AtajosDeLaVentana(atender = { vistas++; true }, reloj = { reloj })
        val f5 = awt(KEY_PRESSED, VK_F5)
        ventana.antes(f5); ventana.despues(f5)
        reloj += 5_000
        ventana.antes(f5); ventana.despues(f5)
        assertEquals(2, vistas)
    }

    @Test fun `P1 Alt+F4 nunca se consume - sigue cerrando la ventana`() {
        var vistas = 0
        val ventana = AtajosDeLaVentana(atender = { vistas++; true })
        val altF4 = awt(KEY_PRESSED, VK_F4, InputEvent.ALT_DOWN_MASK)
        ventana.antes(altF4)
        assertFalse(ventana.despues(altF4))
        assertEquals(0, vistas)
    }

    @Test fun `con Ctrl, Mayus o Windows tampoco hace nada`() {
        var vistas = 0
        val ventana = AtajosDeLaVentana(atender = { vistas++; true })
        for (mod in listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.SHIFT_DOWN_MASK, InputEvent.META_DOWN_MASK)) {
            val e = awt(KEY_PRESSED, VK_F5, mod)
            ventana.antes(e)
            assertFalse(ventana.despues(e))
            ventana.antes(awt(KEY_RELEASED, VK_F5))
        }
        assertEquals(0, vistas)
    }

    @Test fun `soltar la tecla no dispara nada`() {
        var vistas = 0
        val ventana = AtajosDeLaVentana(atender = { vistas++; true })
        val sube = awt(KEY_RELEASED, VK_F5)
        ventana.antes(sube)
        assertFalse(ventana.despues(sube))
        assertEquals(0, vistas)
    }

    // MARK: - Quién gana la tecla

    @Test fun `la pantalla de arriba gana y una hoja encima apaga lo de abajo`() = runComposeUiTest {
        var abajo = 0
        var arriba = 0
        var hoja by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                AtajoDeTeclado(Key.Escape) { abajo++ }
                AtajoDeTeclado(Key.F12) { abajo++ }
                AtajoDeTeclado(Key.Escape) { arriba++ }
                if (hoja) BarreraDeAtajos()
            }
        }
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.Escape)) }
        assertEquals(1 to 0, arriba to abajo)

        hoja = true
        runOnIdle {
            assertFalse(AtajosDeTeclado.atender(Key.Escape), "con la hoja abierta, Esc es de la hoja")
            assertFalse(AtajosDeTeclado.atender(Key.F12))
        }
        hoja = false
        runOnIdle { assertTrue(AtajosDeTeclado.atender(Key.F12)) }
        assertEquals(1 to 1, arriba to abajo)
    }

    @Test fun `P1 una hoja que YA estaba abierta apaga tambien lo que se registra despues`() = runComposeUiTest {
        // Codex r1 #4: el aviso «Venta anterior a medio cobrar» ya está; el cobro se monta después debajo de él.
        var cobro = 0
        var montado by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                BarreraDeAtajos()
                if (montado) AtajoDeTeclado(Key.F5) { cobro++ }
            }
        }
        montado = true
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F5)) }
        assertEquals(0, cobro)
    }

    @Test fun `un atajo apagado deja pasar al de abajo, y al salir de pantalla ya no responde`() = runComposeUiTest {
        var abajo = 0
        var arriba = 0
        var activo by mutableStateOf(false)
        var visible by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                AtajoDeTeclado(Key.Escape) { abajo++ }
                if (visible) AtajoDeTeclado(Key.Escape, activo = activo) { arriba++ }
            }
        }
        runOnIdle { AtajosDeTeclado.atender(Key.Escape) }
        activo = true
        runOnIdle { AtajosDeTeclado.atender(Key.Escape) }
        visible = false
        runOnIdle { AtajosDeTeclado.atender(Key.Escape) }
        assertEquals(1 to 2, arriba to abajo)
    }

    @Test fun `fuera de Windows no se registra nada`() = runComposeUiTest {
        var vistas = 0
        setContent { AtajoDeTeclado(Key.F12) { vistas++ } }
        runOnIdle { assertFalse(AtajosDeTeclado.atender(Key.F12)) }
        assertEquals(0, vistas)
    }
}
