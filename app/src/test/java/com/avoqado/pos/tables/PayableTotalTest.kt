package com.avoqado.pos.tables

import com.avoqado.pos.tables.presentation.payableCents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija el bug de dinero encontrado en hardware (Sunmi T3 Pro, 2026-07-27):
 * sin red el botón decía "Pagar $119.00" y NO hacía nada, porque el cobro
 * leía `session.totalCents` (0 en mesas provisionales) en vez del total que
 * la UI ya mostraba. Un mesero no podía cobrar una mesa sin internet.
 */
class PayableTotalTest {

    @Test
    fun `offline con rondas encoladas cobra lo encolado, no cero`() {
        // El cheque del server no existe todavía; la mesa nació provisional.
        assertEquals(11900, payableCents(fromCheckCents = 0, queuedCents = 11900, sessionTotalCents = 0))
    }

    @Test
    fun `online sin nada encolado cobra el cheque`() {
        assertEquals(24900, payableCents(fromCheckCents = 24900, queuedCents = 0, sessionTotalCents = 24900))
    }

    @Test
    fun `mesa con historial en el server mas una ronda offline suma ambos`() {
        assertEquals(36800, payableCents(fromCheckCents = 24900, queuedCents = 11900, sessionTotalCents = 24900))
    }

    @Test
    fun `sin cheque ni cola cae al total de la sesion`() {
        assertEquals(5000, payableCents(fromCheckCents = 0, queuedCents = 0, sessionTotalCents = 5000))
    }

    @Test
    fun `cuenta realmente vacia devuelve cero y bloquea el cobro`() {
        assertEquals(0, payableCents(fromCheckCents = 0, queuedCents = 0, sessionTotalCents = 0))
    }
}
