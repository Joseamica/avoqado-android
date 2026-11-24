package com.avoqado.pos.pos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La señal «hay venta a medias» del aparato: ¿el carrito de Cobrar tiene artículos?
 *
 * La leen el cambio de sucursal (`MoreMenuViewModel`), el cambio de papel del aparato (`PapelDelAparatoGateViewModel`)
 * y el cambio de papel o de terminal que llega del DASHBOARD (`TpvSettingsRepository`): con venta a medias, esperan.
 *
 * 🔴 **Sólo manda el carrito de Cobrar vigente** (encargo f7529d). Carritos (`CartViewModel`) nacen varios: el de
 * Cobrar, el catálogo de la cuenta de una mesa y el de «Asignar» en bloque; los dos últimos nunca guardan una venta (lo
 * de la mesa va a su cuenta). Antes los tres escribían aquí y ganaba el último: abrir una mesa dejaba la señal en «vacío»
 * con una venta a medias en Cobrar, y el cambio desde el DASHBOARD borraba esa venta.
 *
 * Ahora la pantalla de Cobrar declara su carrito con [tomar], con la edición de la navegación donde se montó, y sólo
 * cuenta el Cobrar de la navegación AL FRENTE ([navegacionMontada]); [update] de cualquier otro carrito no cambia nada.
 * No es «cualquier carrito con artículos»: al rehacer la navegación (cambio de modo, cerrar sesión) Android NO limpia el
 * ViewModel del NavHost viejo (Codex r1); su carrito ya no se puede retomar y no debe frenar nada. [soltar] (desde
 * `onCleared`) lo quita.
 */
@Singleton
class ActiveCartState @Inject constructor() {
    private class Cobrar(val dueno: Any, var itemCount: Int, var totalDisplay: String)

    private val candado = Any()

    /** El carrito de Cobrar de cada edición de la navegación (la más vieja primero). */
    private val cobrarDe = LinkedHashMap<Any?, Cobrar>()

    /** La edición que está al frente; [hayFrente] = false mientras ninguna se ha declarado. */
    private var alFrente: Any? = null
    private var hayFrente = false

    private val _itemCount = MutableStateFlow(0)

    /** Artículos del carrito de Cobrar de la navegación al frente; 0 si no hay. */
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _totalDisplay = MutableStateFlow(VACIO)

    /** Total del mismo carrito que [itemCount]. */
    val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()

    val hasItems: Boolean get() = _itemCount.value > 0

    /**
     * La pantalla de Cobrar de la navegación [edicion] declara que [dueno] es SU carrito, con lo que tiene ahora. Releva al
     * Cobrar anterior de esa misma edición. Si ninguna edición se ha declarado al frente, ésta lo queda.
     */
    fun tomar(dueno: Any, edicion: Any?, itemCount: Int, totalDisplay: String) = synchronized(candado) {
        cobrarDe.remove(edicion)
        cobrarDe[edicion] = Cobrar(dueno, itemCount, totalDisplay)
        if (!hayFrente) {
            alFrente = edicion
            hayFrente = true
        }
        podar()
        publicarElDelFrente()
    }

    /**
     * La navegación [edicion] quedó al frente (`AvoqadoNavGraph`, al montarse y cada vez que su ventana vuelve al frente;
     * girar la pantalla conserva la edición). Desde ahí manda SU carrito de Cobrar, o ninguno si todavía no abre Cobrar:
     * - otra navegación (cambio de modo, volver a entrar): el carrito viejo ya no se puede retomar (Android no limpia el
     *   NavHost viejo) y deja de frenar, aunque Cobrar no se haya abierto en la nueva (Codex r2);
     * - una segunda ventana encima (p. ej. al conectar una impresora USB) y luego Atrás: al volver la primera, su venta
     *   vuelve a contar (Codex r3).
     */
    fun navegacionMontada(edicion: Any?) = synchronized(candado) {
        alFrente = edicion
        hayFrente = true
        publicarElDelFrente()
    }

    /** El carrito [dueno] cambió. Sólo cuenta si es el Cobrar de la navegación al frente: un carrito de consulta no la pisa. */
    fun update(dueno: Any, itemCount: Int, totalDisplay: String) = synchronized(candado) {
        val cobrar = cobrarDe.values.firstOrNull { it.dueno === dueno } ?: return@synchronized
        cobrar.itemCount = itemCount
        cobrar.totalDisplay = totalDisplay
        publicarElDelFrente()
    }

    /** El carrito [dueno] ya no existe: deja de contar. */
    fun soltar(dueno: Any) = synchronized(candado) {
        val edicion = cobrarDe.entries.firstOrNull { it.value.dueno === dueno }?.key ?: return@synchronized
        cobrarDe.remove(edicion)
        publicarElDelFrente()
    }

    private fun publicarElDelFrente() {
        val cobrar = if (hayFrente) cobrarDe[alFrente] else null
        _totalDisplay.value = cobrar?.totalDisplay ?: VACIO
        _itemCount.value = cobrar?.itemCount ?: 0
    }

    /** Las ediciones viejas que Android no limpió no crecen sin fin; la del frente nunca se borra. */
    private fun podar() {
        while (cobrarDe.size > MAX_EDICIONES) {
            val vieja = cobrarDe.keys.firstOrNull { !(hayFrente && it == alFrente) } ?: return
            cobrarDe.remove(vieja)
        }
    }

    private companion object {
        const val VACIO = "$0.00"
        const val MAX_EDICIONES = 8
    }
}
