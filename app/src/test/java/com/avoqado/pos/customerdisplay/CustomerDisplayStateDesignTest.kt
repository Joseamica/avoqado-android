package com.avoqado.pos.customerdisplay

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 Regla que protegen: un diseño nuevo NUNCA se cambia delante del cliente a media venta. Se aplica de inmediato sólo
 * en reposo (`Idle`); si no, queda pendiente y se promueve en el siguiente paso a `Idle`. `clearDesign` es la excepción
 * (inmediato): quitar un diseño que ya no existe no debe esperar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDisplayStateDesignTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private fun diseno(huella: String) = AppliedDesign(
        ficha = Ficha(
            fingerprint = huella,
            dark = false,
            accentArgb = null,
            onAccentArgb = null,
            logo = null,
            welcomeText = null,
            thanksText = null,
        ),
        logoFile = null,
    )

    private fun unItem() = com.avoqado.pos.pos.data.model.CartItem(
        id = "p1",
        type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("cafe"),
        name = "Café",
        unitPrice = 5000,
    )

    private fun carritoConUnProducto(): CartState = CartState(items = listOf(unItem()))

    @Test
    fun `en reposo se aplica de inmediato`() {
        val state = CustomerDisplayState()
        val a = diseno("a")
        state.offerDesign(a)
        assertEquals(a, state.design.value)
    }

    @Test
    fun `con carrito espera al reposo`() {
        val state = CustomerDisplayState()
        val a = diseno("a")
        state.showCart(carritoConUnProducto())
        state.offerDesign(a)
        assertNull(state.design.value)
        state.showCart(CartState())   // carrito vacío = Idle
        assertEquals(a, state.design.value)
    }

    @Test
    fun `show Idle e idle tambien promueven el pendiente`() {
        val state = CustomerDisplayState()
        state.showCart(carritoConUnProducto())
        state.offerDesign(diseno("a"))
        state.show(CustomerContent.Idle)
        assertEquals("a", state.design.value?.ficha?.fingerprint)

        state.showCart(carritoConUnProducto())
        state.offerDesign(diseno("b"))
        assertEquals("a", state.design.value?.ficha?.fingerprint)
        state.idle()
        assertEquals("b", state.design.value?.ficha?.fingerprint)
    }

    @Test
    fun `el ultimo ofrecido gana`() {
        val state = CustomerDisplayState()
        state.showCart(carritoConUnProducto())
        state.offerDesign(diseno("a"))
        state.offerDesign(diseno("b"))
        state.showCart(CartState())
        assertEquals("b", state.design.value?.ficha?.fingerprint)
    }

    @Test
    fun `el regreso automatico del gracias lo aplica`() = runTest {
        val state = CustomerDisplayState()
        state.show(CustomerContent.Done(totalCents = 1000, receiptUrl = null))
        state.offerDesign(diseno("a"))
        assertNull(state.design.value)
        advanceTimeBy(29_999)
        runCurrent()
        assertNull(state.design.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(CustomerContent.Idle, state.content.value)
        assertEquals("a", state.design.value?.ficha?.fingerprint)
    }

    @Test
    fun `clearDesign es inmediato a media venta y borra tambien el pendiente`() {
        val state = CustomerDisplayState()
        state.offerDesign(diseno("a"))               // en reposo: aplicado
        state.showCart(carritoConUnProducto())
        state.offerDesign(diseno("b"))               // pendiente
        state.clearDesign()
        assertNull(state.design.value)
        state.showCart(CartState())                  // no resucita el pendiente
        assertNull(state.design.value)
    }

    @Test
    fun `ofrecer null a media venta limpia al reposo`() {
        val state = CustomerDisplayState()
        state.offerDesign(diseno("a"))
        state.showCart(carritoConUnProducto())
        state.offerDesign(null)
        assertEquals("a", state.design.value?.ficha?.fingerprint)   // lo que ve el cliente no cambia a media venta
        state.showCart(CartState())
        assertNull(state.design.value)
    }
}
