package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.DESCUENTO_CON_APROBACION_LO_APLICA_UN_GERENTE
import com.avoqado.pos.payment.data.model.DESCUENTO_CON_APROBACION_PIDE_PIN
import com.avoqado.pos.payment.data.model.DESCUENTO_CON_APROBACION_SIN_RED
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D4 (hz:f34960c5:D4, founder «B»): en el POS de ANDROID, un % o fijo con «Requiere aprobación» lo aplica directo quien tiene
 * `tpv-orders:discount`; a quien no, se le pide el PIN de un gerente ANTES de crear la venta y de apartar el efectivo (el cobro es
 * `FAIL_FAST`, así que el token viaja a mano). Sin red no entra para nadie y lo dice. PIN apagado: lo dice y no sale nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DescuentoConAprobacionCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playera = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)
    private val gerencial = Discount(id = "d-gerente", name = "10% gerencial", value = 10.0, requiresApproval = true)
    private fun conGerencial() = CartState(items = listOf(playera), orderDiscount = gerencial)

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val roles = mockk<RoleManager>(relaxed = true)
    private val coordinador = mockk<ManagerOverrideCoordinator>(relaxed = true)
    private val conexion = mockk<ConnectivityMonitor>(relaxed = true)
    private val pinPrendido = MutableStateFlow(true)
    private val enLinea = MutableStateFlow(true)

    private lateinit var vm: PaymentFlowViewModel

    private fun creada() = Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 0.0)))

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        every { tpvSettingsRepository.managerPinOverrideEnabled } returns pinPrendido
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)))
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error("Terminal timeout")
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "mesero-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "mesero-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        every { roles.canCompOrders } returns false
        every { conexion.isConnected } returns enLinea
        every { conexion.isServerReachable } returns enLinea
        every { roles.canApproveDiscounts } returns false
        coEvery { coordinador.preauthorize("orders:comp") } returns true
        every { coordinador.tomarPreautorizado("orders:comp") } returns "token-cortesia"
        coEvery { coordinador.preauthorize("tpv-orders:discount", any(), any()) } returns true
        every { coordinador.tomarPreautorizado("tpv-orders:discount") } returns "token-aprobacion"
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns creada()

        vm = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = mockk(relaxed = true),
            kdsOrderBus = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                mockk(relaxed = true),
            ),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            connectivityMonitor = conexion,
            rolesDeSaldo = roles,
            managerOverrideCoordinator = coordinador,
        )
    }

    private fun cobrarEnEfectivo(carrito: CartState) {
        vm.startPaymentFlow(carrito)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(0)
    }

    private fun nadaSeAparto() = coVerify(exactly = 0) {
        cashPaymentRepository.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any(), lineasDelTicket = any())
    }

    private fun nadaSeEncolo() = coVerify(exactly = 0) {
        cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any(), lineasDelTicket = any())
    }

    private fun nadaSeCreo() = coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

    @Test
    fun `cajera sin permiso - PIN de un gerente antes de crear, la venta lleva su token y la etiqueta del descuento`() = runTest {
        val pedidos = mutableListOf<com.avoqado.pos.payment.data.model.CreateOrderRequest>()
        coEvery { orderRepository.createOrder(capture(pedidos), any(), any(), any(), any(), any(), any(), any(), any()) } returns creada()
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", null, "Descuento: 10% gerencial") }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-aprobacion") }
        // El importe no cambia: la etiqueta sólo hace que el SERVIDOR revise y guarde quién aprobó.
        assertEquals("d-gerente", pedidos.single().descuentoDeCatalogoId)
        assertEquals(1000, pedidos.single().discount)
    }

    @Test
    fun `gerente en sesion - no pide PIN y sale directo`() = runTest {
        every { roles.canApproveDiscounts } returns true
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), null) }
    }

    @Test
    fun `sin red no entra para nadie - ni para el gerente - y no crea, no aparta ni encola`() = runTest {
        every { roles.canApproveDiscounts } returns true
        enLinea.value = false
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        assertEquals(DESCUENTO_CON_APROBACION_SIN_RED, (vm.state.value as PaymentFlowState.Error).message)
        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        nadaSeCreo()
        nadaSeAparto()
        nadaSeEncolo()
    }

    @Test
    fun `Codex r2 #1 - la red cae al crear la venta - no se encola, lo dice (aunque ya hubo PIN o sea el gerente)`() = runTest {
        every { roles.canApproveDiscounts } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("se cayó la red"))
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        assertEquals(DESCUENTO_CON_APROBACION_SIN_RED, (vm.state.value as PaymentFlowState.Error).message)
        nadaSeEncolo()
    }

    @Test
    fun `Codex r3 - el SERVIDOR pidio la aprobacion (catalogo viejo) y luego la red cae - tampoco se encola`() = runTest {
        every { roles.canApproveDiscounts } returns true
        val sinMarca = gerencial.copy(id = "cmn3a6xr8000kn227eg8zeh50", requiresApproval = false)
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(403, "Permission 'tpv-orders:discount' required", requerido = "tpv-orders:discount")),
            Result.failure(java.io.IOException("se cayó la red")),
        )
        cobrarEnEfectivo(CartState(items = listOf(playera), orderDiscount = sinMarca))
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", any(), any()) }
        assertEquals(DESCUENTO_CON_APROBACION_SIN_RED, (vm.state.value as PaymentFlowState.Error).message)
        nadaSeEncolo()
    }

    @Test
    fun `PIN apagado - lo dice y no sale nada`() = runTest {
        pinPrendido.value = false
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        assertEquals(DESCUENTO_CON_APROBACION_LO_APLICA_UN_GERENTE, (vm.state.value as PaymentFlowState.Error).message)
        nadaSeCreo()
        nadaSeAparto()
    }

    @Test
    fun `cancelar el PIN - no sale nada ni se guarda nada`() = runTest {
        coEvery { coordinador.preauthorize("tpv-orders:discount", any(), any()) } returns false
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()

        nadaSeCreo()
        nadaSeAparto()
        nadaSeEncolo()
    }

    @Test
    fun `descuento por producto que pide aprobacion - tambien pide el PIN (sin etiqueta de cuenta)`() = runTest {
        val conDescuentoDeRenglon = playera.copy(
            itemDiscountId = "d-renglon", itemDiscountType = "FIXED_AMOUNT", itemDiscountValue = 20.0,
            itemDiscountName = "$20 gerencial", itemDiscountPideAprobacion = true,
        )
        val pedidos = mutableListOf<com.avoqado.pos.payment.data.model.CreateOrderRequest>()
        coEvery { orderRepository.createOrder(capture(pedidos), any(), any(), any(), any(), any(), any(), any(), any()) } returns creada()
        cobrarEnEfectivo(CartState(items = listOf(conDescuentoDeRenglon)))
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-aprobacion") }
        assertEquals(null, pedidos.single().descuentoDeCatalogoId)
    }

    @Test
    fun `cortesia de cuenta y aprobacion en la misma venta - dos PIN, uno tras otro, y los dos tokens viajan (Codex r1 #4)`() = runTest {
        val regalaYPideAprobacion = CartState(
            items = listOf(playera.copy(itemDiscountId = "d-renglon", itemDiscountType = "FIXED_AMOUNT", itemDiscountValue = 20.0,
                itemDiscountName = "$20 gerencial", itemDiscountPideAprobacion = true)),
            orderDiscount = Discount(id = "d", name = "Descuento", value = 100.0),
        )
        cobrarEnEfectivo(regalaYPideAprobacion)
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", any(), any()) }
        coVerify(exactly = 1) {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-cortesia,token-aprobacion")
        }
    }

    @Test
    fun `permisos guardados viejos - el 403 de aprobacion del servidor manda y el siguiente toque pide el PIN`() = runTest {
        every { roles.canApproveDiscounts } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(403, "Permission 'tpv-orders:discount' required", requerido = "tpv-orders:discount")),
            creada(),
        )
        cobrarEnEfectivo(conGerencial())
        advanceUntilIdle()
        assertEquals(DESCUENTO_CON_APROBACION_PIDE_PIN, (vm.state.value as PaymentFlowState.Error).message)
        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }

        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-aprobacion") }
    }

    @Test
    fun `regresion - un descuento sin aprobacion no pide nada ni manda etiqueta`() = runTest {
        val pedidos = mutableListOf<com.avoqado.pos.payment.data.model.CreateOrderRequest>()
        coEvery { orderRepository.createOrder(capture(pedidos), any(), any(), any(), any(), any(), any(), any(), any()) } returns creada()
        cobrarEnEfectivo(CartState(items = listOf(playera), orderDiscount = gerencial.copy(requiresApproval = false)))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), null) }
        assertEquals(null, pedidos.single().descuentoDeCatalogoId)
    }

    @Test
    fun `venta rapida de puros importes con un descuento que pide aprobacion - crea su orden y nunca va por FAST`() = runTest {
        val otroImporte = CartItem(id = "o1", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = 10000)
        cobrarEnEfectivo(CartState(items = listOf(otroImporte), orderDiscount = gerencial))
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", any(), any()) }
        coVerify(exactly = 0) {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        }
    }
}
