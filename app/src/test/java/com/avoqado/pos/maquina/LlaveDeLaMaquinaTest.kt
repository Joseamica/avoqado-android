package com.avoqado.pos.maquina

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
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.PrefsDeDosCapas
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Codex código r1 #2: la venta que la máquina aparta tiene que ser LA MISMA después de «Restaurar» su copia guardada (la PC se reinició
 * con el cliente a media venta). Si no, la duda de esa mercancía se quedaba con la identidad vieja y la tarjeta salía.
 */
class LlaveDeLaMaquinaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val dao = CobroEnMaquinaSobreSqlite()

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")))
    }

    @After fun cerrar() = dao.close()

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

    private val pan = Product(id = "pan", name = "Pan", priceValue = 50.0)

    private fun intento(llave: String) = CobroEnMaquinaEntity(
        "AQ000000000001", "venue-1", llave, "{}", 5_000, 0, 5_000, null, null, creadoEn = 1, estado = EstadoDelIntento.ESPERANDO,
    )

    @Test
    fun `P1 la copia restaurada tras reiniciar es la misma venta para la maquina y su duda sigue frenando la tarjeta`() = runBlocking {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(pan, modifiers = emptyList())
        val llaveAlGuardar = vm.cartState.value.llaveParaLaMaquina
        vm.saveCurrentCart("Cliente de la máquina")
        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()

        val primera = carrito(SavedCartsRepository(PrefsDeDosCapas().context)).apply { restoreSavedCart(guardado) }.cartState.value
        dao.insertar(intento(primera.llaveParaLaMaquina))   // el cliente metió dinero y la PC se apagó: la duda es de esta venta
        val segunda = carrito(SavedCartsRepository(PrefsDeDosCapas().context)).apply { restoreSavedCart(guardado, confirmado = true) }
            .cartState.value

        assertNotEquals("cada restauración es otra venta en memoria", primera.identidadDeVenta, segunda.identidadDeVenta)
        assertEquals(llaveAlGuardar, primera.llaveParaLaMaquina)
        assertEquals("pero la misma venta para la máquina", primera.llaveParaLaMaquina, segunda.llaveParaLaMaquina)
        assertEquals("la tarjeta sigue frenada para esta mercancía", 1, dao.bloqueanLaVenta(segunda.llaveParaLaMaquina))
    }

    @Test
    fun `una copia guardada por una version anterior, sin la llave, usa la de su id en cada restauracion`() {
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString<SavedCart>(
            """{"id":"copia-1","name":"Viejo","items":[{"productId":"pan","name":"Pan","unitPrice":5000,"quantity":1}]}""",
        )
        val una = carrito(SavedCartsRepository(PrefsDeDosCapas().context)).apply { restoreSavedCart(viejo) }.cartState.value
        val otra = carrito(SavedCartsRepository(PrefsDeDosCapas().context)).apply { restoreSavedCart(viejo) }.cartState.value

        assertEquals("carrito:copia-1", una.llaveParaLaMaquina)
        assertEquals(una.llaveParaLaMaquina, otra.llaveParaLaMaquina)
    }

    /**
     * Codex código POS r8 #2 (F3): sólo el carrito que la caja empieza vacío nació en esta caja. Restaurar una copia, recuperar una foto (de
     * cualquier versión) o armar un carrito por fuera da «restaurado» —la máquina no lo cobra— y vaciar empieza otra venta nueva.
     */
    @Test
    fun `P1 Codex POS r8 #2 solo el carrito que la caja empieza vacio nacio aqui - restaurar o recuperar nunca`() {
        val vm = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        assertTrue("la venta nueva", vm.cartState.value.nacioEnEstaCaja)
        vm.addProductWithModifiers(pan, modifiers = emptyList())
        assertTrue("teclear en ella no la cambia", vm.cartState.value.nacioEnEstaCaja)
        val foto = vm.fotoDeLaVenta()
        vm.clearCart()
        assertTrue(vm.restaurarFoto(foto))
        assertFalse("Venta por recuperar", vm.cartState.value.nacioEnEstaCaja)

        vm.clearCart()
        assertTrue("vaciar empieza otra venta nueva", vm.cartState.value.nacioEnEstaCaja)
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString<SavedCart>(
            """{"id":"copia-1","name":"Viejo","items":[{"productId":"pan","name":"Pan","unitPrice":5000,"quantity":1}]}""",
        )
        vm.restoreSavedCart(viejo)
        assertFalse("Restaurar una copia", vm.cartState.value.nacioEnEstaCaja)
        assertFalse("y un carrito armado por fuera tampoco", com.avoqado.pos.pos.presentation.cart.CartState().nacioEnEstaCaja)
    }

    @Test
    fun `un carrito nuevo usa su identidad de venta`() {
        val vm = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        assertEquals(vm.cartState.value.identidadDeVenta, vm.cartState.value.llaveParaLaMaquina)
    }
}
