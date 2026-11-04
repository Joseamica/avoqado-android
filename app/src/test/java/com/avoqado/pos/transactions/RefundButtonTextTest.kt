package com.avoqado.pos.transactions

import com.avoqado.pos.transactions.data.model.textoDelBotonDeReembolso
import org.junit.Assert.assertEquals
import org.junit.Test

/** D9: un toque devuelve dinero y no hay confirmación aparte, así que el botón dice cuánto. */
class RefundButtonTextTest {

    @Test
    fun `dice el importe con el formato de la pantalla`() {
        assertEquals("Reembolsar $129.00", textoDelBotonDeReembolso(129.0))
        assertEquals("Reembolsar $1,234.50", textoDelBotonDeReembolso(1234.5))
        assertEquals("Reembolsar $0.01", textoDelBotonDeReembolso(0.01))
    }

    @Test
    fun `sin importe sólo dice el verbo`() {
        assertEquals("Reembolsar", textoDelBotonDeReembolso(0.0))
        assertEquals("Reembolsar", textoDelBotonDeReembolso(-1.0))
    }
}
