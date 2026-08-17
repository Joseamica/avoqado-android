package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ResolvedModifier
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.UpsellCard
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

    private fun card(productId: String, price: Int = 3500, modifiers: List<ResolvedModifier> = emptyList()) =
        UpsellCard(
            ruleId = "regla-$productId",
            productId = productId,
            name = productId,
            displayPriceCents = price,
            imageUrl = null,
            headline = null,
            badge = null,
            linkedDiscountId = null,
            modifiers = modifiers,
        )

    /**
     * Un CartViewModel simulado cuyo `addProduct`/`addProductWithModifiers` SÍ
     * mueven el flujo, para poder comprobar de dónde lee el acomodador Y por
     * cuál de los dos caminos entró la línea.
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
        val capturedWithMods = slot<Product>()
        val capturedModifiers = slot<List<SelectedModifier>>()
        every {
            vm.addProductWithModifiers(
                product = capture(capturedWithMods),
                quantity = any(),
                modifiers = capture(capturedModifiers),
                note = any(),
                isCortesia = any(),
                cortesiaReason = any(),
                priceAdjustment = any(),
                discountId = any(),
            )
        } answers {
            val p = capturedWithMods.captured
            flow.value = flow.value.copy(
                items = flow.value.items + CartItem(
                    type = CartItemType.ProductItem(p.id),
                    name = p.name,
                    unitPrice = p.priceInCents,
                    selectedModifiers = capturedModifiers.captured,
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
    fun `🔴 con modificadores resueltos, la línea entra por addProductWithModifiers y el total coincide con la tarjeta`() = runBlocking {
        val vm = fakeCart()
        val agua = product("prod_agua", price = 35.0)
        // La misma selección que ya trae la tarjeta ($35 + $15 = $50 = 5000¢).
        val resueltos = listOf(ResolvedModifier("g_tam", "m_gr", "Grande", 15.0))

        val result = CounterUpsellAcceptor(vm).accept(
            listOf(card("prod_agua", price = 5000, modifiers = resueltos)),
            mapOf("prod_agua" to agua),
        )

        // El total del carrito debe coincidir EXACTO con lo que decía la tarjeta.
        assertEquals(5000, result.cart.subtotalCents)
        assertEquals(1, result.added.size)
        verify(exactly = 1) {
            vm.addProductWithModifiers(
                product = agua,
                quantity = any(),
                modifiers = match { it.size == 1 && it[0].modifierId == "m_gr" && it[0].priceInCents == 1500 },
                note = any(),
                isCortesia = any(),
                cortesiaReason = any(),
                priceAdjustment = any(),
                discountId = any(),
            )
        }
        // 🔴 Si esto se quedara en addProduct(), la línea entraría SIN el tamaño
        // y se cobraría el precio pelón: la tarjeta dijo un precio y se cobra otro.
        verify(exactly = 0) { vm.addProduct(any()) }
    }

    @Test
    fun `sin modificadores, la línea sigue entrando por addProduct (no rompe lo de hoy)`() = runBlocking {
        val vm = fakeCart()
        val galleta = product("galleta", price = 35.0)

        val result = CounterUpsellAcceptor(vm).accept(listOf(card("galleta")), mapOf("galleta" to galleta))

        assertEquals(3500, result.cart.subtotalCents)
        verify(exactly = 1) { vm.addProduct(galleta) }
        verify(exactly = 0) { vm.addProductWithModifiers(any(), any(), any(), any(), any(), any(), any(), any()) }
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
