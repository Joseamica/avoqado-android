package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.presentation.promotions.estimadoDePromocion
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Promociones en el carrito: cómo entra un combo, cómo se quita y cuánto se
 * cobra por él.
 *
 * 🔴 Esto NO es cosmético. En la venta rápida el importe que se cobra sale del
 * carrito (`PaymentFlowViewModel.currentBaseAmount()` → `cartState.totalCents`),
 * así que si las líneas de la promoción se quedaran a precio de lista, el 2x1
 * cobraría las dos cervezas. Por eso hay aserciones de dinero, no sólo de forma.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-6-brief.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartPromotionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val activeCartState = mockk<ActiveCartState>(relaxed = true)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true)
    private val captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    private val venueSwitchedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )
    }

    private fun createViewModel(): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = activeCartState,
        orderRepository = orderRepository,
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
    )

    // MARK: - Fixtures

    /** 2x1 de cerveza: entran 2, se cobra 1. Un solo grupo con una sola opción. */
    private val dosPorUno = Promotion(
        id = "promo-2x1",
        name = "2x1 Cervezas",
        pricingMode = "PER_UNIT",
        groups = listOf(
            PromotionGroup(
                id = "g-cerveza",
                name = "Cerveza",
                options = listOf(
                    PromotionOption(
                        id = "o-cerveza",
                        productId = "p-cerveza",
                        quantity = 2,
                        chargedQuantity = 1,
                        productName = "Cerveza",
                        productPriceCents = 5000,
                    ),
                ),
            ),
        ),
    )

    /** Combo de $99: elige plato (uno con +$15) y bebida. */
    private val combo = Promotion(
        id = "promo-combo",
        name = "Combo del día",
        pricingMode = "FIXED_TOTAL",
        priceCents = 9900,
        groups = listOf(
            PromotionGroup(
                id = "g-plato",
                name = "Plato fuerte",
                options = listOf(
                    PromotionOption(
                        id = "o-hamburguesa",
                        productId = "p-hamburguesa",
                        productName = "Hamburguesa",
                        productPriceCents = 12000,
                    ),
                    PromotionOption(
                        id = "o-ensalada",
                        productId = "p-ensalada",
                        priceDeltaCents = 1500,
                        productName = "Ensalada de la casa",
                        productPriceCents = 11000,
                    ),
                ),
            ),
            PromotionGroup(
                id = "g-bebida",
                name = "Bebida",
                options = listOf(
                    PromotionOption(
                        id = "o-refresco",
                        productId = "p-refresco",
                        productName = "Refresco",
                        productPriceCents = 3000,
                    ),
                    PromotionOption(
                        id = "o-agua",
                        productId = "p-agua",
                        productName = "Agua",
                        productPriceCents = 2500,
                    ),
                ),
            ),
        ),
    )

    private val eleccionEnsaladaConRefresco = mapOf(
        "g-plato" to "o-ensalada",
        "g-bebida" to "o-refresco",
    )

    private val burger = Product(id = "burger", name = "Hamburguesa suelta", priceValue = 150.0)

    // MARK: - Tests del brief

    @Test
    fun `aplicar un 2x1 agrega UNA linea con la cantidad que entra`() {
        val vm = createViewModel()

        assertTrue(vm.aplicarPromocion(dosPorUno, emptyMap()))

        val linea = vm.cartState.value.items.single()
        // UNA línea de cantidad 2 (no dos líneas de 1): la deducción de
        // inventario del server multiplica por esta cantidad.
        assertEquals(2, linea.quantity)
        assertEquals("p-cerveza", (linea.type as com.avoqado.pos.pos.data.model.CartItemType.ProductItem).productId)
        assertNotNull(linea.promotionInstanceId)
        assertEquals("2x1 Cervezas", linea.promotionName)
        // 💰 Se cobra UNA cerveza, no dos.
        assertEquals(5000, linea.totalPrice)
        assertEquals(5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `aplicar un combo agrega una linea por opcion elegida, todas con el mismo instanceId`() {
        val vm = createViewModel()

        assertTrue(vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco))

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        assertEquals(1, items.mapNotNull { it.promotionInstanceId }.distinct().size)
        assertEquals(listOf("p-ensalada", "p-refresco"), items.map { (it.type as com.avoqado.pos.pos.data.model.CartItemType.ProductItem).productId })
        // Lo que la Task 8 necesita para armar `promotionRef` sin volver al catálogo.
        assertEquals(listOf("promo-combo", "promo-combo"), items.map { it.promotionId })
        assertEquals(listOf("g-plato", "g-bebida"), items.map { it.promotionGroupId })
        assertEquals(listOf("o-ensalada", "o-refresco"), items.map { it.promotionOptionId })
    }

    @Test
    fun `quitar CUALQUIER linea de una promocion quita la promocion completa`() {
        val vm = createViewModel()
        vm.addProduct(burger)
        vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco)

        val segundaLineaDelCombo = vm.cartState.value.items.last { it.isPromotionLine }
        vm.removeItem(segundaLineaDelCombo.id)

        val restantes = vm.cartState.value.items
        assertEquals(1, restantes.size)
        assertNull(restantes.single().promotionInstanceId)
        assertEquals("Hamburguesa suelta", restantes.single().name)
    }

    @Test
    fun `dos combos iguales son dos instanceId distintos`() {
        val vm = createViewModel()

        repeat(3) { vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco) }

        val items = vm.cartState.value.items
        assertEquals(6, items.size)
        assertEquals(3, items.mapNotNull { it.promotionInstanceId }.distinct().size)
        // 🔴 NUNCA quantity: 3 — el server responde 400 con `quantity ≠ 1`
        // junto a `promotionRef`.
        assertTrue(items.all { it.quantity == 1 })
        assertEquals(3 * 11400, vm.cartState.value.totalCents)
    }

    @Test
    fun `el estimado local usa productPriceCents y priceDeltaCents`() {
        // Combo $99 + opción con +$15 -> estimado $114. Es SÓLO para mostrar…
        assertEquals(11400, estimadoDePromocion(combo, eleccionEnsaladaConRefresco))

        // …y las líneas que entran al carrito suman exactamente ese estimado,
        // porque es el importe que el cajero va a cobrar.
        val vm = createViewModel()
        vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco)
        assertEquals(11400, vm.cartState.value.items.sumOf { it.totalPrice })
    }

    // MARK: - Dinero y bordes que el brief no nombra pero cuestan pesos

    @Test
    fun `sin elegir todos los grupos no entra nada al carrito`() {
        val vm = createViewModel()

        // Media promoción no se agrega: el server la resolvería incompleta.
        assertFalse(vm.aplicarPromocion(combo, mapOf("g-plato" to "o-ensalada")))
        assertTrue(vm.cartState.value.items.isEmpty())
    }

    @Test
    fun `la cantidad de una linea de promocion no se edita desde el carrito`() {
        val vm = createViewModel()
        vm.aplicarPromocion(dosPorUno, emptyMap())
        val linea = vm.cartState.value.items.single()

        vm.incrementQuantity(linea.id)
        vm.updateQuantity(linea.id, 3)

        // 3 combos = 3 instancias, nunca una línea con cantidad 3.
        assertEquals(2, vm.cartState.value.items.single().quantity)
        assertEquals(5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `una linea de promocion no se puede dar de cortesia ni cambiarle el precio`() {
        val vm = createViewModel()
        vm.aplicarPromocion(dosPorUno, emptyMap())
        val linea = vm.cartState.value.items.single()

        vm.updateItemCortesia(linea.id, true, "Cortesia del administrador")
        vm.updateItemPriceAdjustment(linea.id, 1)

        // El server cobra la promoción; regalarla o reprecificarla aquí sería
        // cobrar algo distinto de lo que queda registrado en la orden.
        val despues = vm.cartState.value.items.single()
        assertFalse(despues.isCortesia)
        assertNull(despues.priceAdjustment)
        assertEquals(5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `un carrito guardado conserva la promocion al restaurarse`() {
        val vm = createViewModel()
        vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco)
        val guardados = mutableListOf<SavedCart>()
        every { savedCartsRepository.saveCart(any()) } answers { guardados += firstArg<SavedCart>() }

        assertTrue(vm.saveCurrentCart("Mesa 5"))
        vm.restoreSavedCart(guardados.single())

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        assertEquals(1, items.mapNotNull { it.promotionInstanceId }.distinct().size)
        assertEquals(listOf("promo-combo", "promo-combo"), items.map { it.promotionId })
        assertEquals(listOf("o-ensalada", "o-refresco"), items.map { it.promotionOptionId })
        // 💰 Restaurar no puede convertir el combo en dos productos a precio de lista.
        assertEquals(11400, vm.cartState.value.totalCents)
    }

    @Test
    fun `quitar una promocion no toca a las demas`() {
        val vm = createViewModel()
        vm.aplicarPromocion(combo, eleccionEnsaladaConRefresco)
        vm.aplicarPromocion(dosPorUno, emptyMap())
        val instanciaDelCombo = vm.cartState.value.items.first().promotionInstanceId!!

        vm.quitarPromocion(instanciaDelCombo)

        val restantes = vm.cartState.value.items
        assertEquals(1, restantes.size)
        assertEquals("2x1 Cervezas", restantes.single().promotionName)
    }

    @Test
    fun `tocar el producto suelto no se fusiona con la linea de la promocion`() {
        // 🔴 El caso diario: 2x1 aplicado y el cliente pide una cerveza más.
        // Fusionarla dejaría la línea de la PROMOCIÓN en cantidad 3 → $75 en vez
        // de $100 (el local pierde $25), `quantity = 3` viajando junto a
        // `promotionRef` (400 del server) y 3 unidades descontadas bajo un 2x1.
        val vm = createViewModel()
        val cervezaSuelta = Product(id = "p-cerveza", name = "Cerveza", priceValue = 50.0)

        vm.aplicarPromocion(dosPorUno, emptyMap())
        vm.addProduct(cervezaSuelta)

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        val promo = items.single { it.isPromotionLine }
        val suelta = items.single { !it.isPromotionLine }
        assertEquals(2, promo.quantity)
        assertEquals(5000, promo.totalPrice)
        assertEquals(1, suelta.quantity)
        assertEquals(5000, suelta.totalPrice)
        // 💰 $50 del 2x1 + $50 de la suelta.
        assertEquals(10000, vm.cartState.value.totalCents)
    }

    @Test
    fun `el producto suelto agregado ANTES tampoco absorbe la promocion`() {
        val vm = createViewModel()
        val cervezaSuelta = Product(id = "p-cerveza", name = "Cerveza", priceValue = 50.0)

        vm.addProduct(cervezaSuelta)
        vm.aplicarPromocion(dosPorUno, emptyMap())
        vm.addProduct(cervezaSuelta)

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        // La suelta fusiona con la suelta (comportamiento normal), no con la promo.
        assertEquals(2, items.single { !it.isPromotionLine }.quantity)
        assertEquals(2, items.single { it.isPromotionLine }.quantity)
        assertEquals(10000 + 5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `una promocion gratis se cobra en cero, no a precio de lista`() {
        // 🔴 `validatePromotionForPublish` sólo rechaza `priceCents < 0`, así que
        // una promo a $0 SE PUBLICA y el server deja todas sus líneas en cero.
        // Tratar el 0 como "no sé el precio" cobraría $150 por una orden de $0.
        val gratis = combo.copy(priceCents = 0)
        val sinSobreprecio = mapOf("g-plato" to "o-hamburguesa", "g-bebida" to "o-refresco")
        val vm = createViewModel()

        assertEquals(0, estimadoDePromocion(gratis, sinSobreprecio))
        assertTrue(vm.aplicarPromocion(gratis, sinSobreprecio))
        // $150 de catálogo (hamburguesa + refresco) regalados: se cobra $0.
        assertEquals(0, vm.cartState.value.totalCents)

        // Y si la opción elegida trae sobreprecio, se cobra ESE sobreprecio —
        // que es justo la prueba de que el 0 es un precio y no un "no sé".
        assertEquals(1500, estimadoDePromocion(gratis, eleccionEnsaladaConRefresco))
    }

    @Test
    fun `un PER_UNIT sin precios de producto vale cero, igual que en el server`() {
        // Bruto 0 ⇒ el server resuelve neto 0 (descuento = max(0, 0−objetivo)).
        // `priceCents` NUNCA se usa en un PER_UNIT: mostrarlo sería inventar.
        val sinPrecios = dosPorUno.copy(
            priceCents = 9900,
            groups = listOf(
                dosPorUno.groups.single().copy(
                    options = listOf(dosPorUno.groups.single().options.single().copy(productPriceCents = 0)),
                ),
            ),
        )
        val vm = createViewModel()

        assertEquals(0, estimadoDePromocion(sinPrecios, emptyMap()))
        vm.aplicarPromocion(sinPrecios, emptyMap())
        assertEquals(0, vm.cartState.value.totalCents)
    }

    @Test
    fun `un pricingMode desconocido no inventa precio y cobra el catalogo pelon`() {
        // Único caso de "no sé": un modo que esta versión de la app no conoce.
        // La línea entra a precio de CATÁLOGO, sin sumarle `priceDeltaCents` —
        // una promoción nunca puede cobrar MÁS que el catálogo.
        val modoNuevo = combo.copy(pricingMode = "TIERED_SOMETHING")
        val vm = createViewModel()

        assertNull(estimadoDePromocion(modoNuevo, eleccionEnsaladaConRefresco))
        vm.aplicarPromocion(modoNuevo, eleccionEnsaladaConRefresco)
        // Ensalada $110 (NO $125: el +$15 no se suma) + refresco $30.
        assertEquals(listOf(11000, 3000), vm.cartState.value.items.map { it.totalPrice })
        assertEquals(14000, vm.cartState.value.totalCents)
    }

    // MARK: - Los números del server, fijados (si el reparto cambia, esto truena)

    @Test
    fun `el split de un FIXED_TOTAL reproduce el reparto del server`() {
        // Caso pineado del server: neto 10000 sobre brutos 20000 / 10000 / 5000
        // → [5714, 2857, 1429] (proporcional al bruto, sobrante al mayor residuo,
        // `allocateByWeights`). Con cantidad 1 el unitario ES la parte.
        val tresLineas = Promotion(
            id = "promo-3",
            name = "Combo grande",
            pricingMode = "FIXED_TOTAL",
            priceCents = 10000,
            groups = listOf(
                grupoDeUnaOpcion("g-a", "o-a", "p-a", "Plato", 20000),
                grupoDeUnaOpcion("g-b", "o-b", "p-b", "Postre", 10000),
                grupoDeUnaOpcion("g-c", "o-c", "p-c", "Bebida", 5000),
            ),
        )
        val vm = createViewModel()

        assertEquals(10000, estimadoDePromocion(tresLineas, emptyMap()))
        vm.aplicarPromocion(tresLineas, emptyMap())

        assertEquals(listOf(5714, 2857, 1429), vm.cartState.value.items.map { it.unitPrice })
        assertEquals(10000, vm.cartState.value.totalCents)
    }

    @Test
    fun `un 3x2 pinea el neto del server y deja a la vista la desviacion del carrito`() {
        // Server: 3 entran, 2 se cobran, $50 c/u → neto 10000, EXACTO.
        val tresPorDos = Promotion(
            id = "promo-3x2",
            name = "3x2 Cervezas",
            pricingMode = "PER_UNIT",
            groups = listOf(
                PromotionGroup(
                    id = "g-cerveza",
                    name = "Cerveza",
                    options = listOf(
                        PromotionOption(
                            id = "o-cerveza",
                            productId = "p-cerveza",
                            quantity = 3,
                            chargedQuantity = 2,
                            productName = "Cerveza",
                            productPriceCents = 5000,
                        ),
                    ),
                ),
            ),
        )
        val vm = createViewModel()

        // El estimado —lo que dice la hoja— coincide con el server al centavo.
        assertEquals(10000, estimadoDePromocion(tresPorDos, emptyMap()))

        vm.aplicarPromocion(tresPorDos, emptyMap())
        // 🔴 Pero el carrito cobra 3 × round(10000/3) = 9999: `CartItem` sólo
        // sabe `unitPrice × quantity` y 10000 no es múltiplo de 3. La cota es
        // `Σ floor(cantidad/2)` = 1 centavo aquí. Se fija a propósito: si
        // alguien cambia el reparto, este número cambia y hay que volver a
        // mirarlo, en vez de que la desviación crezca sin que nadie se entere.
        val cobrado = vm.cartState.value.totalCents
        assertEquals(3333, vm.cartState.value.items.single().unitPrice)
        assertEquals(9999, cobrado)
        val desviacion = kotlin.math.abs(10000 - cobrado)
        assertTrue("desviación $desviacion > cota Σ floor(cantidad/2)", desviacion <= 3 / 2)
    }

    private fun grupoDeUnaOpcion(
        grupoId: String,
        opcionId: String,
        productId: String,
        nombre: String,
        precioCents: Int,
    ) = PromotionGroup(
        id = grupoId,
        name = nombre,
        options = listOf(
            PromotionOption(
                id = opcionId,
                productId = productId,
                productName = nombre,
                productPriceCents = precioCents,
            ),
        ),
    )
}
