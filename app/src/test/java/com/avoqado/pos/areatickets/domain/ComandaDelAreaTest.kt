package com.avoqado.pos.areatickets.domain

import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D2 de vales (La Galeterie, 3-nov-2026): «Impresoras y cajón» enseña «Manda comanda a cocina» del área que ESTE aparato
 * emite. Sólo si emite vales de un área y el SERVIDOR ya manda el dato; si no, no se enseña (no se puede guardar).
 */
class ComandaDelAreaTest {
    private fun ajustes(
        emite: Boolean = true,
        area: AreaTicketArea? = AreaTicketArea(id = "area-cremeria", name = "Cremería", fulfillmentMode = "HOLD_UNTIL_PAID", sendsComanda = false),
        prendidos: Boolean = true,
    ) = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = prendidos),
        terminal = AreaTicketTerminalCapabilities(id = "t-1", name = "PC", fulfillmentArea = area, canIssueAreaTickets = emite),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )

    @Test
    fun `el aparato que emite vales de un area ve su comanda`() {
        assertEquals(ComandaDelArea(areaId = "area-cremeria", areaNombre = "Cremería", manda = false), comandaDelArea(ajustes()))
    }

    @Test
    fun `la caja, un aparato sin area, vales apagados o un servidor sin el dato no la ven`() {
        assertNull(comandaDelArea(ajustes(emite = false)))
        assertNull(comandaDelArea(ajustes(area = null)))
        assertNull(comandaDelArea(ajustes(prendidos = false)))
        assertNull(comandaDelArea(ajustes(area = AreaTicketArea(id = "a", name = "A", fulfillmentMode = "IMMEDIATE"))))
    }

    @Test
    fun `sin el campo en el JSON el area queda sin dato, no apagada`() {
        val area = Json { ignoreUnknownKeys = true }.decodeFromString(AreaTicketArea.serializer(), """{"id":"a","name":"A","fulfillmentMode":"IMMEDIATE"}""")
        assertNull(area.sendsComanda)
    }

    @Test
    fun `por que no se puede cambiar gana el permiso, luego la red`() {
        assertEquals("Pídele a tu administrador que lo cambie", motivoParaNoCambiarComanda(puedeConfigurar = false, hayRed = false))
        assertEquals("Necesita internet para cambiarlo", motivoParaNoCambiarComanda(puedeConfigurar = true, hayRed = false))
        assertNull(motivoParaNoCambiarComanda(puedeConfigurar = true, hayRed = true))
    }
}
