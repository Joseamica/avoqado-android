package com.avoqado.pos.pos.data

import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El puente Android del lector de pistola: qué tecla se consume, cuál pasa y cuándo
 * sale un código por [LectorHidBus.codigos].
 *
 * Se prueba por la sobrecarga con enteros porque en los tests JVM `KeyEvent` es un stub
 * (`isReturnDefaultValues`) que devuelve ceros: un `KeyEvent` real aquí no dice nada.
 */
class LectorHidBusTest {

    private companion object {
        const val LECTOR = 7
    }

    private fun LectorHidBus.abajo(c: Char, ms: Long, deviceId: Int = LECTOR, repeat: Int = 0): Boolean =
        procesar(KeyEvent.ACTION_DOWN, keyCodeDe(c), c.code, deviceId, ms, repeat)

    private fun LectorHidBus.arriba(c: Char, ms: Long, deviceId: Int = LECTOR): Boolean =
        procesar(KeyEvent.ACTION_UP, keyCodeDe(c), c.code, deviceId, ms, 0)

    private fun LectorHidBus.enter(ms: Long, keyCode: Int = KeyEvent.KEYCODE_ENTER, deviceId: Int = LECTOR): Boolean =
        procesar(KeyEvent.ACTION_DOWN, keyCode, '\n'.code, deviceId, ms, 0)

    /** Escribe una ráfaga como la de un lector: 10 ms entre teclas. */
    private fun LectorHidBus.rafaga(texto: String, desdeMs: Long = 1_000, deviceId: Int = LECTOR): List<Boolean> =
        texto.mapIndexed { i, c -> abajo(c, desdeMs + i * 10L, deviceId) }

    private fun keyCodeDe(c: Char): Int = if (c.isDigit()) KeyEvent.KEYCODE_0 + (c - '0') else KeyEvent.KEYCODE_A + (c - 'a')

    @Test
    fun `una rafaga que termina en Enter sale por codigos y el Enter se consume`() = runTest {
        val bus = LectorHidBus()

        bus.codigos.test {
            bus.rafaga("7501055310838")
            val enterConsumido = bus.enter(ms = 1_140)

            assertTrue(enterConsumido)
            assertEquals("7501055310838", awaitItem())
        }
    }

    @Test
    fun `el ACTION_UP de una tecla consumida tambien se consume y el de una que paso no`() {
        val bus = LectorHidBus()

        val primera = bus.abajo('7', ms = 1_000)
        val segunda = bus.abajo('5', ms = 1_010)

        assertFalse(primera) // 🔴 la primera pasa: todavía podría ser una persona
        assertTrue(segunda)
        assertFalse(bus.arriba('7', ms = 1_005))
        assertTrue(bus.arriba('5', ms = 1_015))
        // Un segundo UP de la misma tecla ya no tiene DOWN consumido que emparejar.
        assertFalse(bus.arriba('5', ms = 1_020))
    }

    @Test
    fun `el teclado virtual nunca se consume ni emite`() = runTest {
        val bus = LectorHidBus()

        bus.codigos.test {
            val decisiones = bus.rafaga("7501055310838", deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD)
            val enter = bus.enter(ms = 1_140, deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD)

            assertEquals(List(13) { false }, decisiones)
            assertFalse(enter)
            expectNoEvents()
        }
    }

    @Test
    fun `Tab y el Enter del teclado numerico cierran el codigo igual que Enter`() = runTest {
        val bus = LectorHidBus()

        bus.codigos.test {
            bus.rafaga("7501055310838", desdeMs = 1_000)
            bus.enter(ms = 1_140, keyCode = KeyEvent.KEYCODE_TAB)
            assertEquals("7501055310838", awaitItem())

            bus.rafaga("0123456789abcdef0123456789abcdef0123456789abcdef", desdeMs = 5_000)
            bus.enter(ms = 5_490, keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER)
            assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef", awaitItem())
        }
    }

    @Test
    fun `una tecla sostenida que se repite sola no forma una rafaga`() = runTest {
        // Android reenvía la tecla sostenida cada ~50 ms con repeatCount > 0: sin este
        // caso, sostener la "a" y dar Enter escanearía "aaaaaaa".
        val bus = LectorHidBus()

        bus.codigos.test {
            assertFalse(bus.abajo('a', ms = 1_000))
            repeat(8) { i -> assertFalse(bus.abajo('a', ms = 1_050 + i * 50L, repeat = i + 1)) }
            assertFalse(bus.enter(ms = 1_460))
            expectNoEvents()
        }
    }

    @Test
    fun `un modificador a media rafaga no la parte`() = runTest {
        // Un lector que escribe "ABCDEF" manda Shift↓ A↓ A↑ Shift↑ por cada letra: el Shift
        // llega con unicodeChar 0. Sin este caso, cualquier código con mayúsculas se cortaría.
        val bus = LectorHidBus()

        bus.codigos.test {
            val texto = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
            texto.forEachIndexed { i, c ->
                val ms = 1_000 + i * 10L
                if (c.isUpperCase()) {
                    val shift = bus.procesar(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, LECTOR, ms - 3, 0)
                    assertFalse(shift)
                }
                bus.procesar(KeyEvent.ACTION_DOWN, keyCodeDe(c.lowercaseChar()), c.code, LECTOR, ms, 0)
                if (c.isUpperCase()) {
                    assertFalse(bus.procesar(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, LECTOR, ms + 3, 0))
                }
            }
            bus.enter(ms = 1_000 + texto.length * 10L)

            assertEquals(texto, awaitItem())
        }
    }

    @Test
    fun `una tecla que no escribe nada pasa y parte la rafaga`() = runTest {
        val bus = LectorHidBus()

        bus.codigos.test {
            bus.rafaga("750", desdeMs = 1_000)
            // Flecha: unicodeChar 0.
            val flecha = bus.procesar(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, LECTOR, 1_030, 0)
            val enter = bus.enter(ms = 1_040)

            assertFalse(flecha)
            assertFalse(enter)
            expectNoEvents()
        }
    }
}
