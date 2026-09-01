package com.avoqado.pos.pos

import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.DiscountScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué descuento se ve DÓNDE — la regla que sostiene el arreglo del 2026-09-01.
 *
 * El caso real (Testarudo): el venue tenía dos descuentos de "Orden Completa",
 * el cajero los buscó dentro de un artículo, la lista salió vacía y concluyó
 * que los descuentos no servían. La separación es CORRECTA (Square hace lo
 * mismo: el descuento de la venta se aplica desde el resumen, el del artículo
 * tocando el artículo); lo que faltaba era decírselo y darle la entrada desde
 * el carrito.
 *
 * Estas pruebas fijan las dos mitades: un descuento de ORDEN nunca cae en la
 * lista de un artículo, y uno de artículo/categoría sí.
 */
class DiscountScopeVisibilityTest {

    private fun descuento(
        scope: String,
        targetItemIds: List<String>? = null,
        targetCategoryIds: List<String>? = null,
    ) = Discount(
        id = "d-$scope",
        name = "Descuento $scope",
        value = 15.0,
        scope = scope,
        targetItemIds = targetItemIds,
        targetCategoryIds = targetCategoryIds,
    )

    // MARK: - La regla del arreglo

    @Test
    fun `un descuento de ORDEN nunca aparece en la lista de un articulo`() {
        val orden = descuento("ORDER")

        assertEquals(DiscountScope.ORDER, orden.discountScope)
        // Por eso el panel del producto salía vacío con los 2 de Testarudo.
        assertFalse(orden.appliesTo(productId = "prod-1", categoryId = "cat-1"))
    }

    @Test
    fun `los de ORDEN son los que alimentan el carrito`() {
        val todos = listOf(descuento("ORDER"), descuento("ITEM"), descuento("CATEGORY"))

        // Mismo filtro que usan el atajo de Shortcuts y la hoja del carrito.
        val deOrden = todos.filter { it.discountScope == DiscountScope.ORDER }

        assertEquals(1, deOrden.size)
        assertEquals("Descuento ORDER", deOrden[0].name)
    }

    // MARK: - Lo que SÍ debe verse en el artículo

    @Test
    fun `uno de ARTICULO sin lista de destinos aplica a cualquier producto`() {
        assertTrue(descuento("ITEM").appliesTo(productId = "prod-1", categoryId = null))
    }

    @Test
    fun `uno de ARTICULO con lista solo aplica a los suyos`() {
        val soloAlCafe = descuento("ITEM", targetItemIds = listOf("cafe"))

        assertTrue(soloAlCafe.appliesTo(productId = "cafe", categoryId = null))
        assertFalse(soloAlCafe.appliesTo(productId = "galleta", categoryId = null))
    }

    @Test
    fun `uno de CATEGORIA sigue a la categoria, no al producto`() {
        val soloBebidas = descuento("CATEGORY", targetCategoryIds = listOf("bebidas"))

        assertTrue(soloBebidas.appliesTo(productId = "cafe", categoryId = "bebidas"))
        assertFalse(soloBebidas.appliesTo(productId = "cafe", categoryId = "postres"))
    }
}
