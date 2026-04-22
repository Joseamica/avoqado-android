package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import org.junit.Assert.assertEquals
import org.junit.Test

class CartStateTest {

    @Test
    fun `tax applies only to product items`() {
        val productItem = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Hamburguesa",
            unitPrice = 1000,
        )
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Importe personalizado",
            unitPrice = 500,
        )

        val state = CartState(
            items = listOf(productItem, customAmount),
            orderTaxPercent = 16,
        )

        assertEquals(1500, state.subtotalCents)
        assertEquals(1000, state.taxableSubtotalCents)
        assertEquals(160, state.taxCents)
        assertEquals(1660, state.totalCents)
    }

    @Test
    fun `taxable discount is proportional and reduces tax base`() {
        val productItem = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Pizza",
            unitPrice = 1000,
        )
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Servicio",
            unitPrice = 500,
        )
        val discount = Discount(
            id = "d1",
            name = "10 por ciento",
            value = 10.0,
            type = "PERCENTAGE",
            scope = "ORDER",
        )

        val state = CartState(
            items = listOf(productItem, customAmount),
            orderDiscount = discount,
            orderTaxPercent = 16,
        )

        assertEquals(150, state.discountCents)
        assertEquals(100, state.taxableDiscountCents)
        assertEquals(900, state.taxableAmountAfterDiscountCents)
        assertEquals(144, state.taxCents)
        assertEquals(1494, state.totalCents)
    }

    @Test
    fun `tax is zero when cart has only custom amounts`() {
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Importe personalizado",
            unitPrice = 750,
        )

        val state = CartState(
            items = listOf(customAmount),
            orderTaxPercent = 16,
        )

        assertEquals(0, state.taxableSubtotalCents)
        assertEquals(0, state.taxCents)
        assertEquals(750, state.totalCents)
    }
}
