package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStatusTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `PENDING CONFIRMED CHECKED_IN are active`() {
        assertTrue(ReservationStatus.PENDING.isActive)
        assertTrue(ReservationStatus.CONFIRMED.isActive)
        assertTrue(ReservationStatus.CHECKED_IN.isActive)
    }

    @Test
    fun `COMPLETED CANCELLED NO_SHOW are terminal`() {
        assertTrue(ReservationStatus.COMPLETED.isTerminal)
        assertTrue(ReservationStatus.CANCELLED.isTerminal)
        assertTrue(ReservationStatus.NO_SHOW.isTerminal)
        assertFalse(ReservationStatus.PENDING.isTerminal)
    }

    @Test
    fun `serializes via SerialName uppercase`() {
        val encoded = json.encodeToString(ReservationStatus.serializer(), ReservationStatus.CHECKED_IN)
        assertEquals("\"CHECKED_IN\"", encoded)
    }

    @Test
    fun `decodes server enum strings`() {
        val decoded = json.decodeFromString(ReservationStatus.serializer(), "\"NO_SHOW\"")
        assertEquals(ReservationStatus.NO_SHOW, decoded)
    }
}
