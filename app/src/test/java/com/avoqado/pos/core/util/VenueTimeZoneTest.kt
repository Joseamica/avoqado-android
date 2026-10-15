package com.avoqado.pos.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone

class VenueTimeZoneTest {
    @Test fun `only newer system rules replace ICU`() {
        assertTrue(VenueTimeZone.usarReglasDelSistema("2023c", "2019b"))
        assertFalse(VenueTimeZone.usarReglasDelSistema("2024a", "2024a"))
        assertFalse(VenueTimeZone.usarReglasDelSistema("2023a", "2024a"))
        assertFalse(VenueTimeZone.usarReglasDelSistema(null, "2019b"))
        assertFalse(VenueTimeZone.usarReglasDelSistema("2023c", null))
    }

    @Test fun `Mexico midnight keeps the actual UTC instant`() {
        assertEquals(Instant.parse("2026-10-12T05:30:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            LocalDateTime.of(2026, 10, 11, 23, 30), TimeZone.getTimeZone("America/Mexico_City")))
    }

    @Test fun `historical summer time remains distinct from current Mexico time`() {
        assertEquals(Instant.parse("2019-07-01T01:00:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            LocalDateTime.of(2019, 6, 30, 20, 0), TimeZone.getTimeZone("America/Mexico_City")))
    }

    @Test fun `overlapping border hour chooses the first occurrence`() {
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            LocalDateTime.of(2026, 11, 1, 1, 30), TimeZone.getTimeZone("America/Tijuana")))
    }

    @Test fun `missing border hour advances through the spring gap`() {
        assertEquals(Instant.parse("2026-03-08T10:30:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            LocalDateTime.of(2026, 3, 8, 2, 30), TimeZone.getTimeZone("America/Tijuana")))
    }

    @Test fun `local conversion preserves submillisecond precision`() {
        assertEquals(Instant.parse("2026-10-12T05:30:00.123456789Z"), VenueTimeZone.instanteConReglasDelSistema(
            LocalDateTime.of(2026, 10, 11, 23, 30, 0, 123456789), TimeZone.getTimeZone("America/Mexico_City")))
    }

    @Test fun `UTC offset spelling retains zero offset`() {
        assertEquals(0, VenueTimeZone.timeZoneFor("Z").rawOffset)
        assertEquals(0, VenueTimeZone.timeZoneFor("UTC").rawOffset)
        assertEquals(3600000, VenueTimeZone.timeZoneFor("+01:00").rawOffset)
    }


    @Test fun `overlapping border hour retains a valid preferred offset`() {
        val local = LocalDateTime.of(2026, 11, 1, 1, 30)
        val zone = TimeZone.getTimeZone("America/Tijuana")
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            local, zone, java.time.ZoneOffset.ofHours(-8)))
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), VenueTimeZone.instanteConReglasDelSistema(
            local, zone, java.time.ZoneOffset.ofHours(-6)))
    }


    @Test fun `timezone files follow the active Android version`() {
        val data = "/data/misc/zoneinfo/current/tzdata"
        val apex = "/apex/com.android.tzdata/etc/tz/tzdata"
        val system = "/system/usr/share/zoneinfo/tzdata"
        assertEquals(listOf(data, system), VenueTimeZone.rutasDelSistema(28))
        assertEquals(listOf(data, apex, "/apex/com.android.runtime/etc/tz/tzdata", system), VenueTimeZone.rutasDelSistema(29))
        assertEquals(listOf(data, apex), VenueTimeZone.rutasDelSistema(30))
        assertEquals(listOf(apex), VenueTimeZone.rutasDelSistema(31))
        assertEquals(listOf(apex), VenueTimeZone.rutasDelSistema(34))
    }

}
