package com.avoqado.pos.areatickets.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaTicketOperatorMessageTest {
    @Test
    fun `printer connection failure keeps the ticket code and gives an actionable route`() {
        val message = areaTicketPrintFailureMessage(
            code = "9069942672",
            error = IllegalStateException(
                "failed to connect to /192.168.100.220 (port 9100) from /192.168.1.192 after 10000ms",
            ),
        )

        assertTrue(message.contains("9069942672"))
        assertTrue(message.contains("Más → Impresora"))
        assertTrue(message.contains("misma red"))
        assertTrue(message.contains("PDF"))
        assertFalse(message.contains("192.168"))
        assertFalse(message.contains("9100"))
    }
}
