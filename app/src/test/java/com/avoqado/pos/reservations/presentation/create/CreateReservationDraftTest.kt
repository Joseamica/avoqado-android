package com.avoqado.pos.reservations.presentation.create

import com.avoqado.pos.reservations.domain.CreateReservationDraft
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CreateReservationDraftTest {
    @Test
    fun `draft produces matching ISO timestamps in venue zone`() {
        val draft = CreateReservationDraft(
            customerId = "c1",
            productId = "p1",
            durationMinutes = 90,
            date = LocalDate.of(2026, 5, 1),
            time = LocalTime.of(19, 30),
            partySize = 4,
        )
        val req = draft.toRequest(ZoneId.of("America/Mexico_City"))
        // 19:30 CDMX (UTC-6 standard) = 01:30 UTC next day
        assertEquals("2026-05-02T01:30:00Z", req.startsAt)
        assertEquals("2026-05-02T03:00:00Z", req.endsAt)
        assertEquals(4, req.partySize)
        assertEquals("c1", req.customerId)
        assertEquals("p1", req.productId)
    }
}
