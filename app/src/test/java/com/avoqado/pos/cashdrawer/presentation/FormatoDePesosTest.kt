package com.avoqado.pos.cashdrawer.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatoDePesosTest {

    @Test
    fun `P2 un faltante del corte lleva el signo antes del simbolo`() {
        assertEquals("-$129.00", formatCurrency(-12900))
    }

    @Test
    fun `un monto positivo sale con separador de miles`() {
        assertEquals("$1,234.50", formatCurrency(123450))
    }
}
