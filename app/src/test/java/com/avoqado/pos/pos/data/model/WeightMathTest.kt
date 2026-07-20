package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Venta por peso — parser de báscula/manual y aritmética de línea al centavo.
 *
 * Paridad con el server: total = round(price/kg × weightKg, 2). En centavos:
 * round(weightKg × unitPriceCents) HALF-UP.
 */
class WeightMathTest {

    // MARK: - parseWeightKg

    @Test
    fun `parses plain decimal kilograms`() {
        assertEquals(0.435, parseWeightKg("0.435")!!, 0.0)
    }

    @Test
    fun `accepts comma as decimal separator`() {
        assertEquals(0.435, parseWeightKg("0,435")!!, 0.0)
    }

    @Test
    fun `ignores non-numeric noise like unit suffix`() {
        assertEquals(0.435, parseWeightKg("0.435 kg")!!, 0.0)
    }

    @Test
    fun `grams mode divides by 1000`() {
        assertEquals(0.435, parseWeightKg("435", gramsMode = true)!!, 0.0)
    }

    @Test
    fun `blank input is invalid`() {
        assertNull(parseWeightKg(""))
        assertNull(parseWeightKg("   "))
    }

    @Test
    fun `two decimal separators is invalid`() {
        assertNull(parseWeightKg("0.4.3"))
    }

    @Test
    fun `zero is below range and invalid`() {
        assertNull(parseWeightKg("0"))
        assertNull(parseWeightKg("0.000"))
    }

    @Test
    fun `above max kilograms is invalid`() {
        assertNull(parseWeightKg("100"))
        assertNull(parseWeightKg("100.5"))
    }

    @Test
    fun `value just over max rounds down to the milligram and stays valid`() {
        // 99.9991 kg → redondeo a 3 decimales = 99.999 kg (dentro de rango).
        assertEquals(99.999, parseWeightKg("99.9991")!!, 0.0)
    }

    @Test
    fun `min and max bounds are accepted`() {
        assertEquals(0.001, parseWeightKg("0.001")!!, 0.0)
        assertEquals(99.999, parseWeightKg("99.999")!!, 0.0)
    }

    @Test
    fun `rounds to the milligram (3 decimals)`() {
        // 0.4356 kg → 436 g
        assertEquals(0.436, parseWeightKg("0.4356")!!, 0.0)
    }

    // MARK: - weightTotalCents (parity to the cent)

    @Test
    fun `435 grams at 420 pesos per kg is 18270 cents`() {
        // 0.435 kg × $420.00/kg = $182.70
        assertEquals(18270, weightTotalCents(0.435, 42000))
    }

    @Test
    fun `half-up rounding on a exact half cent rounds up`() {
        // 0.5 kg × $0.01/kg = 0.5 cents → HALF-UP = 1 cent (roundToInt = ties toward +inf)
        assertEquals(1, weightTotalCents(0.5, 1))
    }

    @Test
    fun `zero weight is zero cents`() {
        assertEquals(0, weightTotalCents(0.0, 42000))
    }

    @Test
    fun `salmon 650 per kg at 512 grams is 33280 cents`() {
        // 0.512 kg × $650.00/kg = $332.80
        assertEquals(33280, weightTotalCents(0.512, 65000))
    }

    // MARK: - formatWeightKg

    @Test
    fun `formats three decimals with left padding`() {
        assertEquals("0.435", formatWeightKg(0.435))
        assertEquals("0.050", formatWeightKg(0.05))
        assertEquals("1.500", formatWeightKg(1.5))
        assertEquals("12.000", formatWeightKg(12.0))
    }
}
