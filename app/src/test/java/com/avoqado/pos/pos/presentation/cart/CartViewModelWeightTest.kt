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
        coEvery { areaTicketRepository.restore() } returns sesion
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
        coEvery { areaTicketRepository.restore() } returns vieja
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
        coEvery { areaTicketRepository.restore() } returns sesion
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
        coEvery { areaTicketRepository.restore() } returns sesion
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
        coEvery { areaTicketRepository.restore() } coAnswers { sesionReal.current() }
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
}
