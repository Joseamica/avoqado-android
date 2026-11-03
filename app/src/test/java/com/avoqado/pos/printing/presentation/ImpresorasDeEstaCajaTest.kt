package com.avoqado.pos.printing.presentation

import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.StationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpresorasDeEstaCajaTest {
    private val recibos = SavedPrinter(id = "r", name = "BIXOLON SRP-F312", connectionType = "usb", address = "usb:cola:BIXOLON SRP-F312", roles = listOf("receipt"))
    private val cocinaLocal = SavedPrinter(id = "c", name = "Cocina USB", connectionType = "usb", address = "usb:cola:Cocina", roles = listOf("kitchen"))
    private val ambas = SavedPrinter(id = "a", name = "Una sola", connectionType = "usb", address = "usb:cola:Una", roles = listOf("kitchen", "receipt"))

    @Test fun `P1 sin impresora de recibos la caja sale sin configurar aunque tenga otras`() {
        assertEquals(emptyList<SavedPrinter>(), impresorasDeRecibos(listOf(cocinaLocal)))
        assertEquals(listOf(cocinaLocal), otrasDeEstaCaja(listOf(cocinaLocal)))
    }

    @Test fun `una impresora para recibos y comandas cuenta como la de recibos`() {
        assertEquals(listOf(recibos, ambas), impresorasDeRecibos(listOf(recibos, cocinaLocal, ambas)))
        assertEquals(listOf(cocinaLocal), otrasDeEstaCaja(listOf(recibos, cocinaLocal, ambas)))
    }

    /** D2 (founder, 28-oct): sin cocina el cobro ya no avisa; «Impresoras y cajón» es donde un restaurante se entera. */
    @Test fun `P2 sin estaciones ni impresora de cocina la hoja dice que no hay cocina`() {
        assertTrue(sinImpresoraDeCocina(listOf(recibos), PrintConfig()))
        assertFalse(sinImpresoraDeCocina(listOf(recibos, cocinaLocal), PrintConfig()))
        assertFalse(sinImpresoraDeCocina(listOf(ambas), PrintConfig()))
        assertFalse(sinImpresoraDeCocina(emptyList(), PrintConfig(stations = listOf(StationInfo(id = "s", name = "Cocina")))))
        assertTrue(sinImpresoraDeCocina(emptyList(), PrintConfig(stations = listOf(StationInfo(id = "s", name = "Cocina", active = false)))))
        assertTrue(sinImpresoraDeCocina(listOf(cocinaLocal.copy(isEnabled = false)), PrintConfig()))
    }

    @Test fun `el nombre del aparato nunca se inventa`() {
        assertEquals("Avoqado Windows 10 (2)", nombreDelAparato("  Avoqado Windows 10 (2) "))
        assertNull(nombreDelAparato("   "))
        assertNull(nombreDelAparato(null))
        assertEquals("esta computadora", esteAparato(esEscritorio = true))
        assertEquals("este aparato", esteAparato(esEscritorio = false))
    }

    @Test fun `P1 la liga al panel solo para quien puede editar impresoras`() {
        assertEquals("https://dashboard.avoqado.io/venues/la-galeterie/print-stations", ligaDeImpresorasDelPanel("la-galeterie", puedeEditar = true))
        assertNull(ligaDeImpresorasDelPanel("la-galeterie", puedeEditar = false))
        assertNull(ligaDeImpresorasDelPanel("  ", puedeEditar = true))
        assertNull(ligaDeImpresorasDelPanel(null, puedeEditar = true))
        assertEquals("https://dashboard.avoqado.io/venues/caf%C3%A9+norte/print-stations", ligaDeImpresorasDelPanel("café norte", puedeEditar = true))
    }
}
