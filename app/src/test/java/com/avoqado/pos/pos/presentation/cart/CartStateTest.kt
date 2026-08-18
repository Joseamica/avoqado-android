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

    // ── El desglose: el descuento por línea SE VE ────────────────────────────
    //
    // 🔴 Decisión del founder (2026-08-17): el ticket tiene que mostrar el
    // descuento, en pantalla y en papel. Antes el subtotal ya venía rebajado y la
    // línea de descuento nunca aparecía: el cliente no se enteraba de que se lo
    // hicieron, y el desglose no cuadraba contra la orden del server —que guarda
    // subtotal BRUTO y `discountAmount` aparte (`order.mobile.service.ts`)—
    // aunque el total sí coincidiera.
    //
    // 🔴 El TOTAL no cambia con esto. Es presentación: mismo dinero, desglosado.

    private fun conDescuento(unitPrice: Int, value: Double, type: String = "PERCENTAGE") = CartItem(
        type = CartItemType.ProductItem("prod-1"),
        name = "Coca-Cola",
        unitPrice = unitPrice,
        itemDiscountId = "disc-1",
        itemDiscountType = type,
        itemDiscountValue = value,
    )

    @Test
    fun `🔴 el subtotal es BRUTO y el descuento de linea sale aparte`() {
        val state = CartState(
            items = listOf(
                CartItem(type = CartItemType.ProductItem("burger"), name = "Hamburguesa", unitPrice = 14900),
                conDescuento(unitPrice = 2500, value = 20.0),
            ),
        )

        assertEquals("149 + 25, sin rebajar", 17400, state.subtotalCents)
        assertEquals("el -20% de la Coca", 500, state.itemDiscountCents)
        assertEquals("lo que se pinta como Descuento", 500, state.discountCents)
        assertEquals("y el cliente paga lo mismo de siempre", 16900, state.totalCents)
    }

    @Test
    fun `sin descuentos el desglose no cambia`() {
        val state = CartState(
            items = listOf(CartItem(type = CartItemType.ProductItem("p"), name = "X", unitPrice = 5000)),
        )

        assertEquals(5000, state.subtotalCents)
        assertEquals(0, state.itemDiscountCents)
        assertEquals(0, state.discountCents)
        assertEquals(5000, state.totalCents)
    }

    @Test
    fun `🔴 el descuento de ORDEN se aplica sobre lo que queda, no sobre el bruto`() {
        // Si el 10% de orden pegara sobre el bruto (17400) daría 1740; tiene que
        // pegar sobre lo que de verdad se debe tras el descuento de línea (16900)
        // = 1690. Es el comportamiento de siempre; cambiar la base aquí habría
        // regalado $0.50 por venta sin que nadie lo pidiera.
        val state = CartState(
            items = listOf(
                CartItem(type = CartItemType.ProductItem("burger"), name = "Hamburguesa", unitPrice = 14900),
                conDescuento(unitPrice = 2500, value = 20.0),
            ),
            orderDiscount = Discount(id = "d-orden", name = "10%", value = 10.0, type = "PERCENTAGE"),
        )

        assertEquals(1690, state.orderDiscountCents)
        assertEquals("línea + orden, juntos en el renglón", 2190, state.discountCents)
        assertEquals(15210, state.totalCents)
    }

    @Test
    fun `🔴 una cortesia no inventa descuento ni infla el subtotal`() {
        // Ya es gratis: no puede aportar al bruto ni contar como descuento, o el
        // desglose diría que se regalaron dos veces los mismos $25.
        val cortesia = conDescuento(unitPrice = 2500, value = 20.0).apply { isCortesia = true }
        val state = CartState(items = listOf(cortesia))

        assertEquals(0, state.subtotalCents)
        assertEquals(0, state.itemDiscountCents)
        assertEquals(0, state.totalCents)
    }

    @Test
    fun `el descuento de linea baja la base del impuesto`() {
        // El impuesto se calcula sobre lo que de verdad se cobra.
        val state = CartState(
            items = listOf(conDescuento(unitPrice = 10000, value = 10.0)),
            orderTaxPercent = 16,
        )

        assertEquals(10000, state.subtotalCents)
        assertEquals(1000, state.itemDiscountCents)
        assertEquals("base = 9000, no 10000", 9000, state.taxableSubtotalCents)
        assertEquals(1440, state.taxCents)
        assertEquals(10440, state.totalCents)
    }

    @Test
    fun `🔴 el ticket cuadra consigo mismo - lineas, subtotal, descuento y total`() {
        // Lo que se imprime en papel. Si esto falla, el cliente se lleva un ticket
        // cuyos renglones no suman lo que dice abajo.
        val state = CartState(
            items = listOf(
                CartItem(type = CartItemType.ProductItem("burger"), name = "Hamburguesa", unitPrice = 14900),
                conDescuento(unitPrice = 2500, value = 20.0),
            ),
        )

        val sumaDeLineas = state.items.sumOf { it.grossPrice }
        assertEquals("las líneas suman el subtotal", state.subtotalCents, sumaDeLineas)
        assertEquals(
            "subtotal − descuento + impuesto = total",
            state.totalCents,
            state.subtotalCents - state.discountCents + state.taxCents,
        )
    }

    @Test
    fun `🔴 los dos descuentos se pueden pintar por separado, sin atribuirle uno al otro`() {
        // El carrito los muestra en renglones distintos. Antes existía UNA sola
        // fila, con el nombre del descuento de ORDEN y el monto COMBINADO: con los
        // dos aplicados, el de artículo salía atribuido al equivocado; y sin
        // descuento de orden no se pintaba nada, así que el de artículo era
        // invisible aunque sí se cobrara.
        val state = CartState(
            items = listOf(
                CartItem(type = CartItemType.ProductItem("burger"), name = "Hamburguesa", unitPrice = 14900),
                conDescuento(unitPrice = 2500, value = 20.0),
            ),
            orderDiscount = Discount(id = "d-orden", name = "10%", value = 10.0, type = "PERCENTAGE"),
        )

        assertEquals("sólo el de artículo", 500, state.itemDiscountCents)
        assertEquals("sólo el de orden", 1690, state.orderDiscountCents)
        assertEquals("y la suma es lo que se resta", 2190, state.discountCents)
        assertEquals(
            "los dos renglones más el total siguen cuadrando",
            state.totalCents,
            state.subtotalCents - state.itemDiscountCents - state.orderDiscountCents + state.taxCents,
        )
    }
}
