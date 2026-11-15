package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.ajusteQueViaja
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.cart.CartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encargo 1afe04 (founder «A», 15-nov, como Toast): el «Ajuste de precio» pide lo mismo que un descuento con «Requiere
 * aprobación»: «Aplicar descuentos en TPV» o el PIN del encargado al cobrar, y sin red no se cobra. La regla de «este renglón lleva
 * ajuste» es UNA: la misma que decide qué viaja como `adjustedUnitPrice` (el SERVIDOR pide el permiso por ese campo).
 */
class AjusteDePrecioPideAprobacionTest {

    private val playera = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)

    @Test
    fun `un producto con ajuste que baja el precio pide aprobacion y dice que es un ajuste`() {
        val carrito = CartState(items = listOf(playera.copy(priceAdjustment = 8000)))
        assertTrue(carrito.pideAprobacionDeGerente)
        assertEquals(8000, playera.copy(priceAdjustment = 8000).ajusteQueViaja())
        assertTrue(carrito.llevaAjusteDePrecio)
    }

    @Test
    fun `lo que pide aprobacion es exactamente lo que viaja como adjustedUnitPrice`() {
        val casos = listOf(
            playera.copy(priceAdjustment = 8000),
            playera.copy(priceAdjustment = 10000), // igual: no baja
            playera.copy(priceAdjustment = 12000), // sube: el servidor lo ignora
            playera.copy(priceAdjustment = 8000, isCortesia = true, cortesiaReason = "x"),
            playera.copy(type = CartItemType.CustomAmount, priceAdjustment = 8000),
            playera,
        )
        for (item in casos) {
            val viaja = buildOrderItemRequests(listOf(item)).firstOrNull()?.adjustedUnitPrice
            assertEquals("caso $item", viaja, item.ajusteQueViaja())
            assertEquals("caso $item", viaja != null, CartState(items = listOf(item)).pideAprobacionDeGerente)
        }
    }

    @Test
    fun `regresion - sin ajuste, sin cortesia ni descuentos, no pide nada`() {
        val carrito = CartState(items = listOf(playera))
        assertFalse(carrito.pideAprobacionDeGerente)
        assertFalse(carrito.llevaAjusteDePrecio)
        assertNull(playera.ajusteQueViaja())
    }

    @Test
    fun `una cortesia con ajuste no pide esta aprobacion (la cubre orders comp)`() {
        val carrito = CartState(items = listOf(playera.copy(priceAdjustment = 8000, isCortesia = true, cortesiaReason = "x")))
        assertFalse(carrito.pideAprobacionDeGerente)
    }

    @Test
    fun `la fila del ajuste segun el permiso y el PIN del encargado`() {
        assertEquals(AccesoAlAjuste.LIBRE, accesoAlAjuste(puedeAprobar = true, pinPrendido = false))
        assertEquals(AccesoAlAjuste.LIBRE, accesoAlAjuste(puedeAprobar = true, pinPrendido = true))
        assertEquals(AccesoAlAjuste.CON_PIN, accesoAlAjuste(puedeAprobar = false, pinPrendido = true))
        assertEquals(AccesoAlAjuste.LO_APLICA_UN_GERENTE, accesoAlAjuste(puedeAprobar = false, pinPrendido = false))
        assertNull(avisoDeLaFilaDeAjuste(AccesoAlAjuste.LIBRE))
        assertEquals("Lo autoriza un encargado con su PIN al cobrar", avisoDeLaFilaDeAjuste(AccesoAlAjuste.CON_PIN))
        assertEquals("Lo aplica un gerente con su sesión", avisoDeLaFilaDeAjuste(AccesoAlAjuste.LO_APLICA_UN_GERENTE))
    }
}
