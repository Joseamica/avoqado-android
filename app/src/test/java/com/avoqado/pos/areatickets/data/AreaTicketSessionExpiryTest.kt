package com.avoqado.pos.areatickets.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una sesión de cobro caducada no puede volver a la caja como carrito cobrable.
 *
 * Encontrado en hardware (2026-08-09): la T3 se quedó de un día para otro con una
 * sesión abierta a las 01:00. El venue la caduca a los 30 minutos, pero el cliente
 * sólo miraba `status` —que seguía en OPEN— y la restauraba al abrir la app. El
 * cajero se enteraba al pulsar Cobrar, con el cliente enfrente: el server
 * respondía 409 CHECKOUT_SESSION_STALE y la venta quedaba muerta sin salida.
 *
 * El servidor SÍ manda `expiresAt` en cada checkout. Sólo había que mirarlo.
 */
class AreaTicketSessionExpiryTest {
    private val now = 1_754_700_000_000L // instante fijo de referencia
    private fun iso(offsetMillis: Long): String =
        java.time.Instant.ofEpochMilli(now + offsetMillis).toString()

    @Test
    fun `una sesion abierta y vigente sirve`() {
        assertTrue(isAreaTicketCheckoutUsable("OPEN", iso(+10 * 60_000), now))
    }

    @Test
    fun `una sesion abierta pero caducada no sirve`() {
        // El caso real: creada a la 1am, caducada a la 1:30, abierta a las 9:30.
        assertFalse(isAreaTicketCheckoutUsable("OPEN", iso(-8 * 60 * 60_000), now))
    }

    @Test
    fun `justo despues de expirar ya no sirve`() {
        assertFalse(isAreaTicketCheckoutUsable("OPEN", iso(-1), now))
    }

    @Test
    fun `los estados terminales nunca sirven, aunque no hayan caducado`() {
        for (status in listOf("PAID", "CANCELLED", "EXPIRED")) {
            assertFalse(status, isAreaTicketCheckoutUsable(status, iso(+10 * 60_000), now))
        }
    }

    @Test
    fun `una fecha ilegible no tumba el cobro`() {
        // Ante un `expiresAt` que no podemos leer preferimos dejar pasar: el server
        // sigue siendo la autoridad y rechaza si de verdad venció. Tumbar aquí una
        // sesión buena sería peor que dejar que el server la juzgue.
        assertTrue(isAreaTicketCheckoutUsable("OPEN", "no-es-una-fecha", now))
        assertTrue(isAreaTicketCheckoutUsable("OPEN", "", now))
    }
}
