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
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
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
    private val syncOutbox = mockk<com.avoqado.pos.core.data.sync.SyncOutbox>(relaxed = true)

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
        // Sin cobro pendiente propio ni de otras ventas, salvo donde la prueba diga (25-sep: la tarjeta pregunta por los dos).
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns emptyList()
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
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null

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
            syncOutbox = syncOutbox,
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
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
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
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { orden.await() }

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

    /**
     * 🔴 Actualizada a D2 v6/v7 (punto 5, 24-oct). Antes: «Cancelar» mientras se creaba la orden de un cobro en EFECTIVO
     * cancelaba esa orden y no registraba el pago. Ahora, mientras el efectivo viaja (crear la orden o el POST del pago) no hay
     * «Cancelar»: la pantalla no lo ofrece y el ViewModel lo ignora, como iOS en `.processing`. Borrar el apartado no detiene un
     * cobro que puede llegar al servidor; al terminar, la cajera decide con el desenlace a la vista.
     */
    @Test
    fun `P1 cancelar mientras se crea la orden en efectivo se ignora y el cobro sigue`() = runTest {
        val orden = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { orden.await() }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))

        viewModel.startPaymentFlow(cardCart())
        viewModel.confirmCashPreset(2_000)
        runCurrent()
        assertTrue((viewModel.state.value as PaymentFlowState.Processing).efectivoEnVuelo)
        viewModel.cancelarVenta()
        assertTrue("se ignora: sigue procesando", viewModel.state.value is PaymentFlowState.Processing)

        orden.complete(Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1"))))
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.recordCashPayment("order-1", any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) }
        assertTrue(transporte.llamadas.isEmpty())
        assertFalse(viewModel.debeSalir.value)
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `cancelar mientras se crea una orden que al final no se creo solo sale`() = runTest {
        val orden = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { orden.await() }

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
        // 🔴 25-sep: el pendiente de otra venta ya no frena la tarjeta; el cajero lo abre con «Revisar» desde el aviso.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(
            ContextoDeCobro(requestId = "req-vieja", venueId = "venue-1", terminalId = "t1", orderId = "orden-vieja",
                amountCents = 1500, tipCents = 0, desdeMillis = System.currentTimeMillis()),
        )
        coEvery { terminalPaymentService.resolveOutcome("req-vieja") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "req-vieja")
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
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
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
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

    @Test
    fun `D9 si el cobro que si paso se confirma con otra venta en pantalla, no paga la venta nueva`() = runTest {
        stubOrdenCreada()
        cobroEnVuelo()
        transporte.programarEstados(ChargeStatusProbe.Known("COMPLETED", false, paymentId = "pay-1"))
        // La consulta del desenlace tarda: mientras tanto el cajero sale y abre la venta B.
        coEvery { terminalPaymentService.resolveOutcome("req-1") } coAnswers {
            kotlinx.coroutines.delay(10_000)
            TerminalPaymentResult.Success(paymentId = "pay-1", requestId = "req-1")
        }
        every { terminalPaymentService.aplicarDesenlaceTardio(any(), any(), any()) } returns true

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()
        viewModel.startPaymentFlow(
            CartState(items = listOf(CartItem(id = "line-b", type = CartItemType.ProductItem("prod-b"), name = "Café", unitPrice = 900))),
        )
        advanceTimeBy(20_000)
        runCurrent()

        assertFalse("la venta B no queda pagada con el dinero de A", viewModel.state.value is PaymentFlowState.Success)
        assertNull("la venta B no consume el carrito", viewModel.consumeCompletion())
        io.mockk.verify { terminalPaymentService.aplicarDesenlaceTardio("req-1", any(), any()) }
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

        // El rezagado decide sobre SU cobro, y de él ya consta que no se cobró: la única llamada SUELTA la entrada.
        verify(exactly = 1) { terminalPaymentService.aplicarDesenlaceTardio(any(), any(), any()) }
        verify {
            terminalPaymentService.aplicarDesenlaceTardio(
                "req-1", CardChargeOutcome.NotCharged(CardChargeDecision.CANCEL_ALREADY_RESOLVED_MESSAGE), aunSiFueDeclarado = false,
            )
        }
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

    // MARK: - C2 (22-oct): «Cancelación pendiente» con «Ya revisé la terminal: no se cobró»
    //
    // Amaena, 20-oct: la Nexgo se trabó 35 min con un cobro que la tablet había pedido cancelar. La pantalla «Cancelación
    // pendiente» sólo ofrecía «Volver a consultar» y «Salir»: el cajero no tenía cómo decir lo que veía en la terminal.

    private val noCobrado = ChargeStatusProbe.Known("FAILED", inProgress = false, outcome = "NOT_CHARGED")

    /**
     * Deja al ViewModel en «Cancelación pendiente» del cobro `req-1`, cuya orden `order-1` creó este flujo.
     *
     * @param venueDelCobro el venue con el que salió el cobro (el que guarda la intención). La sesión siempre es `venue-1`.
     * @param venueDelContexto el venue que dice el contexto durable del cobro.
     */
    private fun kotlinx.coroutines.test.TestScope.enCancelacionPendiente(
        venueDelCobro: String = "venue-1",
        venueDelContexto: String = "venue-1",
    ) {
        stubOrdenCreada()
        cobroEnVuelo()
        every { terminalPaymentService.intentoEnVuelo() } returns IntentoDeCobroEnVuelo("req-1", "t1", venueDelCobro)
        every { terminalPaymentService.contextoDe("req-1") } returns ContextoDeCobro(
            requestId = "req-1", venueId = venueDelContexto, terminalId = "t1", orderId = "order-1", amountCents = 1500, tipCents = 0,
        )
        transporte.programarEstados(ChargeStatusProbe.Known("UNKNOWN", false))

        cobrarConTarjeta()
        advanceTimeBy(1_000)
        viewModel.cancelarVenta()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("montaje: ${viewModel.state.value}", viewModel.state.value is PaymentFlowState.CancelacionPendiente)
        assertNotNull("montaje: la intención sigue viva", store.leer("req-1"))
        assertTrue("montaje: la orden no se ha tocado", transporte.llamadasBorrar.isEmpty())
    }

    @Test
    fun `P1 desde Cancelacion pendiente declarar no cobrado cancela la orden y cierra el flujo`() = runTest {
        // Fix 1, m3: tres venues distintos (sesión `venue-1`, cobro y contexto), para fijar que manda el de la INTENCIÓN.
        enCancelacionPendiente(venueDelCobro = "venue-del-cobro", venueDelContexto = "venue-del-contexto")
        assertEquals("montaje: la intención guarda el venue del cobro", "venue-del-cobro", store.leer("req-1")!!.venueId)
        val antes = transporte.llamadas.toList()
        // El botón se ofrece sobre ESE cobro: su id, el venue de la intención y el importe de su contexto durable.
        assertEquals(
            com.avoqado.pos.payment.data.model.ObjetivoDeLaDeclaracion(
                requestId = "req-1", venueId = "venue-del-cobro", montoCentavos = 1500,
            ),
            viewModel.objetivoDeLaDeclaracionDeCancelacion(),
        )
        coEvery { terminalPaymentService.declararNoCobrado(any(), any()) } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Liberada
        // Tras la declaración, el servidor ya dice que no se cobró.
        transporte.programarEstados(noCobrado)

        viewModel.declararDesdeCancelacion()
        advanceTimeBy(5_000)
        runCurrent()

        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado(any(), any()) }
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado("req-1", "venue-del-cobro") }
        // 🔴 El ViewModel NO borra la orden por su cuenta: el coordinador vuelve a preguntar y, sólo porque CONSTA que no
        // se cobró, cancela la orden. Es la misma barrera de siempre (cancel → desenlace → borrado).
        assertEquals(antes + listOf("GET:req-1", "DELETE:order-1"), transporte.llamadas)
        coVerify(exactly = 0) { orderRepository.cancelOrder(any(), any(), any()) }
        assertNull("la intención se cerró", store.leer("req-1"))
        assertTrue("con la orden cancelada, el flujo se cierra", viewModel.debeSalir.value)
    }

    @Test
    fun `P1 una intencion solo de orden (requestId null) no ofrece declarar`() = runTest {
        // Un rechazo correlacionado prueba que el cobro no se creó: la intención es SÓLO de la orden. Hoy una intención así
        // no llega a «Cancelación pendiente» (el coordinador la da por «no se cobró, falta la orden»); la guarda es para
        // que nunca se declare sobre un cobro que no existe. Se mira con el borrado en vuelo.
        stubOrdenCreada()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error(CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA, requestId = "req-1", noSeCreo = true)
        val borrado = CompletableDeferred<Unit>()
        transporte.antesDeContestarBorrar = { borrado.await() }

        cobrarConTarjeta()
        advanceUntilIdle()
        viewModel.cancelarVenta()
        runCurrent()

        val intencion = store.leer("orden:order-1")
        assertNotNull("montaje: la intención observada es la de la orden", intencion)
        assertNull("montaje: no hubo cobro", intencion!!.requestId)
        assertEquals(listOf("DELETE:order-1"), transporte.llamadas)

        assertNull("sin cobro no hay nada que declarar: el botón no se ofrece", viewModel.objetivoDeLaDeclaracionDeCancelacion())
        viewModel.declararDesdeCancelacion()
        runCurrent()
        coVerify(exactly = 0) { terminalPaymentService.declararNoCobrado(any(), any()) }

        borrado.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P2 un rechazo de la declaracion se queda en Cancelacion pendiente con el motivo`() = runTest {
        enCancelacionPendiente()
        val antes = transporte.llamadas.toList()
        val delServidor = "Este cobro sí tiene señales de haber pasado. No lo declares: consulta su resultado."
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(mensaje = delServidor, code = "POSITIVE_EVIDENCE_EXISTS")

        viewModel.declararDesdeCancelacion()
        advanceTimeBy(5_000)
        runCurrent()

        val estado = viewModel.state.value
        assertTrue("$estado", estado is PaymentFlowState.CancelacionPendiente)
        estado as PaymentFlowState.CancelacionPendiente
        // 🔴 El texto lo escribe el SERVIDOR (cada código tiene el suyo) y se ve en la MISMA pantalla.
        assertEquals(delServidor, estado.avisoDeDeclaracion)
        assertFalse("se puede volver a consultar", estado.checking)
        // Nada se resolvió: ni consulta nueva del coordinador ni borrado, y la intención sigue viva.
        assertEquals(antes, transporte.llamadas)
        assertNotNull(store.leer("req-1"))
        assertFalse(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 sin red la declaracion desde Cancelacion pendiente no invita al efectivo y no se encola`() = runTest {
        enCancelacionPendiente()
        var declaraciones = 0
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } coAnswers {
            declaraciones++
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.SinConexion
        }

        viewModel.declararDesdeCancelacion()
        advanceTimeBy(5_000)
        runCurrent()

        val estado = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        // La duda es de ESTA venta (su propio cobro): nunca «puedes cobrar en efectivo».
        assertEquals(CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA, estado.avisoDeDeclaracion)
        assertFalse(estado.checking)
        assertNotNull("la intención sigue viva", store.leer("req-1"))
        assertTrue(transporte.llamadasBorrar.isEmpty())
        // 🔴 Online-only A PROPÓSITO: se intentó una vez y no queda en ninguna cola.
        assertEquals(1, declaraciones)
        coVerify(exactly = 0) {
            syncOutbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // MARK: - C2, fix round 1 (revisión de tarea)

    @Test
    fun `P1 con el coordinador consultando la declaracion sale una vez y su respuesta se aplica`() = runTest {
        // I1: el cajero abre el diálogo; mientras lo lee, el coordinador corre solo (su reloj o al reconectar) y la
        // pantalla pasa a «consultando». Su «Sí, no se cobró» NO puede perderse por eso.
        enCancelacionPendiente()
        val consulta = CompletableDeferred<Unit>()
        transporte.antesDeContestarEstado = { consulta.await() }
        coordinador.procesarAhora("req-1")
        runCurrent()
        assertTrue(
            "montaje: el coordinador está consultando",
            (viewModel.state.value as PaymentFlowState.CancelacionPendiente).checking,
        )
        val delServidor = "Este cobro sí tiene señales de haber pasado. No lo declares: consulta su resultado."
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(mensaje = delServidor, code = "POSITIVE_EVIDENCE_EXISTS")

        viewModel.declararDesdeCancelacion()
        runCurrent()

        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado("req-1", "venue-1") }
        assertEquals(delServidor, (viewModel.state.value as PaymentFlowState.CancelacionPendiente).avisoDeDeclaracion)

        // La corrida del coordinador termina sin desenlace: el motivo sigue a la vista (es la respuesta a lo que hizo el
        // cajero, y sólo él la reemplaza con otra consulta o declaración).
        consulta.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        val fin = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertFalse(fin.checking)
        assertFalse(fin.declarando)
        assertEquals(delServidor, fin.avisoDeDeclaracion)
        assertNotNull(store.leer("req-1"))
    }

    @Test
    fun `P1 con la declaracion en vuelo un pendiente del coordinador no deja declarar otra vez`() = runTest {
        // Carrera 2 del reporte: el coordinador publica «pendiente» mientras la declaración va en vuelo. El botón sigue
        // apagado y un segundo toque no manda otra declaración.
        enCancelacionPendiente()
        val respuesta = CompletableDeferred<com.avoqado.pos.payment.data.ResultadoDeDeclaracion>()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } coAnswers { respuesta.await() }

        viewModel.declararDesdeCancelacion()
        runCurrent()
        coordinador.procesarAhora("req-1") // corre solo y vuelve a publicar «pendiente»
        advanceTimeBy(5_000)
        runCurrent()

        val enVuelo = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertTrue("la declaración sigue en vuelo: el botón sigue apagado", enVuelo.declarando)
        viewModel.declararDesdeCancelacion()
        runCurrent()
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado(any(), any()) }

        respuesta.complete(com.avoqado.pos.payment.data.ResultadoDeDeclaracion.SinConexion)
        runCurrent()
        val fin = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertFalse(fin.declarando)
        assertEquals(CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA, fin.avisoDeDeclaracion)
    }

    // F1 (QA en la N86, 3-nov): el aviso «Sin conexión…» no se queda cuando el coordinador ya habló con el servidor.
    @Test
    fun `F1 el aviso de sin conexion se va cuando el coordinador ya hablo con el servidor`() = runTest {
        enCancelacionPendiente()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.SinConexion
        viewModel.declararDesdeCancelacion()
        runCurrent()
        assertEquals(
            "montaje",
            CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA,
            (viewModel.state.value as PaymentFlowState.CancelacionPendiente).avisoDeDeclaracion,
        )

        // Sigue sin red: el aviso sigue, es verdad.
        transporte.programarEstados(ChargeStatusProbe.Unreachable, ChargeStatusProbe.Known("UNKNOWN", false))
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        val sinRed = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertTrue("montaje: el coordinador tampoco tuvo red", sinRed.sinRed)
        assertEquals(CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA, sinRed.avisoDeDeclaracion)

        // Volvió: el coordinador ya habló con el servidor.
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        val fin = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertFalse("montaje: el coordinador ya tuvo red", fin.sinRed)
        assertNull(fin.avisoDeDeclaracion)
    }

    // D6 (QA en la N86, 3-nov): la terminal ya se soltó y la pantalla seguía diciendo que no confirma y que «se libera sola».
    @Test
    fun `D6 con la terminal ya libre la pantalla lo dice, se va el aviso de que se libera sola y la orden no se toca`() = runTest {
        enCancelacionPendiente()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(mensaje = "Todavía no.", code = "EXECUTION_STILL_ACTIVE")
        viewModel.declararDesdeCancelacion()
        runCurrent()
        assertEquals(
            "montaje",
            CancelacionDeCobro.TERMINAL_REPORTA_SIN_TERMINAR,
            (viewModel.state.value as PaymentFlowState.CancelacionPendiente).avisoDeDeclaracion,
        )

        // Aún no se suelta: el aviso sigue, es verdad.
        transporte.programarEstados(ChargeStatusProbe.Known("UNKNOWN", false))
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        val antes = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertFalse(antes.terminalLibre)
        assertEquals(CancelacionDeCobro.TERMINAL_REPORTA_SIN_TERMINAR, antes.avisoDeDeclaracion)

        transporte.programarEstados(
            ChargeStatusProbe.Known("TIMED_OUT", false, failureCode = "AUTO_RELEASED", outcome = "UNRESOLVED"),
        )
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        val fin = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertTrue(fin.terminalLibre)
        assertNull(fin.avisoDeDeclaracion)
        assertTrue("libera el aparato, no la venta", transporte.llamadasBorrar.isEmpty())
        assertFalse(viewModel.debeSalir.value)
    }

    @Test
    fun `D6 sin red manda el texto de sin red, aunque la ultima consulta dijera terminal libre`() = runTest {
        enCancelacionPendiente()
        transporte.programarEstados(
            ChargeStatusProbe.Known("TIMED_OUT", false, failureCode = "AUTO_RELEASED", outcome = "UNRESOLVED"),
        )
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("montaje", (viewModel.state.value as PaymentFlowState.CancelacionPendiente).terminalLibre)

        transporte.programarEstados(ChargeStatusProbe.Unreachable)
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        val fin = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertTrue(fin.sinRed)
        assertFalse(fin.terminalLibre)
    }

    @Test
    fun `P2 si una corrida vieja del coordinador contesta pendiente tras liberar se vuelve a preguntar y la orden se cancela`() = runTest {
        // m1: la declaración vuelve «liberada» mientras una corrida del coordinador tiene el candado (su `procesarAhora` se
        // pierde), y esa corrida contesta con lo que vio ANTES de la liberación.
        enCancelacionPendiente()
        val antes = transporte.llamadas.toList()
        val respuesta = CompletableDeferred<com.avoqado.pos.payment.data.ResultadoDeDeclaracion>()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } coAnswers { respuesta.await() }
        val consulta = CompletableDeferred<Unit>()
        transporte.antesDeContestarEstado = { consulta.await() }
        transporte.programarEstados(ChargeStatusProbe.Known("UNKNOWN", false), noCobrado)

        viewModel.declararDesdeCancelacion()
        runCurrent()
        coordinador.procesarAhora("req-1") // la corrida vieja: su GET queda retenido
        runCurrent()
        respuesta.complete(com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Liberada)
        runCurrent()
        consulta.complete(Unit) // contesta lo de antes: «no consta»
        advanceTimeBy(5_000)
        runCurrent()

        // 🔴 Se vuelve a preguntar UNA vez; recién con NOT_CHARGED se cancela la orden.
        assertEquals(antes + listOf("GET:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        assertNull(store.leer("req-1"))
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P1 tras liberar la pantalla sigue en Cancelacion pendiente hasta que el coordinador cierre`() = runTest {
        // m4 (a) y m2: el ViewModel no cierra nada por su cuenta, y el motivo de un rechazo anterior no se queda al lado
        // de «Consultando…».
        enCancelacionPendiente()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(mensaje = "Todavía no.", code = "EXECUTION_STILL_ACTIVE")
        viewModel.declararDesdeCancelacion()
        runCurrent()
        // C4 (P5): con `EXECUTION_STILL_ACTIVE` la pantalla dice el texto honesto, también desde «Cancelación pendiente».
        assertEquals("montaje: un primer intento rechazado", CancelacionDeCobro.TERMINAL_REPORTA_SIN_TERMINAR,
            (viewModel.state.value as PaymentFlowState.CancelacionPendiente).avisoDeDeclaracion)

        val consulta = CompletableDeferred<Unit>()
        transporte.antesDeContestarEstado = { consulta.await() }
        transporte.programarEstados(noCobrado)
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Liberada
        viewModel.declararDesdeCancelacion()
        runCurrent()

        val intermedio = viewModel.state.value
        assertTrue("sigue en la cancelación: $intermedio", intermedio is PaymentFlowState.CancelacionPendiente)
        intermedio as PaymentFlowState.CancelacionPendiente
        assertTrue("consultando mientras el coordinador pregunta", intermedio.checking)
        assertNull("el rechazo viejo ya no se muestra", intermedio.avisoDeDeclaracion)
        assertFalse(viewModel.debeSalir.value)
        assertTrue("la orden todavía no se toca", transporte.llamadasBorrar.isEmpty())

        consulta.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("DELETE:order-1"), transporte.llamadasBorrar)
        assertTrue(viewModel.debeSalir.value)
    }

    @Test
    fun `P2 una sesion vencida al declarar desde Cancelacion pendiente pide confirmar otra vez`() = runTest {
        // m4 (b): la red funcionó; decir «sin conexión» sería falso. La declaración no se reenvió sola.
        enCancelacionPendiente()
        val antes = transporte.llamadas.toList()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.SesionRenovada

        viewModel.declararDesdeCancelacion()
        runCurrent()

        val estado = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertEquals(CancelacionDeCobro.DECLARACION_SESION_RENOVADA, estado.avisoDeDeclaracion)
        assertFalse(estado.declarando)
        assertEquals(antes, transporte.llamadas)
        assertNotNull(store.leer("req-1"))
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado(any(), any()) }
    }

    @Test
    fun `P2 una respuesta de la declaracion que llega con la cancelacion ya cerrada no repinta la pantalla`() = runTest {
        // m4 (c): mientras la declaración va en vuelo, la terminal confirma sola y el coordinador cierra la cancelación.
        // La generación no cambió: lo que impide repintar es que la pantalla ya no observa ESA cancelación.
        enCancelacionPendiente()
        val respuesta = CompletableDeferred<com.avoqado.pos.payment.data.ResultadoDeDeclaracion>()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } coAnswers { respuesta.await() }
        viewModel.declararDesdeCancelacion()
        runCurrent()

        transporte.programarEstados(cancelado)
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("montaje: el coordinador cerró y el flujo sale", viewModel.debeSalir.value)
        val tras = viewModel.state.value
        val llamadas = transporte.llamadas.toList()

        respuesta.complete(com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(mensaje = "tarde", code = null))
        runCurrent()

        assertEquals("la respuesta tardía no repinta nada", tras, viewModel.state.value)
        assertFalse(viewModel.state.value is PaymentFlowState.CancelacionPendiente)
        assertEquals(llamadas, transporte.llamadas)
    }

    @Test
    fun `P2 una respuesta de la declaracion que llega despues de Salir no repinta la pantalla`() = runTest {
        // m4 (c), el caso que nombró la revisión: el cajero sale con la declaración en vuelo.
        enCancelacionPendiente()
        val respuesta = CompletableDeferred<com.avoqado.pos.payment.data.ResultadoDeDeclaracion>()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1") } coAnswers { respuesta.await() }
        viewModel.declararDesdeCancelacion()
        runCurrent()

        viewModel.salirDejandoPendiente()
        val tras = viewModel.state.value
        val llamadas = transporte.llamadas.toList()
        respuesta.complete(com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Liberada)
        runCurrent()

        assertEquals(tras, viewModel.state.value)
        assertTrue(viewModel.debeSalir.value)
        assertEquals("la intención sigue para el coordinador", 1, store.todas().size)
        assertEquals(llamadas, transporte.llamadas)
    }

    // MARK: - C4 (22-oct, P4): el MESERO declara desde «Cancelación pendiente» con el PIN de un gerente
    //
    // El PIN entra en el camino ÚNICO de la declaración (`declarar`), así que vale igual aquí que en «Cobro sin confirmar».
    // La bandera propia (`declarando`) se respeta mientras se pide el PIN y se repite la declaración.

    private val pideGerente = com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Rechazada(
        mensaje = "Pide a un gerente que ingrese su PIN para confirmar que este cobro no pasó.",
        code = "SUPERVISOR_AUTHORIZATION_REQUIRED",
    )

    @Test
    fun `P1 mesero en Cancelacion pendiente - PIN, declara y el coordinador cancela la orden`() = runTest {
        enCancelacionPendiente()
        val antes = transporte.llamadas.toList()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1", null) } returns pideGerente
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1", "1234") } returns
            com.avoqado.pos.payment.data.ResultadoDeDeclaracion.Liberada
        // Tras la declaración, el servidor ya dice que no se cobró.
        transporte.programarEstados(noCobrado)

        viewModel.declararDesdeCancelacion()
        runCurrent()

        val peticion = viewModel.pidiendoPinDeGerente.value
        assertNotNull("el servidor pidió gerente: se abre el teclado", peticion)
        assertEquals(com.avoqado.pos.payment.data.model.OrigenDeLaDeclaracion.CANCELACION_PENDIENTE, peticion!!.origen)
        assertTrue(
            "la bandera propia se respeta mientras se pide el PIN",
            (viewModel.state.value as PaymentFlowState.CancelacionPendiente).declarando,
        )
        viewModel.declararDesdeCancelacion() // un segundo toque no manda otra declaración
        runCurrent()
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado(any(), any(), any()) }
        assertEquals("nada se resolvió todavía", antes, transporte.llamadas)

        viewModel.enviarPinDeGerente("1234")
        advanceTimeBy(5_000)
        runCurrent()

        assertNull("con el PIN aceptado el teclado se cierra", viewModel.pidiendoPinDeGerente.value)
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado("req-1", "venue-1", "1234") }
        // 🔴 Igual que con la declaración del cajero: el ViewModel NO borra la orden. El coordinador vuelve a preguntar y,
        // sólo porque CONSTA que no se cobró, la cancela.
        assertEquals(antes + listOf("GET:req-1", "DELETE:order-1"), transporte.llamadas)
        coVerify(exactly = 0) { orderRepository.cancelOrder(any(), any(), any()) }
        assertNull("la intención se cerró", store.leer("req-1"))
        assertTrue("con la orden cancelada, el flujo se cierra", viewModel.debeSalir.value)
    }

    @Test
    fun `P1 cerrar el teclado del PIN sin teclear deja el boton vivo y la bandera en falso`() = runTest {
        enCancelacionPendiente()
        val antes = transporte.llamadas.toList()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1", null) } returns pideGerente
        viewModel.declararDesdeCancelacion()
        runCurrent()
        val peticion = viewModel.pidiendoPinDeGerente.value
        assertNotNull("montaje: el teclado está abierto", peticion)

        viewModel.cerrarTecladoDelPin(peticion!!.id)
        runCurrent()

        assertNull(viewModel.pidiendoPinDeGerente.value)
        val estado = viewModel.state.value as PaymentFlowState.CancelacionPendiente
        assertFalse("el teclado cerrado no deja muerto el botón", estado.declarando)
        // Cerrar = cancelar: la declaración falla como fallaba antes de C4, con el motivo del SERVIDOR.
        assertEquals(pideGerente.mensaje, estado.avisoDeDeclaracion)
        assertEquals(antes, transporte.llamadas)
        assertNotNull("la intención sigue viva", store.leer("req-1"))

        // La bandera volvió a falso: un toque nuevo SÍ declara.
        viewModel.declararDesdeCancelacion()
        runCurrent()
        coVerify(exactly = 2) { terminalPaymentService.declararNoCobrado("req-1", "venue-1", null) }
        assertNotNull("y el servidor vuelve a pedir el PIN", viewModel.pidiendoPinDeGerente.value)
    }

    @Test
    fun `P2 si la cancelacion se resuelve sola con el teclado abierto el teclado se va y un PIN tardio no declara`() = runTest {
        enCancelacionPendiente()
        coEvery { terminalPaymentService.declararNoCobrado("req-1", "venue-1", null) } returns pideGerente
        viewModel.declararDesdeCancelacion()
        runCurrent()
        assertNotNull("montaje: el teclado está abierto", viewModel.pidiendoPinDeGerente.value)

        // La terminal confirma sola: el coordinador cierra la cancelación.
        transporte.programarEstados(cancelado)
        coordinador.procesarAhora("req-1")
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("montaje: el coordinador cerró y el flujo sale", viewModel.debeSalir.value)
        assertNull("ya no hay nada que declarar: el teclado se va", viewModel.pidiendoPinDeGerente.value)
        viewModel.enviarPinDeGerente("1234")
        runCurrent()
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado(any(), any(), any()) }
    }

    private companion object {
        const val CardChargeDecisionMessage = "Estamos confirmando el cobro. No vuelvas a pasar la tarjeta."
    }
}
