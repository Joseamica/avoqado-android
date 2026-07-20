package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Venta por peso a nivel carrito: total de línea al centavo, subtítulo, y D9 (cada pesada es una
 * línea independiente que jamás se fusiona).
 */
class CartWeightTest {

    private fun weightItem(productId: String, unitPricePerKgCents: Int, weightKg: Double) = CartItem(
        type = CartItemType.ProductItem(productId),
        name = "Jamón serrano",
        unitPrice = unitPricePerKgCents,
        quantity = 1,
        weightKg = weightKg,
    )

    @Test
    fun `weighted line total is round(weightKg times pricePerKg) to the cent`() {
        // 0.435 kg × $420.00/kg = $182.70
        val item = weightItem("jamon", 42000, 0.435)
        assertEquals(18270, item.totalPrice)
    }

    @Test
    fun `weighted line ignores quantity for pricing`() {
        // quantity fija en 1 en líneas pesadas; el precio depende solo del peso.
        val item = weightItem("jamon", 42000, 0.435).copy(quantity = 1)
        assertEquals(18270, item.totalPrice)
    }

    @Test
    fun `weightSummary renders kg and price per kg`() {
        val item = weightItem("jamon", 42000, 0.435)
        assertEquals("0.435 kg × $420.00/kg", item.weightSummary)
    }

    @Test
    fun `normal line has no weightSummary`() {
        val normal = CartItem(
            type = CartItemType.ProductItem("burger"),
            name = "Hamburguesa",
            unitPrice = 15000,
            quantity = 2,
        )
        assertNull(normal.weightSummary)
        // Regresión: la línea normal sigue multiplicando por cantidad.
        assertEquals(30000, normal.totalPrice)
    }

    @Test
    fun `two weighings of the same product are two independent lines (D9)`() {
        // Dos pesadas del mismo jamón (0.435 y 0.512 kg) = dos ventas distintas, nunca fusionadas.
        val first = weightItem("jamon", 42000, 0.435) // $182.70
        val second = weightItem("jamon", 42000, 0.512) // $215.04
        val state = CartState(items = listOf(first, second))

        assertEquals(2, state.items.size)
        assertEquals(2, state.itemCount) // cada línea pesada cuenta como 1
        assertEquals(18270 + 21504, state.subtotalCents)
    }
}
