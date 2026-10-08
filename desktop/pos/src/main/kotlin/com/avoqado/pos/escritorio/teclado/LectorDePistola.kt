package com.avoqado.pos.escritorio.teclado

import android.util.Log
import com.avoqado.pos.pos.data.LectorHidBus
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * La pistola de códigos (USB o Bluetooth) en Windows. En Android entra por `MainActivity.dispatchKeyEvent` → `LectorHidBus`;
 * en escritorio no hay Activity y, hasta el 7-oct, nada la escuchaba: en la pantalla de cobro (sin campo de texto) lo que
 * tecleaba la pistola no llegaba a ningún lado. Caso real: La Galeterie — el vale salía bien y la caja «no lo leía».
 *
 * 🔴 Por qué no se usa `LectorHid` de Android (ver LectorHidHallazgoTest): ante Windows pistola y teclado son el MISMO
 * aparato, y LectorHid se come teclas en cuanto ve dos rápidas — una persona perdería letras. Aquí no se come nada a ciegas:
 * cada tecla se RETIENE hasta saber si es de una ráfaga. Si la ráfaga cierra con Enter (o Tab) y mide al menos [largoMinimo],
 * es un código y ninguna de sus teclas llega a la app; si no, se DEVUELVEN todas, en su orden. A una persona le cuesta una
 * demora de [esperaMs] como mucho.
 *
 * 🔴 Con un campo de texto escribiendo no se toca nada: la pistola teclea ahí como un teclado (el campo manual de
 * «Escanear» y los buscadores dependen de eso).
 *
 * Todo corre en el hilo de Swing (el despachador de teclas y el Timer). Lógica pura salvo las tres funciones que recibe.
 */
class LectorDePistola(
    private val reenviar: (KeyEvent) -> Unit,
    private val emitir: (String) -> Unit,
    private val programar: (Int, () -> Unit) -> Unit,
    /** Máximo entre dos teclas de la misma ráfaga: una pistola va a ~10 ms; una persona, a 80-300 ms. Calibrable. */
    private val maxIntervaloMs: Long = 60,
    /** Cuánto se retiene sin que llegue otra tecla. Mayor que [maxIntervaloMs]: el reloj de Windows avanza a saltos de ~16 ms. */
    private val esperaMs: Int = 100,
    /** Como en Android: dos teclas rápidas + Enter (un atajo) no son un código. */
    private val largoMinimo: Int = 4,
) {
    private val retenidas = mutableListOf<KeyEvent>()
    private val texto = StringBuilder()
    private val abajo = mutableSetOf<Int>()           // retenidas que bajaron y todavía no suben
    private val tragarAlSoltar = mutableSetOf<Int>()  // teclas de un código ya emitido: su «soltar» tampoco llega
    private var tragarTipeoDelFinal = false
    private var ultimaMs = 0L

    /** El temporizador soltó una ráfaga que, por el reloj de las teclas, seguía viva (PC trabada): su resto pasa tal cual. */
    private var partida = false
    private var turno = 0

    /** @return true si [e] NO sigue su camino ahora (se retuvo, o era del código). */
    fun procesar(e: KeyEvent, escribiendo: Boolean): Boolean = when (e.id) {
        KeyEvent.KEY_PRESSED -> alBajar(e, escribiendo)
        KeyEvent.KEY_TYPED -> when {
            tragarTipeoDelFinal && e.keyChar in FINALES -> { tragarTipeoDelFinal = false; true }
            retenidas.isEmpty() -> false
            else -> { retenidas += e; true }
        }
        KeyEvent.KEY_RELEASED -> when {
            tragarAlSoltar.remove(e.keyCode) -> true
            e.keyCode in MODIFICADORES || retenidas.isEmpty() -> false
            else -> { retenidas += e; abajo -= e.keyCode; true }
        }
        else -> false
    }

    private fun alBajar(e: KeyEvent, escribiendo: Boolean): Boolean {
        tragarTipeoDelFinal = false
        // La letra ya trae su Shift en los modificadores: un Shift suelto ni parte la ráfaga ni se retiene.
        if (e.keyCode in MODIFICADORES) return false
        if (escribiendo) {
            soltar()
            partida = false
            return false
        }
        val seguida = e.`when` - ultimaMs <= maxIntervaloMs
        val esFinal = e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB
        if (partida) {
            if (seguida) {
                // Su inicio ya se devolvió: emitirla sería un código cortado (un vale que no existe).
                ultimaMs = e.`when`
                if (esFinal) partida = false
                return false
            }
            partida = false
        }
        if (esFinal) {
            val codigo = texto.toString().trim()
            if (retenidas.isEmpty() || !seguida || codigo.length < largoMinimo) {
                soltar()
                return false
            }
            tragarAlSoltar += abajo
            tragarAlSoltar += e.keyCode
            tragarTipeoDelFinal = true
            descartar()
            emitir(codigo)
            return true
        }
        val c = e.keyChar
        val escribe = c != KeyEvent.CHAR_UNDEFINED && !c.isISOControl() && !esAtajo(e)
        if (!escribe) {
            soltar()
            return false
        }
        if (retenidas.isNotEmpty() && !seguida) soltar()
        retenidas += e
        abajo += e.keyCode
        texto.append(c)
        ultimaMs = e.`when`
        val mio = ++turno
        programar(esperaMs) {
            if (mio == turno && retenidas.isNotEmpty()) {
                soltar()
                partida = true
            }
        }
        return true
    }

    /** Ctrl o Alt solos (no los dos: Ctrl+Alt es AltGr, que escribe «@» en teclado español) o la tecla Windows. */
    private fun esAtajo(e: KeyEvent) = e.isControlDown != e.isAltDown || e.isMetaDown

    /** Devuelve lo retenido, una sola vez y en su orden. */
    private fun soltar() {
        val salen = retenidas.toList()
        descartar()
        salen.forEach(reenviar)
    }

    private fun descartar() {
        turno++
        retenidas.clear()
        texto.setLength(0)
        abajo.clear()
    }

    private companion object {
        val FINALES = setOf('\n', '\r', '\t')
        val MODIFICADORES = setOf(
            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
            KeyEvent.VK_WINDOWS, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK,
        )
    }
}

/**
 * Lo que emite la pistola entra al MISMO bus que en Android (`LectorHidBus.codigos` → `CartViewModel.codigosEscaneados`).
 * Su `_codigos` es privado y `app/` no se toca desde escritorio: se emite por reflexión. Si Android lo renombra o le cambia
 * el tipo, esto TRUENA (y PistolaAlCobroTest también) en vez de emitir a nadie en silencio.
 */
fun emisorDelBus(bus: LectorHidBus): (String) -> Unit {
    val campo = runCatching { LectorHidBus::class.java.getDeclaredField("_codigos") }.getOrNull()
    check(campo != null && MutableSharedFlow::class.java.isAssignableFrom(campo.type)) {
        "LectorHidBus ya no tiene «_codigos: MutableSharedFlow<String>»: la pistola de Windows no llega al cobro (revisa emisorDelBus)"
    }
    @Suppress("UNCHECKED_CAST")
    val flujo = campo.apply { isAccessible = true }.get(bus) as MutableSharedFlow<String>
    return { flujo.tryEmit(it) }
}

/**
 * Engancha [LectorDePistola] a la ventana de la caja con un KeyEventDispatcher de AWT, que ve cada tecla ANTES que Compose.
 * Si algo truena aquí, la tecla sigue su camino: el lector nunca deja al cajero sin teclado.
 */
object PistolaDeLaVentana {
    private var despachador: KeyEventDispatcher? = null

    fun instalar(ventana: Window, emitir: (String) -> Unit) {
        desinstalar()
        val focos = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val lector = LectorDePistola(
            // La entrega normal de AWT al componente, SIN volver a pasar por este despachador.
            reenviar = { focos.redispatchEvent(it.component, it) },
            // Nunca se registra el código (puede ser la tarjeta de un cliente): sólo cuántos caracteres.
            emitir = { Log.i("Lector", "código leído (${it.length} caracteres)"); emitir(it) },
            programar = { ms, f -> Timer(ms) { f() }.apply { isRepeats = false }.start() },
        )
        despachador = KeyEventDispatcher { e ->
            val origen = e.component ?: return@KeyEventDispatcher false
            val deLaCaja = origen === ventana || SwingUtilities.getWindowAncestor(origen) === ventana
            // inputMethodRequests != null = un campo de Compose escribiendo (lo mismo que lee TecladoDeLaVentana).
            deLaCaja && runCatching { lector.procesar(e, escribiendo = origen.inputMethodRequests != null) }
                .onFailure { Log.w("Lector", "El lector de pistola falló; la tecla sigue su camino", it) }
                .getOrDefault(false)
        }.also(focos::addKeyEventDispatcher)
    }

    fun desinstalar() {
        despachador?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
        despachador = null
    }
}
