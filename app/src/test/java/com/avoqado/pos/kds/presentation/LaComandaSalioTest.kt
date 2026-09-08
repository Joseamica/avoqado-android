package com.avoqado.pos.kds.presentation

import com.avoqado.pos.printing.data.EstadoDeComanda
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 #11 de la auditoría de Codex (2026-09-07). El KDS confirmaba como impresa una comanda que
 * NO salió, y ese `confirm-print` impide que otra tablet la recoja: el pedido se pierde con su
 * reclamación consumida.
 */
class LaComandaSalioTest {

    @Test
    fun `P1 solo un Salio cuenta como impresa`() {
        assertTrue(laComandaSalio(EstadoDeComanda.Salio))
    }

    @Test
    fun `P1 un NoSalio NO se confirma — se suelta para que otra tablet lo intente`() {
        val fallo = EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "ORD-1", trabajo = null)
        assertFalse("confirmarla deja el pedido sin comanda Y sin quien lo recoja", laComandaSalio(fallo))
    }

    @Test
    fun `P1 un null tampoco se confirma — es el lado seguro`() {
        assertFalse(laComandaSalio(null))
    }

    @Test
    fun `un Insistiendo todavia no es una comanda impresa`() {
        assertFalse(laComandaSalio(EstadoDeComanda.Insistiendo(2, 6, listOf("Cocina"), "ORD-1")))
    }
}
