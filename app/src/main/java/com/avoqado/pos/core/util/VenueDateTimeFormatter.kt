package com.avoqado.pos.core.util

import com.avoqado.pos.core.data.local.SecureStorage
import java.io.DataInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.TimeZone
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

    fun toZonedDateTime(iso: String?): ZonedDateTime? = parseIso(iso)?.let { VenueTimeZone.at(it, secureStorage.venueTimezone) }
    fun toZonedDateTime(epochMillis: Long?): ZonedDateTime? =
        epochMillis?.let { VenueTimeZone.at(Instant.ofEpochMilli(it), secureStorage.venueTimezone) }

    fun toLocalDate(iso: String?): LocalDate? = toZonedDateTime(iso)?.toLocalDate()

    private fun format(instant: Instant?, pattern: String): String {
        if (instant == null) return "N/A"
        val zoned = VenueTimeZone.at(instant, secureStorage.venueTimezone)
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

    // Algunos fabricantes actualizan tzdata pero dejan ICU viejo; nunca sustituir un ICU más reciente.
    private val usarSistema by lazy {
        val versionSistema = rutasDelSistema(android.os.Build.VERSION.SDK_INT).firstNotNullOfOrNull { ruta ->
            runCatching {
                DataInputStream(File(ruta).inputStream()).use { entrada ->
                    val cabecera = ByteArray(12)
                    entrada.readFully(cabecera)
                    Regex("tzdata([0-9]{4}[a-z]+)\\u0000").matchEntire(String(cabecera, Charsets.US_ASCII))?.groupValues?.get(1)
                }
            }.getOrNull()
        }
        // ICU es API pública en Android; lectura opcional porque este archivo también compila en Windows.
        val versionIcu = runCatching {
            Class.forName("android.icu.util.TimeZone").getMethod("getTZDataVersion").invoke(null) as String
        }.getOrNull()
        usarReglasDelSistema(versionSistema, versionIcu)
    }

    // Orden del cargador de tzdata de AOSP: Android 10/11 aún aceptan actualizaciones en /data.
    internal fun rutasDelSistema(sdk: Int): List<String> {
        val data = "/data/misc/zoneinfo/current/tzdata"
        val apex = "/apex/com.android.tzdata/etc/tz/tzdata"
        val system = "/system/usr/share/zoneinfo/tzdata"
        return when {
            sdk >= 31 -> listOf(apex)
            sdk == 30 -> listOf(data, apex)
            sdk == 29 -> listOf(data, apex, "/apex/com.android.runtime/etc/tz/tzdata", system)
            else -> listOf(data, system)
        }
    }

    internal fun usarReglasDelSistema(sistema: String?, icu: String?): Boolean =
        sistema != null && icu != null && sistema > icu

    fun timeZoneFor(timezone: String? = null): TimeZone {
        val id = timezone?.takeIf { it.isNotBlank() } ?: currentId
        val normalized = when {
            id == "Z" -> "GMT"
            id.startsWith("UTC") -> "GMT" + id.removePrefix("UTC")
            id == "UT" || id.startsWith("UT+") || id.startsWith("UT-") -> "GMT" + id.removePrefix("UT")
            id.startsWith("+") || id.startsWith("-") -> "GMT$id"
            else -> id
        }
        return TimeZone.getTimeZone(normalized).takeIf { it.id != "GMT" || normalized == "GMT" }
            ?: TimeZone.getTimeZone(DEFAULT_TZ)
    }

    fun at(instant: Instant, timezone: String? = null): ZonedDateTime =
        if (usarSistema) instant.atZone(ZoneOffset.ofTotalSeconds(timeZoneFor(timezone).getOffset(instant.toEpochMilli()) / 1000))
        else instant.atZone(zoneIdFor(timezone))

    fun now(timezone: String? = null): ZonedDateTime = at(Instant.now(), timezone)

    fun fromLocalDateTime(local: LocalDateTime, timezone: String? = null, preferredOffset: ZoneOffset? = null): ZonedDateTime =
        if (usarSistema) at(instanteConReglasDelSistema(local, timeZoneFor(timezone), preferredOffset), timezone)
        else ZonedDateTime.ofLocal(local, zoneIdFor(timezone), preferredOffset)

    internal fun instanteConReglasDelSistema(local: LocalDateTime, timezone: TimeZone, preferredOffset: ZoneOffset? = null): Instant {
        val wall = local.toInstant(ZoneOffset.UTC).toEpochMilli()
        val offsets = listOf(-172_800_000L, 0L, 172_800_000L).map { timezone.getOffset(Math.addExact(wall, it)) }.distinct()
        val candidates = offsets.map { offset -> Math.subtractExact(wall, offset.toLong()) to offset }
        val valid = candidates.filter { (instant, offset) -> timezone.getOffset(instant) == offset }
        // Como atZone: primera ocurrencia en un solapamiento; avanzar por el hueco de primavera.
        val resolved = valid.firstOrNull { it.second == preferredOffset?.totalSeconds?.times(1000) }?.first
            ?: valid.minOfOrNull { it.first } ?: candidates.maxOf { it.first }
        return Instant.ofEpochMilli(resolved).plusNanos(local.nano % 1_000_000L)
    }

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
