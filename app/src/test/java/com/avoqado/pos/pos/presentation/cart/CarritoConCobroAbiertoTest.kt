package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
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
import com.avoqado.pos.pos.data.model.SavedCartItem
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D9: con el cobro abierto, la venta que se cobra (la foto) no cambia por nada que no sea el propio cobro. Antes cada camino de
 * la pantalla traía su barrera (D8) y siempre aparecía otro; ahora el candado vive en el CARRITO, por donde pasan todos.
 * Mapa completo: docs/audit/2026-11-18-carrito-con-cobro-abierto/promesa.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarritoConCobroAbiertoTest {

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
    private val apartadoDeVales = MutableStateFlow<AreaTicketCheckout?>(null)

    private val pantallaDelCliente = com.avoqado.pos.customerdisplay.CustomerDisplayState()
    private val cambioDeSucursal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val cafe = Product(id = "cafe", name = "Café", priceValue = 35.0)
    private val pan = Product(id = "pan", name = "Pan", priceValue = 20.0)

    @Before
    fun setup() {
        every { areaTicketRepository.session.checkout } returns apartadoDeVales
        every { areaTicketRepository.session.ventaDuena } returns null
        every { areaTicketRepository.session.current() } returns null
        every { productsRepository.products } returns MutableStateFlow(listOf(cafe, pan))
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { savedCartsRepository.saveCart(any()) } returns true
        every { savedCartsRepository.deleteCart(any()) } returns true
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns cambioDeSucursal
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )
    }

    private fun carrito(
        semilla: ClassCheckoutSeed = classCheckoutSeed,
        tiempos: com.avoqado.pos.tables.data.ServiceCoursesRepository? = null,
    ): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = activeCartState,
        orderRepository = orderRepository,
        staffRepository = staffRepository,
        classCheckoutSeed = semilla,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = pantallaDelCliente,
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
        serviceCoursesRepository = tiempos,
    )

    /** Un café en el carrito y el cobro abierto (ya tomó su foto). */
    private fun cafeConElCobroAbierto(): CartViewModel = carrito().apply {
        addProduct(cafe)
        abrirCobro()
        consumeAvisoDelCobroAbierto()
    }

    private fun CartViewModel.renglon(): String = cartState.value.items.first().id

    private fun assertNoCambio(nombre: String, vm: CartViewModel, antes: CartState, clienteAntes: CartViewModel.SelectedCustomer?) {
        assertEquals("«$nombre» cambió la venta fotografiada", antes, vm.cartState.value)
        assertEquals("«$nombre» cambió al cliente", clienteAntes, vm.selectedCustomer.value)
        assertEquals("«$nombre» no avisó", AVISO_COBRO_ABIERTO, vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 con el cobro abierto ninguna entrada de la cajera cambia la venta y cada una lo avisa`() {
        val entradas: List<Pair<String, (CartViewModel, String) -> Unit>> = listOf(
            "agregar producto" to { vm, _ -> vm.addProduct(pan) },
            "agregar con modificadores" to { vm, _ -> vm.addProductWithModifiers(pan, modifiers = emptyList()) },
            "monto libre" to { vm, _ -> vm.addCustomAmount("Varios", 5_000) },
            "cantidad tecleada" to { vm, id -> vm.updateQuantity(id, 3) },
            "más uno" to { vm, id -> vm.incrementQuantity(id) },
            "menos uno" to { vm, id -> vm.decrementQuantity(id) },
            "quitar" to { vm, id -> vm.removeItem(id) },
            "quitar por la cajera" to { vm, id -> vm.quitarPorElCajero(id) },
            "nota del renglón" to { vm, id -> vm.updateItemNote(id, "sin azúcar") },
            "cortesía del renglón" to { vm, id -> vm.updateItemCortesia(id, true, "regalo") },
            "marcar cortesía" to { vm, id -> vm.markItemAsCortesia(id, "regalo") },
            "ajuste de precio" to { vm, id -> vm.updateItemPriceAdjustment(id, 1_000) },
            "descuento de cuenta" to { vm, _ -> vm.applyOrderDiscount(Discount(id = "d-10", name = "10 %", value = 10.0)) },
            "nota de la orden" to { vm, _ -> vm.setOrderNote("para llevar") },
            "tipo de orden" to { vm, _ -> vm.setOrderType("TAKEOUT") },
            "vendedor" to { vm, _ -> vm.selectStaff("staff-2", "Ana") },
            "elegir cliente" to { vm, _ -> vm.setSelectedCustomer("cli-1", "Ana") },
            "vaciar" to { vm, _ -> vm.vaciar() },
            "vaciar a pelo" to { vm, _ -> vm.clearCart() },
            "terminar la venta" to { vm, _ -> vm.finalizarVenta() },
            "terminar la venta del vale" to { vm, _ -> vm.finalizarVentaSiEs(vm.cartState.value.identidadDeVenta) },
        )
        entradas.forEach { (nombre, entrada) ->
            val vm = cafeConElCobroAbierto()
            val antes = vm.cartState.value
            val clienteAntes = vm.selectedCustomer.value
            entrada(vm, vm.renglon())
            assertNoCambio(nombre, vm, antes, clienteAntes)
        }
    }

    @Test
    fun `P1 guardar el carrito con el cobro abierto no escribe la copia ni vacia la venta`() {
        val vm = cafeConElCobroAbierto()
        val antes = vm.cartState.value

        assertFalse(vm.saveCurrentCart("Mesa 4"))

        verify(exactly = 0) { savedCartsRepository.saveCart(any()) }
        assertNoCambio("guardar", vm, antes, null)
    }

    @Test
    fun `P1 restaurar una copia con el cobro abierto no sustituye la venta`() {
        val copia = SavedCart(id = "S1", name = "S1", items = listOf(SavedCartItem(productId = "pan", name = "Pan", unitPrice = 2_000, quantity = 1, lineId = "x")))
        every { savedCartsRepository.savedCarts } returns MutableStateFlow(listOf(copia))
        val vm = cafeConElCobroAbierto()
        val antes = vm.cartState.value

        assertEquals(Restauracion.CobroAbierto, vm.restoreSavedCart(copia, confirmado = true))

        verify(exactly = 0) { savedCartsRepository.deleteCart(any()) }
        assertNoCambio("restaurar", vm, antes, null)
    }

    @Test
    fun `P1 pagar despues con el cobro abierto no difiere la venta`() = runTest {
        val vm = cafeConElCobroAbierto()
        val antes = vm.cartState.value

        assertEquals(ResultadoPagarDespues.Error(AVISO_COBRO_ABIERTO), vm.createPayLaterOrder("cli-1"))

        assertEquals(antes, vm.cartState.value)
        assertNull("el diálogo de «Pagar después» ya lo dice: sin un segundo aviso", vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 inscribir y cobrar con el cobro abierto deja la clase pendiente para despues`() {
        val vm = cafeConElCobroAbierto()
        val antes = vm.cartState.value

        vm.consumePendingClassSeed()

        verify(exactly = 0) { classCheckoutSeed.consume() }
        assertEquals(antes, vm.cartState.value)
    }

    @Test
    fun `P1 la recarga de vendedores espera al cierre del cobro y entonces no vacia la venta, solo le quita al vendedor`() {
        val vm = cafeConElCobroAbierto()
        val antes = vm.cartState.value
        coEvery { staffRepository.getActiveStaff() } returns Result.success(listOf(StaffMember(id = "otro", firstName = "Ana", lastName = "Q")))

        vm.fetchStaff()

        // Durante el cobro la venta no cambia (un abono puede estar confirmándose), pero el disco ya no guarda al vendedor.
        assertEquals(antes, vm.cartState.value)
        verify(exactly = 1) { secureStorage.clearSelectedStaffForCurrentVenue() }

        every { secureStorage.selectedStaffIdForCurrentVenue } returns null
        every { secureStorage.selectedStaffNameForCurrentVenue } returns null
        vm.cerrarCobro()

        assertEquals("la venta sigue (puede traer su «Saldo pendiente»)", listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("user-1", vm.cartState.value.selectedStaffId)
    }

    @Test
    fun `cambiar de sucursal termina la venta aunque quedara el cobro abierto`() {
        val vm = carrito()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        vm.abrirCobro()

        cambioDeSucursal.tryEmit(Unit)

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `con el cobro abierto la pantalla del cliente es del cobro y se pone al dia al cerrarlo`() {
        val vm = carrito()
        vm.addProduct(cafe)
        vm.abrirCobro()
        val delCobro = pantallaDelCliente.content.value

        vm.aplicarCobroConfirmado(splitType = "EQUALPARTS", paidItemIds = emptySet(), remainingBalanceCents = 1_000)
        assertEquals("el «Saldo pendiente» no tapa lo que enseña el cobro", delCobro, pantallaDelCliente.content.value)

        vm.cerrarCobro()
        vm.refreshCustomerDisplay()
        assertTrue(pantallaDelCliente.content.value != delCobro)
    }

    // MARK: - Lo que el propio cobro sí escribe

    @Test
    fun `P1 el cobro completo vacia la venta y suelta al cliente con el cobro abierto`() {
        val vm = carrito()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        vm.abrirCobro()

        val cobro = vm.aplicarCobroConfirmado(splitType = null, paidItemIds = emptySet(), remainingBalanceCents = 0)

        assertTrue(cobro.ventaTerminada)
        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
        assertNull(vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 el cobro con saldo deja Saldo pendiente con el cobro abierto`() {
        val vm = cafeConElCobroAbierto()

        val cobro = vm.aplicarCobroConfirmado(splitType = "EQUALPARTS", paidItemIds = emptySet(), remainingBalanceCents = 1_500)

        assertEquals(CartViewModel.RamaCobro.QUEDA_SALDO, cobro.rama)
        assertEquals(listOf("Saldo pendiente"), vm.cartState.value.items.map { it.name })
        assertEquals(1_500, vm.cartState.value.totalCents)
        assertNull(vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 el cobro por producto quita lo pagado con el cobro abierto`() {
        val vm = carrito()
        vm.addProduct(cafe)
        vm.addProduct(pan)
        vm.abrirCobro()
        val idCafe = vm.cartState.value.items.first { it.name == "Café" }.id

        vm.aplicarCobroConfirmado(splitType = "BYPRODUCT", paidItemIds = setOf(idCafe), remainingBalanceCents = 2_000)

        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertNull(vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 el cobro puede vaciar la venta y recuperar su foto con el cobro abierto`() {
        val vm = carrito()
        vm.addProduct(cafe)
        val foto = vm.fotoDeLaVenta()
        vm.abrirCobro()

        vm.vaciarDesdeElCobro()
        assertTrue(vm.cartState.value.isEmpty)

        assertTrue(vm.restaurarFotoDelCobro(foto))
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertNull(vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 la venta por recuperar no restaura su foto con el cobro abierto, el cobro si`() {
        val vm = carrito()
        vm.addProduct(pan)
        val otraVenta = vm.fotoDeLaVenta()
        vm.clearCart()
        vm.abrirCobro()

        assertFalse("«Venta por recuperar» no mete otra venta con el recibo abierto", vm.restaurarFoto(otraVenta))
        assertTrue(vm.cartState.value.isEmpty)
        assertEquals(AVISO_COBRO_ABIERTO, vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 borrar una copia guardada con el cobro abierto no la borra`() {
        val vm = cafeConElCobroAbierto()

        vm.deleteSavedCart("S1")

        verify(exactly = 0) { savedCartsRepository.deleteCart(any()) }
        assertEquals(AVISO_COBRO_ABIERTO, vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `P1 la clase que esperaba al catalogo y encontro el cobro abierto queda pendiente y la cajera la agrega al cerrarlo`() {
        val catalogo = MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.products } returns catalogo
        val semilla = ClassCheckoutSeed().apply { put(ClassCheckoutSeed.Seed(productId = "cafe", quantity = 1, reservationId = "res-1")) }
        lateinit var vm: CartViewModel
        var cargaConCobro = false   // la carga del arranque (init) no abre nada; la de la semilla sí
        coEvery { productsRepository.fetchProducts(any(), any()) } coAnswers {
            if (cargaConCobro) { vm.abrirCobro(); catalogo.value = listOf(cafe) }
        }
        vm = carrito(semilla)
        cargaConCobro = true

        vm.consumePendingClassSeed()

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.cartState.value.reservationId)
        assertTrue("la clase no se pierde", semilla.hasPending())

        vm.cerrarCobro()
        vm.consumePendingClassSeed()
        assertTrue("D13: ya no entra sola", vm.cartState.value.isEmpty)
        assertNull(vm.agregarClasePendiente(semilla.enEspera.value.single()))   // la cajera la agrega desde «Clase pendiente»
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("res-1", vm.cartState.value.reservationId)
    }

    @Test
    fun `al cerrar el cobro la cajera vuelve a poder cambiar la venta`() {
        val vm = cafeConElCobroAbierto()
        vm.addProduct(pan)
        vm.cerrarCobro()
        vm.consumeAvisoDelCobroAbierto()

        vm.addProduct(pan)

        assertEquals(listOf("Café", "Pan"), vm.cartState.value.items.map { it.name })
        assertNull(vm.avisoDelCobroAbierto.value)
    }

    @Test
    fun `los tiempos de cocina que llegan con el cobro abierto se aplican al cerrarlo`() {
        val cursos = MutableStateFlow<List<com.avoqado.pos.pos.data.model.ServiceCourseSnapshot>>(emptyList())
        val tiempos = mockk<com.avoqado.pos.tables.data.ServiceCoursesRepository>(relaxed = true)
        every { tiempos.courses } returns cursos
        every { tiempos.immediateSnapshot() } answers { cursos.value.firstOrNull { it.kind == "IMMEDIATE" } }
        every { secureStorage.venueType } returns "RESTAURANT"
        val vm = carrito(tiempos = tiempos)
        vm.addProduct(cafe)
        vm.abrirCobro()
        val antes = vm.cartState.value

        cursos.value = listOf(com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("immediate", "Inmediato", "IMMEDIATE", preparationVersion = 1))

        assertEquals("el saldo de regalo compara el carrito vivo con la foto: no cambia durante el cobro", antes, vm.cartState.value)
        vm.cerrarCobro()
        assertEquals("immediate", vm.cartState.value.items.single().serviceCourse?.id)
    }

    @Test
    fun `la clase no entra a una venta a medio cobrar, espera a que termine`() {
        val semilla = ClassCheckoutSeed().apply { put(ClassCheckoutSeed.Seed(productId = "cafe", quantity = 1, reservationId = "res-1")) }
        val vm = carrito(semilla)
        vm.addCustomAmount("Saldo pendiente", 2_000)
        vm.markPendingSplitOrder("orden-1")

        vm.consumePendingClassSeed()

        assertEquals(listOf("Saldo pendiente"), vm.cartState.value.items.map { it.name })
        assertTrue(semilla.hasPending())
    }

    // ── D13: la clase en espera ya no entra sola ──────────────────────────────

    private fun bea(venue: String? = "venue-1") =
        ClassCheckoutSeed.Seed(productId = "cafe", quantity = 1, reservationId = "res-bea", persona = "Bea", venueId = venue)

    @Test
    fun `D13 la clase que espero nunca entra sola, ni a la venta de otra persona`() {
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.addCustomAmount("Saldo pendiente", 2_000)
        vm.markPendingSplitOrder("orden-a")
        vm.consumePendingClassSeed()                 // A va a medio cobrar: la clase de Bea queda pendiente

        vm.vaciarDesdeElCobro()                      // A terminó
        vm.addProduct(pan)                           // la cajera arma o recupera la venta B de otra persona
        vm.consumePendingClassSeed()                 // regresa a Mostrador, cierra un cobro de B…

        assertEquals("la clase de Bea no se mete en la venta de B", listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertEquals(listOf(bea()), vm.clasesPendientesDeEsteNegocio())
        assertNotNull("ni con un toque: va en su propia venta", vm.agregarClasePendiente(bea()))
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })

        vm.vaciarDesdeElCobro()                      // B terminó: carrito vacío, tampoco entra sola
        vm.consumePendingClassSeed()
        assertTrue(vm.cartState.value.isEmpty)

        assertNull(vm.agregarClasePendiente(bea()))  // la cajera la agrega
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("res-bea", vm.cartState.value.reservationId)
        assertTrue(vm.clasesPendientesDeEsteNegocio().isEmpty())
    }

    @Test
    fun `D13 una clase recien inscrita entra al carrito vivo como siempre`() {
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.addProduct(pan)                           // Bea lleva un pan y además se inscribe

        vm.consumePendingClassSeed()

        assertEquals(listOf("Pan", "Café"), vm.cartState.value.items.map { it.name })
        assertEquals("res-bea", vm.cartState.value.reservationId)
    }

    @Test
    fun `D13 dos inscripciones antes de volver a Mostrador no se borran entre si`() {
        val ana = ClassCheckoutSeed.Seed(productId = "pan", quantity = 1, reservationId = "res-ana", persona = "Ana", venueId = "venue-1")
        val semilla = ClassCheckoutSeed().apply { put(bea()); put(ana) }
        val vm = carrito(semilla)

        vm.consumePendingClassSeed()                 // entra la de Bea; la de Ana choca con su reserva y queda pendiente

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("res-bea", vm.cartState.value.reservationId)
        assertEquals(listOf(ana), vm.clasesPendientesDeEsteNegocio())

        vm.vaciarDesdeElCobro()                      // se cobró la de Bea
        assertNull(vm.agregarClasePendiente(ana))
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertEquals("res-ana", vm.cartState.value.reservationId)
    }

    @Test
    fun `D13 la clase de otra sucursal no se pierde ni se enseña aqui`() {
        val semilla = ClassCheckoutSeed().apply { put(bea(venue = "venue-norte")) }
        val vm = carrito(semilla)

        vm.consumePendingClassSeed()

        assertTrue(vm.cartState.value.isEmpty)
        assertTrue(vm.clasesPendientesDeEsteNegocio().isEmpty())
        assertEquals("espera en su sucursal", listOf(bea(venue = "venue-norte")), semilla.enEspera.value)
    }

    @Test
    fun `D13 con un cliente elegido la clase no se agrega a su nombre`() {
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.abrirCobro()
        vm.consumePendingClassSeed()
        vm.cerrarCobro()
        vm.setSelectedCustomer("cliente-beto", "Beto")   // quitó el último artículo de Beto y quedó Beto elegido

        assertNotNull(vm.agregarClasePendiente(bea()))
        assertTrue(vm.cartState.value.isEmpty)
        assertEquals(listOf(bea()), vm.clasesPendientesDeEsteNegocio())
    }

    @Test
    fun `D13 quitar de pendientes no toca nada mas`() {
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.abrirCobro()
        vm.consumePendingClassSeed()
        vm.cerrarCobro()

        vm.quitarClasePendiente(bea())

        assertTrue(vm.clasesPendientesDeEsteNegocio().isEmpty())
        assertTrue(vm.cartState.value.isEmpty)
    }

    @Test
    fun `D13 la clase pendiente empieza una venta nueva aunque el carrito vacio fuera otra venta`() {
        // Codex r2 #1: la cajera vació (renglón por renglón) la copia de Beto y quitó al cliente; el carrito conserva su identidad.
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.abrirCobro()
        vm.consumePendingClassSeed()
        vm.cerrarCobro()
        val deBeto = vm.cartState.value.identidadDeVenta

        assertNull(vm.agregarClasePendiente(bea()))

        assertNotEquals("la clase va en su propia venta", deBeto, vm.cartState.value.identidadDeVenta)
        assertNull(vm.cartState.value.carritoGuardadoId)
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `D13 si su producto no esta en el catalogo la clase queda pendiente en vez de perderse`() {
        // Codex r2 #8: antes salía con un log, con el lugar ya apartado en el servidor.
        val fantasma = ClassCheckoutSeed.Seed(productId = "ya-no-existe", quantity = 1, reservationId = "res-x", persona = "Xime", venueId = "venue-1")
        val semilla = ClassCheckoutSeed().apply { put(fantasma) }
        val vm = carrito(semilla)

        vm.consumePendingClassSeed()

        assertEquals(listOf(fantasma), vm.clasesPendientesDeEsteNegocio())
        assertEquals("Esta clase ya no está en el catálogo de esta sucursal.", vm.agregarClasePendiente(fantasma))
    }

    @Test
    fun `D13 una respuesta tardia no agrega la clase que la cajera ya quito`() {
        // Codex r3 #3: tocó «Agregar», y mientras se consultaba a recepción tocó «Ya no la cobro aquí».
        val semilla = ClassCheckoutSeed().apply { put(bea()) }
        val vm = carrito(semilla)
        vm.abrirCobro()
        vm.consumePendingClassSeed()
        vm.cerrarCobro()
        vm.quitarClasePendiente(bea())

        assertEquals("Esta clase ya no está pendiente.", vm.agregarClasePendiente(bea()))
        assertTrue(vm.cartState.value.isEmpty)
    }

    @Test
    fun `D13 tras un choque la siguiente inscripcion de la fila tambien queda pendiente`() {
        // Codex r3 #4: el carrito ya trae otra reserva y llegaron dos inscripciones antes de volver a Mostrador.
        val ana = ClassCheckoutSeed.Seed(productId = "pan", quantity = 1, reservationId = "res-ana", persona = "Ana", venueId = "venue-1")
        val ori = ClassCheckoutSeed.Seed(productId = "cafe", quantity = 1, reservationId = "res-ori", persona = "Ori", venueId = "venue-1")
        val semilla = ClassCheckoutSeed().apply { put(ori) }
        val vm = carrito(semilla)
        vm.consumePendingClassSeed()                 // la de Ori ya está en el carrito
        semilla.put(bea()); semilla.put(ana)

        vm.consumePendingClassSeed()

        assertEquals(listOf(bea(), ana), vm.clasesPendientesDeEsteNegocio())
        assertFalse(semilla.hayFrescas())
    }

    @Test
    fun `D13 la inscripcion de otra sucursal no detiene la fila`() {
        // Codex r4 #2: Norte espera en su sucursal y la de Sur (ésta) se procesa en su turno.
        val norte = ClassCheckoutSeed.Seed(productId = "cafe", quantity = 1, reservationId = "res-n", persona = "Nadia", venueId = "venue-norte")
        val semilla = ClassCheckoutSeed().apply { put(norte); put(bea()) }
        val vm = carrito(semilla)

        vm.consumePendingClassSeed()

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("res-bea", vm.cartState.value.reservationId)
        assertEquals(listOf(norte), semilla.enEspera.value)
    }
}
