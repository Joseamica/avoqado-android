package com.avoqado.pos.pos.presentation.product.variantes

import com.avoqado.pos.pos.data.model.Product
import org.junit.Assert.*
import org.junit.Test

class ExchangeVariantSelectionTest {
    private val empty = Product("empty", "M", trackInventory = true, availableQuantity = 0)
    @Test fun `normal sale still allows exhausted stock and retains its warning`() {
        assertTrue(empty.isOutOfStock)
        assertTrue(variantSelectable(empty))
        assertEquals(TextosVariantes.AGOTADO_AVISO, avisoDeExistencia(empty))
    }
    @Test fun `exchange cannot choose a struck or exhausted variant`() {
        assertFalse(variantSelectable(empty, disableOutOfStock = true))
        assertFalse(variantSelectable(empty.copy(availableQuantity = 5), setOf("empty")))
        assertTrue(variantSelectable(empty.copy(availableQuantity = 5), disableOutOfStock = true))
    }
}
