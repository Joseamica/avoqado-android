package com.avoqado.pos.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** D5 · B3 (C1): la mesa cobrada sin internet es sólo efectivo; el subtítulo ya no manda a «enviar el cobro a una terminal». */
class SubtituloDelCobroTest {
    @Test fun `la mesa cobrada sin internet dice solo efectivo, lo demas como antes`() {
        assertEquals("Cobra en efectivo", subtituloDelCobro(terminalsUnavailable = false, metodosHabilitados = true, soloEfectivoSinRed = true))
        assertEquals("Cobra en efectivo", subtituloDelCobro(terminalsUnavailable = true, metodosHabilitados = true, soloEfectivoSinRed = true))
        assertEquals("Cobra en efectivo o conecta una terminal",
            subtituloDelCobro(terminalsUnavailable = true, metodosHabilitados = true, soloEfectivoSinRed = false))
        assertEquals("Envía el cobro a una terminal para procesar el pago",
            subtituloDelCobro(terminalsUnavailable = false, metodosHabilitados = true, soloEfectivoSinRed = false))
    }
}
