package com.avoqado.pos.pos.data

import javax.inject.Inject
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
        /**
         * D13 (18-nov): ya tuvo su oportunidad y no pudo entrar (el cobro abierto, una venta a medio cobrar, otra reserva en
         * el carrito). Desde entonces sólo entra a un carrito VACÍO: la venta que la detuvo ya terminó y nunca se mete en la de
         * otra persona.
         */
        val enEspera: Boolean = false,
    )

    /** D13: una fila, no una casilla. Una segunda inscripción antes de volver a Mostrador ya no borra a la primera. */
    private val pendientes = ArrayDeque<Seed>()

    fun put(seed: Seed) = synchronized(this) { pendientes.addLast(seed) }

    /** La siguiente clase pendiente, una sola vez. */
    fun consume(): Seed? = synchronized(this) { pendientes.removeFirstOrNull() }

    fun hasPending(): Boolean = synchronized(this) { pendientes.isNotEmpty() }

    /** La siguiente sin sacarla, para saber si espera un carrito vacío. */
    fun siguiente(): Seed? = synchronized(this) { pendientes.firstOrNull() }

    /** La que se sacó y no pudo entrar regresa al frente, en espera (D9, D13). */
    fun devolver(seed: Seed) = synchronized(this) { pendientes.addFirst(seed.copy(enEspera = true)) }

    /** La siguiente no pudo entrar sin sacarla (cobro abierto, venta a medio cobrar): queda en espera. */
    fun esperar() = synchronized(this) {
        pendientes.firstOrNull()?.let { pendientes[0] = it.copy(enEspera = true) }
        Unit
    }
}
