package com.avoqado.pos.notifications.presentation

import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.navigation.MainTab
import com.avoqado.pos.notifications.data.model.AppNotification
import com.avoqado.pos.notifications.presentation.DestinoDelAviso.OtraSucursal
import com.avoqado.pos.notifications.presentation.DestinoDelAviso.Pestana
import com.avoqado.pos.notifications.presentation.DestinoDelAviso.Reserva
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Ver reserva» de la campanita (D4, visto en la D3 el 28-oct): el aviso RESERVATION_NEEDS_REVIEW trae
 * `actionUrl = "reservations/<id>"` (relativo, como lo arma el SERVIDOR para el DASHBOARD) y tocarlo sólo lo marcaba leído.
 * Ahora abre el detalle de ESA reserva, donde está «Marcar resuelto». Lo demás, igual que antes.
 */
class DestinoDelAvisoTest {

    @Test
    fun `reservations con id - con o sin diagonal - abre el detalle de esa reserva`() {
        assertEquals(Reserva("cm9x1abc2def3ghi4jkl5mno6"), destinoDe("reservations/cm9x1abc2def3ghi4jkl5mno6"))
        assertEquals(Reserva("cm9x1abc2def3ghi4jkl5mno6"), destinoDe("/reservations/cm9x1abc2def3ghi4jkl5mno6"))
        assertEquals("sin la consulta", Reserva("r1"), destinoDe("reservations/r1?from=mail"))
    }

    @Test
    fun `reservations sin id - con o sin diagonal - va a la pestana Calendario`() {
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/reservations"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("reservations"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/reservations/"))
    }

    @Test
    fun `las subrutas del DASHBOARD no son una reserva - van al Calendario como antes`() {
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("reservations/calendar?clase=cs1"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/reservations/calendar"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/reservations/waitlist"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/reservations/settings"))
    }

    @Test
    fun `las demas rutas no cambian`() {
        assertEquals(Pestana(MainTab.INVENTORY), destinoDe("/inventory/raw-materials?filter=low"))
        assertEquals(Pestana(MainTab.TRANSACTIONS), destinoDe("/orders/ORD-1"))
        assertEquals(Pestana(MainTab.TRANSACTIONS), destinoDe("/payments"))
        assertEquals(Pestana(MainTab.TABLES), destinoDe("/tables"))
        assertEquals(Pestana(MainTab.CALENDAR), destinoDe("/calendar"))
        assertNull("sin diagonal, como antes: no lleva a ninguna parte", destinoDe("inventory/raw-materials"))
        assertNull(destinoDe("/algo-desconocido"))
        assertNull(destinoDe(null))
        assertNull(destinoDe("  "))
    }

    // «Ver reserva» de un aviso de OTRA sucursal (Codex código r3 #1): la campanita junta las de todas, pero el detalle es
    // de la ACTIVA. No se abre (daría 404): se dice a cuál cambiarse, con el texto canónico igual al de iOS.

    private val sucursales = listOf(StoredVenue(id = "vA", name = "Sucursal Centro"), StoredVenue(id = "vB", name = "Polanco"))
    private fun aviso(venueId: String?, actionUrl: String? = "reservations/r1") =
        AppNotification(id = "n1", title = "Revisa la reserva", venueId = venueId, actionUrl = actionUrl)

    @Test
    fun `aviso de la sucursal activa - abre el detalle`() {
        assertEquals(Reserva("r1"), decidirDestino(aviso("vB"), sucursalActiva = "vB", sucursales))
    }

    @Test
    fun `aviso sin sucursal - abre el detalle como hoy`() {
        assertEquals(Reserva("r1"), decidirDestino(aviso(null), sucursalActiva = "vB", sucursales))
        assertEquals(Reserva("r1"), decidirDestino(aviso(" "), sucursalActiva = "vB", sucursales))
    }

    @Test
    fun `aviso de otra sucursal conocida - no abre y dice a cual cambiarse`() {
        assertEquals(
            OtraSucursal("Este aviso es de otra sucursal: cámbiate a Sucursal Centro para abrir la reserva."),
            decidirDestino(aviso("vA"), sucursalActiva = "vB", sucursales),
        )
    }

    @Test
    fun `aviso de otra sucursal sin nombre - texto generico`() {
        val generico = OtraSucursal("Este aviso es de otra sucursal: cámbiate a esa sucursal para abrir la reserva.")
        assertEquals(generico, decidirDestino(aviso("vZ"), sucursalActiva = "vB", sucursales))
        assertEquals("nombre vacío", generico, decidirDestino(aviso("vA"), "vB", listOf(StoredVenue(id = "vA", name = " "))))
    }

    @Test
    fun `de otra sucursal sólo cambia la reserva - las demas rutas siguen igual`() {
        assertEquals(Pestana(MainTab.INVENTORY), decidirDestino(aviso("vA", "/inventory"), "vB", sucursales))
        assertEquals(Pestana(MainTab.CALENDAR), decidirDestino(aviso("vA", "reservations/calendar?clase=cs1"), "vB", sucursales))
        assertNull(decidirDestino(aviso("vA", null), "vB", sucursales))
    }
}
