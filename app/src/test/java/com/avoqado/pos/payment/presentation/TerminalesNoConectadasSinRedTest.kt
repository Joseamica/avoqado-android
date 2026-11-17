package com.avoqado.pos.payment.presentation

import com.avoqado.pos.payment.presentation.PaymentFlowViewModel.TerminalAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D68 #12 (prueba final del 13-nov): la sonda corrió con internet y sin terminal conectada, se cortó la red y «Cobrar con terminal»
 * seguía en gris con «No hay terminales conectadas». Sin red, la fila se toca y el cobro dice que la tarjeta necesita internet.
 */
class TerminalesNoConectadasSinRedTest {
    @Test fun `con red y sin terminales el gris se queda`() {
        assertTrue(terminalesNoConectadasCon(TerminalAvailability.NONE, enLinea = true))
    }

    @Test fun `sin red el gris de no hay terminales no se ensena`() {
        assertFalse(terminalesNoConectadasCon(TerminalAvailability.NONE, enLinea = false))
    }

    @Test fun `con terminal, revisando o sin poder verificar nunca es gris`() {
        for (d in listOf(TerminalAvailability.AVAILABLE, TerminalAvailability.CHECKING, TerminalAvailability.ERROR)) {
            assertFalse(terminalesNoConectadasCon(d, enLinea = true))
            assertFalse(terminalesNoConectadasCon(d, enLinea = false))
        }
    }
}
