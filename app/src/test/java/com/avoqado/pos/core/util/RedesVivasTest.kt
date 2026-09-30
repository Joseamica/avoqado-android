package com.avoqado.pos.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `registerNetworkCallback` avisa POR RED. Perder el WiFi no deja al aparato sin red si siguen vivos
 * los datos del SIM. Medido en la N86 (29-sep): el monitor marcaba «sin conexión» al caer el WiFi y
 * el aviso se quedó pegado ~10 min con el servidor contestando por datos.
 */
class RedesVivasTest {

    @Test
    fun `P1 perder el WiFi con los datos vivos NO deja al aparato sin red`() {
        val redes = RedesVivas()
        redes.agregar("wifi")
        redes.agregar("datos")
        assertTrue(redes.quitar("wifi"))
    }

    @Test
    fun `perder la ultima red si deja al aparato sin red`() {
        val redes = RedesVivas()
        redes.agregar("wifi")
        assertFalse(redes.quitar("wifi"))
    }

    @Test
    fun `perder una red que nunca se aviso no apaga las que siguen vivas`() {
        val redes = RedesVivas()
        redes.agregar("datos")
        assertTrue(redes.quitar("desconocida"))
    }
}
