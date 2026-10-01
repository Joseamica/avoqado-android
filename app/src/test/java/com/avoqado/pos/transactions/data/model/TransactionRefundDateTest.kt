package com.avoqado.pos.transactions.data.model

import com.avoqado.pos.core.util.VenueTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La hora del reembolso se pinta en la zona del NEGOCIO, no el texto UTC crudo del servidor.
 * QA en la CPad, 1-oct-2026: un reembolso hecho a las 10:54 en México salía «2026-10-01 16:54».
 */
class TransactionRefundDateTest {

    @Before fun zona() = VenueTimeZone.set("America/Mexico_City")

    @Test
    fun `la hora sale en la zona del negocio`() {
        val refund = TransactionRefund(id = "r1", createdAt = "2026-10-01T16:54:38.302Z")
        assertEquals("01/10/26, 10:54", refund.dateShortDisplay)
    }

    @Test
    fun `sin fecha no pinta nada`() {
        assertNull(TransactionRefund(id = "r1").dateShortDisplay)
    }
}
