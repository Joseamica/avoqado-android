package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.DescuentoDelCliente
import com.avoqado.pos.giftcards.domain.FotoDeLaVenta
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.AutorizacionDelDescuento
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D2 · el carrito de Mostrador pone solo el descuento que el negocio le asignó al cliente (spec 2026-11-11, «Bloque C v3»):
 * con `discounts:apply` se pone; sin él, aviso y PIN del gerente atado al monto; «Quitar» vale para esta venta; nunca conviven
 * dos descuentos de cuenta (ni con el premio ni con un combo); el carrito guardado y la foto conservan marca, token, tope y
 * «quitados»; y un carrito restaurado por otra sesión sin permiso ni token frena antes de crear nada.
 */
class CartViewModelDescuentoDelClienteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val coordinador = mockk<ManagerOverrideCoordinator>(relaxed = true)
    private val conectado = MutableStateFlow(true)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true)

    /** Los permisos efectivos de la sesión: cada prueba decide si trae `discounts:apply`. */
    private var permisos: List<String> = listOf("discounts:apply")
    private var pinPrendido = true

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
        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.accessToken } returns "token"
        every { secureStorage.giftCardRequestsJson } returns null
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        every { secureStorage.venuePermissions } answers { permisos }
        every { secureStorage.managerPinOverrideEnabled } answers { pinPrendido }
        every { monitor.isConnected } returns conectado
        every { monitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { staffRepository.getActiveStaff() } returns Result.success(listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")))
    }

    private fun carrito(repo: SavedCartsRepository = SavedCartsRepository(PrefsDeDosCapas().context)): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = repo,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = orderRepository,
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true),
        captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
        connectivityMonitor = monitor,
        managerOverrideCoordinator = coordinador,
    )

    private val playera = Product(id = "playera", name = "Playera", priceValue = 100.0)
    private val gorra = Product(id = "gorra", name = "Gorra", priceValue = 20.0)
    private val cecilia = Customer(
        id = "c-cecilia",
        firstName = "FULLTEST-Cecilia",
        descuentoDelCliente = DescuentoDelCliente("d-10", "Cliente frecuente", "PERCENTAGE", 10.0),
    )
    private val ernesto = Customer(
        id = "c-ernesto",
        firstName = "FULLTEST-Ernesto",
        descuentoDelCliente = DescuentoDelCliente("d-50", "Vecino", "FIXED_AMOUNT", 50.0),
    )
    private val manual = Discount(id = "m-5", name = "Manual 5 %", value = 5.0, type = "PERCENTAGE", scope = "ORDER")
    private val premio = PremioPorAplicar(id = "r1", etiqueta = "Café gratis", tipo = "FIXED_AMOUNT", valor = 20.0)
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
                        id = "o-cerveza", productId = "p-cerveza", quantity = 2, chargedQuantity = 1,
                        productName = "Cerveza", productPriceCents = 5000,
                    ),
                ),
            ),
        ),
    )
    private val llaveCecilia = "descuento-cliente:c-cecilia:d-10:1000"

    private fun CartViewModel.conPlayeraYCecilia() = apply {
        addProduct(playera)
        setSelectedCustomer(cecilia)
    }

    private fun autorizarConPin(vm: CartViewModel, llave: String = llaveCecilia, token: String = "tok-1") {
        coEvery { coordinador.preauthorize("discounts:apply", llave, any()) } returns true
        every { coordinador.tomarPreautorizado("discounts:apply", llave) } returns token
        vm.pedirAutorizacionDelDescuento()
    }

    // MARK: - 6a · con permiso se pone solo

    @Test
    fun `6a con discounts apply elegir a Cecilia pone su 10 por ciento en 100 y cobra 90`() {
        val vm = carrito().conPlayeraYCecilia()

        val estado = vm.cartState.value
        assertEquals(DESCUENTO_DEL_CLIENTE_SOURCE, estado.orderDiscount?.source)
        assertEquals("d-10", estado.orderDiscount?.id)
        assertEquals(1000, estado.orderDiscountCents)
        assertEquals(9000, estado.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
        assertTrue(vm.puedeCrearOrdenConDescuentoDelCliente())
    }

    @Test
    fun `6a2 un cliente que llega de una busqueda nueva trae su descuento aunque la memoria de clientes este llena`() {
        // La lista de clientes pasa el objeto que recibió (con su descuento): el carrito nunca lo vuelve a buscar en la memoria,
        // que deja de guardar a los 500. Este cliente no está en ninguna memoria: sólo llega en el objeto.
        val deUnaBusquedaNueva = cecilia.copy(id = "c-cliente-501")
        val vm = carrito()
        vm.addProduct(playera)
        vm.setSelectedCustomer(deUnaBusquedaNueva)

        assertEquals("d-10", vm.cartState.value.orderDiscount?.id)
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        // La tarjeta escaneada del MISMO cliente no trae su descuento: no se lo quita.
        vm.setSelectedCustomer("c-cliente-501", "FULLTEST-Cecilia")
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
    }

    // MARK: - 6b · sin permiso: aviso y PIN atado al monto

    @Test
    fun `6b sin permiso no se pone, avisa y el PIN se ata al monto que vio el gerente`() {
        permisos = listOf("orders:read")
        val vm = carrito().conPlayeraYCecilia()

        assertNull(vm.cartState.value.orderDiscount)
        val aviso = vm.avisoDescuentoDelCliente.value!!
        assertEquals("FULLTEST-Cecilia tiene 10 % de descuento. Tu puesto no puede aplicarlo.", aviso.texto)
        assertEquals(AvisoDescuentoDelCliente.Accion.PEDIR_AUTORIZACION, aviso.accion)
        assertEquals("Pedir autorización", aviso.boton)

        autorizarConPin(vm)

        coVerify(exactly = 1) {
            coordinador.preauthorize("discounts:apply", llaveCecilia, "Descuento de FULLTEST-Cecilia −$10.00")
        }
        val estado = vm.cartState.value
        assertEquals(DESCUENTO_DEL_CLIENTE_SOURCE, estado.orderDiscount?.source)
        assertEquals(1000, estado.orderDiscountCents)
        assertEquals(AutorizacionDelDescuento("tok-1", 1000, "c-cecilia", "d-10"), estado.autorizacionDelDescuento)
        assertNull(vm.avisoDescuentoDelCliente.value)
        assertTrue(vm.puedeCrearOrdenConDescuentoDelCliente())
    }

    @Test
    fun `6b con el PIN apagado el aviso no ofrece pedir autorizacion`() {
        permisos = listOf("orders:read")
        pinPrendido = false
        val vm = carrito().conPlayeraYCecilia()

        val aviso = vm.avisoDescuentoDelCliente.value!!
        assertNull(aviso.accion)
        assertNull(aviso.boton)
    }

    @Test
    fun `6b el gerente cancela el PIN y no se pone nada`() {
        permisos = listOf("orders:read")
        val vm = carrito().conPlayeraYCecilia()
        coEvery { coordinador.preauthorize("discounts:apply", llaveCecilia, any()) } returns false

        vm.pedirAutorizacionDelDescuento()

        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.cartState.value.autorizacionDelDescuento)
    }

    // MARK: - 6c · Quitar, cambiar y quitar al cliente

    @Test
    fun `6c Quitar y volver a elegir a Cecilia no lo regresa`() {
        val vm = carrito().conPlayeraYCecilia()

        vm.quitarDescuentoDelCliente()
        assertNull(vm.cartState.value.orderDiscount)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)

        vm.setSelectedCustomer(null)
        vm.setSelectedCustomer(cecilia)
        assertNull(vm.cartState.value.orderDiscount)
        assertNull(vm.avisoDescuentoDelCliente.value)
    }

    @Test
    fun `6c cambiar a Ernesto quita el de Cecilia y pone el de Ernesto, y quitar al cliente quita el suyo`() {
        val vm = carrito().conPlayeraYCecilia()

        vm.setSelectedCustomer(ernesto)
        assertEquals("d-50", vm.cartState.value.orderDiscount?.id)
        assertEquals(5000, vm.cartState.value.orderDiscountCents)

        vm.setSelectedCustomer(null)
        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(10000, vm.cartState.value.totalCents)
    }

    @Test
    fun `6c quitar el descuento desde la hoja de descuentos tambien cuenta como Quitar`() {
        val vm = carrito().conPlayeraYCecilia()

        vm.applyOrderDiscount(null)

        assertNull(vm.cartState.value.orderDiscount)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)
    }

    // MARK: - 6d · nunca conviven dos (en los dos sentidos)

    @Test
    fun `6d un descuento manual encima quita el del cliente, queda quitado y avisa`() {
        val vm = carrito().conPlayeraYCecilia()

        vm.applyOrderDiscount(manual)

        assertEquals("m-5", vm.cartState.value.orderDiscount?.id)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)
        assertTrue(vm.avisoDeVales.value!!.startsWith("Se quitó el descuento de FULLTEST-Cecilia"))
        // Y quitar el manual no lo regresa: se quitó en esta venta.
        vm.applyOrderDiscount(null)
        assertNull(vm.cartState.value.orderDiscount)
    }

    @Test
    fun `6d un cupon encima quita el del cliente`() {
        val vm = carrito().conPlayeraYCecilia()

        vm.applyOrderDiscount(manual.copy(id = "cupon", couponCode = "HOLA10"))

        assertEquals("HOLA10", vm.cartState.value.couponCode)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)
    }

    @Test
    fun `6d el premio de sellos despues del descuento del cliente lo quita, nunca suman dos`() {
        val vm = carrito().conPlayeraYCecilia()

        assertTrue(vm.setPendingStampReward(premio))

        assertNull(vm.cartState.value.orderDiscount)
        assertEquals("r1", vm.cartState.value.pendingStampReward?.id)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)
        assertEquals(10000 - 2000, vm.cartState.value.totalCents)
    }

    @Test
    fun `6d agregar un combo quita el del cliente`() {
        val vm = carrito().conPlayeraYCecilia()

        assertTrue(vm.aplicarPromocion(dosPorUno, emptyMap()))

        assertNull(vm.cartState.value.orderDiscount)
        assertTrue("c-cecilia" in vm.cartState.value.descuentosDeClienteQuitados)
    }

    @Test
    fun `6d con un manual ya puesto no se pone y Usar el de Cecilia lo reemplaza`() {
        val vm = carrito()
        vm.addProduct(playera)
        vm.applyOrderDiscount(manual)
        vm.setSelectedCustomer(cecilia)

        assertEquals("m-5", vm.cartState.value.orderDiscount?.id)
        val aviso = vm.avisoDescuentoDelCliente.value!!
        assertEquals("FULLTEST-Cecilia tiene 10 % de descuento.", aviso.texto)
        assertEquals("Usar el de FULLTEST-Cecilia", aviso.boton)
        assertEquals(AvisoDescuentoDelCliente.Accion.USAR_EL_DEL_CLIENTE, aviso.accion)

        vm.usarDescuentoDelCliente()

        assertEquals("d-10", vm.cartState.value.orderDiscount?.id)
        assertEquals(9000, vm.cartState.value.totalCents)
        assertNull(vm.avisoDescuentoDelCliente.value)
    }

    @Test
    fun `6d con el premio ya puesto no se pone y Usar el de Cecilia quita el premio`() {
        val vm = carrito()
        vm.addProduct(playera)
        // La tarjeta escaneada liga al cliente (sin su descuento) y su premio; luego llega de la lista con su descuento.
        vm.setSelectedCustomer("c-cecilia", "FULLTEST-Cecilia")
        vm.setPendingStampReward(premio)
        vm.setSelectedCustomer(cecilia)

        assertNull(vm.cartState.value.orderDiscount)
        assertEquals(AvisoDescuentoDelCliente.Accion.USAR_EL_DEL_CLIENTE, vm.avisoDescuentoDelCliente.value?.accion)

        vm.usarDescuentoDelCliente()

        assertNull(vm.cartState.value.pendingStampReward)
        assertEquals("d-10", vm.cartState.value.orderDiscount?.id)
        assertEquals(9000, vm.cartState.value.totalCents)
    }

    @Test
    fun `6d con un combo ya puesto no se pone y lo dice`() {
        val vm = carrito()
        vm.addProduct(playera)
        vm.aplicarPromocion(dosPorUno, emptyMap())
        vm.setSelectedCustomer(cecilia)

        assertNull(vm.cartState.value.orderDiscount)
        val aviso = vm.avisoDescuentoDelCliente.value!!
        assertEquals("Con combos en la cuenta el descuento de FULLTEST-Cecilia no se pone solo.", aviso.texto)
        assertNull(aviso.accion)
    }

    // MARK: - 6e · el tope del PIN

    @Test
    fun `6e si la cuenta crece despues del PIN y pasa el tope se pide otra vez, y al bajar vuelve a servir`() {
        permisos = listOf("orders:read")
        val vm = carrito().conPlayeraYCecilia()
        autorizarConPin(vm)

        vm.addProduct(gorra)
        assertEquals(1200, vm.cartState.value.orderDiscountCents)
        val aviso = vm.avisoDescuentoDelCliente.value!!
        assertEquals("El descuento de FULLTEST-Cecilia subió a $12.00: pide autorización otra vez.", aviso.texto)
        assertEquals(AvisoDescuentoDelCliente.Accion.PEDIR_AUTORIZACION, aviso.accion)
        assertFalse(vm.puedeCrearOrdenConDescuentoDelCliente())

        val renglonGorra = vm.cartState.value.items.first { it.name == "Gorra" }.id
        vm.removeItem(renglonGorra)
        assertTrue(vm.puedeCrearOrdenConDescuentoDelCliente())
        assertNull(vm.avisoDescuentoDelCliente.value)
    }

    // MARK: - 6f · persistencia

    @Test
    fun `6f guardar y restaurar el carrito conserva marca, token, tope y quitados`() {
        val prefs = PrefsDeDosCapas()
        val vm = conTokenYUnQuitado(carrito(SavedCartsRepository(prefs.context)))
        assertTrue(vm.saveCurrentCart("Cecilia"))

        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()
        val otro = carrito()
        otro.restoreSavedCart(guardado)

        comprobarRestaurado(otro)
    }

    @Test
    fun `6f la foto de la venta conserva marca, token, tope y quitados`() {
        val vm = conTokenYUnQuitado(carrito())
        val foto = Json.decodeFromString(FotoDeLaVenta.serializer(), Json.encodeToString(FotoDeLaVenta.serializer(), vm.fotoDeLaVenta()))

        val otro = carrito()
        assertTrue(otro.restaurarFoto(foto))

        comprobarRestaurado(otro)
    }

    /** Ernesto quitado con permiso; luego, sin permiso, Cecilia con el PIN del gerente (tope $10.00). */
    private fun conTokenYUnQuitado(vm: CartViewModel): CartViewModel {
        vm.addProduct(playera)
        vm.setSelectedCustomer(ernesto)
        vm.quitarDescuentoDelCliente()
        permisos = listOf("orders:read")
        vm.setSelectedCustomer(cecilia)
        autorizarConPin(vm)
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        return vm
    }

    private fun comprobarRestaurado(vm: CartViewModel) {
        val estado = vm.cartState.value
        assertEquals(DESCUENTO_DEL_CLIENTE_SOURCE, estado.orderDiscount?.source)
        assertEquals(1000, estado.orderDiscountCents)
        assertEquals(AutorizacionDelDescuento("tok-1", 1000, "c-cecilia", "d-10"), estado.autorizacionDelDescuento)
        assertEquals(setOf("c-ernesto"), estado.descuentosDeClienteQuitados)
        val cliente = vm.selectedCustomer.value!!
        assertEquals("c-cecilia", cliente.id)
        assertEquals("FULLTEST-Cecilia", cliente.name)
        assertEquals("d-10", cliente.descuentoDelCliente?.discountId)
        assertTrue(vm.puedeCrearOrdenConDescuentoDelCliente())
        assertNull(vm.avisoDescuentoDelCliente.value)
    }

    // MARK: - 6g · re-revisión antes de crear la orden

    @Test
    fun `6g carrito restaurado por otra sesion sin permiso ni token frena Cobrar y Pagar despues con el texto del 403`() = runTest {
        val prefs = PrefsDeDosCapas()
        val conPermiso = carrito(SavedCartsRepository(prefs.context)).conPlayeraYCecilia()
        assertTrue(conPermiso.saveCurrentCart("Cecilia"))
        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()

        permisos = listOf("orders:read")
        val vm = carrito()
        vm.restoreSavedCart(guardado)
        val texto403 = "Tu puesto no puede aplicar el descuento de FULLTEST-Cecilia. Pide autorización o quítalo."

        assertEquals(DESCUENTO_DEL_CLIENTE_SOURCE, vm.cartState.value.orderDiscount?.source)
        assertFalse(vm.puedeCrearOrdenConDescuentoDelCliente())
        assertEquals(texto403, vm.avisoDescuentoDelCliente.value?.texto)
        assertEquals(AvisoDescuentoDelCliente.Accion.PEDIR_AUTORIZACION, vm.avisoDescuentoDelCliente.value?.accion)

        assertTrue(vm.detenerCobroPorDescuentoDelCliente())
        assertEquals(texto403, vm.avisoDeVales.value)

        val diferida = vm.createPayLaterOrder("c-cecilia")
        assertEquals(ResultadoPagarDespues.Error(texto403), diferida)
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(1, vm.cartState.value.items.size)
    }

    @Test
    fun `6g con permiso propio el cobro no se frena`() {
        val vm = carrito().conPlayeraYCecilia()

        assertFalse(vm.detenerCobroPorDescuentoDelCliente())
        assertNull(vm.avisoDeVales.value)
    }

    // MARK: - 6h · sin red

    @Test
    fun `6h sin permiso y sin red pedir autorizacion dice que necesita internet y no abre el teclado`() {
        permisos = listOf("orders:read")
        conectado.value = false
        val vm = carrito().conPlayeraYCecilia()

        vm.pedirAutorizacionDelDescuento()

        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        assertEquals(
            "Sin conexión: pedir la autorización del descuento de FULLTEST-Cecilia necesita internet.",
            vm.avisoDescuentoDelCliente.value?.texto,
        )
        assertNull(vm.cartState.value.orderDiscount)
    }

    @Test
    fun `6h con permiso propio y sin red se pone igual`() {
        conectado.value = false
        val vm = carrito().conPlayeraYCecilia()

        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        assertTrue(vm.puedeCrearOrdenConDescuentoDelCliente())
    }
}
