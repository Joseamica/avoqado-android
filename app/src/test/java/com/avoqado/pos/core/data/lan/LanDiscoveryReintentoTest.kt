package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Un registro NSD fallido se reintenta con espera acotada (1, 2, 4, 8, 16, 30 s) y la ráfaga se agota: nunca un bucle caliente. */
class LanDiscoveryReintentoTest {

    @Test
    fun `la espera crece y se acota a 30 s, y a los 6 intentos se rinde hasta el siguiente reanunciar`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), (0..5).map { LanDiscovery.esperaDeReintentoDeAnuncio(it) })
        assertNull(LanDiscovery.esperaDeReintentoDeAnuncio(6))
    }
}
