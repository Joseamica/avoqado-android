package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleReadingTest {

    private val now = 10_000L

    private fun reading(
        netKg: String = "0.435",
        stable: Boolean = true,
        observedAt: Long = now,
    ) = NormalizedScaleReading(
        deviceId = "qa-scale",
        grossKg = netKg,
        tareKg = "0.000",
        netKg = netKg,
        stable = stable,
        observedAtEpochMillis = observedAt,
    )

    @Test
    fun `accepts a fresh stable net weight`() {
        val result = validateScaleReading(reading(), nowEpochMillis = now)

        assertTrue(result.accepted)
        assertEquals(0.435, result.weightKg!!, 0.0)
        assertNull(result.rejection)
    }

    @Test
    fun `rejects an unstable reading`() {
        val result = validateScaleReading(reading(stable = false), nowEpochMillis = now)

        assertFalse(result.accepted)
        assertNull(result.weightKg)
        assertEquals(ScaleReadingRejection.UNSTABLE, result.rejection)
    }

    @Test
    fun `rejects a stale reading`() {
        val result = validateScaleReading(
            reading(observedAt = now - DEFAULT_SCALE_READING_MAX_AGE_MILLIS - 1),
            nowEpochMillis = now,
        )

        assertFalse(result.accepted)
        assertEquals(ScaleReadingRejection.STALE, result.rejection)
    }

    @Test
    fun `rejects a reading from the future`() {
        val result = validateScaleReading(reading(observedAt = now + 1), nowEpochMillis = now)

        assertFalse(result.accepted)
        assertEquals(ScaleReadingRejection.STALE, result.rejection)
    }

    @Test
    fun `rejects zero or malformed net weight`() {
        val zero = validateScaleReading(reading(netKg = "0.000"), nowEpochMillis = now)
        val malformed = validateScaleReading(reading(netKg = "kg"), nowEpochMillis = now)
        val negative = validateScaleReading(reading(netKg = "-0.435"), nowEpochMillis = now)

        assertEquals(ScaleReadingRejection.INVALID_WEIGHT, zero.rejection)
        assertEquals(ScaleReadingRejection.INVALID_WEIGHT, malformed.rejection)
        assertEquals(ScaleReadingRejection.INVALID_WEIGHT, negative.rejection)
    }

    @Test
    fun `decodes stable and unstable LP7516 frames`() {
        val stable = decodeScaleFrame(
            ScaleProtocol.JUSTA_LP7516_ASCII,
            "justa-cedis",
            "ST,GS,+  36.320kg\r\n",
            now,
        )
        val unstable = decodeScaleFrame(
            ScaleProtocol.JUSTA_LP7516_ASCII,
            "justa-cedis",
            "US,NT,+0.435kg",
            now,
        )

        assertTrue(stable.accepted)
        assertEquals("36.320", stable.reading?.netKg)
        assertEquals("36.320", stable.reading?.grossKg)
        assertTrue(stable.reading?.stable == true)
        assertTrue(unstable.accepted)
        assertEquals("0.435", unstable.reading?.netKg)
        assertNull(unstable.reading?.grossKg)
        assertFalse(unstable.reading?.stable == true)
    }

    @Test
    fun `rejects overload negative and pounds from LP7516`() {
        val overload = decodeScaleFrame(
            ScaleProtocol.JUSTA_LP7516_ASCII,
            "justa",
            "OL,GS,+99.999kg",
            now,
        )
        val negative = decodeScaleFrame(
            ScaleProtocol.JUSTA_LP7516_ASCII,
            "justa",
            "ST,GS,-0.435kg",
            now,
        )
        val pounds = decodeScaleFrame(
            ScaleProtocol.JUSTA_LP7516_ASCII,
            "justa",
            "ST,GS,+1.000lb",
            now,
        )

        assertEquals(ScaleFrameRejection.OVERLOAD, overload.rejection)
        assertEquals(ScaleFrameRejection.NEGATIVE_WEIGHT, negative.rejection)
        assertEquals(ScaleFrameRejection.UNSUPPORTED_UNIT, pounds.rejection)
    }

    @Test
    fun `Torrey PCR requires two matching polled readings`() {
        val first = decodeScaleFrame(
            ScaleProtocol.TORREY_PCR_ASCII,
            "torrey-pcr",
            "\r 0.435 kg\r",
            now,
        ).reading!!
        val second = decodeScaleFrame(
            ScaleProtocol.TORREY_PCR_ASCII,
            "torrey-pcr",
            "\r 0.435 kg\r",
            now + 250,
        ).reading!!
        val tracker = ScaleStabilityTracker()

        assertFalse(tracker.observe(first).stable)
        assertTrue(tracker.observe(second).stable)
    }

    @Test
    fun `Torrey PCR stability resets when weight changes or response is late`() {
        val tracker = ScaleStabilityTracker()
        fun torrey(weight: String, at: Long) = decodeScaleFrame(
            ScaleProtocol.TORREY_PCR_ASCII,
            "torrey-pcr",
            "$weight kg",
            at,
        ).reading!!

        assertFalse(tracker.observe(torrey("0.435", now)).stable)
        assertFalse(tracker.observe(torrey("0.500", now + 250)).stable)
        assertFalse(tracker.observe(torrey("0.500", now + 2_000)).stable)
        assertTrue(tracker.observe(torrey("0.500", now + 2_250)).stable)
    }
}
