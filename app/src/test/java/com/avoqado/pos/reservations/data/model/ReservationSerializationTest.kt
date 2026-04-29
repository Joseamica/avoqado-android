package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class ReservationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun loadFixture(name: String): String =
        File("src/test/resources/fixtures/$name").readText()

    @Test
    fun `decodes real server list response without losing fields`() {
        val raw = loadFixture("reservation_list_response.json")
        val decoded = json.decodeFromString(ReservationListResponse.serializer(), raw)

        assertNotNull(decoded.data)
        decoded.data.forEach { r ->
            assertNotNull(r.id)
            assertNotNull(r.confirmationCode)
            assertNotNull(r.status)
            assertNotNull(r.startsAt)
            assertNotNull(r.endsAt)
        }
    }

    @Test
    fun `displayName falls back through customer to guestName to placeholder`() {
        val withCustomer = makeReservation(
            customer = CustomerLite("c1", firstName = "María", lastName = "López"),
            guestName = null,
        )
        assertEquals("María López", withCustomer.displayName)

        val withGuest = makeReservation(customer = null, guestName = "Walk-in")
        assertEquals("Walk-in", withGuest.displayName)

        val withNothing = makeReservation(customer = null, guestName = null)
        assertEquals("Sin nombre", withNothing.displayName)
    }

    private fun makeReservation(
        customer: CustomerLite? = null,
        guestName: String? = null,
    ) = Reservation(
        id = "r",
        venueId = "v",
        confirmationCode = "ABC",
        cancelSecret = "secret",
        status = ReservationStatus.CONFIRMED,
        channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z",
        endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60,
        customer = customer,
        guestName = guestName,
        createdAt = "2026-04-29T00:00:00.000Z",
        updatedAt = "2026-04-29T00:00:00.000Z",
    )
}
