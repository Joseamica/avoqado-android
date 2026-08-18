package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.LinkedDiscount
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

    private fun card(
        productId: String,
        price: Int = 3500,
        modifiers: List<ResolvedModifier> = emptyList(),
        linkedDiscount: LinkedDiscount? = null,
    ) =
        UpsellCard(
            ruleId = "regla-$productId",
            productId = productId,
            name = productId,
            displayPriceCents = price,
            imageUrl = null,
            headline = null,
            badge = linkedDiscount?.badge,
            linkedDiscount = linkedDiscount,
            modifiers = modifiers,
        )

    /**
     * Un CartViewModel simulado cuyo `addProduct`/`addProductWithModifiers` SÍ
     * mueven el flujo, para poder comprobar de dónde lee el acomodador Y por
     * cuál de los dos caminos entró la línea.
     */
    private fun fakeCart(
        initial: CartState = CartState(),
        descuentosVivos: List<Discount> = emptyList(),
    ): CartViewModel {
        val flow = MutableStateFlow(initial)
        val vm = mockk<CartViewModel>(relaxed = true)
        every { vm.cartState } returns flow

        // El acomodador consulta el catálogo VIVO de descuentos antes de atar uno
        // a la línea: mandar un id que el server ya no conoce tumba la venta entera.
        val repo = mockk<DiscountsRepository>(relaxed = true)
        every { repo.discounts } returns MutableStateFlow(descuentosVivos)
        every { vm.discountsRepository } returns repo

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
        // Se leen los argumentos REALES de la llamada (`arg<T>(n)`) en vez de
        // capturarlos en slots: un slot no puede capturar un nulo, y `discount`
        // es nulo en la mayoría de los casos.
        every {
            vm.addProductWithModifiers(
                product = any(),
                quantity = any(),
                modifiers = any(),
                note = any(),
                isCortesia = any(),
                cortesiaReason = any(),
                priceAdjustment = any(),
                discount = any(),
            )
        } answers {
            val p = arg<Product>(0)
            val mods = arg<List<SelectedModifier>>(2)
            val d = arg<Discount?>(7)
            flow.value = flow.value.copy(
                items = flow.value.items + CartItem(
                    type = CartItemType.ProductItem(p.id),
                    name = p.name,
                    unitPrice = p.priceInCents,
                    selectedModifiers = mods,
                    itemDiscountId = d?.id,
                    itemDiscountType = d?.type,
                    itemDiscountValue = d?.value,
                    itemDiscountName = d?.name,
                ),
            )
        }
        return vm
    }

    /** El descuento tal como sigue vivo en el catálogo del POS. */
    private fun descuentoVivo(id: String = "d1", type: String = "PERCENTAGE", value: Double = 20.0) =
        Discount(id = id, name = "Promo", value = value, type = type, scope = "ITEM")

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
                discount = any(),
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

    // ── Descuento ligado: la tarjeta y el cobro dicen lo MISMO ────────────────

    @Test
    fun `🔴 con descuento ligado, el carrito cobra EXACTO lo que prometio la tarjeta`() = runBlocking {
        val vm = fakeCart(descuentosVivos = listOf(descuentoVivo()))
        val galleta = product("galleta", price = 35.0)
        val tarjeta = card("galleta", price = 2800, linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 20.0, "-20%"))

        val result = CounterUpsellAcceptor(vm).accept(listOf(tarjeta), mapOf("galleta" to galleta))

        // 🔴 El defecto que esto impide: la tarjeta decía $28 y el carrito cobraba
        // $35 porque el descuento ligado nunca llegaba a la línea.
        assertEquals(tarjeta.displayPriceCents, result.cart.totalCents)
        assertEquals(2800, result.cart.totalCents)
        assertEquals(1, result.added.size)
        // Y el desglose lo MUESTRA: precio de lista arriba, descuento en su renglón.
        assertEquals(3500, result.cart.subtotalCents)
        assertEquals(700, result.cart.discountCents)
    }

    @Test
    fun `🔴 con descuento ligado Y modificadores, el carrito cobra EXACTO lo de la tarjeta`() = runBlocking {
        val vm = fakeCart(descuentosVivos = listOf(descuentoVivo()))
        val agua = product("prod_agua", price = 35.0)
        val resueltos = listOf(ResolvedModifier("g_tam", "m_gr", "Grande", 15.0))
        // ($35 + $15) × 0.8 = $40 — el descuento pega sobre la línea COMPLETA,
        // igual que el server.
        val tarjeta = card(
            "prod_agua",
            price = 4000,
            modifiers = resueltos,
            linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 20.0, "-20%"),
        )

        val result = CounterUpsellAcceptor(vm).accept(listOf(tarjeta), mapOf("prod_agua" to agua))

        assertEquals(tarjeta.displayPriceCents, result.cart.totalCents)
        assertEquals(4000, result.cart.totalCents)
        // Desglose: ($35 + $15) de lista, menos $10 de descuento.
        assertEquals(5000, result.cart.subtotalCents)
        assertEquals(1000, result.cart.discountCents)
    }

    @Test
    fun `el id del descuento viaja a la linea para que el server lo registre`() = runBlocking {
        val vm = fakeCart(descuentosVivos = listOf(descuentoVivo()))
        val galleta = product("galleta", price = 35.0)

        val result = CounterUpsellAcceptor(vm).accept(
            listOf(card("galleta", price = 2800, linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 20.0, "-20%"))),
            mapOf("galleta" to galleta),
        )

        // Sin el id, el server registraría la orden a precio de lista y el arqueo
        // no cuadraría contra lo que cobró el POS.
        assertEquals("d1", result.cart.items.single().itemDiscountId)
    }

    @Test
    fun `🔴 si el descuento ya no esta vivo, la tarjeta NO se agrega`() = runBlocking {
        // El descuento se borró o venció entre que el POS bajó las reglas y el
        // toque. Mandar ese id tumba la venta ENTERA con 400
        // (`order.mobile.service.ts` rechaza ids desconocidos a propósito), y
        // agregarla sin descuento cobraría $35 tras prometer $28. Se reporta como
        // no disponible: se pierde la sugerencia, nunca la venta.
        val vm = fakeCart(descuentosVivos = listOf(descuentoVivo(id = "otro")))
        val galleta = product("galleta", price = 35.0)

        val result = CounterUpsellAcceptor(vm).accept(
            listOf(card("galleta", price = 2800, linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 20.0, "-20%"))),
            mapOf("galleta" to galleta),
        )

        assertTrue(result.added.isEmpty())
        assertEquals(1, result.unavailable.size)
        assertEquals(0, result.cart.subtotalCents)
    }

    @Test
    fun `sin catalogo de descuentos todavia, se confia en la regla`() = runBlocking {
        // Arranque en frío o sin red: el catálogo de descuentos aún no baja. Vacío
        // significa "no sé", no "ya no existe" — tratarlo como ausencia apagaría
        // TODAS las promociones del local justo cuando no hay red.
        val vm = fakeCart(descuentosVivos = emptyList())
        val galleta = product("galleta", price = 35.0)

        val result = CounterUpsellAcceptor(vm).accept(
            listOf(card("galleta", price = 2800, linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 20.0, "-20%"))),
            mapOf("galleta" to galleta),
        )

        assertEquals(1, result.added.size)
        assertEquals(2800, result.cart.totalCents)
    }
}
