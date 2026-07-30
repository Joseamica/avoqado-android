package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductDisplayPriceTest {

    @Test
    fun `regular product displays its unit price`() {
        val product = Product(
            id = "piece",
            name = "Papas",
            priceValue = 42.0,
        )

        assertEquals("$42.00", product.displayPrice)
    }

    @Test
    fun `weighted product makes the per-kilogram price explicit`() {
        val product = Product(
            id = "weighted",
            name = "Jamón",
            priceValue = 420.0,
            soldByWeight = true,
            unit = "KILOGRAM",
        )

        assertEquals("$420.00/kg", product.displayPrice)
    }
}
