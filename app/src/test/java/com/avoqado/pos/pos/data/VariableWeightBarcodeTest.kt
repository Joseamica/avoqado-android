package com.avoqado.pos.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariableWeightBarcodeTest {

    @Test
    fun `decodes EAN13 prefix plus PLU5 plus grams5`() {
        // 20 · 00123 · 00435 · check digit 8 (GS1) = PLU 00123, 0.435 kg.
        val decoded = decodeVariableWeightBarcode("2000123004358", prefix = "20")

        assertEquals("00123", decoded?.plu)
        assertEquals(0.435, decoded?.weightKg ?: 0.0, 0.0)
    }

    @Test
    fun `rejects a valid EAN13 that belongs to another configured prefix`() {
        assertNull(decodeVariableWeightBarcode("2000123004358", prefix = "21"))
    }

    @Test
    fun `rejects a corrupted check digit instead of charging a different weight`() {
        assertNull(decodeVariableWeightBarcode("2000123004357", prefix = "20"))
    }

    @Test
    fun `rejects zero weight`() {
        // Payload 20·00123·00000 has GS1 check digit 8.
        assertNull(decodeVariableWeightBarcode("2000123000008", prefix = "20"))
    }
}
