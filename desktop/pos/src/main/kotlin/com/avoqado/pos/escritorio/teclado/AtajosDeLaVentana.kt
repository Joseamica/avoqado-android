package com.avoqado.pos.escritorio.teclado

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Lo que la ventana de Windows le pasa a los atajos de teclado del cobro (`AtajosDeTeclado`, founder 19-oct).
 *
 * - [antes] va en `onPreviewKeyEvent`: lleva la cuenta de qué tecla está apretada y se come la REPETICIÓN de Enter,
 *   Esc y F1–F12 ([SIN_REPETICION]): sostener F5 no puede abrir «Monto recibido» y cobrar exacto con su propia
 *   repetición, ni un Enter sostenido confirmar algo (Codex r1 #1). Nadie en la app usa la repetición de esas teclas.
 * - [despues] va en `onKeyEvent`: recibe sólo lo que la pantalla NO usó (un campo de texto se queda sus dígitos, una
 *   hoja su Esc) y lo entrega una vez por apretón: sostener F5 no repite el cobro.
 * - Con Alt, Ctrl, Mayús o Windows no hace nada: Alt+F4 tiene que seguir cerrando la ventana.
 *
 * Si Windows se come el «soltar» (la ventana perdió el foco con la tecla abajo), la tecla vuelve a contar tras
 * [SOSTENIDA_MS] sin repeticiones. Windows tarda hasta ~1 s en empezar a repetir (retardo máximo del teclado) y luego
 * repite cada ~30 ms, así que una tecla sostenida nunca llega a 2 s sin evento (Codex r1 #6).
 */
class AtajosDeLaVentana(
    private val atender: (Key) -> Boolean,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private val abajo = mutableMapOf<Key, Long>()
    private var primeraVez = false

    fun antes(e: KeyEvent): Boolean {
        when (e.type) {
            KeyEventType.KeyDown -> {
                val ahora = reloj()
                val previa = abajo[e.key]
                primeraVez = previa == null || ahora - previa > SOSTENIDA_MS
                abajo[e.key] = ahora
                if (!primeraVez && e.key in SIN_REPETICION) return true
            }
            KeyEventType.KeyUp -> abajo.remove(e.key)
        }
        return false
    }

    fun despues(e: KeyEvent): Boolean =
        e.type == KeyEventType.KeyDown && primeraVez &&
            !e.isAltPressed && !e.isCtrlPressed && !e.isShiftPressed && !e.isMetaPressed &&
            atender(e.key)

    companion object {
        const val SOSTENIDA_MS = 2_000L
        val SIN_REPETICION = setOf(
            Key.Enter, Key.NumPadEnter, Key.Escape,
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
        )
    }
}
