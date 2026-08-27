package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 🔴 EL guardrail de dinero del descuento POR LÍNEA.
 *
 * El defecto que este archivo existe para impedir (medido 2026-08-17): el carrito
 * NUNCA aplicaba el descuento de un artículo. `CartItem.totalPrice` sólo miraba
 * `priceAdjustment ?: unitPrice`, así que el cajero aplicaba "-20%" a un platillo,
 * el POS cobraba el precio COMPLETO, y el server —que sí lo aplica en
 * `order.mobile.service.ts`— registraba la orden rebajada. El cliente pagaba de
 * más y la orden quedaba sobrepagada.
 *
 * 🔴 La aritmética de aquí es ESPEJO EXACTO del server
 * (`avoqado-server/src/services/mobile/order.mobile.service.ts` +
 * `services/shared/discount.service.ts::calculateDiscountPesos`):
 *
 *     itemTotal    = precio × cantidad + modificadores × cantidad
 *     lineDiscount = PERCENTAGE ? round(itemTotal × valor / 100) : round(valor)
 *                    …topado a itemTotal
 *     se cobra     = itemTotal − lineDiscount
 *
 * Dos detalles que parecen menores y NO lo son:
 *  - el descuento pega sobre producto **+ modificadores**, no sólo sobre el
 *    producto (si no, un 20% sobre $35 + $15 daría $43 aquí y $40 en el server);
 *  - el redondeo es HALF-UP, no truncado (truncar desviaba un centavo por venta,
 *    que es justo lo que descuadra un arqueo).
 */
class CartItemDiscountTest {

    private fun linea(
        unitPrice: Int = 3500,
        quantity: Int = 1,
        modifiers: List<SelectedModifier> = emptyList(),
        discountType: String? = null,
        discountValue: Double? = null,
        isCortesia: Boolean = false,
        weightKg: Double? = null,
    ) = CartItem(
        type = CartItemType.ProductItem("p1"),
        name = "Producto",
        unitPrice = unitPrice,
        quantity = quantity,
        selectedModifiers = modifiers,
        isCortesia = isCortesia,
        itemDiscountId = if (discountType != null) "disc-1" else null,
        itemDiscountType = discountType,
        itemDiscountValue = discountValue,
        weightKg = weightKg,
    )

    private fun modificador(priceInCents: Int) = SelectedModifier(
        groupId = "g1",
        groupName = "Tamaño",
        modifierId = "m1",
        modifierName = "Grande",
        priceInCents = priceInCents,
    )

    // ── 1. Lo nuevo: el descuento por línea SÍ baja lo que se cobra ────────────

    @Test
    fun `P1 un porcentaje baja el total de la linea`() {
        val item = linea(unitPrice = 3500, discountType = "PERCENTAGE", discountValue = 20.0)

        assertEquals("700¢ de descuento sobre 3500¢", 700, item.itemDiscountCents)
        assertEquals("$35 con -20% se cobra $28", 2800, item.totalPrice)
    }

    @Test
    fun `P1 el descuento pega sobre producto MAS modificadores, igual que el server`() {
        // $35 de producto + $15 del tamaño = $50 de línea. Un -20% sobre la línea
        // son $10, no $7: si el descuento pegara sólo en el producto daríamos $43
        // aquí y el server registraría $40.
        val item = linea(
            unitPrice = 3500,
            modifiers = listOf(modificador(1500)),
            discountType = "PERCENTAGE",
            discountValue = 20.0,
        )

        assertEquals(1000, item.itemDiscountCents)
        assertEquals("(35 + 15) × 0.8 = 40", 4000, item.totalPrice)
    }

    @Test
    fun `un monto fijo baja el total de la linea`() {
        val item = linea(unitPrice = 3500, discountType = "FIXED_AMOUNT", discountValue = 15.0)

        assertEquals(1500, item.itemDiscountCents)
        assertEquals(2000, item.totalPrice)
    }

    @Test
    fun `P1 FIXED y FIXED_AMOUNT significan lo mismo`() {
        // El server SÓLO dice `FIXED_AMOUNT`. Android sólo reconocía `FIXED`, así
        // que un descuento de $15 se leía como 15% — iOS ya aceptaba las dos
        // (CartModels.swift:551) y Android no.
        val conNombreDelServer = linea(unitPrice = 3500, discountType = "FIXED_AMOUNT", discountValue = 15.0)
        val conNombreViejo = linea(unitPrice = 3500, discountType = "FIXED", discountValue = 15.0)

        assertEquals(2000, conNombreDelServer.totalPrice)
        assertEquals(2000, conNombreViejo.totalPrice)
    }

    @Test
    fun `P1 el descuento JAMAS deja la linea en negativo`() {
        // Un "-$50" sobre un producto de $35 se topa en la línea, no la invierte.
        val item = linea(unitPrice = 3500, discountType = "FIXED_AMOUNT", discountValue = 50.0)

        assertEquals(3500, item.itemDiscountCents)
        assertEquals(0, item.totalPrice)
    }

    @Test
    fun `un porcentaje sobre varias unidades pega sobre el total de la linea`() {
        val item = linea(unitPrice = 3500, quantity = 3, discountType = "PERCENTAGE", discountValue = 10.0)

        assertEquals("10% de 10500¢", 1050, item.itemDiscountCents)
        assertEquals(9450, item.totalPrice)
    }

    @Test
    fun `P1 un monto fijo es PLANO por linea, no por unidad`() {
        // El server hace `roundPesos(value)` una sola vez sobre el total de la
        // línea. Multiplicarlo por la cantidad regalaría $30 de más aquí.
        val item = linea(unitPrice = 3500, quantity = 3, discountType = "FIXED_AMOUNT", discountValue = 15.0)

        assertEquals(1500, item.itemDiscountCents)
        assertEquals(9000, item.totalPrice)
    }

    @Test
    fun `P1 el redondeo es HALF-UP, igual que roundPesos del server`() {
        // 1611¢ × 20% = 322.2¢. El server redondea a 322 y cobra 1289.
        // Truncar el PRODUCTO (lo que hacía `toCard`) daba 1288: un centavo de
        // descuadre por venta.
        val item = linea(unitPrice = 1611, discountType = "PERCENTAGE", discountValue = 20.0)

        assertEquals(322, item.itemDiscountCents)
        assertEquals(1289, item.totalPrice)
    }

    @Test
    fun `una linea pesada tambien se descuenta`() {
        // 0.5 kg × $420/kg = $210. Un -10% son $21.
        val item = linea(
            unitPrice = 42000,
            weightKg = 0.5,
            discountType = "PERCENTAGE",
            discountValue = 10.0,
        )

        assertEquals(2100, item.itemDiscountCents)
        assertEquals(18900, item.totalPrice)
    }

    @Test
    fun `la cortesia gana sobre el descuento`() {
        val item = linea(unitPrice = 3500, discountType = "PERCENTAGE", discountValue = 20.0, isCortesia = true)

        assertEquals(0, item.totalPrice)
    }

    // ── 2. Regresión: lo que ya funcionaba sigue igual ────────────────────────

    @Test
    fun `sin descuento el total no cambia`() {
        assertEquals(3500, linea().totalPrice)
        assertEquals(0, linea().itemDiscountCents)
    }

    @Test
    fun `sin descuento, producto con modificadores y cantidad sigue igual`() {
        val item = linea(unitPrice = 3500, quantity = 2, modifiers = listOf(modificador(1500)))

        assertEquals("(35 + 15) × 2", 10000, item.totalPrice)
    }

    @Test
    fun `un id de descuento SIN snapshot no cobra de menos`() {
        // Carrito guardado con una versión vieja de la app: trae el id pero no el
        // tipo/valor. Sin snapshot no se puede calcular nada — se cobra completo,
        // que es el comportamiento de hoy, nunca un descuento inventado.
        val item = CartItem(
            type = CartItemType.ProductItem("p1"),
            name = "Producto",
            unitPrice = 3500,
            itemDiscountId = "disc-1",
        )

        assertEquals(0, item.itemDiscountCents)
        assertEquals(3500, item.totalPrice)
    }

    @Test
    fun `el ajuste manual de precio sigue mandando sobre el precio de lista`() {
        val item = CartItem(
            type = CartItemType.ProductItem("p1"),
            name = "Producto",
            unitPrice = 3500,
            priceAdjustment = 2000,
        )

        assertEquals(2000, item.totalPrice)
    }

    @Test
    fun `el descuento pega DESPUES del ajuste manual de precio`() {
        val item = CartItem(
            type = CartItemType.ProductItem("p1"),
            name = "Producto",
            unitPrice = 3500,
            priceAdjustment = 2000,
            itemDiscountId = "disc-1",
            itemDiscountType = "PERCENTAGE",
            itemDiscountValue = 10.0,
        )

        assertEquals(200, item.itemDiscountCents)
        assertEquals(1800, item.totalPrice)
    }
}
