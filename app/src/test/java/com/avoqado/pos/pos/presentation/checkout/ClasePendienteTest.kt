package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.reservations.data.model.ReservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D13 (Codex r2 #3): recepción pudo registrar la llegada o cancelar mientras la clase esperaba en Mostrador. */
class ClasePendienteTest {
    @Test fun `la reserva que sigue confirmada se cobra aqui`() {
        assertNull(yaNoSeCobraAqui(ReservationStatus.CONFIRMED, "Bea"))
        assertNull(yaNoSeCobraAqui(ReservationStatus.PENDING, "Bea"))
        // Codex r3 #1: el QR del kiosco la marca «llegó» sin abrir cuenta: se sigue cobrando aquí.
        assertNull(yaNoSeCobraAqui(ReservationStatus.CHECKED_IN, "Bea"))
    }

    @Test fun `solo la cancelada ya no se cobra aqui`() {
        assertEquals("La reserva de Bea se canceló: ya no se cobra aquí.", yaNoSeCobraAqui(ReservationStatus.CANCELLED, "Bea"))
        // Codex r6 #1: recepción la completa sin cobro; «no se presentó» con la persona en caja es un error de recepción.
        assertNull(yaNoSeCobraAqui(ReservationStatus.COMPLETED, "Bea"))
        assertNull(yaNoSeCobraAqui(ReservationStatus.NO_SHOW, null))
    }
}
