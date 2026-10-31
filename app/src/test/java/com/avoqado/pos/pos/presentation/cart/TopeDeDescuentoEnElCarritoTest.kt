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
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D13: la foto del descuento en el renglón lleva el TOPE máximo del descuento de catálogo (`itemDiscountMax`, en pesos),
 * se llena donde se llena el resto de la foto, sobrevive al carrito guardado y un carrito guardado viejo (sin el campo)
 * se lee como «sin tope». Gemela de iOS: mismo nombre de campo.
 */
class TopeDeDescuentoEnElCarritoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** El catálogo de descuentos VIVO del POS (`DiscountsRepository.discounts`): lo que cada prueba quiera que haya cargado. */
    private val catalogo = MutableStateFlow<List<Discount>>(emptyList())

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { discountsRepository.discounts } returns catalogo
        every { authRepository.venueSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")))
    }

    private fun carrito(repo: SavedCartsRepository): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = repo,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = mockk<OrderRepository>(relaxed = true),
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true),
        captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
    )

    private val playera = Product(id = "playera", name = "Playera", priceValue = 100.0)
    private val mitadMaximoDiez = Discount(
        id = "d50", name = "50 % máx $10", value = 50.0, type = "PERCENTAGE", scope = "ITEM", maxDiscountAmount = 10.0,
    )

    @Test
    fun `al aplicar un descuento con tope la foto del renglon lleva el tope y se cobra 90`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList(), discount = mitadMaximoDiez)

        val renglon = vm.cartState.value.items.single()
        assertEquals(10.0, renglon.itemDiscountMax!!, 0.0)
        assertEquals(9000, renglon.totalPrice)
    }

    @Test
    fun `sin descuento o sin tope la foto no lleva tope`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList())
        vm.addProductWithModifiers(
            playera.copy(id = "gorra", name = "Gorra"), modifiers = emptyList(),
            discount = mitadMaximoDiez.copy(maxDiscountAmount = null),
        )

        assertTrue(vm.cartState.value.items.all { it.itemDiscountMax == null })
        assertEquals(5000, vm.cartState.value.items[1].itemDiscountCents)
    }

    @Test
    fun `un carrito guardado con tope lo conserva al restaurarse, tambien tras reiniciar`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList(), discount = mitadMaximoDiez)
        assertTrue(vm.saveCurrentCart("Mesa 1"))

        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()
        assertEquals(10.0, guardado.items.single().itemDiscountMax!!, 0.0)

        val otro = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        otro.restoreSavedCart(guardado)
        val restaurado = otro.cartState.value.items.single()
        assertEquals(10.0, restaurado.itemDiscountMax!!, 0.0)
        assertEquals(9000, restaurado.totalPrice)
    }

    /**
     * Un carrito guardado ANTES de D13: su JSON no trae la llave `itemDiscountMax` (ni la de tope en el descuento de cuenta).
     * 50 % del catálogo, sin tope en la foto.
     */
    private fun carritoViejoJson(ajuste: Int? = null, conDescuentoDeCuenta: Boolean = false): SavedCart =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            """{"id":"v1","name":"Viejo","items":[{"productId":"playera","name":"Playera","unitPrice":10000,"quantity":1,
            ${ajuste?.let { """"priceAdjustment":$it,""" } ?: ""}
            "itemDiscountId":"d50","itemDiscountType":"PERCENTAGE","itemDiscountValue":50.0,"itemDiscountName":"50 %"}]
            ${if (conDescuentoDeCuenta) ""","orderDiscount":{"id":"dc","name":"20 %","value":20.0,"type":"PERCENTAGE","scope":"ORDER"}""" else ""}}""",
        )

    private fun restaurar(guardado: SavedCart): CartViewModel {
        val vm = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        vm.restoreSavedCart(guardado)
        return vm
    }

    @Test
    fun `un carrito guardado viejo, sin el campo, toma el tope del catalogo vivo y cobra 90`() {
        val viejo = carritoViejoJson()
        assertNull(viejo.items.single().itemDiscountMax)
        catalogo.value = listOf(mitadMaximoDiez)

        val vm = restaurar(viejo)
        val restaurado = vm.cartState.value.items.single()
        assertEquals(10.0, restaurado.itemDiscountMax!!, 0.0)
        assertEquals(1000, restaurado.itemDiscountCents)
        assertEquals(9000, vm.cartState.value.totalCents)
    }

    @Test
    fun `caso Codex, ajuste viejo hacia arriba de 180 sobre 100 con el descuento topado, cobra 90`() {
        catalogo.value = listOf(mitadMaximoDiez)

        val vm = restaurar(carritoViejoJson(ajuste = 18000))
        assertEquals(9000, vm.cartState.value.totalCents)
    }

    @Test
    fun `un carrito viejo cuyo descuento no esta en el catalogo se queda con lo guardado`() {
        // El catálogo cargado trae OTROS descuentos (o el de la línea ya se desactivó): no hay con qué refrescar.
        catalogo.value = listOf(mitadMaximoDiez.copy(id = "otro"))

        val vm = restaurar(carritoViejoJson())
        val restaurado = vm.cartState.value.items.single()
        assertNull(restaurado.itemDiscountMax)
        assertEquals(5000, restaurado.itemDiscountCents)
        assertEquals(5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `con el catalogo todavia sin cargar un carrito viejo se queda con lo guardado`() {
        // `DiscountsRepository.discounts` arranca vacío hasta que termina el primer fetch (o el caché): restaurar no espera la red.
        assertTrue(catalogo.value.isEmpty())

        val vm = restaurar(carritoViejoJson())
        assertNull(vm.cartState.value.items.single().itemDiscountMax)
        assertEquals(5000, vm.cartState.value.totalCents)
    }

    @Test
    fun `el descuento vivo manda sobre la foto vieja, tipo y valor tambien, porque el servidor usa el vivo`() {
        // El dueño lo bajó de 50 % a 30 % y le quitó el tope: el servidor cobrará 30 %.
        catalogo.value = listOf(mitadMaximoDiez.copy(value = 30.0, maxDiscountAmount = null))

        val vm = restaurar(carritoViejoJson())
        val restaurado = vm.cartState.value.items.single()
        assertNull(restaurado.itemDiscountMax)
        assertEquals(30.0, restaurado.itemDiscountValue!!, 0.0)
        assertEquals(3000, vm.cartState.value.items.single().itemDiscountCents)
        assertEquals(7000, vm.cartState.value.totalCents)
    }

    @Test
    fun `un carrito viejo sin descuento en la linea no cambia`() {
        catalogo.value = listOf(mitadMaximoDiez)
        val sinDescuento = Json { ignoreUnknownKeys = true }.decodeFromString<SavedCart>(
            """{"id":"v2","name":"Sin","items":[{"productId":"playera","name":"Playera","unitPrice":10000,"quantity":1}]}""",
        )

        val vm = restaurar(sinDescuento)
        val restaurado = vm.cartState.value.items.single()
        assertNull(restaurado.itemDiscountId)
        assertNull(restaurado.itemDiscountMax)
        assertEquals(10000, vm.cartState.value.totalCents)
    }

    @Test
    fun `el descuento de cuenta de un carrito viejo tambien toma el tope del catalogo vivo`() {
        catalogo.value = listOf(
            mitadMaximoDiez,
            Discount(id = "dc", name = "20 % máx $10", value = 20.0, type = "PERCENTAGE", scope = "ORDER", maxDiscountAmount = 10.0),
        )

        val vm = restaurar(carritoViejoJson(conDescuentoDeCuenta = true))
        // Línea: 50 % topado a $10 => $90; cuenta: 20 % de $90 = $18, topado a $10 => $80.
        assertEquals(10.0, vm.cartState.value.orderDiscount!!.maxDiscountAmount!!, 0.0)
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        assertEquals(8000, vm.cartState.value.totalCents)
    }

    @Test
    fun `un descuento de cuenta manual que no esta en el catalogo se restaura tal cual`() {
        catalogo.value = listOf(mitadMaximoDiez)

        val vm = restaurar(carritoViejoJson(conDescuentoDeCuenta = true))
        assertNull(vm.cartState.value.orderDiscount!!.maxDiscountAmount)
        assertEquals(1800, vm.cartState.value.orderDiscountCents)
    }

    @Test
    fun `el descuento de cuenta con tope viaja en el carrito guardado y se restaura con su tope`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList())
        vm.applyOrderDiscount(Discount(id = "dc", name = "20 % máx $10", value = 20.0, type = "PERCENTAGE", scope = "ORDER", maxDiscountAmount = 10.0))
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        assertTrue(vm.saveCurrentCart("Mesa 2"))

        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()
        assertEquals(10.0, guardado.orderDiscount!!.maxDiscountAmount!!, 0.0)

        val otro = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        otro.restoreSavedCart(guardado)
        assertEquals(1000, otro.cartState.value.orderDiscountCents)
    }
}
