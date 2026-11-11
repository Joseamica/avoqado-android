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
import com.avoqado.pos.payment.data.model.CORTESIA_DE_CUENTA_LA_DA_UN_GERENTE
import com.avoqado.pos.payment.data.model.CORTESIA_DE_CUENTA_PIDE_PIN
import com.avoqado.pos.payment.data.model.CORTESIA_DE_CUENTA_SIN_RED
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
 * hz:e0b12f00:D2 (igual que la TPV, D1): en el POS, un «Descuento de la cuenta» que deja los productos en $0, de alguien sin
 * `orders:comp`, pide el PIN de un gerente ANTES de crear la venta y de apartar el efectivo. Con PIN apagado o sin red se dice por qué y
 * no se guarda nada. Con el token, la venta no se aparta ni se encola (vale 60 s y una vez).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DescuentoDeCuentaQueRegalaCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playera = CartItem(id = "l1", type = CartItemType.ProductItem("p1"), name = "Playera", unitPrice = 10000)
    private fun conDescuento(porcentaje: Double) =
        CartState(items = listOf(playera), orderDiscount = Discount(id = "d", name = "Descuento", value = porcentaje))

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
        coEvery { coordinador.preauthorize("orders:comp") } returns true
        every { coordinador.tomarPreautorizado("orders:comp") } returns "token-gerente"
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

    @Test
    fun `mesero sin permiso, 100 por ciento en efectivo - PIN antes y la venta lleva el token`() = runTest {
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
    }

    @Test
    fun `permisos guardados viejos - el 403 del servidor manda y el siguiente toque pide el PIN (Codex r2 #2)`() = runTest {
        every { roles.canCompOrders } returns true
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(OrderRepository.ServerException(403, "Permission 'orders:comp' required", requerido = "orders:comp"))
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()
        coVerify(exactly = 0) { coordinador.preauthorize(any()) }

        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
    }

    @Test
    fun `Ya pago de otra forma con el 403 - su reserva se queda (A5) y el reintento con PIN reusa la MISMA llave (Codex r3 y r4)`() = runTest {
        every { roles.canCompOrders } returns true
        val llaves = mutableListOf<String>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), capture(llaves), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(403, "Permission 'orders:comp' required", requerido = "orders:comp")),
            creada(),
        )
        vm.startPaymentFlow(conDescuento(100.0))
        val otraForma = com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(com.avoqado.pos.payment.domain.ManualPaymentMethod.entries.first())
        vm.confirmManualChoice(otraForma)
        advanceUntilIdle()
        assertEquals(CORTESIA_DE_CUENTA_PIDE_PIN, (vm.state.value as PaymentFlowState.Error).message)
        coVerify(exactly = 0) { cashPaymentRepository.descartarReservaRechazada(any()) }

        vm.confirmManualChoice(otraForma)
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
        assertEquals("la venta reintenta con la MISMA llave: un «Sí, se cobró» de la reserva vieja no puede duplicarla", 2, llaves.size)
        assertEquals(llaves[0], llaves[1])
    }

    @Test
    fun `venta rapida de puros importes libres - no pide PIN, va por el cobro rapido como hoy (Codex r2 #3)`() = runTest {
        val otroImporte = CartItem(id = "o1", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = 10000)
        cobrarEnEfectivo(CartState(items = listOf(otroImporte), orderDiscount = Discount(id = "d", name = "Descuento", value = 100.0)))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelar el PIN - no sale nada ni se guarda nada`() = runTest {
        coEvery { coordinador.preauthorize("orders:comp") } returns false
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        nadaSeAparto()
        nadaSeEncolo()
    }

    @Test
    fun `99 por ciento - no pide PIN y sigue como hoy`() = runTest {
        cobrarEnEfectivo(conDescuento(99.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), null) }
    }

    @Test
    fun `gerente en sesion - no pide PIN y sigue como hoy`() = runTest {
        every { roles.canCompOrders } returns true
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), null) }
    }

    @Test
    fun `PIN apagado - lo dice y no sale nada`() = runTest {
        pinPrendido.value = false
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        nadaSeAparto()
        assertEquals(CORTESIA_DE_CUENTA_LA_DA_UN_GERENTE, (vm.state.value as PaymentFlowState.Error).message)
    }

    @Test
    fun `sin red - lo dice antes de guardar y no aparta ni encola nada`() = runTest {
        enLinea.value = false
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        nadaSeAparto()
        nadaSeEncolo()
        assertEquals(CORTESIA_DE_CUENTA_SIN_RED, (vm.state.value as PaymentFlowState.Error).message)
    }

    @Test
    fun `con el token, si la red se cae a media venta - se guarda como cualquier efectivo, SIN el token (Codex r1 #2)`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException())
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()

        // Lo que la cajera ya hizo se guarda ANTES de la red (el apartado) y, al fallar, va a la cola: el token nunca va a disco.
        coVerify(exactly = 1) {
            cashPaymentRepository.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any(), lineasDelTicket = any())
        }
        coVerify(exactly = 1) {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any(), lineasDelTicket = any())
        }
        assertTrue("${vm.state.value}", (vm.state.value as? PaymentFlowState.Success)?.isQueued == true)
    }

    @Test
    fun `el servidor dice que regala y la app no lo esperaba - se borra el apartado y el siguiente toque pide el PIN (Codex r1 #1)`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(OrderRepository.ServerException(403, "Permission 'orders:comp' required", requerido = "orders:comp"))
        cobrarEnEfectivo(conDescuento(99.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { coordinador.preauthorize(any()) }
        coVerify(exactly = 1) { cashPaymentRepository.descartarReservaRechazada(any()) }
        nadaSeEncolo()
        assertEquals(CORTESIA_DE_CUENTA_PIDE_PIN, (vm.state.value as PaymentFlowState.Error).message)

        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
    }

    @Test
    fun `el token es de UN intento - el siguiente cobro vuelve a pedir el PIN`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(OrderRepository.ServerException(400, "Otro rechazo"))
        cobrarEnEfectivo(conDescuento(100.0))
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { coordinador.preauthorize("orders:comp") }
    }

    @Test
    fun `con tarjeta - tambien pide el PIN antes y la venta lleva el token`() = runTest {
        vm.startPaymentFlow(conDescuento(100.0))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinador.preauthorize("orders:comp") }
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), "token-gerente") }
        verify(exactly = 1) { coordinador.tomarPreautorizado("orders:comp") }
    }
}
