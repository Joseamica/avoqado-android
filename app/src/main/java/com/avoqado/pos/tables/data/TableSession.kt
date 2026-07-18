package com.avoqado.pos.tables.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TABLE_SERVICE (PRO) — the active table context that wraps the NORMAL selling
 * flow (Square-style: a table is not a separate register, it's a check the
 * regular register is working on).
 *
 * Mirrors the [com.avoqado.pos.pos.data.ClassCheckoutSeed] hand-off pattern:
 * a singleton the Mesas screen writes and the Checkout/Payment screens read.
 * When no session is active every existing flow is byte-identical.
 *
 * Modes:
 * - [Mode.ORDERING]: the cart's CTA becomes "Enviar a cocina" — items picked
 *   with the normal grid/modifiers are appended to the table's open order and
 *   fired as per-station comandas.
 * - [Mode.PAYING]: the payment flow pays the EXISTING table order (no new
 *   order is created) and the table is released on completion.
 */
@Singleton
class TableSession @Inject constructor() {

    enum class Mode { ORDERING, PAYING }

    data class Active(
        val tableId: String,
        val tableNumber: String,
        val areaName: String?,
        val orderId: String,
        val orderNumber: String,
        /** Order.version at session start — optimistic concurrency for add-round. */
        val version: Int,
        /** Order total in cents at session start (PAYING mode seeds the cart with it). */
        val totalCents: Int,
        val mode: Mode,
        /** "Dividir la cuenta" from the check panel: the register auto-opens
         *  the split sheet on arrival (consumed once by CheckoutScreen). */
        val openSplitOnArrival: Boolean = false,
    ) {
        val label: String get() = "Mesa $tableNumber" + (areaName?.let { " · $it" } ?: "")
    }

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    fun start(session: Active) {
        _active.value = session
    }

    fun current(): Active? = _active.value

    /** Refresh the optimistic-concurrency version after a successful round. */
    fun updateVersion(version: Int) {
        _active.value = _active.value?.copy(version = version)
    }

    /**
     * Split-the-bill: after a PARTIAL payment the session's charge target
     * becomes the remaining balance, so any later cart re-seed charges what's
     * actually owed instead of the original total.
     */
    fun updateRemaining(remainingCents: Int) {
        _active.value = _active.value?.copy(totalCents = remainingCents)
    }

    fun clear() {
        _active.value = null
    }
}
