package com.avoqado.pos.core.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avoqado.pos.transactions.data.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class VenueTimeZoneDeviceTest {
    private lateinit var original: String

    @Before fun rememberVenue() {
        original = VenueTimeZone.current
        VenueTimeZone.set("America/Mexico_City")
    }

    @After fun restoreVenue() { VenueTimeZone.set(original) }

    @Test fun sameCashPaymentShowsMexicoCityTime() {
        val payment = Transaction(id = "FULLTEST-timezone", createdAt = "2026-10-12T01:07:42.313Z")
        assertEquals("19:07", payment.timeDisplay)
    }

    @Test fun midnightBoundaryStaysOnOriginalBusinessDay() {
        val payment = Transaction(id = "FULLTEST-midnight", createdAt = "2026-10-12T05:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 11), payment.parsedDateTime?.toLocalDate())
        assertEquals("23:30", payment.timeDisplay)
    }

    @Test fun historicalSummerTimeIsPreserved() {
        val payment = Transaction(id = "FULLTEST-history", createdAt = "2019-07-01T01:00:00Z")
        assertEquals("20:00", payment.timeDisplay)
        assertEquals(LocalDate.of(2019, 6, 30), payment.parsedDateTime?.toLocalDate())
    }

    @Test fun borderTimezoneKeepsItsOwnRules() {
        VenueTimeZone.set("America/Tijuana")
        val payment = Transaction(id = "FULLTEST-border", createdAt = "2026-10-12T01:07:42.313Z")
        assertEquals("18:07", payment.timeDisplay)
    }

    @Test fun localAppointmentUsesCurrentMexicoRules() {
        assertEquals(java.time.Instant.parse("2026-10-12T05:30:00Z"),
            VenueTimeZone.fromLocalDateTime(java.time.LocalDateTime.of(2026, 10, 11, 23, 30)).toInstant())
    }

    @Test fun borderOverlapAndGapKeepHistoricalRules() {
        assertEquals(java.time.Instant.parse("2026-11-01T08:30:00Z"),
            VenueTimeZone.fromLocalDateTime(java.time.LocalDateTime.of(2026, 11, 1, 1, 30), "America/Tijuana").toInstant())
        assertEquals(java.time.Instant.parse("2026-03-08T10:30:00Z"),
            VenueTimeZone.fromLocalDateTime(java.time.LocalDateTime.of(2026, 3, 8, 2, 30), "America/Tijuana").toInstant())
    }


    @Test fun draggedCalendarHourKeepsItsInstantAcrossOverlap() {
        val shifted = VenueTimeZone.at(java.time.Instant.parse("2026-11-01T14:00:00Z").minusSeconds(330 * 60L), "America/Tijuana")
        assertEquals(1, shifted.hour)
        assertEquals(30, shifted.minute)
        assertEquals(java.time.ZoneOffset.ofHours(-7), shifted.offset)
        val secondOccurrence = VenueTimeZone.at(java.time.Instant.parse("2026-11-01T14:00:00Z").minusSeconds(270 * 60L), "America/Tijuana")
        assertEquals(java.time.Instant.parse("2026-11-01T09:30:00Z"),
            VenueTimeZone.fromLocalDateTime(secondOccurrence.toLocalDateTime(), "America/Tijuana", secondOccurrence.offset).toInstant())
    }

}
