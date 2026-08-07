package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 EL guardrail de dinero del upsell.
 *
 * El bug que este test existe para impedir, textual del spec (C1):
 *
 *   `val cartState by cartViewModel.cartState.collectAsState()` es una lectura de
 *   Compose que NO se actualiza hasta la siguiente recomposición. Agregar un producto
 *   y en la MISMA función leer esa variable para congelar el total devuelve el
 *   carrito VIEJO. Resultado: se cobra sin el producto aceptado, y `clearCart()` lo
 *   borra después — el negocio regala el producto y la orden ni lo registra.
 *
 * Por eso el acomodador DEVUELVE el carrito resultante, leído del FLUJO. Si alguien
 * cambia `cartViewModel.cartState.value` por una variable capturada, este test truena.
 */
class UpsellAcceptorTest {

    private fun product(id: String, name: String = id, price: Double = 35.0, outOfStock: Boolean = false, active: Boolean? = true) =
        Product(
            id = id,
            name = name,
            priceValue = price,
            active = active,
            upsellEnabled = true,
            trackInventory = if (outOfStock) true else null,
            availableQuantity = if (outOfStock) 0 else null,
        )

    private fun card(productId: String, price: Int = 3500) =
        UpsellCard(
            ruleId = "regla-$productId",
            productId = productId,
            name = productId,
            displayPriceCents = price,
            imageUrl = null,
            headline = null,
            badge = null,
            linkedDiscountId = null,
        )

    /**
     * Un CartViewModel simulado cuyo `addProduct` SÍ mueve el flujo, para poder
     * comprobar de dónde lee el acomodador.
     */
    private fun fakeCart(initial: CartState = CartState()): CartViewModel {
        val flow = MutableStateFlow(initial)
        val vm = mockk<CartViewModel>(relaxed = true)
        every { vm.cartState } returns flow
        val captured = slot<Product>()
        every { vm.addProduct(capture(captured)) } answers {
            val p = captured.captured
            flow.value = flow.value.copy(
                items = flow.value.items + CartItem(
                    type = CartItemType.ProductItem(p.id),
                    name = p.name,
                    unitPrice = p.priceInCents,
                ),
            )
        }
        return vm
    }

    @Test
    fun `🔴 el carrito devuelto YA incluye lo aceptado (no espera recomposición)`() = runBlocking {
        val cafe = CartItem(type = CartItemType.ProductItem("cafe"), name = "Café", unitPrice = 5000)
        val vm = fakeCart(CartState(items = listOf(cafe)))
        val galleta = product("galleta", price = 35.0)

        val result = CounterUpsellAcceptor(vm).accept(listOf(card("galleta")), mapOf("galleta" to galleta))

        // Café $50 + galleta $35 = $85. Si el acomodador leyera una variable vieja,
        // aquí seguiría diciendo 5000 y se cobrarían $50.
        assertEquals(8500, result.cart.subtotalCents)
        assertEquals(2, result.cart.items.size)
        assertEquals(1, result.added.size)
    }

    @Test
    fun `un producto agotado entre la sugerencia y el toque NO se agrega`() = runBlocking {
        val vm = fakeCart()
        val agotada = product("galleta", outOfStock = true)

        val result = CounterUpsellAcceptor(vm).accept(listOf(card("galleta")), mapOf("galleta" to agotada))

        assertTrue(result.added.isEmpty())
        assertEquals(1, result.unavailable.size)
        assertEquals(0, result.cart.subtotalCents)
    }

    @Test
    fun `un producto desactivado entre la sugerencia y el toque NO se agrega`() = runBlocking {
        val vm = fakeCart()
        val inactiva = product("galleta", active = false)

        val result = CounterUpsellAcceptor(vm).accept(listOf(card("galleta")), mapOf("galleta" to inactiva))

        assertTrue(result.added.isEmpty())
        assertEquals(1, result.unavailable.size)
    }

    @Test
    fun `un producto que desapareció del catálogo NO truena`() = runBlocking {
        val vm = fakeCart()

        val result = CounterUpsellAcceptor(vm).accept(listOf(card("fantasma")), emptyMap())

        assertTrue(result.added.isEmpty())
        assertEquals(1, result.unavailable.size)
    }

    @Test
    fun `🔴 se cobra el precio VIVO del producto, no el que decía la tarjeta`() = runBlocking {
        val vm = fakeCart()
        // La tarjeta se pintó con $35, pero el producto ya cuesta $50.
        val subioDePrecio = product("galleta", price = 50.0)

        val result = CounterUpsellAcceptor(vm).accept(
            listOf(card("galleta", price = 3500)),
            mapOf("galleta" to subioDePrecio),
        )

        assertEquals(5000, result.cart.subtotalCents)
    }

    @Test
    fun `acepta varios y reporta cuáles entraron`() = runBlocking {
        val vm = fakeCart()
        val catalog = mapOf(
            "galleta" to product("galleta", price = 35.0),
            "jugo" to product("jugo", price = 45.0),
            "pan" to product("pan", outOfStock = true),
        )

        val result = CounterUpsellAcceptor(vm)
            .accept(listOf(card("galleta"), card("jugo"), card("pan")), catalog)

        assertEquals(8000, result.cart.subtotalCents) // 35 + 45
        assertEquals(2, result.added.size)
        assertEquals(1, result.unavailable.size)
    }
}
