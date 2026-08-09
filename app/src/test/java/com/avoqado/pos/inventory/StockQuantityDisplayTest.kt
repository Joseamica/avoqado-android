package com.avoqado.pos.inventory

import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.onHandDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Existencia de un producto por peso en la pantalla de Inventario.
 *
 * En la prueba física de vales por área (2026-08-08) la app mostraba "8" con
 * 8.065 kg reales: sin decimales y sin unidad. Para una cremería el decimal ES
 * el inventario — 65 g de jamón a $240/kg son $15.60 que el dueño no ve, y el
 * caso feo llega a 999 g.
 */
class StockQuantityDisplayTest {
    private fun item(onHand: Double, unit: String?) =
        StockItem(id = "p1", name = "QA Jamón por kg", onHand = onHand, unit = unit)

    @Test
    fun `un producto por kilo muestra gramos y unidad`() {
        assertEquals("8.065 kg", item(8.065, "KILOGRAM").onHandDisplay)
        assertEquals("9.565 kg", item(9.565, "KILOGRAM").onHandDisplay)
    }

    @Test
    fun `no arrastra ceros inutiles`() {
        assertEquals("2.5 kg", item(2.5, "KILOGRAM").onHandDisplay)
        assertEquals("10 kg", item(10.0, "KILOGRAM").onHandDisplay)
    }

    @Test
    fun `menos de un kilo se lee completo en vez de cero`() {
        // Con truncado esto era "0" y el mostrador tenía 435 g de jamón.
        assertEquals("0.435 kg", item(0.435, "KILOGRAM").onHandDisplay)
    }

    @Test
    fun `las piezas siguen siendo enteras y sin sufijo`() {
        assertEquals("47", item(47.0, "PIECE").onHandDisplay)
        assertEquals("47", item(47.0, null).onHandDisplay)
    }

    @Test
    fun `otras unidades tambien traen su sufijo`() {
        assertEquals("1.75 L", item(1.75, "LITER").onHandDisplay)
        assertEquals("250 g", item(250.0, "GRAM").onHandDisplay)
    }

    @Test
    fun `cero se muestra como cero y no vacio`() {
        assertEquals("0 kg", item(0.0, "KILOGRAM").onHandDisplay)
    }
}
