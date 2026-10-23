package com.avoqado.pos.customerdisplay

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 El «¡Gracias!» se queda hasta su tiempo o hasta la venta siguiente (tablero D2, 21-oct). Al cobrar, el carrito se
 * vacía en el mismo instante y le avisaba a la pantalla del cliente «vacío ⇒ reposo»: el gracias (con el texto propio del
 * negocio) duraba ~0.1 s en la D3. Un carrito VACÍO ya no lo quita; uno con productos (venta nueva) sí.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDisplayGraciasTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private val gracias = CustomerContent.Done(totalCents = 1000, receiptUrl = null)

    private fun carritoConUnProducto() = CartState(
        items = listOf(
            com.avoqado.pos.pos.data.model.CartItem(
                id = "p1",
                type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("cafe"),
                name = "Café",
                unitPrice = 5000,
            ),
        ),
    )

    @Test
    fun `el carrito vacio al cerrar la venta no borra el gracias`() = runTest {
        val state = CustomerDisplayState()
        state.show(gracias)
        state.showCart(CartState())   // la venta se cerró y el carrito quedó vacío
        assertEquals(gracias, state.content.value)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(gracias, state.content.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(CustomerContent.Idle, state.content.value)
    }

    @Test
    fun `una venta nueva si quita el gracias`() = runTest {
        val state = CustomerDisplayState()
        state.show(gracias)
        state.showCart(carritoConUnProducto())
        assertTrue(state.content.value is CustomerContent.Cart)
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(state.content.value is CustomerContent.Cart)   // el timer viejo no borra el carrito nuevo
    }

    @Test
    fun `idle explicito si lo quita`() {
        val state = CustomerDisplayState()
        state.show(gracias)
        state.idle()
        assertEquals(CustomerContent.Idle, state.content.value)
    }
}
