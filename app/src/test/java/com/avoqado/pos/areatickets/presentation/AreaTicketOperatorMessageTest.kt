package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.areatickets.data.AreaTicketException
import org.junit.Assert.assertEquals
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

    @Test
    fun `sin red en area externa dice que hacer y no promete el POS normal`() {
        val offline = AreaTicketException(
            "AREA_TICKETS_REQUIRE_CONNECTION",
            "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
            true,
        )
        assertEquals(
            "Sin conexión con Avoqado no se puede emitir el vale. Reintenta cuando vuelva la conexión; mientras, la caja principal puede capturar los productos a mano.",
            areaTicketIssueFailureMessage(offline, externalArea = true),
        )
        assertEquals(offline.message, areaTicketIssueFailureMessage(offline, externalArea = false))
    }

    @Test
    fun `el conflicto de llave avisa del vale que pudo quedar creado`() {
        val conflicto = AreaTicketException("AREA_TICKET_IDEMPOTENCY_CONFLICT", "La llave de idempotencia ya fue utilizada…", false)
        assertEquals(
            "Un vale anterior pudo quedar creado sin imprimirse. Revísalo en el dashboard antes de cobrarlo otra vez; ya puedes emitir este de nuevo.",
            areaTicketIssueFailureMessage(conflicto, externalArea = false),
        )
    }

    @Test
    fun `un rechazo del servidor se muestra tal cual`() {
        val negocio = AreaTicketException("EXTERNAL_CODE_MAPPING_MISSING", "Falta el SKU de caja externa para: Leche de almendra (extra).", false)
        assertEquals("Falta el SKU de caja externa para: Leche de almendra (extra).", areaTicketIssueFailureMessage(negocio, externalArea = true))
    }
}
