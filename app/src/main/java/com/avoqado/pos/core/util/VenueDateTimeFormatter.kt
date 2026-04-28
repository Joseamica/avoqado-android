package com.avoqado.pos.core.util

import com.avoqado.pos.core.data.local.SecureStorage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized formatter that converts UTC timestamps to the active venue's timezone.
 * Mirrors the web dashboard's `useVenueDateTime` hook.
 *
 * Backend always sends UTC ISO 8601 strings (e.g. "2026-04-23T14:30:00.000Z") or
 * epoch millis. This class parses them and renders in the venue's local timezone,
 * defaulting to America/Mexico_City when no venue is selected.
 *
 * Two access patterns:
 * - **DI (preferred)**: inject `VenueDateTimeFormatter` into ViewModels/Repositories.
 * - **Static holder**: pure data classes (e.g. `TransactionModel.timeDisplay`) read
 *   the timezone via [VenueTimeZone.current], which is updated by [SecureStorage]
 *   whenever `venueId` changes.
 */
@Singleton
class VenueDateTimeFormatter @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    fun zoneId(): ZoneId = VenueTimeZone.zoneIdFor(secureStorage.venueTimezone)

    fun formatDateTime(iso: String?): String = format(parseIso(iso), DATETIME_PATTERN)
    fun formatDateTime(epochMillis: Long?): String = format(epochMillis?.let(Instant::ofEpochMilli), DATETIME_PATTERN)

    fun formatDate(iso: String?): String = format(parseIso(iso), DATE_PATTERN)
    fun formatDate(epochMillis: Long?): String = format(epochMillis?.let(Instant::ofEpochMilli), DATE_PATTERN)

    fun formatTime(iso: String?): String = format(parseIso(iso), TIME_PATTERN)
    fun formatTime(epochMillis: Long?): String = format(epochMillis?.let(Instant::ofEpochMilli), TIME_PATTERN)

    fun formatShort(iso: String?): String = format(parseIso(iso), SHORT_PATTERN)
    fun formatShort(epochMillis: Long?): String = format(epochMillis?.let(Instant::ofEpochMilli), SHORT_PATTERN)

    fun toZonedDateTime(iso: String?): ZonedDateTime? = parseIso(iso)?.atZone(zoneId())
    fun toZonedDateTime(epochMillis: Long?): ZonedDateTime? =
        epochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId()) }

    fun toLocalDate(iso: String?): LocalDate? = toZonedDateTime(iso)?.toLocalDate()

    private fun format(instant: Instant?, pattern: String): String {
        if (instant == null) return "N/A"
        val zoned = instant.atZone(zoneId())
        return zoned.format(DateTimeFormatter.ofPattern(pattern, SPANISH_MX))
    }

    companion object {
        const val DATETIME_PATTERN = "d 'de' MMM, yyyy, HH:mm"
        const val DATE_PATTERN = "d 'de' MMM, yyyy"
        const val DATE_LONG_PATTERN = "d 'de' MMMM, yyyy"
        const val TIME_PATTERN = "HH:mm"
        const val SHORT_PATTERN = "dd/MM/yy, HH:mm"
        val SPANISH_MX: Locale = Locale("es", "MX")

        /**
         * Parse a UTC ISO 8601 string emitted by the backend. Tolerates several shapes:
         * - "2026-04-23T14:30:00.000Z" (most common)
         * - "2026-04-23T14:30:00Z"
         * - "2026-04-23T14:30:00.000+00:00"
         * - "2026-04-23T14:30:00" (no zone -> assumed UTC)
         */
        fun parseIso(iso: String?): Instant? {
            if (iso.isNullOrBlank()) return null
            return try {
                Instant.parse(iso)
            } catch (_: DateTimeParseException) {
                // Fall back: try LocalDateTime (no zone) and assume UTC.
                try {
                    LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

/**
 * Static holder for the active venue's timezone. Updated by [SecureStorage] when
 * the user logs in or switches venues. Allows pure data classes (DTOs) to format
 * dates without DI plumbing.
 *
 * Default: `America/Mexico_City` until the first venue load.
 */
object VenueTimeZone {
    @Volatile private var currentId: String = DEFAULT_TZ

    val current: String get() = currentId

    fun set(timezone: String?) {
        currentId = timezone?.takeIf { it.isNotBlank() } ?: DEFAULT_TZ
    }

    fun zoneIdFor(timezone: String?): ZoneId = try {
        ZoneId.of(timezone?.takeIf { it.isNotBlank() } ?: currentId)
    } catch (_: Exception) {
        ZoneId.of(DEFAULT_TZ)
    }

    fun zoneId(): ZoneId = zoneIdFor(currentId)

    const val DEFAULT_TZ = "America/Mexico_City"
}
