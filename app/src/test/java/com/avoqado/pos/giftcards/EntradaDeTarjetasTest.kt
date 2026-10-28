package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.data.AjustesDeTarjetas
import com.avoqado.pos.giftcards.data.EstadoDeAjustes
import com.avoqado.pos.giftcards.data.VentaPermitida
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.domain.estadoDeLaHoja
import org.junit.Assert.*
import org.junit.Test

class EntradaDeTarjetasTest {
    private fun ajustes(entrega: Boolean = true, vendible: Boolean = true, mensaje: String? = null, activar: String? = null) =
        EstadoDeAjustes.Disponible(AjustesDeTarjetas(
            enabled = true, suggestedAmounts = listOf(500.0), minAmount = 100.0, maxAmount = 10000.0,
            sale = VentaPermitida(vendible, message = mensaje, howToEnable = activar),
            capacidades = if (entrega) setOf("ENTREGA") else emptySet(),
        ))

    private fun hoja(estado: EstadoDeAjustes = ajustes(), red: Boolean = true, leer: Boolean = true, vender: Boolean = true, vacia: Boolean = true) =
        estadoDeLaHoja(estado, red, leer, vender, vacia, "Pro")

    @Test fun `404 oculta toda entrada aun sin red`() {
        for (red in listOf(true, false)) {
            val e = hoja(EstadoDeAjustes.NoDisponible, red)
            assertFalse(e.mosaicoVisible)
            assertFalse(e.vender.visible)
            assertFalse(e.consultar.visible)
        }
    }

    @Test fun `desconocido oculta vender con y sin red`() {
        for (red in listOf(true, false)) {
            val e = hoja(EstadoDeAjustes.Desconocido, red)
            assertTrue(e.mosaicoVisible)
            assertFalse(e.vender.visible)
            assertTrue(e.consultar.visible)
            assertEquals(red, e.consultar.habilitada)
            assertEquals(if (red) null else TextosDeTarjetas.SIN_RED, e.consultar.motivo)
        }
    }

    @Test fun `desconocido conserva permiso de consultar`() {
        assertEquals(TextosDeTarjetas.SIN_PERMISO_CONSULTAR, hoja(EstadoDeAjustes.Desconocido, leer = false).consultar.motivo)
    }

    @Test fun `sin entrega nunca aparece vender incluso sin red`() {
        for (red in listOf(true, false)) {
            val e = hoja(ajustes(entrega = false), red)
            assertTrue(e.mosaicoVisible)
            assertFalse(e.vender.visible)
            assertEquals(red, e.consultar.habilitada)
        }
    }

    @Test fun `403 conserva las dos filas y sus permisos`() {
        val e = hoja(EstadoDeAjustes.SinPermiso)
        assertTrue(e.mosaicoVisible)
        assertTrue(e.vender.visible)
        assertFalse(e.vender.habilitada)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_VENDER, e.vender.motivo)
        assertTrue(e.consultar.visible)
        assertFalse(e.consultar.habilitada)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_CONSULTAR, e.consultar.motivo)
    }

    @Test fun `sin red gana a 403 y a permisos plan y carrito`() {
        for (estado in listOf(EstadoDeAjustes.SinPermiso, ajustes(vendible = false, mensaje = "Apagadas", activar = "PLAN"))) {
            val e = hoja(estado, red = false, leer = false, vender = false, vacia = false)
            assertFalse(e.vender.habilitada)
            assertFalse(e.consultar.habilitada)
            assertEquals(TextosDeTarjetas.SIN_RED, e.vender.motivo)
            assertEquals(TextosDeTarjetas.SIN_RED, e.consultar.motivo)
        }
    }

    @Test fun `permisos de vender y consultar son independientes`() {
        val sinVender = hoja(vender = false)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_VENDER, sinVender.vender.motivo)
        assertTrue(sinVender.consultar.habilitada)
        val sinLeer = hoja(leer = false)
        assertTrue(sinLeer.vender.habilitada)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_CONSULTAR, sinLeer.consultar.motivo)
    }

    @Test fun `permiso gana a mensaje del servidor y a carrito`() {
        val e = hoja(ajustes(vendible = false, mensaje = "Apagadas"), vender = false, vacia = false)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_VENDER, e.vender.motivo)
    }

    @Test fun `no vendible conserva mensaje literal y etiqueta Pro`() {
        val mensaje = "Las tarjetas de regalo vienen en el plan Pro o como función suelta. Revísalo en tu panel: Ajustes › Plan."
        val e = hoja(ajustes(vendible = false, mensaje = mensaje, activar = "PLAN"), vacia = false)
        assertFalse(e.vender.habilitada)
        assertEquals(mensaje, e.vender.motivo)
        assertEquals("Pro", e.vender.etiquetaDePlan)
        assertTrue(e.consultar.habilitada)
    }

    @Test fun `apagada o sin llaves no inventa etiqueta de plan`() {
        for (activar in listOf("DASHBOARD_SETTINGS", "CONTACT_AVOQADO")) {
            val e = hoja(ajustes(vendible = false, mensaje = "Mensaje del servidor", activar = activar))
            assertEquals("Mensaje del servidor", e.vender.motivo)
            assertNull(e.vender.etiquetaDePlan)
        }
    }

    @Test fun `venta con articulos explica su cuenta propia`() {
        val e = hoja(vacia = false)
        assertTrue(e.vender.visible)
        assertFalse(e.vender.habilitada)
        assertEquals(TextosDeTarjetas.VENTA_CON_ARTICULOS, e.vender.motivo)
        assertTrue(e.consultar.habilitada)
    }

    @Test fun `con entrega permisos red y venta vacia ambas acciones activas`() {
        val e = hoja()
        assertTrue(e.mosaicoVisible)
        assertTrue(e.vender.habilitada)
        assertTrue(e.consultar.habilitada)
        assertNull(e.vender.motivo)
        assertNull(e.vender.etiquetaDePlan)
        assertNull(e.consultar.motivo)
    }
}
