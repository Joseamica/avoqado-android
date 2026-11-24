package com.avoqado.pos.pos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La señal «hay venta a medias» del aparato: ¿algún carrito vivo tiene artículos?
 *
 * La leen el cambio de sucursal (`MoreMenuViewModel`), el cambio de papel del aparato (`PapelDelAparatoGateViewModel`)
 * y el cambio de papel o de terminal que llega del DASHBOARD (`TpvSettingsRepository`): con venta a medias, esperan.
 *
 * 🔴 **Cada carrito escribe SU parte, con su dueño** (encargo f7529d). Carritos nacen varios a la vez: el de Cobrar, el
 * catálogo de la cuenta de una mesa y el de «Asignar» en bloque. Antes había UN número y lo pisaba el último que
 * emitía: abrir una mesa dejaba la señal en «vacío» con una venta a medias en Cobrar, y el cambio desde el DASHBOARD
 * borraba esa venta. Ahora hay venta a medias si ALGÚN dueño tiene artículos, y cada carrito suelta su parte al irse
 * ([soltar], desde el `onCleared` del carrito).
 */
@Singleton
class ActiveCartState @Inject constructor() {
    private class Parte(val itemCount: Int, val totalDisplay: String)

    /** Una parte por dueño, en orden de su último aviso (el más nuevo al final). */
    private val partes = LinkedHashMap<Any, Parte>()

    private val _itemCount = MutableStateFlow(0)

    /** Artículos del carrito con venta a medias (el que avisó más reciente, si hubiera más de uno); 0 si ninguno. */
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _totalDisplay = MutableStateFlow(VACIO)

    /** Total del mismo carrito que [itemCount]. */
    val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()

    val hasItems: Boolean get() = _itemCount.value > 0

    /** El carrito [dueno] dice cuántos artículos tiene ahora. Sólo cambia su parte: nunca la de otro carrito. */
    fun update(dueno: Any, itemCount: Int, totalDisplay: String) = synchronized(partes) {
        partes.remove(dueno)
        partes[dueno] = Parte(itemCount, totalDisplay)
        publicar()
    }

    /** El carrito [dueno] ya no existe: su parte deja de contar. */
    fun soltar(dueno: Any) = synchronized(partes) {
        if (partes.remove(dueno) != null) publicar()
    }

    private fun publicar() {
        val conArticulos = partes.values.lastOrNull { it.itemCount > 0 }
        _totalDisplay.value = conArticulos?.totalDisplay ?: VACIO
        _itemCount.value = conArticulos?.itemCount ?: 0
    }

    private companion object {
        const val VACIO = "$0.00"
    }
}
