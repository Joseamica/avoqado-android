package com.avoqado.pos.pos.data

import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Singleton

/**
 * One-shot hand-off for the walk-in class flow.
 *
 * The class detail screen reserves a seat (capacity-checked on the server),
 * drops the class product here, then navigates to the Checkout tab.
 * [com.avoqado.pos.pos.presentation.checkout.CheckoutScreen] consumes it once
 * on arrival to pre-fill the cart — mirroring how Square's register loads an
 * appointment/service into the current sale.
 *
 * Why a separate holder instead of just adding to the cart directly:
 * each screen owns its own CartViewModel instance (the cart is NOT a shared
 * singleton — only [ActiveCartState] is, and it tracks count/total only).
 * Holding just the seed keeps every existing per-screen cart untouched when
 * no walk-in charge is in flight.
 */
@Singleton
class ClassCheckoutSeed @Inject constructor() {
    data class Seed(
        val productId: String,
        val quantity: Int,
        /** Links the resulting sale back to the reservation. Consumed by the
         *  payment flow once the reservation↔sale linkage lands. */
        val reservationId: String? = null,
        /** D13: de quién es, para el aviso «Clase pendiente» (nombre que se capturó al inscribir). */
        val persona: String? = null,
        /** D13: la sucursal donde se inscribió; en otra sucursal no entra ni se enseña. */
        val venueId: String? = null,
    )

    /** Recién inscritas: la siguiente entra sola al llegar a Mostrador, como siempre. D13: una fila, no una casilla. */
    private val frescas = ArrayDeque<Seed>()

    /**
     * D13 (18-nov): las que no pudieron entrar en su momento (el cobro abierto, una venta a medio cobrar, otra reserva en el
     * carrito). Ya NUNCA entran solas —meterlas solas cuando «parecía» el momento las mandaba a la venta de otra persona o con
     * otro cliente (Codex D9 r8, D13 r1)—: Mostrador las enseña como «Clase pendiente» y la cajera decide cuándo agregarlas.
     */
    private val _enEspera = MutableStateFlow<List<Seed>>(emptyList())
    val enEspera: StateFlow<List<Seed>> = _enEspera.asStateFlow()

    fun put(seed: Seed) = synchronized(this) { frescas.addLast(seed) }

    /** La siguiente recién inscrita, una sola vez. */
    fun consume(): Seed? = synchronized(this) { frescas.removeFirstOrNull() }

    fun hasPending(): Boolean = synchronized(this) { frescas.isNotEmpty() || _enEspera.value.isNotEmpty() }

    /** D13 (Codex r3 #4): queda otra recién inscrita en la fila (no cuenta las pendientes, que ya no entran solas). */
    fun hayFrescas(): Boolean = synchronized(this) { frescas.isNotEmpty() }

    fun estaEnEspera(seed: Seed): Boolean = seed in _enEspera.value

    /** No pudo entrar: pasa a «Clase pendiente» (sin repetir la misma reserva). */
    fun esperar(seed: Seed) = synchronized(this) {
        val repetida = seed.reservationId != null && _enEspera.value.any { it.reservationId == seed.reservationId }
        if (!repetida) _enEspera.value = _enEspera.value + seed
    }

    /** Ninguna recién inscrita puede entrar ahora (cobro abierto, venta a medio cobrar): todas pasan a pendientes. */
    fun esperarTodas() = synchronized(this) {
        while (frescas.isNotEmpty()) esperar(frescas.removeFirst())
    }

    /** La cajera la agregó o la quitó de pendientes. */
    fun quitarDeEspera(seed: Seed) = synchronized(this) { _enEspera.value = _enEspera.value - seed }
}
