package com.avoqado.pos.inventory

import com.avoqado.pos.inventory.data.model.StockItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D16 (3-nov): una sola regla de iniciales en ANDROID, la de las tarjetas desde D6 (`inicialesDeTile`). En la Sunmi,
 * Inventario › Descripción general decía «AG» en la fila de «Agua Mineral 1L» (las dos primeras letras) y «AM» en su
 * detalle, las dos a la vez en la misma pantalla. La fila y los movimientos usan `StockItem.initials`.
 */
class InicialesDelInventarioTest {

    @Test
    fun `la fila de inventario usa las iniciales de las tarjetas`() {
        assertEquals("AM", StockItem(id = "p1", name = "Agua Mineral 1L").initials)
        assertEquals("JP", StockItem(id = "p2", name = "Jamón por kg").initials)
    }

    @Test
    fun `una sola palabra sigue dando sus dos primeras letras`() {
        assertEquals("LA", StockItem(id = "p3", name = "Latte").initials)
        assertEquals("", StockItem(id = "p4", name = "").initials)
    }
}
