package com.avoqado.pos.areatickets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaTicketHttpErrorTest {
    @Test
    fun `uses domain message and code from API envelope`() {
        val error = parseAreaTicketHttpError(
            body = """
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "AREA_TICKET_NOT_FOUND",
                    "message": "No encontramos ese comprobante en este local.",
                    "retryable": false
                  }
                }
            """.trimIndent(),
            statusCode = 404,
        )

        assertEquals("AREA_TICKET_NOT_FOUND", error.code)
        assertEquals("No encontramos ese comprobante en este local.", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun `malformed server error gets safe user-facing fallback`() {
        val error = parseAreaTicketHttpError("<html>not found</html>", 404)

        assertEquals("HTTP_404", error.code)
        assertEquals("No encontramos ese vale o comprobante en este local.", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun `server failures remain retryable when envelope is unavailable`() {
        val error = parseAreaTicketHttpError(null, 503)

        assertTrue(error.retryable)
    }
}
