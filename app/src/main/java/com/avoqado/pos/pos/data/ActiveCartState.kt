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
 * Ahora la pantalla de Cobrar declara su carrito con [tomar] y sólo ése publica ([update] de otro dueño no cambia nada).
 * No es «cualquier carrito con artículos»: al rehacer la navegación (cambio de modo, cerrar sesión) Android NO limpia el
 * ViewModel del NavHost viejo (Codex r1); su carrito ya no se puede retomar y no debe frenar nada. Cada navegación
 * nueva lo releva al montarse ([navegacionMontada], Codex r2) y el Cobrar nuevo al [tomar]. [soltar] (desde `onCleared`)
 * apaga la señal si el que se va es el vigente.
 */
@Singleton
class ActiveCartState @Inject constructor() {
    private val candado = Any()

    /** El carrito de la pantalla de Cobrar vigente; null mientras ninguna lo ha declarado. */
    private var vigente: Any? = null

    /** La edición de la navegación donde se montó el Cobrar [vigente]. */
    private var edicionDelVigente: Any? = null

    private val _itemCount = MutableStateFlow(0)

    /** Artículos del carrito de Cobrar vigente; 0 si no hay. */
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _totalDisplay = MutableStateFlow(VACIO)

    /** Total del carrito de Cobrar vigente. */
    val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()

    val hasItems: Boolean get() = _itemCount.value > 0

    /**
     * La pantalla de Cobrar de la navegación [edicion] declara que [dueno] es SU carrito, con lo que tiene ahora. Releva al
     * anterior.
     */
    fun tomar(dueno: Any, edicion: Any?, itemCount: Int, totalDisplay: String) = synchronized(candado) {
        vigente = dueno
        edicionDelVigente = edicion
        publicar(itemCount, totalDisplay)
    }

    /**
     * Se montó la navegación [edicion] (`AvoqadoNavGraph`, una por cada vez que se rehace la pantalla; girarla conserva la
     * misma). Si el Cobrar vigente es de otra, su carrito ya no se puede retomar (Android no limpia el NavHost viejo):
     * deja de contar aunque el Cobrar nuevo todavía no se monte (un aparato que arranca en Mesas). Codex r2.
     */
    fun navegacionMontada(edicion: Any) = synchronized(candado) {
        if (vigente != null && edicionDelVigente != edicion) {
            vigente = null
            edicionDelVigente = null
            publicar(0, VACIO)
        }
    }

    /** El carrito [dueno] cambió. Sólo cuenta si es el de Cobrar vigente: un carrito de consulta no la pisa. */
    fun update(dueno: Any, itemCount: Int, totalDisplay: String) = synchronized(candado) {
        if (dueno === vigente) publicar(itemCount, totalDisplay)
    }

    /** El carrito [dueno] ya no existe. Si era el vigente, ya no hay venta a medias que cuidar. */
    fun soltar(dueno: Any) = synchronized(candado) {
        if (dueno === vigente) {
            vigente = null
            edicionDelVigente = null
            publicar(0, VACIO)
        }
    }

    private fun publicar(itemCount: Int, totalDisplay: String) {
        _totalDisplay.value = totalDisplay
        _itemCount.value = itemCount
    }

    private companion object {
        const val VACIO = "$0.00"
    }
}
