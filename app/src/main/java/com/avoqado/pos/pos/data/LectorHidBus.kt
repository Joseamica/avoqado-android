package com.avoqado.pos.pos.data

import android.view.KeyCharacterMap
import android.view.KeyEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Puente entre las teclas que recibe la Activity y [LectorHid], compartido por toda la app.
 *
 * Es singleton porque las dos puntas no se conocen: el lector "entra" por
 * `MainActivity.dispatchKeyEvent` (que ve TODAS las teclas) y quien consume los códigos es
 * la pantalla de cobro. [codigos] es el canal entre ambas.
 *
 * 🔴 Sin interruptor, a propósito: se detecta por hardware. Sin lector conectado nunca
 * llega una ráfaga y nada cambia; un switch obligaría al negocio a configurar lo que se
 * puede deducir.
 *
 * Un código emitido cuando nadie escucha se descarta (sin replay): un escaneo hecho en
 * otra pantalla no debe aparecer de golpe al volver al cobro.
 */
@Singleton
class LectorHidBus @Inject constructor() {
    private val lector = LectorHid()
    private val _codigos = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val codigos: SharedFlow<String> = _codigos.asSharedFlow()

    /**
     * keyCodes cuyo ACTION_DOWN se consumió: su ACTION_UP debe consumirse también, o el
     * campo con foco recibe la mitad de una tecla.
     */
    private val consumidas = mutableSetOf<Int>()

    /** @return `true` si la tecla NO debe llegar a la UI. */
    fun procesar(event: KeyEvent): Boolean = procesar(
        accion = event.action,
        keyCode = event.keyCode,
        // `unicodeChar` ya aplica el metaState del propio evento (Shift → mayúscula).
        unicodeChar = event.unicodeChar,
        deviceId = event.deviceId,
        // `eventTime` es SystemClock.uptimeMillis(): monótono y viene con el evento.
        eventTimeMs = event.eventTime,
        repeatCount = event.repeatCount,
    )

    /**
     * La misma decisión, con los datos ya sacados del [KeyEvent]: así se prueba sin
     * Android (en los tests JVM `KeyEvent` es un stub que devuelve ceros).
     */
    internal fun procesar(
        accion: Int,
        keyCode: Int,
        unicodeChar: Int,
        deviceId: Int,
        eventTimeMs: Long,
        repeatCount: Int,
    ): Boolean {
        if (accion == KeyEvent.ACTION_UP) return consumidas.remove(keyCode)
        if (accion != KeyEvent.ACTION_DOWN) return false
        // Una tecla SOSTENIDA se repite sola cada ~50 ms: parecería una ráfaga sin serlo.
        // Un lector nunca repite; la persona que la sostiene sigue viendo su tecla.
        if (repeatCount > 0) return false
        // 🔴 Un lector que escribe una MAYÚSCULA manda Shift↓ A↓ A↑ Shift↑: el Shift llega como
        // tecla propia con `unicodeChar = 0`. Si contara como "tecla que no escribe nada",
        // partiría la ráfaga y ningún código con mayúsculas llegaría entero. Los modificadores
        // no se meten al lector ni se consumen: la letra ya trae el Shift aplicado.
        if (keyCode in MODIFICADORES) return false

        // El teclado en pantalla y `adb shell input` mandan deviceId -1 (VIRTUAL_KEYBOARD).
        val esFisico = deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && deviceId > 0
        val esTerminador = keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB
        // 0, un carácter de control o un acento muerto (bit COMBINING_ACCENT, negativo)
        // no escriben nada: se tratan como tecla que no imprime.
        val caracter = if (unicodeChar in 0x20..0xFFFF) Char(unicodeChar) else null

        return when (val decision = lector.procesar(caracter, esTerminador, esFisico, deviceId, eventTimeMs)) {
            TeclaHid.DejarPasar -> false
            TeclaHid.Consumir -> {
                consumidas += keyCode
                true
            }
            is TeclaHid.Codigo -> {
                _codigos.tryEmit(decision.texto)
                consumidas += keyCode
                true
            }
        }
    }

    private companion object {
        /** Teclas que cambian a otras sin escribir nada. Constantes, no `KeyEvent.isModifierKey`: en los tests JVM ese estático es un stub. */
        val MODIFICADORES = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
        )
    }
}
