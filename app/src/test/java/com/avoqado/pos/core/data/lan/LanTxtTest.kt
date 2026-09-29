package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El TXT del anuncio, PURO (etapa 3 del KDS, 3.5, D1). Espejo de `LanTxtTests` de iOS. */
class LanTxtTest {

    @Test
    fun `construir pone kds solo si hay estaciones, y hub siempre`() {
        val sin = LanTxt.construir("tablet-1", "venue-1", isWired = true, bootedAtMillis = 1_000, kdsStations = emptySet(), hub = false)
        assertEquals(mapOf("did" to "tablet-1", "wired" to "1", "boot" to "1000", "venue" to "venue-1", "hub" to "0"), sin)
        assertFalse(sin.containsKey("kds"))

        val con = LanTxt.construir("tablet-1", "venue-1", isWired = false, bootedAtMillis = 1_000, kdsStations = setOf("st_postres", "st_barra"), hub = true)
        assertEquals("st_barra,st_postres", con["kds"])
        assertEquals("1", con["hub"])
        assertEquals("0", con["wired"])
    }

    @Test
    fun `estacionesDe separa por coma y tolera vacio`() {
        assertEquals(setOf("st_barra", "st_postres"), LanTxt.estacionesDe("st_barra, st_postres"))
        assertEquals(emptySet<String>(), LanTxt.estacionesDe(""))
        assertEquals(emptySet<String>(), LanTxt.estacionesDe(null))
    }

    @Test
    fun `P1 peerDesde ignora otro venue, se ignora a si mismo y acepta un peer sin venue`() {
        val base = mapOf("did" to "otro", "wired" to "1", "boot" to "5", "venue" to "venue-1")
        assertNull(LanTxt.peerDesde(base + ("venue" to "venue-2"), "10.0.0.2", 9000, "yo", "venue-1"))
        assertNull(LanTxt.peerDesde(base + ("did" to "yo"), "10.0.0.2", 9000, "yo", "venue-1"))
        assertNull(LanTxt.peerDesde(base, null, 9000, "yo", "venue-1"))
        assertNull(LanTxt.peerDesde(base - "did", "10.0.0.2", 9000, "yo", "venue-1"))
        val sinVenue = LanTxt.peerDesde(base - "venue", "10.0.0.2", 9000, "yo", "venue-1")
        assertEquals("otro", sinVenue?.deviceId)
        assertTrue(sinVenue!!.isWired)
        assertEquals(5L, sinVenue.bootedAtMillis)
    }

    @Test
    fun `peerDesde lee kds y hub - sin hub en el TXT es una app vieja que SI sirve leases`() {
        val viejo = LanTxt.peerDesde(mapOf("did" to "otro", "venue" to "venue-1"), "10.0.0.2", 9000, "yo", "venue-1")!!
        assertTrue(viejo.sirveLeases)
        assertEquals(emptySet<String>(), viejo.kdsStations)

        val cocina = LanTxt.peerDesde(mapOf("did" to "cpad", "venue" to "venue-1", "kds" to "st_barra", "hub" to "0"), "10.0.0.3", 9001, "yo", "venue-1")!!
        assertFalse(cocina.sirveLeases)
        assertEquals(setOf("st_barra"), cocina.kdsStations)
    }
}
