package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Encargo f7529d (tarjeta hz:nube0000:na4096a0). La señal «hay venta a medias» ([ActiveCartState]) es UNA para todo el
 * aparato, pero carritos nacen varios: el de Cobrar, el catálogo de la cuenta de una mesa (`TableOrderScreen`) y el de
 * «Asignar» en bloque (`TablesScreen`). Antes, cada carrito escribía su número encima del de los demás y ganaba el
 * último: en modo Mesas, con una venta a medias en Cobrar, abrir una mesa dejaba la señal en «vacío». Con eso el cambio
 * de papel o de terminal desde el DASHBOARD rehacía la pantalla y borraba la venta de Cobrar, y el candado «Termina la
 * venta antes de cambiar» dejaba pasar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelVentaAMediasTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)

    /** La señal real, compartida por todos los carritos del aparato (en la app es un `@Singleton`). */
    private val ventaAMedias = ActiveCartState()

    private val taco = Product(id = "prod-taco", name = "Taco", priceValue = 40.0)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun carrito(): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = ventaAMedias,
        orderRepository = mockk<OrderRepository>(relaxed = true),
        staffRepository = staffRepository,
        classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
        validateReferralUseCase = mockk(relaxed = true),
        captureReferralUseCase = mockk(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = mockk(relaxed = true),
        walletScanRepository = mockk(relaxed = true),
    )

    /** Lo que hace Android al salir de la pantalla dueña del carrito: limpia su ViewModel. */
    private fun cerrar(vm: CartViewModel) = androidx.lifecycle.ViewModelStore().apply { put("carrito", vm) }.clear()

    /** El carrito que la pantalla de Cobrar de la navegación [edicion] declara como suyo (`AvoqadoNavGraph`). */
    private fun carritoDeCobrar(edicion: String = "navegacion-1"): CartViewModel =
        carrito().also { it.esElCarritoDeCobrar(edicion) }

    @Test
    fun `P1 abrir una mesa no apaga la venta a medias de Cobrar`() = runTest {
        val cobrar = carritoDeCobrar()
        cobrar.addProduct(taco)
        assertTrue("con un renglón en Cobrar hay venta a medias", ventaAMedias.hasItems)

        carrito() // el catálogo de la cuenta de la mesa nace vacío

        assertTrue("el carrito vacío de la mesa no borra la venta de Cobrar", ventaAMedias.hasItems)
        assertEquals(1, ventaAMedias.itemCount.value)
        assertEquals(cobrar.cartState.value.totalDisplay, ventaAMedias.totalDisplay.value)
    }

    @Test
    fun `P1 cerrar la mesa tampoco la apaga, y vaciar Cobrar si`() = runTest {
        val cobrar = carritoDeCobrar()
        cobrar.addProduct(taco)
        val mesa = carrito()

        cerrar(mesa)
        assertTrue("salir de la mesa no termina la venta de Cobrar", ventaAMedias.hasItems)

        cobrar.clearCart()
        assertFalse("Cobrar vacío: ya no hay venta a medias", ventaAMedias.hasItems)
        assertEquals(0, ventaAMedias.itemCount.value)
    }

    @Test
    fun `P1 un carrito que no es el de Cobrar no enciende la senal`() = runTest {
        carritoDeCobrar()
        val consulta = carrito()

        consulta.addProduct(taco)

        assertFalse("sólo la venta de Cobrar frena un cambio", ventaAMedias.hasItems)
    }

    @Test
    fun `P2 el carrito de Cobrar que se va suelta la senal`() = runTest {
        val cobrar = carritoDeCobrar()
        cobrar.addProduct(taco)

        cerrar(cobrar)

        assertFalse("un carrito que ya no existe no puede detener un cambio", ventaAMedias.hasItems)
        assertEquals("$0.00", ventaAMedias.totalDisplay.value)
    }

    /**
     * Codex r1: al rehacer la navegación (cambio de modo, cerrar sesión), Android NO limpia el ViewModel del NavHost viejo
     * (no hay onCleared). Su carrito ya no se puede retomar: manda el Cobrar nuevo, y lo que haga el viejo no cuenta.
     */
    @Test
    fun `P1 al rehacer la pantalla manda el Cobrar nuevo aunque el viejo siga vivo con articulos`() = runTest {
        val viejo = carritoDeCobrar()
        viejo.addProduct(taco)

        val nuevo = carritoDeCobrar(edicion = "navegacion-2")
        assertFalse("el Cobrar nuevo está vacío: el viejo no frena nada", ventaAMedias.hasItems)

        nuevo.addProduct(taco)
        nuevo.addProduct(taco)
        viejo.clearCart()
        assertEquals("lo que haga el carrito viejo no pisa al nuevo", 2, ventaAMedias.itemCount.value)

        cerrar(viejo)
        assertTrue("soltar el viejo tampoco", ventaAMedias.hasItems)
    }

    @Test
    fun `P2 volver a la pestana de Cobrar con su mismo carrito conserva la venta`() = runTest {
        val cobrar = carritoDeCobrar()
        cobrar.addProduct(taco)
        carrito() // la mesa

        cobrar.esElCarritoDeCobrar("navegacion-1") // la pantalla de Cobrar vuelve a montarse con su mismo ViewModel

        assertEquals(1, ventaAMedias.itemCount.value)
    }

    /**
     * Codex r2: un aparato que arranca en Mesas. Con una venta en Cobrar se cierra sesión (o cambia el modo) y se vuelve a
     * entrar: la navegación nueva se monta sin abrir Cobrar. El carrito viejo ya no se puede retomar y no frena nada.
     */
    @Test
    fun `P1 una navegacion nueva releva al Cobrar viejo aunque Cobrar no se monte`() = runTest {
        val viejo = carritoDeCobrar(edicion = "navegacion-1")
        viejo.addProduct(taco)

        ventaAMedias.navegacionMontada("navegacion-2")
        carrito() // abre la cuenta de una mesa sin pasar por Cobrar

        assertFalse(ventaAMedias.hasItems)
        viejo.addProduct(taco)
        assertFalse("lo que haga el carrito viejo después tampoco cuenta", ventaAMedias.hasItems)
    }

    @Test
    fun `P1 girar la pantalla conserva la misma navegacion y la venta`() = runTest {
        val cobrar = carritoDeCobrar(edicion = "navegacion-1")
        cobrar.addProduct(taco)

        ventaAMedias.navegacionMontada("navegacion-1") // la Activity se recrea y la edición se restaura (rememberSaveable)

        assertTrue(ventaAMedias.hasItems)
        assertEquals(1, ventaAMedias.itemCount.value)
    }
}
