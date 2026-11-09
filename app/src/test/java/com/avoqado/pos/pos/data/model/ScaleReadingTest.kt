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

    @Test
    fun `Rhino BAR-8RS decodes P response and requires matching readings`() {
        val first = decodeScaleFrame(
            ScaleProtocol.RHINO_BAR8RS_ASCII,
            "rhino-cremeria",
            " 0.096 kg",
            now,
        ).reading!!
        val second = decodeScaleFrame(
            ScaleProtocol.RHINO_BAR8RS_ASCII,
            "rhino-cremeria",
            "0.096 kg",
            now + 500,
        ).reading!!
        val tracker = ScaleStabilityTracker()

        assertEquals("0.096", first.netKg)
        assertFalse(tracker.observe(first).stable)
        assertTrue(tracker.observe(second).stable)
        assertEquals("P", ScaleProtocol.RHINO_BAR8RS_ASCII.pollCommand?.decodeToString())
        assertEquals(9_600, ScaleProtocol.RHINO_BAR8RS_ASCII.defaultBaudRate)
    }

    private fun rhino(weight: String, at: Long) = decodeScaleFrame(
        ScaleProtocol.RHINO_BAR8RS_ASCII,
        "rhino-cremeria",
        "$weight kg",
        at,
    ).reading!!

    private fun rhinoTracker() = stabilityTrackerFor(ScaleProtocol.RHINO_BAR8RS_ASCII)

    /** Medido en CREMERIA 1 de La Galeterie (9-nov): el último dígito brinca un paso de 2 g y apagaba «Agregar». */
    @Test
    fun `Rhino stays stable when the last digit bounces one scale division`() {
        val tracker = rhinoTracker()

        assertFalse(tracker.observe(rhino("0.004", now)).stable)
        assertFalse(tracker.observe(rhino("0.004", now + 500)).stable)
        assertTrue(tracker.observe(rhino("0.002", now + 1_000)).stable)
        assertTrue(tracker.observe(rhino("0.004", now + 1_500)).stable)
        assertTrue(tracker.observe(rhino("0.002", now + 2_000)).stable)
    }

    @Test
    fun `Rhino tolerates a full 5 g step despite Double rounding`() {
        val tracker = rhinoTracker()

        assertFalse(tracker.observe(rhino("15.430", now)).stable)
        assertFalse(tracker.observe(rhino("15.435", now + 500)).stable)
        assertTrue(tracker.observe(rhino("15.430", now + 1_000)).stable)
    }

    @Test
    fun `Rhino becomes unstable when the weight really moves`() {
        val tracker = rhinoTracker()

        assertFalse(tracker.observe(rhino("0.004", now)).stable)
        assertFalse(tracker.observe(rhino("0.004", now + 500)).stable)
        assertTrue(tracker.observe(rhino("0.004", now + 1_000)).stable)
        assertFalse(tracker.observe(rhino("0.250", now + 1_500)).stable)
        assertFalse(tracker.observe(rhino("0.250", now + 2_000)).stable)
        assertTrue(tracker.observe(rhino("0.250", now + 2_500)).stable)
    }

    /** Codex r1 #1: con un ancla que se reiniciaba, el acomodo lento quedaba «estable» a ratos. */
    @Test
    fun `Rhino slow creep is never stable`() {
        val tracker = rhinoTracker()

        listOf("0.430", "0.434", "0.438", "0.442", "0.446").forEachIndexed { i, kg ->
            assertFalse("lectura $kg", tracker.observe(rhino(kg, now + 500L * i)).stable)
        }
    }

    @Test
    fun `Rhino stability restarts after a late reading`() {
        val tracker = rhinoTracker()

        assertFalse(tracker.observe(rhino("0.500", now)).stable)
        assertFalse(tracker.observe(rhino("0.500", now + 500)).stable)
        assertFalse(tracker.observe(rhino("0.500", now + 2_000)).stable)
        assertFalse(tracker.observe(rhino("0.500", now + 2_500)).stable)
        assertTrue(tracker.observe(rhino("0.500", now + 3_000)).stable)
    }

    /** Codex r2: un cero en medio (quitar el producto y volver a ponerlo) corta la racha de la Rhino. */
    @Test
    fun `Rhino rejected frame breaks the run of readings`() {
        val tracker = rhinoTracker()

        assertFalse(tracker.observe(rhino("0.430", now)).stable)
        assertFalse(tracker.observe(rhino("0.434", now + 500)).stable)
        tracker.rejected()
        assertFalse(tracker.observe(rhino("0.430", now + 1_500)).stable)
        assertFalse(tracker.observe(rhino("0.430", now + 2_000)).stable)
        assertTrue(tracker.observe(rhino("0.430", now + 2_500)).stable)
    }

    @Test
    fun `Torrey ignores a rejected frame as before`() {
        val tracker = stabilityTrackerFor(ScaleProtocol.TORREY_PCR_ASCII)
        fun torrey(weight: String, at: Long) = decodeScaleFrame(
            ScaleProtocol.TORREY_PCR_ASCII,
            "torrey-pcr",
            "$weight kg",
            at,
        ).reading!!

        assertFalse(tracker.observe(torrey("0.435", now)).stable)
        tracker.rejected()
        assertTrue(tracker.observe(torrey("0.435", now + 250)).stable)
    }

    /** Codex r1 #2: Torrey (pasos de 1 g en la PCR-20) debe seguir rechazando un brinco de 2 g, como antes. */
    @Test
    fun `Torrey keeps comparing each reading with the previous one`() {
        val tracker = stabilityTrackerFor(ScaleProtocol.TORREY_PCR_ASCII)
        fun torrey(weight: String, at: Long) = decodeScaleFrame(
            ScaleProtocol.TORREY_PCR_ASCII,
            "torrey-pcr",
            "$weight kg",
            at,
        ).reading!!

        assertFalse(tracker.observe(torrey("1.125", now)).stable)
        assertTrue(tracker.observe(torrey("1.126", now + 250)).stable)
        assertFalse(tracker.observe(torrey("1.124", now + 500)).stable)
    }

    @Test
    fun `only Rhino changes its stability rule`() {
        assertEquals(0.0051, ScaleProtocol.RHINO_BAR8RS_ASCII.stabilityToleranceKg, 0.0)
        assertEquals(3, ScaleProtocol.RHINO_BAR8RS_ASCII.stabilityReadings)
        for (other in listOf(ScaleProtocol.TORREY_PCR_ASCII, ScaleProtocol.JUSTA_LP7516_ASCII)) {
            assertEquals(0.001, other.stabilityToleranceKg, 0.0)
            assertEquals(2, other.stabilityReadings)
        }
    }

    @Test
    fun `Rhino rejects negative weight and non kilogram units`() {
        val negative = decodeScaleFrame(
            ScaleProtocol.RHINO_BAR8RS_ASCII,
            "rhino",
            "-0.096 kg",
            now,
        )
        val pounds = decodeScaleFrame(
            ScaleProtocol.RHINO_BAR8RS_ASCII,
            "rhino",
            "0.500 lb",
            now,
        )

        assertEquals(ScaleFrameRejection.NEGATIVE_WEIGHT, negative.rejection)
        assertEquals(ScaleFrameRejection.UNSUPPORTED_UNIT, pounds.rejection)
    }

    @Test
    fun `frame assembler tolerates fragmented response without line ending`() {
        val assembler = ScaleFrameAssembler()

        assertTrue(assembler.append(ScaleProtocol.RHINO_BAR8RS_ASCII, "0.0").isEmpty())
        assertEquals(
            listOf("0.096 kg"),
            assembler.append(ScaleProtocol.RHINO_BAR8RS_ASCII, "96 kg"),
        )
    }

    @Test
    fun `frame assembler separates coalesced responses and LP line endings`() {
        val polled = ScaleFrameAssembler()
        val continuous = ScaleFrameAssembler()

        assertEquals(
            listOf("0.096 kg", "0.097 kg"),
            polled.append(ScaleProtocol.RHINO_BAR8RS_ASCII, "0.096 kg0.097 kg"),
        )
        assertEquals(
            listOf("ST,GS,+  36.320kg"),
            continuous.append(
                ScaleProtocol.JUSTA_LP7516_ASCII,
                "ST,GS,+  36.320kg\r\n",
            ),
        )
    }
}
