package com.avoqado.pos.payment

import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.POR_PRODUCTO_CON_PROMOCION
import com.avoqado.pos.pos.presentation.cart.POR_PRODUCTO_PREMIO_EMPATADO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D10 (27-oct, founder «Sí, gris en esos casos»): en dos ventas raras la tablet no puede saber cómo reparte el SERVIDOR, así que
 * «Por producto» sale en gris con la razón (dividir por monto o en partes iguales sigue igual). Codex D10 r2.
 */
class PorProductoApagadoTest {
    private fun linea(id: String, cents: Int, combo: String? = null) =
        CartItem(id = id, type = CartItemType.ProductItem("p-$id"), name = id, unitPrice = cents, promotionInstanceId = combo)
    private val diez = Discount(id = "d", name = "Diez", value = 10.0, type = "PERCENTAGE")
    private val gratis = PremioPorAplicar(id = "p", etiqueta = "Gratis", tipo = "FREE_PRODUCT", valor = null)

    @Test
    fun `promocion con descuento de toda la cuenta - gris`() {
        val cart = CartState(items = listOf(linea("a", 5000, "promo"), linea("b", 5000, "promo"), linea("c", 10000)), orderDiscount = diez)
        assertEquals(POR_PRODUCTO_CON_PROMOCION, cart.razonSinPorProducto)
    }

    @Test
    fun `promocion sin descuento de cuenta - se puede`() {
        assertNull(CartState(items = listOf(linea("a", 5000, "promo"), linea("b", 5000, "promo"), linea("c", 10000))).razonSinPorProducto)
    }

    @Test
    fun `descuento de cuenta sin promocion - se puede (D10)`() {
        assertNull(CartState(items = listOf(linea("a", 10000), linea("b", 10000)), orderDiscount = diez).razonSinPorProducto)
    }

    @Test
    fun `producto gratis con dos productos del mismo precio - gris`() {
        val cart = CartState(items = listOf(linea("a", 10000), linea("b", 10000), linea("c", 3000)), pendingStampReward = gratis)
        assertEquals(POR_PRODUCTO_PREMIO_EMPATADO, cart.razonSinPorProducto)
    }

    @Test
    fun `producto gratis aun sin empate - gris (Codex D10 r3)`() {
        val cart = CartState(items = listOf(linea("a", 10000), linea("c", 3000)), pendingStampReward = gratis)
        assertEquals(POR_PRODUCTO_PREMIO_EMPATADO, cart.razonSinPorProducto)
    }

    @Test
    fun `premio de porcentaje - se puede (D10)`() {
        val diezPorciento = PremioPorAplicar(id = "p", etiqueta = "10 %", tipo = "PERCENTAGE", valor = 10.0)
        assertNull(CartState(items = listOf(linea("a", 10000), linea("c", 3000)), pendingStampReward = diezPorciento).razonSinPorProducto)
    }
}
