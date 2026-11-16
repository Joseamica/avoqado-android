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
import com.avoqado.pos.payment.data.model.CORTESIA_DE_PRODUCTO_LA_DA_UN_GERENTE
import com.avoqado.pos.payment.data.model.CORTESIA_DE_PRODUCTO_PIDE_PIN
import com.avoqado.pos.payment.data.model.CORTESIA_DE_PRODUCTO_SIN_RED
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentErrorSource
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
 * D15 (hz:87f4b879:D15): regalar un PRODUCTO (cortesía del renglón, o un descuento de catálogo tipo cortesía en él) también pide
 * `orders:comp` en el SERVIDOR. A quien no lo tiene, el POS le pide el PIN de un gerente ANTES de crear la venta y de apartar el
 * efectivo, como el descuento de cuenta que deja en $0 (D2). Antes el 403 llegaba después, con «No tienes permiso» y un texto falso,
 * y con un descuento que pide aprobación en la misma venta se tiraba el primer PIN (T10 del full-testing del D4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CortesiaDeProductoCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playera = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)
    private val helado = CartItem(
        id = "l2", type = CartItemType.ProductItem("p2"), name = "Helado de Vainilla", unitPrice = 5000,
        isCortesia = true, cortesiaReason = "Cortesía del administrador",
    )
    private fun conCortesia() = CartState(items = listOf(playera, helado))

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
        every { secureStorage.userId } returns "cajera-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "cajera-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        every { roles.canCompOrders } returns false
        every { roles.canApproveDiscounts } returns false
        every { conexion.isConnected } returns enLinea
        every { conexion.isServerReachable } returns enLinea
        coEvery { coordinador.preauthorize("orders:comp", any(), any()) } returns true
        every { coordinador.tomarPreautorizado("orders:comp") } returns "token-gerente"
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

    private fun error() = vm.state.value as PaymentFlowState.Error

    @Test
    fun `cajera sin permiso - el PIN se pide ANTES de crear, dice que producto regala, y la venta lleva su token`() = runTest {
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", null, "Cortesía: Helado de Vainilla") }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
    }

    @Test
    fun `T10 - cortesia de producto y un descuento que pide aprobacion - dos PIN seguidos, ninguno se tira, una sola venta`() = runTest {
        val gerencial = Discount(id = "d-gerente", name = "10% gerencial", value = 10.0, requiresApproval = true)
        cobrarEnEfectivo(conCortesia().copy(orderDiscount = gerencial))
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        coVerify(exactly = 1) { coordinador.preauthorize("tpv-orders:discount", any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente,token-aprobacion")
        }
    }

    @Test
    fun `descuento de catalogo tipo cortesia en un renglon - tambien pide el PIN`() = runTest {
        val conComp = playera.copy(itemDiscountId = "d-comp", itemDiscountType = "COMP", itemDiscountValue = 100.0, itemDiscountName = "Cortesía")
        cobrarEnEfectivo(CartState(items = listOf(conComp)))
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
    }

    @Test
    fun `con tarjeta - tambien pide el PIN antes y la venta lleva el token`() = runTest {
        vm.startPaymentFlow(conCortesia())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
    }

    @Test
    fun `quien tiene el permiso - no pide PIN y sale como hoy`() = runTest {
        every { roles.canCompOrders } returns true
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), null) }
    }

    @Test
    fun `quien tiene el permiso, si la red se cae a media venta - se encola como hoy`() = runTest {
        every { roles.canCompOrders } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException())
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        coVerify(exactly = 1) {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any(), lineasDelTicket = any())
        }
        assertTrue("${vm.state.value}", (vm.state.value as? PaymentFlowState.Success)?.isQueued == true)
    }

    @Test
    fun `cancelar el PIN - no sale nada ni se guarda nada`() = runTest {
        coEvery { coordinador.preauthorize("orders:comp", any(), any()) } returns false
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        nadaSeCreo()
        nadaSeAparto()
        nadaSeEncolo()
    }

    @Test
    fun `PIN apagado - aviso de producto que no es error, y no sale nada`() = runTest {
        pinPrendido.value = false
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        assertEquals(CORTESIA_DE_PRODUCTO_LA_DA_UN_GERENTE, error().message)
        assertEquals(PaymentErrorSource.SOLO_GERENTE, error().source)
        coVerify(exactly = 0) { coordinador.preauthorize(any(), any(), any()) }
        nadaSeCreo()
        nadaSeAparto()
    }

    @Test
    fun `sin red y sin permiso - lo dice como Sin conexion y no crea, no aparta ni encola`() = runTest {
        enLinea.value = false
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        assertEquals(CORTESIA_DE_PRODUCTO_SIN_RED, error().message)
        assertEquals(PaymentErrorSource.NETWORK, error().source)
        nadaSeCreo()
        nadaSeAparto()
        nadaSeEncolo()
    }

    @Test
    fun `permisos guardados viejos - el 403 del SERVIDOR dice lo del producto y el siguiente toque pide el PIN`() = runTest {
        every { roles.canCompOrders } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(403, "Permission 'orders:comp' required", requerido = "orders:comp")),
            creada(),
        )
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()
        assertEquals(CORTESIA_DE_PRODUCTO_PIDE_PIN, error().message)
        assertEquals(PaymentErrorSource.PIDE_PIN, error().source)
        nadaSeEncolo()

        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
    }
    @Test
    fun `Codex r2 #1 - permisos viejos, PIN y luego cae la red - no se encola, lo dice`() = runTest {
        every { roles.canCompOrders } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(403, "Permission 'orders:comp' required", requerido = "orders:comp")),
            Result.failure(java.net.SocketTimeoutException()),
        )
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        assertEquals(CORTESIA_DE_PRODUCTO_SIN_RED, error().message)
        assertEquals(PaymentErrorSource.NETWORK, error().source)
        nadaSeEncolo()
    }

    @Test
    fun `sin permiso, PIN y luego cae la red al crear la venta - no se encola (el SERVIDOR la rechazaria), lo dice`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException())
        cobrarEnEfectivo(conCortesia())
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp", any(), any()) }
        assertEquals(CORTESIA_DE_PRODUCTO_SIN_RED, error().message)
        nadaSeEncolo()
    }
}
