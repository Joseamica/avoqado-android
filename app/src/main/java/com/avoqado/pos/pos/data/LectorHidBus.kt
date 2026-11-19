package com.avoqado.pos.pos.data

import android.view.KeyCharacterMap
import android.view.KeyEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

/**
 * Un código leído. [lecturaUs] es la MISMA ráfaga traducida con el teclado de EE. UU. (D14, 18-nov): con el teclado del
 * aparato en español (Android 14+ lo elige solo por el idioma), el «-» de una pistola en inglés llega como «'» y
 * «PIZZAS-003» se lee «PIZZAS'003». Viaja pegada a su código (nunca se busca por texto: una etiqueta que de verdad dice
 * «PIZZAS'003» no hereda la traducción de otra lectura) y sólo existe cuando la lectura del aparato trae un carácter que
 * un código no usa ([LectorHidBus.RAROS]): así una pistola configurada en español, que ya lee bien «AB-123», nunca se
 * reinterpreta como «AB/123» (Codex r1 #1 y #2). Quien busca prueba [texto] y, si no encuentra nada, [lecturaUs].
 */
data class CodigoLeido(val texto: String, val lecturaUs: String? = null)

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
    private val _leidos = MutableSharedFlow<CodigoLeido>(extraBufferCapacity = 8)

    /** Cada código con su lectura en inglés, si la hay ([CodigoLeido]). */
    val leidos: SharedFlow<CodigoLeido> = _leidos.asSharedFlow()

    /** Sólo el texto, para quien busca códigos de puros dígitos (vales), que se leen igual en los dos teclados. */
    val codigos: Flow<String> = leidos.map { it.texto }

    /**
     * keyCodes cuyo ACTION_DOWN se consumió: su ACTION_UP debe consumirse también, o el
     * campo con foco recibe la mitad de una tecla.
     */
    private val consumidas = mutableSetOf<Int>()

    /** El teclado de EE. UU. (el del teclado virtual): traduce la tecla sin la distribución que eligió el aparato. */
    private val tecladoUs: KeyCharacterMap? by lazy { runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }.getOrNull() }

    /**
     * El lector INTEGRADO de las Sunmi (OrderPAD y similares) no entra por teclas: su modo
     * "teclado" teclea como teclado VIRTUAL, que [LectorHid] descarta a propósito, y el código
     * caía al buscador. Se usa su aviso ([LectorIntegradoSunmi.ACCION]), que ya trae el código
     * entero; lo escucha `MainActivity`.
     */
    fun recibirDelLectorIntegrado(texto: String?) {
        val codigo = texto?.trim().orEmpty()
        if (codigo.isNotEmpty()) _leidos.tryEmit(CodigoLeido(codigo))
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
        unicodeCharUs = tecladoUs?.get(event.keyCode, event.metaState)?.takeIf { it != 0 } ?: event.unicodeChar,
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
        unicodeCharUs: Int = unicodeChar,
    ): Boolean {
        if (accion == KeyEvent.ACTION_UP) return consumidas.remove(keyCode)
        if (accion != KeyEvent.ACTION_DOWN) return false
        // Una tecla SOSTENIDA se repite sola cada ~50 ms: parecería una ráfaga sin serlo.
        // Un lector nunca repite; la persona que la sostiene sigue viendo su tecla, salvo en la casilla de cantidad, donde
        // ninguna tecla física escribe: ni la 1.ª pulsación ni sus repeticiones (Codex D5 r1 #3).
        if (repeatCount > 0) {
            if (!(casillaDeCantidad && esTeclaFisicaQueEscribe(deviceId, unicodeChar, keyCode))) return false
            consumidas += keyCode
            return true
        }
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
        val caracterUs = if (unicodeCharUs in 0x20..0xFFFF) Char(unicodeCharUs) else null

        return when (val decision = lector.procesar(caracter, esTerminador, esFisico, deviceId, eventTimeMs, caracterUs)) {
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
                val lecturaUs = decision.textoUs.takeIf { us ->
                    us != decision.texto && decision.texto.any { it in RAROS } && us.none { it in RAROS }
                }
                _leidos.tryEmit(CodigoLeido(decision.texto, lecturaUs))
                consumidas += keyCode
                true
            }
        }
    }

    companion object {
        /**
         * Lo que el teclado del aparato en español escribe en lugar de un símbolo de una pistola en inglés y que un código no
         * usa: «'» (en vez de «-»), «¿» («=»), «¡» («+»), «ñ»/«Ñ» («;»/«:»), «°» («~»), «¬». Si la lectura del aparato no trae
         * ninguno, se queda como está (D14).
         */
        internal const val RAROS = "'¿¡ñÑ°¬"

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
