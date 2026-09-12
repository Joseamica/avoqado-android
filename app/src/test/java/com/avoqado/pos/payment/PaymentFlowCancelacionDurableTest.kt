package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntentoDeCobroEnVuelo
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.RespuestaDeCancelacion
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * «Cancelar» en «Cobrar en terminal» — la cancelación DURABLE (diseño §C.4, 11-sep).
 *
 * Semántica única: se cancela el COBRO y, sólo cuando consta que no se cobró, se cancela la
 * ORDEN si la creó este mismo flujo. Nunca la cuenta de una mesa ni la de un split existente.
 * La intención se guarda en disco ANTES de tocar la red y la reproduce un coordinador que no
 * muere con la pantalla; el borrado de la orden es barrera y nunca sale antes del desenlace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowCancelacionDurableTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val tableSession = TableSession()

    private val almacen = AlmacenQuePuedeFallar()
    private val store = CancelacionesDeCobroEnTexto(almacen)
    private val transporte = CancelacionTransportFalso()
    private lateinit var coordinador: CancelacionDeCobroCoordinator
    private lateinit var viewModel: PaymentFlowViewModel

    private val cancelado = ChargeStatusProbe.Known("CANCELLED", inProgress = false, cancelDisposition = "ACCEPTED")

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1")))
        every { terminalPaymentService.unresolvedRequestId } returns null
        every { terminalPaymentService.intentoEnVuelo() } returns null
        every { terminalPaymentService.contextoDe(any()) } returns null
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        // Un `Result<Unit>` de un mock relajado no es `Result.success(Unit)`: su `fold` revienta
        // con ClassCastException al cerrar la venta.
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null

        // El coordinador REAL, con disco en memoria y transporte falso, corriendo en el despachador
        // de pruebas (Main). Sin `start()`: sin reloj de fondo que haga girar a `advanceUntilIdle`.
        coordinador = CancelacionDeCobroCoordinator(
            store = store,
            transporte = transporte,
            conectado = MutableStateFlow(true),
            servidorAlcanzable = MutableStateFlow(true),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            reloj = { 0L },
            esperasDeSondeo = listOf(1_000L, 2_000L),
        )

        val comandaDispatcher = ComandaDispatcher(
            printConfigRepository,
            ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
        )
        viewModel = PaymentFlowViewModel(
            tableSession = tableSession,
            syncOutbox = mockk(relaxed = true),
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = mockk<KDSOrderBus>(relaxed = true),
            printerService = printerService,
            secureStorage = secureStorage,
            comandaDispatcher = comandaDispatcher,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = coordinador,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    private fun cardCart() = CartState(
        items = listOf(CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Pizza", unitPrice = 1500)),
    )

    private fun stubOrdenCreada(id: String = "order-1") {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = id)))
    }

    /** El cobro sale y queda EN VUELO (la terminal todavía no contesta). */
    private fun cobroEnVuelo(resultadoTardio: TerminalPaymentResult = TerminalPaymentResult.Undetermined("x", "req-1")) {
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(600_000)
            resultadoTardio
        }
        every { terminalPaymentService.intentoEnVuelo() } returns IntentoDeCobroEnVuelo("req-1", "t1", "venue-1")
    }

    private fun cobrarConTarjeta(cart: CartState = cardCart()) {
        viewModel.startPaymentFlow(cart)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
    }

    // MARK: - La intención se guarda ANTES de la red, y el borrado es barrera

    @Test
    fun `P1 cancelar con el cobro en vuelo guarda la intencion antes de la red y no borra la orden en paralelo`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        val gate = CompletableDeferred<Unit>()
        var guardadaAntesDelCancel = false
        transporte.antesDeContestarCancel = {
            guardadaAntesDelCancel = store.leer("req-1") != null
            gate.await()
        }
        transporte.programarEstados(cancelado)

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        runCurrent()

        assertTrue("la intención se escribió en disco antes del POST del cancel", guardadaAntesDelCancel)
        verify { terminalPaymentService.marcarCancelacionPedida("req-1") }
        assertEquals(listOf("CANCEL:req-1"), transporte.llamadas)
        assertTrue(viewModel.state.value is PaymentFlowState.CancelandoCobro)
        // El ViewModel ya no borra la orden por su cuenta: eso lo hace el coordinador, DESPUÉS.
        coVerify(exactly = 0) { orderRepository.cancelOrder(any(), any(), any()) }

        gate.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        assertTrue("con la orden cancelada, el flujo se cierra", viewModel.debeSalir.value)
        val intencionCerrada = store.leer("req-1")
        assertNull(intencionCerrada)
    }

    @Test
    fun `P1 la intencion registra la venta con la orden que creo este flujo`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarCancels(RespuestaDeCancelacion(http = null)) // sin red: se queda en disco

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        runCurrent()

        val intencion = store.leer("req-1")!!
        assertEquals("req-1", intencion.requestId)
        assertEquals("t1", intencion.terminalId)
        assertEquals("venue-1", intencion.venueId)
        assertEquals("order-1", intencion.orderId)
        assertTrue("la orden la creó este flujo", intencion.borrarOrden)
        assertEquals("user-456", intencion.actorStaffId)
        assertEquals(FaseDeCancelacion.PEDIR_CANCEL, intencion.faseActual)
    }

    @Test
    fun `P1 si no se puede guardar la intencion no sale ninguna peticion y la pantalla lo dice`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        almacen.fallarAlEscribir = true

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        runCurrent()

        assertTrue("ninguna petición sin la intención en disco", transporte.llamadas.isEmpty())
        verify(exactly = 0) { terminalPaymentService.marcarCancelacionPedida(any()) }
        assertEquals(CancelacionDeCobro.NO_SE_PUDO_GUARDAR, viewModel.cancelFailure.value)
        assertTrue("el cobro sigue en la pantalla de siempre", viewModel.state.value is PaymentFlowState.SentToTerminal)
    }

    @Test
    fun `P1 un doble toque en Cancelar manda UN solo cancel`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        val gate = CompletableDeferred<Unit>()
        transporte.antesDeContestarCancel = { gate.await() }

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        viewModel.cancelarVenta()
        runCurrent()

        assertEquals(1, transporte.llamadasCancel.size)
        assertEquals(1, store.todas().size)
        gate.complete(Unit)
    }

    // MARK: - La carrera de «Procesando pago…»

    @Test
    fun `P1 cancelar mientras se crea la orden no envia el cobro y cancela esa orden`() = runTest {
        val orden = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any()) } coAnswers { orden.await() }

        cobrarConTarjeta()
        runCurrent()
        assertTrue(viewModel.state.value is PaymentFlowState.Processing)

        viewModel.cancelarVenta()
        assertTrue(viewModel.state.value is PaymentFlowState.CancelandoCobro)

        orden.complete(Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1"))))
        advanceUntilIdle()

        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf("DELETE:order-1"), transporte.llamadas)
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 cancelar mientras se crea la orden en efectivo no registra el pago`() = runTest {
        val orden = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any()) } coAnswers { orden.await() }

        viewModel.startPaymentFlow(cardCart())
        viewModel.confirmCashPreset(2_000)
        runCurrent()
        viewModel.cancelarVenta()

        orden.complete(Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1"))))
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf("DELETE:order-1"), transporte.llamadas)
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `cancelar mientras se crea una orden que al final no se creo solo sale`() = runTest {
        val orden = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any()) } coAnswers { orden.await() }

        cobrarConTarjeta()
        runCurrent()
        viewModel.cancelarVenta()
        orden.complete(Result.failure(OrderRepository.ServerException(400, "Producto inválido")))
        advanceUntilIdle()

        assertTrue(transporte.llamadas.isEmpty())
        assertTrue(viewModel.debeSalir.value)
    }

    // MARK: - «Cobro sin confirmar»: dos salidas distintas

    @Test
    fun `P1 desde Cobro sin confirmar, Salir queda pendiente y no borra nada`() = runTest {
        stubOrdenCreada()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Undetermined(CardChargeDecisionMessage, "req-1")

        cobrarConTarjeta()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PaymentFlowState.Undetermined)

        viewModel.salirDejandoPendiente()
        advanceUntilIdle()

        assertTrue(transporte.llamadas.isEmpty())
        assertTrue(store.todas().isEmpty())
        coVerify(exactly = 0) { orderRepository.cancelOrder(any(), any(), any()) }
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 desde Cobro sin confirmar, Cancelar la venta crea la intencion con el contexto de la llave`() = runTest {
        stubOrdenCreada()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Undetermined(CardChargeDecisionMessage, "req-1")
        every { terminalPaymentService.contextoDe("req-1") } returns
            ContextoDeCobro(requestId = "req-1", venueId = "venue-original", terminalId = "t1", orderId = "order-1")
        transporte.programarEstados(cancelado)

        cobrarConTarjeta()
        advanceUntilIdle()
        viewModel.cancelarVenta()
        advanceUntilIdle()

        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        assertEquals("el cancel viaja al venue del COBRO, no al actual", "venue-original", transporte.venues.first())
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `desde un cobro sin confirmar de una venta ANTERIOR no se ofrece cancelar esa orden`() = runTest {
        every { terminalPaymentService.unresolvedRequestId } returns "req-vieja"
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        assertTrue((viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)

        viewModel.cancelarVenta()
        advanceUntilIdle()

        assertTrue("una venta anterior no es de este flujo: no se toca su orden", transporte.llamadas.isEmpty())
        assertTrue(store.todas().isEmpty())
        assertTrue(viewModel.debeSalir.value)
    }

    // MARK: - Mesas y split: la cuenta existente nunca se borra

    @Test
    fun `P1 la cuenta de una mesa nunca se borra al cancelar el cobro`() = runTest {
        tableSession.start(
            TableSession.Active(
                tableId = "mesa-1",
                tableNumber = "4",
                areaName = null,
                orderId = "orden-mesa",
                orderNumber = "0042",
                version = 1,
                totalCents = 1500,
                mode = TableSession.Mode.PAYING,
            ),
        )
        cobroEnVuelo()
        transporte.programarEstados(cancelado)

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()

        val intencion = transporte.llamadas
        assertTrue("jamás DELETE de la cuenta de la mesa: $intencion", transporte.llamadasBorrar.isEmpty())
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any()) }
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 la orden de un split que ya existia nunca se borra`() = runTest {
        cobroEnVuelo()
        transporte.programarEstados(cancelado)

        viewModel.startPaymentFlow(cardCart(), resumeOrderId = "orden-split")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(transporte.llamadasBorrar.isEmpty())
        assertFalse(store.todas().any { it.borrarOrden })
    }

    // MARK: - Se cobró al final

    @Test
    fun `P1 si la terminal si cobro, la venta queda pagada con el aviso y la orden no se borra`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarEstados(ChargeStatusProbe.Known("COMPLETED", false, paymentId = "pay-1"))
        coEvery { terminalPaymentService.resolveOutcome("req-1") } returns
            TerminalPaymentResult.Success(paymentId = "pay-1", requestId = "req-1")

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()

        val estado = viewModel.state.value
        assertTrue("$estado", estado is PaymentFlowState.Success)
        estado as PaymentFlowState.Success
        assertEquals("pay-1", estado.paymentId)
        assertTrue("la pantalla dice que sí se cobró", estado.cobroTrasCancelar)
        assertTrue(transporte.llamadasBorrar.isEmpty())
        assertNotNull("la venta pagada consume el carrito", viewModel.consumeCompletion())
        assertFalse(viewModel.debeSalir.value)
    }

    // MARK: - Pendiente: ámbar, con salida

    @Test
    fun `P1 una cancelacion que no consta queda pendiente y se puede salir sin perderla`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarEstados(ChargeStatusProbe.Known("UNKNOWN", false))

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()

        val estado = viewModel.state.value
        assertEquals(PaymentFlowState.CancelacionPendiente(totalAmount = 1500, sinRed = false), estado)

        viewModel.salirDejandoPendiente()

        assertTrue(viewModel.debeSalir.value)
        assertEquals("la intención sigue viva para el coordinador", 1, store.todas().size)
        assertTrue(transporte.llamadasBorrar.isEmpty())
    }

    @Test
    fun `P1 sin red en la tablet la cancelacion lo dice`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarCancels(RespuestaDeCancelacion(http = null))

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        runCurrent()

        assertEquals(PaymentFlowState.CancelacionPendiente(totalAmount = 1500, sinRed = true), viewModel.state.value)
    }

    @Test
    fun `volver a consultar reintenta en el momento`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarEstados(ChargeStatusProbe.Known("UNKNOWN", false))

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()
        val antes = transporte.llamadasEstado.size

        transporte.programarEstados(cancelado)
        viewModel.volverAConsultarCancelacion()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(antes + 1, transporte.llamadasEstado.size)
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 un resultado tardio del cobro tras constar que no se cobro no vuelve a armar la llave`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo(resultadoTardio = TerminalPaymentResult.Undetermined("x", "req-1"))
        transporte.programarEstados(cancelado)

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceUntilIdle() // llega el resultado tardío del POST

        verify(exactly = 0) { terminalPaymentService.rearmUnresolvedCharge("req-1") }
        verify { terminalPaymentService.rearmUnresolvedCharge(null) }
    }

    // MARK: - Desde «Error»

    @Test
    fun `P1 tras un rechazo que prueba que el cobro no se creo, cancelar borra la orden sin esperar a la terminal`() = runTest {
        stubOrdenCreada()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error(CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA, requestId = "req-1", noSeCreo = true)

        cobrarConTarjeta()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PaymentFlowState.Error)

        viewModel.cancelarVenta()
        advanceUntilIdle()

        assertEquals(listOf("DELETE:order-1"), transporte.llamadas)
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 tras un rechazo sin evidencia, cancelar pasa por la terminal antes de borrar`() = runTest {
        stubOrdenCreada()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error("El cobro fue rechazado. No se cobró la tarjeta.", requestId = "req-1")
        every { terminalPaymentService.contextoDe("req-1") } returns
            ContextoDeCobro(requestId = "req-1", venueId = "venue-1", terminalId = "t1", orderId = "order-1")
        transporte.programarEstados(ChargeStatusProbe.Known("FAILED", false)) // servidor viejo: no alcanza

        cobrarConTarjeta()
        advanceUntilIdle()
        viewModel.cancelarVenta()
        advanceUntilIdle()

        assertEquals(listOf("CANCEL:req-1", "GET:req-1"), transporte.llamadas)
        assertTrue(viewModel.state.value is PaymentFlowState.CancelacionPendiente)
    }

    @Test
    fun `cancelar sin nada que cancelar sale de inmediato`() = runTest {
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()

        viewModel.cancelarVenta()

        assertTrue(viewModel.debeSalir.value)
        assertTrue(transporte.llamadas.isEmpty())
        assertTrue(store.todas().isEmpty())
    }

    @Test
    fun `una venta nueva empieza sin avisos ni salida pendientes de la anterior`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        almacen.fallarAlEscribir = true
        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        assertNotNull(viewModel.cancelFailure.value)

        viewModel.startPaymentFlow(cardCart())

        assertNull(viewModel.cancelFailure.value)
        assertFalse(viewModel.debeSalir.value)
    }

    private companion object {
        const val CardChargeDecisionMessage = "Estamos confirmando el cobro. No vuelvas a pasar la tarjeta."
    }
}
