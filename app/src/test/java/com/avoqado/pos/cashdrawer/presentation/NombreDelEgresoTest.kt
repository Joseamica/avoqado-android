package com.avoqado.pos.cashdrawer.presentation

import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/** D38: en la lista de movimientos, lo que la cajera saca con el botón se llama «Retiro»; un reembolso en efectivo, no. */
class NombreDelEgresoTest {

    @Test
    fun `el retiro que hace la cajera se llama Retiro`() {
        assertEquals("Retiro", nombreDelEgreso("Bajada al banco"))
        assertEquals("Retiro", nombreDelEgreso(null))
        assertEquals("Retiro", nombreDelEgreso(""))
    }

    @Test
    fun `el egreso que escribe el servidor al reembolsar no se llama Retiro`() {
        assertEquals("Reembolso en efectivo", nombreDelEgreso("${CorteTicketBuilder.PREFIJO_REEMBOLSO} cliente inconforme"))
        assertEquals("Reembolso en efectivo", nombreDelEgreso(CorteTicketBuilder.PREFIJO_REEMBOLSO))
    }
}
