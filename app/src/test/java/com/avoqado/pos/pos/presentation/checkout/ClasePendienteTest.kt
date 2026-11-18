package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.reservations.data.model.ReservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** D13 (Codex r2 #3): recepción pudo registrar la llegada o cancelar mientras la clase esperaba en Mostrador. */
class ClasePendienteTest {
    @Test fun `la reserva que sigue confirmada se cobra aqui`() {
        assertNull(yaNoSeCobraAqui(ReservationStatus.CONFIRMED, "Bea"))
        assertNull(yaNoSeCobraAqui(ReservationStatus.PENDING, "Bea"))
    }

    @Test fun `la que ya registro recepcion o se cancelo ya no se cobra aqui`() {
        assertEquals("La reserva de Bea se canceló: ya no se cobra aquí.", yaNoSeCobraAqui(ReservationStatus.CANCELLED, "Bea"))
        assertNotNull(yaNoSeCobraAqui(ReservationStatus.CHECKED_IN, "Bea"))
        assertNotNull(yaNoSeCobraAqui(ReservationStatus.NO_SHOW, null))
        assertNotNull(yaNoSeCobraAqui(ReservationStatus.COMPLETED, ""))
    }
}
