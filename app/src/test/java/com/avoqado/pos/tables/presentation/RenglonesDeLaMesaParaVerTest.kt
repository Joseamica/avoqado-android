package com.avoqado.pos.tables.presentation

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.tables.data.OrderDetailItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D39 (ensayo 11 y 12-nov): el cobro de una mesa con Coca + Agua + Café decía «Ver productos (1)». */
class RenglonesDeLaMesaParaVerTest {
    private fun renglon(nombre: String, pesos: Double, cantidad: Int = 1) =
        OrderDetailItem(id = nombre, productName = nombre, quantity = cantidad, unitPrice = pesos, total = pesos * cantidad)

    private val cuenta = listOf(renglon("Coca", 25.0), renglon("Agua", 20.0), renglon("Café", 30.0))

    @Test
    fun `D39 una mesa con 3 productos ensena sus 3 renglones y el contador dice 3`() {
        val vistos = renglonesDeLaMesaParaVer(cuenta, emptyList(), subtotalCents = 7500)

        // Lo que pinta «Ver productos (N)»: `paymentContext.items.sumOf { it.quantity }`.
        assertEquals(3, vistos.sumOf { it.quantity })
        assertEquals(listOf("Coca", "Agua", "Café"), vistos.map { it.name })
        assertEquals(listOf(2500, 2000, 3000), vistos.map { it.lineTotal })
    }

    @Test
    fun `D39 cuenta con cantidades cuenta piezas`() {
        val vistos = renglonesDeLaMesaParaVer(listOf(renglon("Taco", 20.0, cantidad = 3), renglon("Agua", 20.0)), emptyList(), 8000)
        assertEquals(4, vistos.sumOf { it.quantity })
    }

    @Test
    fun `D39 si no cuadran con el subtotal no se ensenan`() {
        // Un abono ($25 de $75 por pagar), un cargo por servicio o una cuenta que cambió: queda el renglón «Cuenta Mesa N».
        assertTrue(renglonesDeLaMesaParaVer(cuenta, emptyList(), subtotalCents = 2500).isEmpty())
        assertTrue(renglonesDeLaMesaParaVer(cuenta, emptyList(), subtotalCents = 8250).isEmpty())
        assertTrue("sin cuenta leída ni rondas", renglonesDeLaMesaParaVer(emptyList(), emptyList(), 0).isEmpty())
    }

    @Test
    fun `D39 una mesa abierta sin red ensena las rondas de su cola`() {
        val enCola = listOf(
            CartItem(id = "c", type = CartItemType.ProductItem("p-coca"), name = "Coca", unitPrice = 2500),
            CartItem(id = "a", type = CartItemType.ProductItem("p-agua"), name = "Agua", unitPrice = 2000, quantity = 2),
        )
        val vistos = renglonesDeLaMesaParaVer(emptyList(), enCola, subtotalCents = 6500)
        assertEquals(3, vistos.sumOf { it.quantity })
    }
}
