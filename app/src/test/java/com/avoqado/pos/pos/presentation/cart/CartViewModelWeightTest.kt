package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.VariableWeightBarcodeSettings
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
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.junit.Assert.assertTrue
import io.mockk.verify
import io.mockk.coVerify
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutOrder
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketScanData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Venta por peso en [CartViewModel]: D9 — cada pesada agrega una LÍNEA NUEVA (nunca se fusiona),
 * mientras que un producto normal sigue fusionando cantidad (regresión).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelWeightTest {

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
    private val apartadoDeVales = MutableStateFlow<AreaTicketCheckout?>(null)

    @Before
    fun setup() {
        every { areaTicketRepository.session.checkout } returns apartadoDeVales
        every { areaTicketRepository.session.ventaDuena } returns null
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.userFirstName } returns "Jose"
        every { secureStorage.userLastName } returns "Tester"
        every { secureStorage.userEmail } returns "jose@example.com"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )
        coEvery { areaTicketRepository.settings() } returns AreaTicketSettingsData(
            venueId = "venue-1",
            areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
            terminal = AreaTicketTerminalCapabilities(id = "terminal-1", name = "Caja"),
            scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
            variableWeightBarcode = VariableWeightBarcodeSettings(
                entitled = true,
                enabled = true,
                prefix = "20",
            ),
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
        walletScanRepository = mockk(relaxed = true),
    )

    private val jamon = Product(
        id = "jamon",
        name = "Jamón serrano",
        priceValue = 420.0,
        soldByWeight = true,
        unit = "KILOGRAM",
    )

    private val burger = Product(
        id = "burger",
        name = "Hamburguesa",
        priceValue = 150.0,
    )

    @Test
    fun `two weighings of the same product create two separate lines (D9)`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)
        vm.addProductByWeight(jamon, 0.512)

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        assertEquals(0.435, items[0].weightKg!!, 0.0)
        assertEquals(0.512, items[1].weightKg!!, 0.0)
        // $182.70 + $215.04
        assertEquals(18270 + 21504, vm.cartState.value.subtotalCents)
    }

    @Test
    fun `weighted line carries price per kg as unitPrice and quantity 1`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)

        val item = vm.cartState.value.items.single()
        assertEquals(42000, item.unitPrice)
        assertEquals(1, item.quantity)
        assertEquals(18270, item.totalPrice)
    }

    @Test
    fun `zero weight is ignored (no line added)`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.0)
        assertEquals(0, vm.cartState.value.items.size)
    }

    @Test
    fun `normal product still merges into a single line with summed quantity (regression)`() {
        val vm = createViewModel()
        vm.addProduct(burger)
        vm.addProduct(burger)

        val items = vm.cartState.value.items
        assertEquals(1, items.size)
        assertEquals(2, items.single().quantity)
        assertNull(items.single().weightKg)
    }

    @Test
    fun `weighted and normal lines coexist without merging`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)
        vm.addProduct(burger)
        vm.addProductByWeight(jamon, 0.512)

        val items = vm.cartState.value.items
        assertEquals(3, items.size)
        assertNotNull(items[0].weightKg)
        assertNull(items[1].weightKg)
        assertNotNull(items[2].weightKg)
    }

    @Test
    fun `external scale EAN13 resolves its PLU and weight without opening manual capture`() = runTest {
        every { productsRepository.products } returns MutableStateFlow(listOf(jamon.copy(sku = "00123")))
        val vm = createViewModel()

        val result = vm.resolveScannedBarcode("2000123004358")

        result as ScannedBarcodeResult.WeightedProductFound
        assertEquals("jamon", result.product.id)
        assertEquals(0.435, result.weightKg, 0.0)
    }

    // Pendientes del 19-oct, de raíz (Codex r1 #2 a r4 #1): el CARRITO —no la pantalla, que se recrea al cambiar de
    // pestaña— sabe si hay un cobro abierto y cuántos códigos se están consultando. Con el cobro abierto, un vale no se
    // pide ni toca el carrito; mientras haya consultas, el cobro espera. El escaneo vive en este archivo porque aquí está
    // el arnés del carrito con vales.
    @Test
    fun `P1 con el cobro abierto en el carrito el vale no se pide ni lo toca`() = runTest {
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val vm = createViewModel()
        vm.abrirCobro()

        val result = vm.resolveScannedBarcode("9470000015")

        assertTrue(result is ScannedBarcodeResult.Error)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    @Test
    fun `P1 si el cobro se abre mientras responde el servidor, el vale no toca el carrito`() = runTest {
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val checkout = mockk<AreaTicketCheckout>(relaxed = true)
        val vm = createViewModel()
        coEvery { areaTicketRepository.addTicket("9470000015") } coAnswers { vm.abrirCobro(); checkout }

        val result = vm.resolveScannedBarcode("9470000015")

        assertTrue(result is ScannedBarcodeResult.Error)
        verify(exactly = 0) { checkout.tickets }
    }

    // Codex r5: agregar un vale CAMBIA el servidor. Si la pantalla se va (cambiar de pestaña, girar la tablet) cuando el
    // vale ya se pidió, el carrito lo termina igual —si no, el servidor quedaba con A+B y el carrito con A—, y el cobro
    // espera hasta entonces.
    @Test
    fun `P1 si la pantalla se va con el vale ya pedido, el carrito lo recibe igual y el cobro espera hasta entonces`() = runTest {
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val respuesta = kotlinx.coroutines.CompletableDeferred<AreaTicketCheckout>()
        coEvery { areaTicketRepository.addTicket("9470000015") } coAnswers { respuesta.await() }
        val vm = createViewModel()

        val consulta = launch { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        assertTrue(vm.hayConsultasDeCodigo)
        consulta.cancel()
        runCurrent()
        assertTrue("el servidor todavía no contesta: el cobro debe esperar", vm.hayConsultasDeCodigo)

        val checkout = mockk<AreaTicketCheckout>(relaxed = true)
        respuesta.complete(checkout)
        runCurrent()
        verify { checkout.tickets }
        assertFalse(vm.hayConsultasDeCodigo)
    }

    @Test
    fun `si la pantalla se va antes de pedir el vale, no se pide y el cobro no espera`() = runTest {
        val nuncaContesta = kotlinx.coroutines.CompletableDeferred<AreaTicketScanData>()
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } coAnswers { nuncaContesta.await() }
        val vm = createViewModel()

        val consulta = launch { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        consulta.cancel()
        runCurrent()

        assertFalse(vm.hayConsultasDeCodigo)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    // Codex r6 #2: un paso intermedio (guardar la configuración en disco) se traga la cancelación y regresa normal. Aun así,
    // si la pantalla ya se fue, el vale NO se pide: la cancelación se revisa justo antes de pedirlo.
    @Test
    fun `si la pantalla se va mientras se guarda la configuracion, el vale no se pide`() = runTest {
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        var consulta: kotlinx.coroutines.Job? = null
        val ajustes = areaTicketRepository.settings()
        coEvery { areaTicketRepository.settings() } coAnswers { consulta?.cancel(); ajustes }
        val vm = createViewModel()

        consulta = launch { vm.resolveScannedBarcode("9470000015") }
        runCurrent()

        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
        assertFalse(vm.hayConsultasDeCodigo)
    }

    // Codex r6 #1: el cobro de vales se detuvo sin cobrar porque el carrito no traía los vales del servidor. El carrito toma los
    // de la sesión (que el cobro acaba de releer), sin red, para que el siguiente cobro sea por lo que el servidor tiene.
    @Test
    fun `tras un cobro detenido por vales que no coincidian, el carrito toma los de la sesion`() {
        every { areaTicketRepository.session.current() } returns checkoutConVales("linea-a" to "10.00", "linea-b" to "25.50")
        val vm = createViewModel()

        vm.traerValesDeLaSesion()

        assertEquals(
            listOf("linea-a" to 1000, "linea-b" to 2550),
            vm.cartState.value.items.map { it.areaTicketLineId to it.unitPrice },
        )
    }

    // D1 (20-oct): el apartado del vale ya no existe y el cobro se detuvo pidiendo volver a escanearlo. El renglón tiene que
    // poder quitarse: antes el borrado le pedía al servidor quitar el vale de una sesión que ya no había, fallaba en silencio
    // y el renglón se quedaba para siempre.
    @Test
    fun `un vale sin apartado se quita del carrito sin pedirle nada al servidor`() {
        every { areaTicketRepository.session.current() } returns checkoutConVales("linea-a" to "10.00")
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        val renglon = vm.cartState.value.items.single()
        every { areaTicketRepository.session.current() } returns null

        vm.removeItem(renglon.id)

        assertTrue(vm.cartState.value.items.isEmpty())
        coVerify(exactly = 0) { areaTicketRepository.removeTicket(any()) }
    }

    // D1 (20-oct): cuando el apartado se suelta de verdad («Cancelar» con red, vencido o pagado), los vales salen del carrito y
    // lo demás se queda. Antes los renglones se quedaban sin apartado y el siguiente cobro los vendía como importe libre.
    @Test
    fun `cuando el apartado se suelta los vales salen del carrito y lo demas se queda`() {
        val apartado = checkoutConVales("linea-a" to "10.00")
        every { areaTicketRepository.session.current() } returns apartado
        apartadoDeVales.value = apartado
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        vm.addCustomAmount(name = "Agua", amountCents = 2500)

        apartadoDeVales.value = null

        assertEquals(listOf("Agua"), vm.cartState.value.items.map { it.name })
    }

    // Al abrir no hay apartado todavía (se restaura después): eso no es «se soltó» y no toca el carrito.
    @Test
    fun `sin apartado desde el principio el carrito no se toca`() {
        every { areaTicketRepository.session.current() } returns checkoutConVales("linea-a" to "10.00")
        val vm = createViewModel()
        vm.traerValesDeLaSesion()

        apartadoDeVales.value = null

        assertEquals(listOf("linea-a"), vm.cartState.value.items.map { it.areaTicketLineId })
    }

    private fun checkoutConVales(vararg lineas: Pair<String, String>) = AreaTicketCheckout(
        id = "checkout-1",
        status = "MATERIALIZED",
        version = 2,
        expiresAt = "2026-10-21T00:00:00.000Z",
        createdAt = "2026-10-20T00:00:00.000Z",
        tickets = lineas.map { (id, total) ->
            AreaTicket(
                id = "vale-$id",
                code = "947$id",
                status = "CLAIMED",
                fulfillmentArea = AreaTicketArea(id = "cocina", name = "Cocina", fulfillmentMode = "PICKUP"),
                subtotal = total,
                total = total,
                issuedAt = "2026-10-20T00:00:00.000Z",
                lines = listOf(
                    AreaTicketLine(id = id, clientLineId = "c-$id", productNameSnapshot = "Torta", quantity = "1", unitPrice = total, total = total),
                ),
            )
        },
        totals = AreaTicketCheckoutTotals(subtotal = "0.00", discountAmount = "0.00", total = "0.00"),
    )

    // MARK: - D3 de vales: tras un abono, el carrito queda en «Saldo pendiente» y la sesión no lo infla (Codex r7 AJENO #3)

    private fun sesionConOrden(status: String, pagado: String) =
        checkoutConVales("linea-a" to "50.00", "linea-b" to "100.00").copy(
            status = status,
            order = AreaTicketCheckoutOrder(
                id = "orden-vales",
                orderNumber = "AREA-9",
                paymentStatus = "PARTIAL",
                status = "OPEN",
                paidAmount = pagado,
                remainingBalance = "75.00",
                total = "150.00",
            ),
        )

    // Tras cobrar $75 de $150, el carrito queda en «Saldo pendiente $75». La sesión sigue viva (el servidor no deja cancelar
    // una venta con abono) y, al volver a la pantalla, restaurarla metía TODOS los vales encima: $75 + $150 = $225.
    @Test
    fun `D3 tras un abono, volver a la pantalla no mete los vales encima del saldo pendiente`() = runTest {
        val sesion = sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        every { areaTicketRepository.session.current() } returns sesion
        coEvery { areaTicketRepository.restore(any()) } returns sesion
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")

        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(7500, vm.cartState.value.totalCents)
        assertTrue(vm.cartState.value.items.none { it.areaTicketLineId != null })
    }

    // La copia del aparato puede no saber del abono (su relectura falló tras cobrar), pero el carrito sí sabe que continúa ESA
    // orden: tampoco se le meten los vales.
    @Test
    fun `D3 con la copia vieja de la sesion, el carrito que continua esa orden tampoco recibe los vales`() = runTest {
        val vieja = sesionConOrden(status = "MATERIALIZED", pagado = "0.00")
        every { areaTicketRepository.session.current() } returns vieja
        coEvery { areaTicketRepository.restore(any()) } returns vieja
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")

        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(7500, vm.cartState.value.totalCents)
        assertTrue(vm.cartState.value.items.none { it.areaTicketLineId != null })
    }

    // El cajero dejó ese saldo y empezó otra venta: la sesión con abono que quedó viva no se mete en la venta nueva.
    @Test
    fun `D3 una venta nueva no recibe los vales de una sesion con abono que quedo viva`() = runTest {
        val sesion = sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        every { areaTicketRepository.session.current() } returns sesion
        coEvery { areaTicketRepository.restore(any()) } returns sesion
        val vm = createViewModel()
        vm.addProduct(burger)

        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    // Lo de siempre sigue igual: una sesión SIN abonos se restaura con sus vales.
    @Test
    fun `restaurar una sesion sin abonos sigue poniendo sus vales en el carrito`() = runTest {
        val sesion = checkoutConVales("linea-a" to "50.00", "linea-b" to "100.00")
        every { areaTicketRepository.session.current() } returns sesion
        coEvery { areaTicketRepository.restore(any()) } returns sesion
        val vm = createViewModel()

        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(listOf("linea-a", "linea-b"), vm.cartState.value.items.map { it.areaTicketLineId })
        assertEquals(15000, vm.cartState.value.totalCents)
    }

    // Codex r1 de D3: se cobró el abono pero la relectura falló, así que la copia del aparato sigue diciendo «sin abonos». Si
    // luego el cajero vacía el carrito (se pierde el vínculo con la orden), al empezar otra venta y volver a la pantalla
    // regresaban los vales viejos. Ahora el carrito anota el abono en la sesión en cuanto sabe que la venta sigue con saldo.
    @Test
    fun `D3 con la copia vieja y el carrito vaciado, la venta nueva no recibe los vales de la orden con abono`() = runTest {
        val sesionReal = com.avoqado.pos.areatickets.data.AreaTicketSession(mockk(relaxed = true))
        sesionReal.update("venue-1", sesionConOrden(status = "MATERIALIZED", pagado = "0.00"))
        every { areaTicketRepository.session } returns sesionReal
        coEvery { areaTicketRepository.restore(any()) } coAnswers { sesionReal.current() }
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")

        vm.clearCart()
        vm.addProduct(burger)
        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    // Dividir OTRA venta no marca la sesión de vales: su orden es distinta y sus vales se siguen viendo.
    @Test
    fun `D3 dividir otra venta no marca la sesion de vales`() = runTest {
        val sesionReal = com.avoqado.pos.areatickets.data.AreaTicketSession(mockk(relaxed = true))
        sesionReal.update("venue-1", sesionConOrden(status = "MATERIALIZED", pagado = "0.00"))
        every { areaTicketRepository.session } returns sesionReal
        val vm = createViewModel()

        vm.markPendingSplitOrder("otra-orden")

        assertEquals("MATERIALIZED", sesionReal.current()?.status)
    }

    // MARK: - D5 (20-oct): una venta de vales con abono no se vacía (Toast y Clover tampoco lo dejan)

    @Test
    fun `D5 con un abono en la venta de vales que sigue el carrito, Vaciar se bloquea`() {
        every { areaTicketRepository.session.current() } returns sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")

        assertTrue(vm.vaciarBloqueadoPorAbono())
    }

    @Test
    fun `D5 sin abono, o si el carrito no sigue esa venta, Vaciar se deja`() {
        every { areaTicketRepository.session.current() } returns sesionConOrden(status = "MATERIALIZED", pagado = "0.00")
        val vm = createViewModel()
        vm.markPendingSplitOrder("orden-vales")
        assertFalse(vm.vaciarBloqueadoPorAbono())

        every { areaTicketRepository.session.current() } returns sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        vm.markPendingSplitOrder(null)
        assertFalse(vm.vaciarBloqueadoPorAbono())
    }

    // MARK: - D4 (20-oct): con una venta cobrada a medias no se escanea un vale (armaba una orden nueva y la vieja quedaba abierta)

    @Test
    fun `D4 con una venta cobrada a medias, escanear un vale se detiene sin pedirlo`() = runTest {
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 5000)
        vm.markPendingSplitOrder("orden-normal")

        val result = vm.resolveScannedBarcode("9470000015")

        assertEquals(VALE_CON_VENTA_A_MEDIAS, (result as ScannedBarcodeResult.Error).message)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    @Test
    fun `D4 con una venta cobrada a medias, restaurar la sesion no mete vales encima del saldo`() = runTest {
        val sesion = checkoutConVales("linea-a" to "10.00")
        every { areaTicketRepository.session.current() } returns sesion
        coEvery { areaTicketRepository.restore(any()) } returns sesion
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 5000)
        vm.markPendingSplitOrder("orden-normal")

        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(5000, vm.cartState.value.totalCents)
        assertTrue(vm.cartState.value.items.none { it.areaTicketLineId != null })
    }

    @Test
    fun `un vale no se deja para pagar despues`() = runTest {
        every { areaTicketRepository.session.current() } returns checkoutConVales("linea-a" to "10.00")
        val vm = createViewModel()
        vm.traerValesDeLaSesion()

        val resultado = vm.createPayLaterOrder("cliente-1")

        assertEquals(VALES_NO_PAGAR_DESPUES, resultado.exceptionOrNull()?.message)
    }

    @Test
    fun `D5 una venta de vales con abono no se deja para pagar despues`() = runTest {
        every { areaTicketRepository.session.current() } returns sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")

        val resultado = vm.createPayLaterOrder("cliente-1")

        assertEquals(com.avoqado.pos.payment.presentation.VALES_CON_ABONO, resultado.exceptionOrNull()?.message)
    }

    @Test
    fun `D5 con abono no se quita el saldo pendiente a mano (deslizar o anular)`() {
        every { areaTicketRepository.session.current() } returns sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00")
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")
        val saldo = vm.cartState.value.items.single()

        assertFalse(vm.quitarPorElCajero(saldo.id))
        assertEquals(7500, vm.cartState.value.totalCents)
        assertEquals(com.avoqado.pos.payment.presentation.VALES_CON_ABONO, vm.avisoDeVales.value)
    }

    // MARK: - Rediseño r7 (Codex r6, 21-oct): dueño del apartado, aviso «Cobrar saldo» y venta con abono cerrada

    private fun sesionReal(checkout: AreaTicketCheckout): com.avoqado.pos.areatickets.data.AreaTicketSession {
        val real = com.avoqado.pos.areatickets.data.AreaTicketSession(mockk(relaxed = true))
        real.update("venue-1", checkout)
        every { areaTicketRepository.session } returns real
        return real
    }

    private fun conAbonoEnElCarrito(): CartViewModel {
        sesionReal(sesionConOrden(status = "PARTIALLY_PAID", pagado = "75.00"))
        val vm = createViewModel()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 7500)
        vm.markPendingSplitOrder("orden-vales")
        return vm
    }

    // Regla 8 (Codex r6 #5): el cajero vació la venta pero el apartado no se pudo soltar (sin red). Sus vales no aparecen en
    // la venta siguiente: al restaurar se vuelve a intentar soltarlo.
    @Test
    fun `regla 8 los vales de una venta vaciada no aparecen en la siguiente y se intenta soltarlos`() = runTest {
        val real = sesionReal(checkoutConVales("linea-a" to "10.00"))
        coEvery { areaTicketRepository.restore(any()) } coAnswers { real.current() }
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        assertEquals(1, vm.cartState.value.items.size)

        vm.clearCart()
        vm.addProduct(burger)
        vm.restoreAreaTicketSession()
        runCurrent()

        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
        coVerify { areaTicketRepository.cancel(soloSi = "checkout-1") }
    }

    // MARK: - J5 (vales-ciclo r0): el vale leído para la venta A nunca cae en la venta B que el cajero empezó mientras tanto.

    private fun sesionVacia(): com.avoqado.pos.areatickets.data.AreaTicketSession {
        val real = com.avoqado.pos.areatickets.data.AreaTicketSession(mockk(relaxed = true))
        every { areaTicketRepository.session } returns real
        return real
    }

    @Test
    fun `J5 si la venta cambia mientras se resuelve el codigo, el vale no se pide ni entra a la venta nueva`() = runTest {
        val real = sesionVacia()
        val resuelto = kotlinx.coroutines.CompletableDeferred<AreaTicketScanData>()
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } coAnswers { resuelto.await() }
        coEvery { areaTicketRepository.addTicket("9470000015") } coAnswers {
            checkoutConVales("linea-a" to "10.00").also { real.update("venue-1", it) }
        }
        val vm = createViewModel()

        val consulta = async { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        vm.finalizarVenta()
        vm.addProduct(burger)
        resuelto.complete(AreaTicketScanData(type = "AREA_TICKET", code = "9470000015"))
        val result = consulta.await()

        assertEquals(VALES_VENTA_CAMBIO_AL_LEER, (result as? ScannedBarcodeResult.Error)?.message)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `J5 si cambia el negocio mientras se leen los ajustes, el vale no se pide`() = runTest {
        sesionVacia()
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        var negocio = "venue-1"
        every { secureStorage.venueId } answers { negocio }
        val ajustes = areaTicketRepository.settings()
        coEvery { areaTicketRepository.settings() } coAnswers { negocio = "venue-2"; ajustes }
        val vm = createViewModel()

        val result = vm.resolveScannedBarcode("9470000015")

        assertEquals(VALES_VENTA_CAMBIO_AL_LEER, (result as? ScannedBarcodeResult.Error)?.message)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    @Test
    fun `J5 si la venta cambia mientras se aparta el vale, no entra a la venta nueva y su apartado se suelta`() = runTest {
        val real = sesionVacia()
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val respuesta = kotlinx.coroutines.CompletableDeferred<AreaTicketCheckout>()
        coEvery { areaTicketRepository.addTicket("9470000015") } coAnswers { respuesta.await().also { real.update("venue-1", it) } }
        val vm = createViewModel()

        val consulta = async { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        vm.finalizarVenta()
        vm.addProduct(burger)
        respuesta.complete(checkoutConVales("linea-a" to "10.00").copy(status = "OPEN"))
        val result = consulta.await()
        runCurrent()

        assertEquals(VALES_VENTA_CAMBIO_AL_LEER, (result as? ScannedBarcodeResult.Error)?.message)
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
        coVerify(exactly = 1) { areaTicketRepository.cancel(soloSi = "checkout-1") }
    }

    // J7 (vales-ciclo r0): reimprimir o guardar el PDF de un vale anterior termina SÓLO la venta que lo emitió.
    @Test
    fun `J7 terminar la venta que emitio el vale no vacia otra venta del carrito`() {
        val vm = createViewModel()
        vm.addProduct(burger)
        val ventaQueEmitio = vm.cartState.value.identidadDeVenta
        vm.finalizarVenta()
        vm.addProduct(burger)

        vm.finalizarVentaSiEs(ventaQueEmitio)
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
        vm.finalizarVentaSiEs(null)
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })

        vm.finalizarVentaSiEs(vm.cartState.value.identidadDeVenta)
        assertTrue(vm.cartState.value.isEmpty)
    }

    // D2 (vales-ciclo): la llave de emisión de vales se suelta al VACIAR a mano, la salida que da el aviso del 409 («… o
    // vacíalo»). Terminar la venta por otro camino (guardar el carrito, pagar después) la conserva: si el vale cuya respuesta
    // se perdió vuelve con el mismo contenido, el servidor devuelve ESE vale en vez de crear otro.
    @Test
    fun `D2 vaciar a mano suelta la llave de emision y terminar la venta por otro camino no`() {
        val vm = createViewModel()
        vm.addProduct(burger)
        vm.finalizarVenta()
        verify(exactly = 0) { secureStorage.pendingAreaTicketIssueKey = any() }

        vm.addProduct(burger)
        vm.vaciar()

        assertTrue(vm.cartState.value.isEmpty)
        verify(exactly = 1) { secureStorage.pendingAreaTicketIssueKey = null }
    }

    // J12 (vales-ciclo, Codex r1): la restauración que tardó vuelve con el apartado de la venta B que el cajero empezó mientras
    // tanto. Lo compara con la venta A de cuando se pidió: no es de A, pero tampoco es para soltarlo, porque B sigue en pantalla.
    @Test
    fun `J12 una restauracion atrasada no suelta el apartado de la venta nueva`() = runTest {
        val real = sesionVacia()
        val respuesta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { areaTicketRepository.restore(any()) } coAnswers { respuesta.await(); real.current() }
        val vm = createViewModel()
        vm.addProduct(burger)

        vm.restoreAreaTicketSession()
        runCurrent()
        vm.finalizarVenta()
        vm.addProduct(burger)
        real.update("venue-1", checkoutConVales("linea-b" to "10.00").copy(status = "OPEN"))
        real.anotarVentaDuena(vm.cartState.value.identidadDeVenta)
        respuesta.complete(Unit)
        runCurrent()

        coVerify(exactly = 0) { areaTicketRepository.cancel(soloSi = any()) }
        assertEquals("checkout-1", real.current()?.id)
    }

    // J13 (vales-ciclo, Codex r2): la restauración que tardó instala un apartado SIN dueño (venía del disco, tras reiniciar)
    // cuando el carrito ya es otra venta. Queda de la venta de cuando se pidió, y la regla 8 lo suelta al siguiente escaneo:
    // sin dueño, la venta nueva lo adoptaba con sus vales viejos.
    @Test
    fun `J13 una restauracion atrasada sin dueno queda de la venta de cuando se pidio y no pasa a la nueva`() = runTest {
        val real = sesionVacia()
        val respuesta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { areaTicketRepository.restore(any()) } coAnswers {
            respuesta.await()
            real.update("venue-1", checkoutConVales("linea-a" to "10.00").copy(status = "OPEN"))
            real.current()
        }
        val vm = createViewModel()
        val ventaDeCuandoSePidio = vm.cartState.value.identidadDeVenta

        vm.restoreAreaTicketSession()
        runCurrent()
        vm.addProduct(burger)
        vm.finalizarVenta()
        vm.addProduct(burger)
        respuesta.complete(Unit)
        runCurrent()

        assertEquals(ventaDeCuandoSePidio, real.ventaDuena)
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    // F2 (vales-ciclo, Codex r3): el escaneo de A espera a que se suelte el apartado anterior; mientras, el cajero pasa a B. Al
    // volver, la cancelación vieja no prepara dueño ni llaves para A (pisaba la creación de B) y el escaneo lo dice.
    @Test
    fun `F2 una cancelacion atrasada no prepara la creacion de una venta que ya se fue`() = runTest {
        val real = sesionReal(checkoutConVales("linea-a" to "10.00"))
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val respuesta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { areaTicketRepository.cancel(soloSi = "checkout-1") } coAnswers { respuesta.await(); real.clearIf("checkout-1"); null }
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        vm.clearCart()
        val ventaA = vm.cartState.value.identidadDeVenta

        val consulta = async { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        vm.addProduct(burger)
        vm.finalizarVenta()
        respuesta.complete(Unit)
        val result = consulta.await()
        runCurrent()

        assertEquals(VALES_VENTA_CAMBIO_AL_LEER, (result as ScannedBarcodeResult.Error).message)
        assertTrue(real.ventaDuena != ventaA)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    // Regla 8: con un apartado de otra venta que no se pudo soltar, escanear no le cuelga el vale nuevo a esa venta.
    @Test
    fun `regla 8 escanear con el apartado de otra venta sin soltar se detiene y lo dice`() = runTest {
        sesionReal(checkoutConVales("linea-a" to "10.00"))
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        vm.clearCart()

        val result = vm.resolveScannedBarcode("9470000015")

        assertEquals(VALES_SOLTAR_ANTERIOR, (result as ScannedBarcodeResult.Error).message)
        coVerify(exactly = 0) { areaTicketRepository.addTicket(any()) }
    }

    // Regla 8 (Codex r6 #5): la respuesta de «quitar vale» de A llega cuando el carrito ya es otra venta: no le mete el otro
    // vale de A.
    @Test
    fun `regla 8 la respuesta tardia de quitar un vale no entra a la venta siguiente`() = runTest {
        val real = sesionReal(checkoutConVales("linea-a" to "10.00", "linea-b" to "20.00"))
        val respuesta = kotlinx.coroutines.CompletableDeferred<AreaTicketCheckout>()
        coEvery { areaTicketRepository.removeTicket("vale-linea-a") } coAnswers { respuesta.await() }
        val vm = createViewModel()
        vm.traerValesDeLaSesion()
        val valeA = vm.cartState.value.items.first { it.areaTicketLineId == "linea-a" }

        vm.removeItem(valeA.id)
        runCurrent()
        vm.clearCart()
        vm.addProduct(burger)
        respuesta.complete(real.current()!!.copy(tickets = real.current()!!.tickets.drop(1)))
        runCurrent()

        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    // Regla 10: la venta con abono salió del carrito por otra puerta (reimprimir y cambiar usuario llaman a `finalizarVenta`).
    // El aviso fijo la ofrece, y «Cobrar saldo» la vuelve a sembrar continuando su orden.
    @Test
    fun `regla 10 la venta con abono que sale del carrito queda en el aviso y Cobrar saldo la regresa`() = runTest {
        val vm = conAbonoEnElCarrito()
        coEvery { areaTicketRepository.refresh() } coAnswers { areaTicketRepository.session.current() }
        backgroundScope.launch { vm.saldoDeVales.collect {} }
        runCurrent()
        assertNull(vm.saldoDeVales.value)

        vm.finalizarVenta()
        runCurrent()
        assertEquals(SaldoDeVales("orden-vales", 7500), vm.saldoDeVales.value)

        vm.cobrarSaldoDeVales()
        runCurrent()
        assertEquals(listOf("Saldo pendiente" to 7500), vm.cartState.value.items.map { it.name to it.unitPrice })
        assertNull(vm.saldoDeVales.value)
        assertEquals("orden-vales", vm.resolvePendingSplitOrderForCharge())
    }

    // F3 (vales-ciclo, Codex r3): «Cobrar saldo» relee la venta; mientras tanto el cajero agrega algo y vacía. El carrito vuelve a
    // estar vacío, pero ya es OTRA venta: el saldo de la operación anterior no se siembra en ella.
    @Test
    fun `F3 Cobrar saldo no siembra el saldo en otra venta que quedo vacia mientras releia`() = runTest {
        val vm = conAbonoEnElCarrito()
        val respuesta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { areaTicketRepository.refresh() } coAnswers { respuesta.await(); areaTicketRepository.session.current() }
        vm.finalizarVenta()
        runCurrent()

        vm.cobrarSaldoDeVales()
        runCurrent()
        vm.addProduct(burger)
        vm.clearCart()
        respuesta.complete(Unit)
        runCurrent()

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `regla 10 Cobrar saldo con otra venta en el carrito pide terminarla y no la toca`() = runTest {
        val vm = conAbonoEnElCarrito()
        vm.finalizarVenta()
        vm.addProduct(burger)

        vm.cobrarSaldoDeVales()
        runCurrent()

        assertEquals(VALES_SALDO_CON_OTRA_VENTA, vm.avisoDeVales.value)
        assertEquals(listOf("Hamburguesa"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `regla 10 si el saldo ya se cobro en otro aparato el aviso lo dice y no siembra nada`() = runTest {
        val vm = conAbonoEnElCarrito()
        vm.finalizarVenta()
        coEvery { areaTicketRepository.refresh() } returns sesionConOrden(status = "PAID", pagado = "150.00")

        vm.cobrarSaldoDeVales()
        runCurrent()

        assertEquals(VALES_SALDO_YA_COBRADO, vm.avisoDeVales.value)
        assertTrue(vm.cartState.value.items.isEmpty())
    }

    // Regla 11 (Codex r6 #1 y #2): agregar algo a la venta con abono no rompe su vínculo (eso apagaba Vaciar y los demás
    // candados); el cobro no se abre hasta quitarlo.
    @Test
    fun `regla 11 agregar a la venta con abono detiene el cobro sin soltar la venta`() {
        val vm = conAbonoEnElCarrito()
        vm.addProduct(burger)

        assertTrue(vm.detenerCobroDeVentaCambiada())
        assertEquals(VALES_ABONO_CAMBIADA, vm.avisoDeVales.value)
        assertNull(vm.resolvePendingSplitOrderForCharge())
        assertTrue("el candado de Vaciar sigue", vm.vaciarBloqueadoPorAbono())

        val agregado = vm.cartState.value.items.first { it.name == "Hamburguesa" }
        assertTrue("lo agregado sí se quita", vm.quitarPorElCajero(agregado.id))
        assertFalse(vm.detenerCobroDeVentaCambiada())
        assertEquals("orden-vales", vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `regla 11 un descuento sobre el saldo de la venta con abono detiene el cobro`() {
        val vm = conAbonoEnElCarrito()
        vm.applyOrderDiscount(Discount(id = "d1", name = "10%", type = "PERCENTAGE", value = 10.0))

        assertTrue(vm.detenerCobroDeVentaCambiada())
    }

    @Test
    fun `regla 11 bajar de 1 el saldo pendiente tambien pasa por el candado de D5`() {
        val vm = conAbonoEnElCarrito()
        val saldo = vm.cartState.value.items.single()

        vm.decrementQuantity(saldo.id)

        assertEquals(7500, vm.cartState.value.totalCents)
    }
}
