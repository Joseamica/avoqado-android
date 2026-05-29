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
    )

    @Volatile
    private var pending: Seed? = null

    fun put(seed: Seed) {
        pending = seed
    }

    /** Returns the pending seed exactly once, then clears it (one-shot). */
    fun consume(): Seed? {
        val current = pending
        pending = null
        return current
    }

    fun hasPending(): Boolean = pending != null
}
