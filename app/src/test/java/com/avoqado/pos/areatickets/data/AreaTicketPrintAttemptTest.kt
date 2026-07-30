package com.avoqado.pos.areatickets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AreaTicketPrintAttemptTest {
    @Test
    fun `successful reprint always carries an audit reason`() {
        assertEquals(
            "Reimpresión solicitada por el operador desde el POS.",
            normalizeAreaTicketPrintReason(reprint = true, reason = null),
        )
    }

    @Test
    fun `explicit reprint reason is preserved and trimmed`() {
        assertEquals(
            "Vale dañado",
            normalizeAreaTicketPrintReason(reprint = true, reason = "  Vale dañado  "),
        )
    }

    @Test
    fun `original successful print does not invent a reason`() {
        assertNull(normalizeAreaTicketPrintReason(reprint = false, reason = null))
    }
}
