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

    /**
     * El lector INTEGRADO de las Sunmi (OrderPAD y similares) no entra por teclas: su modo
     * "teclado" teclea como teclado VIRTUAL, que [LectorHid] descarta a propósito, y el código
     * caía al buscador. Se usa su aviso ([LectorIntegradoSunmi.ACCION]), que ya trae el código
     * entero; lo escucha `MainActivity`.
     */
    fun recibirDelLectorIntegrado(texto: String?) {
        val codigo = texto?.trim().orEmpty()
        if (codigo.isNotEmpty()) _codigos.tryEmit(codigo)
    }

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
        casillaDeCantidad: Boolean = com.avoqado.pos.designsystem.components.CasillaDeCantidad.activa,
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
            // 🔴 Con la casilla de cantidad activa sólo vale el teclado de la pantalla (founder, 17-nov: en ANDROID no hay
            // teclado). Una tecla física ahí es la pistola —su 1.er carácter, que LectorHid deja pasar mientras no la
            // conoce, se volvía cantidad— o un Enter que soltaría la casilla y picaría otro botón (Codex r1 #1, r2 #6).
            TeclaHid.DejarPasar -> if (casillaDeCantidad && esTeclaFisicaQueEscribe(deviceId, unicodeChar, keyCode)) {
                consumidas += keyCode
                true
            } else {
                false
            }
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

    companion object {
        /** Teclas que cambian a otras sin escribir nada. Constantes, no `KeyEvent.isModifierKey`: en los tests JVM ese estático es un stub. */
        private val MODIFICADORES = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
        )

        /**
         * Una tecla FÍSICA (pistola o teclado de verdad, no el de la pantalla ni `adb input`) que escribe algo, o un Enter o
         * un Tab (lo que cierra un código; el Tab movería el foco, Codex r6 #7):
         * lo que la casilla de cantidad no recibe (founder, 17-nov: en ANDROID no hay teclado). La usa también la hoja del
         * teléfono, que es otra ventana y no pasa por `MainActivity.dispatchKeyEvent`.
         */
        fun esTeclaFisicaQueEscribe(evento: KeyEvent): Boolean =
            esTeclaFisicaQueEscribe(evento.deviceId, evento.unicodeChar, evento.keyCode)

        internal fun esTeclaFisicaQueEscribe(deviceId: Int, unicodeChar: Int, keyCode: Int): Boolean {
            val esFisico = deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && deviceId > 0
            val escribe = unicodeChar in 0x20..0xFFFF
            val cierra = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_TAB
            return esFisico && (escribe || cierra)
        }
    }
}
