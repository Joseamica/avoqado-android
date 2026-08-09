package com.avoqado.pos.pos.product

import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.availableWeightLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cuánto queda, dicho ANTES de pesar.
 *
 * El panel de captura sólo decía “Captura el peso en kilogramos”: quien atiende
 * el mostrador no tenía forma de saber si alcanzaba, y emitía el vale a ciegas.
 */
class WeightAvailabilityLabelTest {
    private fun ham(exact: Double?, tracked: Boolean = true) = Product(
        id = "p1",
        name = "QA Jamón por kg",
        priceValue = 240.0,
        soldByWeight = true,
        unit = "KILOGRAM",
        trackInventory = tracked,
        availableQuantityExact = exact,
    )

    @Test
    fun `dice los kilos disponibles con precision de gramo`() {
        assertEquals("Disponible: 8.065 kg", ham(8.065).availableWeightLabel)
        assertEquals("Disponible: 0.435 kg", ham(0.435).availableWeightLabel)
    }

    @Test
    fun `no arrastra ceros`() {
        assertEquals("Disponible: 10 kg", ham(10.0).availableWeightLabel)
    }

    @Test
    fun `avisa cuando ya no queda`() {
        assertEquals("Sin existencia registrada", ham(0.0).availableWeightLabel)
    }

    @Test
    fun `calla si el venue no rastrea inventario de este producto`() {
        assertNull(ham(8.065, tracked = false).availableWeightLabel)
    }

    @Test
    fun `calla con servidores viejos que no mandan el campo`() {
        assertNull(ham(null).availableWeightLabel)
    }
}
