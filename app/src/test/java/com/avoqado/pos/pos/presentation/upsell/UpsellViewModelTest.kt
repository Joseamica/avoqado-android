package com.avoqado.pos.pos.presentation.upsell

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CustomerContent
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.UpsellRepository
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.UpsellRule
import com.avoqado.pos.pos.data.model.UpsellRulesPayload
import com.avoqado.pos.pos.data.model.UpsellSurfaces
import com.avoqado.pos.pos.presentation.cart.CartState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Upsell — el momento previo al cobro.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md
 *
 * 🔴 La propiedad que más importa de este archivo NO es que se muestren tarjetas:
 * es que NADA de esto pueda impedir un cobro. Un local que no puede cobrar porque
 * falló el motor de sugerencias es infinitamente peor que uno que no sugiere nada.
 * Por eso casi todos los casos de abajo verifican "devuelve null y se sigue".
 */
class UpsellViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repo: UpsellRepository
    private lateinit var products: ProductsRepository
    private lateinit var display: CustomerDisplayState
    private lateinit var storage: SecureStorage

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun product(id: String, price: Double = 35.0, upsellEnabled: Boolean? = true) =
        Product(id = id, name = id, priceValue = price, active = true, upsellEnabled = upsellEnabled)

    private fun rule(id: String = "r1", suggested: String = "galleta") =
        UpsellRule(id = id, suggestedProductId = suggested, triggerType = "ALWAYS")

    private fun cart(vararg productIds: String) = CartState(
        items = productIds.map {
            CartItem(type = CartItemType.ProductItem(it), name = it, unitPrice = 5000)
        },
    )

    /** `impressionId` real (UUID) — no se puede forzar, así que el holdout se
     *  controla con el porcentaje: 0 = nadie, 100 = todos. */
    private fun viewModel(
        rules: List<UpsellRule> = listOf(rule()),
        catalog: List<Product> = listOf(product("galleta")),
        surfaces: UpsellSurfaces = UpsellSurfaces(),
        holdoutPercent: Int = 0,
        venueId: String? = "venue-1",
        realDisplay: CustomerDisplayState? = null,
    ): UpsellViewModel {
        repo = mockk(relaxed = true)
        every { repo.rules } returns rules
        every { repo.surfaces } returns surfaces
        every { repo.holdoutPercent } returns holdoutPercent

        products = mockk(relaxed = true)
        every { products.products } returns MutableStateFlow(catalog)

        display = realDisplay ?: mockk<CustomerDisplayState>(relaxed = true).also {
            every { it.isPresenting } returns MutableStateFlow(false)
        }

        storage = mockk(relaxed = true)
        every { storage.venueId } returns venueId

        return UpsellViewModel(repo, products, display, storage)
    }

    // ── el camino feliz ───────────────────────────────────────────────────────

    @Test
    fun `con una regla activa y producto habilitado se abre el momento`() {
        val vm = viewModel()
        val moment = vm.offer(cart("cafe"), UpsellContext.COUNTER)

        assertNotNull(moment)
        assertEquals(1, moment!!.cards.size)
        assertEquals("galleta", moment.cards[0].productId)
        assertEquals(moment, vm.moment.value)
    }

    @Test
    fun `las tarjetas se espejan en la pantalla del cliente`() {
        val vm = viewModel()
        vm.offer(cart("cafe"), UpsellContext.COUNTER)

        io.mockk.verify { display.show(match { it is CustomerContent.Upsell && it.cards.size == 1 }) }
    }

    // ── todo lo que hace que el cobro siga de largo ───────────────────────────

    @Test
    fun `un carrito vacío no abre momento`() {
        assertNull(viewModel().offer(CartState(), UpsellContext.COUNTER))
    }

    @Test
    fun `sin reglas no abre momento`() {
        assertNull(viewModel(rules = emptyList()).offer(cart("cafe"), UpsellContext.COUNTER))
    }

    @Test
    fun `🔴 el veto del dueño sobre el producto gana`() {
        val vm = viewModel(catalog = listOf(product("galleta", upsellEnabled = false)))
        assertNull(vm.offer(cart("cafe"), UpsellContext.COUNTER))
    }

    @Test
    fun `la perilla apagada de esa superficie no abre momento`() {
        val vm = viewModel(surfaces = UpsellSurfaces(counter = false))
        assertNull(vm.offer(cart("cafe"), UpsellContext.COUNTER))
    }

    @Test
    fun `apagar mostrador NO apaga mesa-cobrando (son perillas independientes)`() {
        val vm = viewModel(surfaces = UpsellSurfaces(counter = false, tablePaying = true))
        assertNull(vm.offer(cart("cafe"), UpsellContext.COUNTER))
        assertNotNull(vm.offer(cart("cafe"), UpsellContext.TABLE_PAYING))
    }

    @Test
    fun `🔴 un fallo interno NO abre momento — el cobro sigue`() {
        val vm = viewModel()
        // El catálogo revienta al leerse: el peor caso realista.
        every { products.products } throws IllegalStateException("catálogo roto")

        assertNull(vm.offer(cart("cafe"), UpsellContext.COUNTER))
        assertNull(vm.moment.value)
    }

    // ── grupo de control ──────────────────────────────────────────────────────

    @Test
    fun `🔴 el grupo de control NO ve tarjetas pero SÍ se registra`() {
        val vm = viewModel(holdoutPercent = 100) // todos al control

        assertNull(vm.offer(cart("cafe"), UpsellContext.COUNTER))
        assertNull(vm.moment.value)
        // Sin este registro no hay contra qué comparar y el "aumento" del reporte
        // sería sólo el total de lo aceptado — un número que siempre se ve bien.
        io.mockk.verify(exactly = 1) { repo.rules }
        dispatcher.scheduler.advanceUntilIdle()
        io.mockk.coVerify { repo.recordImpression(match { it.contains("\"surface\":\"HOLDOUT\"") }, any()) }
    }

    // ── marcar / desmarcar ────────────────────────────────────────────────────

    @Test
    fun `marcar una tarjeta NO agrega nada, sólo cambia la selección`() {
        val vm = viewModel()
        val moment = vm.offer(cart("cafe"), UpsellContext.COUNTER)!!

        vm.toggle(moment.cards[0].ruleId)

        assertEquals(setOf(moment.cards[0].ruleId), vm.moment.value!!.selected)
        assertEquals(3500, vm.moment.value!!.selectedDeltaCents)
        io.mockk.verify { display.updateUpsellSelection(setOf(moment.cards[0].ruleId)) }
    }

    @Test
    fun `volver a tocar desmarca`() {
        val vm = viewModel()
        val ruleId = vm.offer(cart("cafe"), UpsellContext.COUNTER)!!.cards[0].ruleId

        vm.toggle(ruleId)
        vm.toggle(ruleId)

        assertTrue(vm.moment.value!!.selected.isEmpty())
        assertEquals(0, vm.moment.value!!.selectedDeltaCents)
    }

    // ── cierre del momento ────────────────────────────────────────────────────

    @Test
    fun `🔴 al cerrar se sueltan los callbacks de la pantalla del cliente`() {
        // El CustomerDisplayState REAL: lo que interesa aquí es su estado, no que
        // se haya llamado un método.
        val realDisplay = CustomerDisplayState()
        val vm = viewModel(realDisplay = realDisplay)

        vm.offer(cart("cafe"), UpsellContext.COUNTER)
        assertNotNull(realDisplay.onUpsellToggled)
        assertNotNull(realDisplay.onUpsellConfirmed)
        assertNotNull(realDisplay.onUpsellDismissed)

        vm.finish(emptyList(), 0)

        // Un callback vivo de un momento ya cerrado es cómo un toque tardío del
        // cliente reabre algo cerrado — el mismo defecto que ya costó un bug en
        // la pantalla de propina.
        assertNull(realDisplay.onUpsellToggled)
        assertNull(realDisplay.onUpsellConfirmed)
        assertNull(realDisplay.onUpsellDismissed)
        assertNull(vm.moment.value)
    }

    @Test
    fun `un rechazo también se registra (es el dato que dice si estorba)`() {
        val vm = viewModel()
        vm.offer(cart("cafe"), UpsellContext.COUNTER)

        vm.finish(emptyList(), 0)
        dispatcher.scheduler.advanceUntilIdle()

        io.mockk.coVerify { repo.recordImpression(match { it.contains("\"accepted\":[]") }, any()) }
    }

    @Test
    fun `🔴 los montos viajan en PESOS, no en centavos`() {
        val vm = viewModel()
        val moment = vm.offer(cart("cafe"), UpsellContext.COUNTER)!!
        vm.toggle(moment.cards[0].ruleId)

        vm.finish(moment.cards, addedAmountCents = 3500)
        dispatcher.scheduler.advanceUntilIdle()

        // 3500 centavos = 35.0 pesos. Mandar 3500 inflaría el reporte 100x.
        io.mockk.coVerify {
            repo.recordImpression(
                match { it.contains("\"reportedAmount\":35.0") && it.contains("\"cartSubtotalBefore\":50.0") },
                any(),
            )
        }
    }

    @Test
    fun `el externalId de lo aceptado es determinista y por índice`() {
        val vm = viewModel()
        val moment = vm.offer(cart("cafe"), UpsellContext.COUNTER)!!

        vm.finish(moment.cards, 3500)
        dispatcher.scheduler.advanceUntilIdle()

        io.mockk.coVerify {
            repo.recordImpression(match { it.contains("upsell:${moment.impressionId}:0") }, any())
        }
    }

    @Test
    fun `sin venue no se intenta registrar nada`() {
        val vm = viewModel(venueId = null)
        vm.offer(cart("cafe"), UpsellContext.COUNTER)
        vm.finish(emptyList(), 0)
        dispatcher.scheduler.advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { repo.recordImpression(any(), any()) }
    }
}
