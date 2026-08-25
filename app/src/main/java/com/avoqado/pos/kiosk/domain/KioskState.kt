package com.avoqado.pos.kiosk.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canal único hacia la cara del cliente cuando trabaja como KIOSCO.
 *
 * Singleton por la misma razón que [com.avoqado.pos.customerdisplay.CustomerDisplayState]:
 * la ventana de la segunda pantalla vive fuera del ciclo de vida de cualquier
 * pantalla del cajero, así que nadie "dueño" puede sostener el estado.
 *
 * 🔴 **Apagado por defecto, y esa es la garantía de que no rompe nada.** Con
 * [enabled] en false este objeto no existe para la app: la segunda pantalla
 * sigue siendo el espejo de siempre y ni una línea del mostrador cambia de
 * comportamiento. Prenderlo es lo único que mete al kiosco a la ventana.
 */
@Singleton
class KioskState @Inject constructor() {

    // MARK: - ¿El kiosco está prendido?

    private val _enabled = MutableStateFlow(false)

    /**
     * true ⇒ la cara del cliente es un kiosco de autoservicio.
     * false ⇒ es el espejo del mostrador de siempre.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        _enabled.value = value
        // Al apagar se vuelve a reposo: si no, al re-prenderlo aparecería el
        // nombre del cliente anterior en la pantalla, de cara a la entrada.
        if (!value) restart()
    }

    // MARK: - Lo que se ve

    private val _content = MutableStateFlow<KioskContent>(KioskContent.Welcome)
    val content: StateFlow<KioskContent> = _content.asStateFlow()

    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var revertJob: Job? = null

    fun show(content: KioskContent) {
        _content.value = content
        revertJob?.cancel()

        // 🔴 Todo lo que enseñe datos de una persona se borra solo. Un kiosco
        // en la entrada no tiene quien lo cierre: sin este temporizador, el
        // nombre y las clases de quien acaba de pasar se quedan ahí para el
        // siguiente de la fila.
        val timeoutMs = when (content) {
            is KioskContent.CheckedIn, is KioskContent.Purchased -> DONE_TIMEOUT_MS
            is KioskContent.Found, is KioskContent.Offer -> IDENTIFIED_TIMEOUT_MS
            is KioskContent.Trouble -> TROUBLE_TIMEOUT_MS
            // Identify no caduca sola: alguien tecleando despacio no merece que
            // se le borre el número a media captura. Se sale con el botón.
            else -> null
        }
        timeoutMs?.let { scheduleRestart(it) }
    }

    private fun scheduleRestart(afterMs: Long) {
        revertJob?.cancel()
        revertJob = timerScope.launch {
            delay(afterMs)
            _content.value = KioskContent.Welcome
        }
    }

    /**
     * El cliente sigue tocando: reinicia el temporizador para que no se le
     * desaparezca la pantalla enfrente. No aplica al reposo ni a la captura.
     */
    fun keepAlive() {
        val c = _content.value
        val timeoutMs = when (c) {
            is KioskContent.CheckedIn, is KioskContent.Purchased -> DONE_TIMEOUT_MS
            is KioskContent.Found, is KioskContent.Offer -> IDENTIFIED_TIMEOUT_MS
            is KioskContent.Trouble -> TROUBLE_TIMEOUT_MS
            else -> return
        }
        scheduleRestart(timeoutMs)
    }

    /** Vuelve al reposo YA y suelta cualquier dato de la persona anterior. */
    fun restart() {
        revertJob?.cancel()
        _content.value = KioskContent.Welcome
    }

    // MARK: - Lo que el cliente toca (de vuelta hacia quien conduce)

    /** Tocó "Tengo clase hoy": arranca la identificación. */
    var onStart: (() -> Unit)? = null

    /** Un dígito del teclado propio. La ventana del cliente NO recibe el del sistema. */
    var onDigit: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onPickCountry: (() -> Unit)? = null

    /** Buscar con el número tecleado. */
    var onSearch: (() -> Unit)? = null

    /** Tocó su nombre en la lista de la clase en curso. Es el camino normal. */
    var onCheckIn: ((KioskPerson) -> Unit)? = null

    /**
     * Confirmó desde el camino a mano (se buscó y eligió su clase). Separado del
     * de arriba a propósito: son dos superficies distintas hacia el mismo
     * check-in, y colapsarlas obligaría a adivinar de cuál vino el toque.
     */
    var onCheckInSession: ((KioskSession) -> Unit)? = null

    /** "Quiero un paquete": abre la oferta. */
    var onSeePacks: (() -> Unit)? = null

    /** Marca/desmarca un paquete. NO cobra. */
    var onPackToggled: ((String) -> Unit)? = null

    /** Confirmó la compra: AQUÍ sí sale el cobro hacia la terminal. */
    var onBuy: (() -> Unit)? = null

    /** Empezar de nuevo / cancelar. Siempre disponible. */
    var onRestart: (() -> Unit)? = null

    /** Suelta las devoluciones de llamada al desmontar quien conducía. */
    fun clearCallbacks() {
        onStart = null
        onDigit = null
        onDelete = null
        onPickCountry = null
        onSearch = null
        onCheckIn = null
        onCheckInSession = null
        onSeePacks = null
        onPackToggled = null
        onBuy = null
        onRestart = null
    }

    private companion object {
        // Alcanza para leer "listo, Ana" y alejarse.
        const val DONE_TIMEOUT_MS = 12_000L
        // Con datos de una persona en pantalla se corta antes: es lo que
        // vería el siguiente de la fila si ella se va sin cerrar.
        const val IDENTIFIED_TIMEOUT_MS = 45_000L
        const val TROUBLE_TIMEOUT_MS = 15_000L
    }
}
