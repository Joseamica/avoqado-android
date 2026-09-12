package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.ReplayDeComandasPendientes
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutOrder
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val paymentSyncService = mockk<PaymentSyncService>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    /** Visible para poder comprobar CUÁNDO se persiste una comanda que no salió. */
    private val almacenDePendientes = AlmacenEnMemoria()
    private val storeDePendientes by lazy { ComandasPendientesStore(almacenDePendientes) }
    /** El MISMO dispatcher que ve el ViewModel y el reintento periódico — ver más abajo. */
    private val comandaDispatcherReal by lazy {
        ComandaDispatcher(
            printConfigRepository,
            ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
        )
    }

    private lateinit var viewModel: PaymentFlowViewModel

    /**
     * El coordinador de la cancelación durable, REAL, con disco en memoria y transporte falso: si
     * fuera un mock relajado, «Cancelar» no registraría nada y estas pruebas pasarían sin ejercitar
     * una sola línea del camino que dicen guardar.
     */
    private val almacenDeCancelaciones = AlmacenQuePuedeFallar()
    private val storeDeCancelaciones = com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto(almacenDeCancelaciones)
    private val transporteDeCancelacion = CancelacionTransportFalso()
    private lateinit var cancelacionDeCobro: com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator

    @Before
    fun setup() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        coEvery {
            // `any()` cubre las dos rutas: la sonda automática la pide marcada como
            // de fondo, y elegir "Cobrar con terminal" la pide sin marcar.
            terminalPaymentService.fetchOnlineTerminals(any())
        } returns TerminalListResult.Success(
            listOf(
                OnlineTerminal(
                    terminalId = "t1",
                    name = "Terminal 1",
                    isOnline = true,
                    hasSocket = true,
                ),
            ),
        )

        every {
            cashPaymentRepository.processCashPayment(any(), any())
        } returns CashPaymentResult.Success(changeCents = 0)

        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "fast-pay-1", receiptAccessKey = null))

        coEvery {
            cashDrawerRepository.addCashSale(any(), any())
        } returns null

        coEvery {
            kdsRepository.createOrder(any(), any(), any(), any())
        } returns Result.success(Unit)

        coEvery { kdsOrderBus.publish(any()) } returns Unit
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        coEvery { printerService.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.Printed(1)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null
        // Entradas del camino del dinero: se fijan explícitas, nunca al valor por defecto de un
        // mock relajado. Sin cobro en vuelo y sin contexto guardado, salvo donde la prueba diga.
        every { terminalPaymentService.intentoEnVuelo() } returns null
        every { terminalPaymentService.contextoDe(any()) } returns null

        // PRINT_STATIONS — default to "no stations configured" so existing tests keep
        // exercising the legacy single-ticket path unless a test overrides this.
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        // Sin `start()`: sin reloj de fondo que haga girar a `advanceUntilIdle`.
        cancelacionDeCobro = com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator(
            store = storeDeCancelaciones,
            transporte = transporteDeCancelacion,
            conectado = kotlinx.coroutines.flow.MutableStateFlow(true),
            servidorAlcanzable = kotlinx.coroutines.flow.MutableStateFlow(true),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
            ),
            reloj = { 0L },
            esperasDeSondeo = listOf(1_000L, 2_000L),
        )

        viewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = storeDePendientes,
            // 🔴 El REAL, con el mismo almacén y el mismo dispatcher: si aquí fuera un mock
            // relajado, «Volver a imprimir» no mandaría nada y las pruebas del botón pasarían
            // sin ejercitar una sola línea del camino que dicen guardar.
            replayDeComandas = ReplayDeComandasPendientes(storeDePendientes, comandaDispatcherReal),
            // 🔴 NO REGRESIÓN: el despachador va REAL, armado con los mismos mocks de siempre.
            // Mockearlo escondería justo lo que hay que probar — los tests de abajo siguen
            // verificando `printerService.autoPrintKitchenTicket` y `comandaPrinter.printComandas`
            // tal cual los verificaban antes de que ComandaDispatcher existiera, así que si la
            // extracción cambiara UNA llamada del camino post-pago, truenan.
            //
            // Task 4 + ronda de arreglo 1: `ReintentoDeComanda` también va REAL, construido con el
            // MISMO `comandaPrinter` de siempre (su default `esperar = { delay(it) }`; ya es
            // inyectable por Hilt de punta a punta, pero aquí se sigue construyendo a mano, igual
            // que `ComandaDispatcher`, para poder controlarlo desde el mismo mock). Bajo
            // `UnconfinedTestDispatcher` + `advanceUntilIdle()` los `delay()` del reintento se
            // saltan en tiempo virtual; sin `advanceUntilIdle()` la coroutine queda SUSPENDIDA ahí,
            // que es justo lo que exige "el cobro nunca se frena".
            comandaDispatcher = comandaDispatcherReal,
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = cancelacionDeCobro,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    @Test
    fun `mixed cart includes custom amount in create order request`() = runTest {
        val requestSlot = slot<CreateOrderRequest>()
        coEvery {
            orderRepository.createOrder(capture(requestSlot), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1")),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        val productItem = CartItem(
            id = "line-product",
            type = CartItemType.ProductItem("prod-1"),
            name = "Hamburguesa",
            unitPrice = 1000,
        )
        val customAmount = CartItem(
            id = "line-custom",
            type = CartItemType.CustomAmount,
            name = "Cargo servicio",
            unitPrice = 300,
        )
        val cart = CartState(items = listOf(productItem, customAmount))

        viewModel.startPaymentFlow(cart)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        val request = requestSlot.captured
        assertEquals(2, request.items.size)
        assertTrue(request.items.any { it.productId == "prod-1" })
        assertTrue(request.items.any { it.productId == null && it.name == "Cargo servicio" && it.unitPrice == 300 })
        assertEquals(1300, request.subtotal)
        assertEquals(1300, request.total)
    }

    @Test
    fun `retry on card error reuses existing order and does not create a second one`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1")),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal offline")

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-product",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Pizza",
                    unitPrice = 1500,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.retry()
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.createOrder(any(), "user-456", any(), any(), any()) }
        coVerify(exactly = 2) {
            terminalPaymentService.sendPaymentToTerminal(
                terminalId = "t1",
                amountCents = 1500,
                tipCents = 0,
                rating = null,
                orderId = "order-1",
                processedByStaffId = "user-456",
            )
        }
        assertTrue(viewModel.state.value is PaymentFlowState.Error)
    }

    // MARK: - Doble cobro con tarjeta (incidente 2026-08-10, Sunmi D3)

    /** Carrito mínimo con un producto, para los casos de tarjeta. */
    private fun cardCart() = CartState(
        items = listOf(
            CartItem(
                id = "line-product",
                type = CartItemType.ProductItem("prod-1"),
                name = "Pizza",
                unitPrice = 1500,
            ),
        ),
    )

    private fun stubOrderCreation() {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1")))
    }

    // MARK: - Cancelar es una PETICIÓN, no una garantía

    /**
     * Deja el envío EN VUELO para poder cancelar en medio, como el cajero de verdad: manda el
     * cobro, el cliente empieza a pagar, y la cancelación sale antes de que la terminal conteste.
     */
    private fun terminalRespondsLate(result: TerminalPaymentResult) {
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } coAnswers {
            kotlinx.coroutines.delay(60_000)
            result
        }
        // La ranura arranca LIBRE, como en producción (SharedPreferences devuelve null si no
        // hay nada). Explícito a propósito: es una entrada del camino del dinero y no puede
        // depender del valor por defecto de un mock relajado.
        every { terminalPaymentService.unresolvedRequestId } returns null
        // Con el POST en vuelo, «Cancelar» apunta a ESA solicitud: es lo que el servicio real
        // devuelve desde antes del POST y hasta que el intento termina.
        every { terminalPaymentService.intentoEnVuelo() } returns
            com.avoqado.pos.payment.data.IntentoDeCobroEnVuelo("req-1", "t1", "venue-1")
    }

    @Test
    fun `si la terminal cobro DESPUES de cancelar, el cobro no desaparece`() = runTest {
        // 🔴 El hueco: cancelar es una PETICIÓN, no una garantía. Si la tarjeta ya se pasó, la
        // terminal cobra igual y avisa TARDE. El guard de resultado obsoleto tiraba ese
        // desenlace ENTERO —incluido el cobro exitoso—: el dinero salía, la venta quedaba
        // marcada como impaga y el cajero cobraba otra vez.
        stubOrderCreation()
        terminalRespondsLate(TerminalPaymentResult.Success(paymentId = "pay-tarde", requestId = "req-1"))

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000) // el envío sigue en vuelo: el cliente está pagando
        viewModel.cancel() // el cajero cancela desde el POS
        advanceUntilIdle() // …y la terminal contesta "cobrado", tarde

        // La referencia queda armada en la llave DURABLE: la próxima venta se topa con ella
        // y el cajero puede resolverla desde "Cobro sin confirmar".
        verify { terminalPaymentService.rearmUnresolvedCharge("req-1") }
        // Pero NO se secuestra la pantalla de la que el cajero ya se fue.
        assertFalse(
            "cancelar significa que la pantalla no se toca",
            viewModel.state.value is PaymentFlowState.Success,
        )
    }

    @Test
    fun `un desenlace tardio que sigue sin saberse tambien queda pendiente`() = runTest {
        stubOrderCreation()
        terminalRespondsLate(
            TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1"),
        )

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000)
        viewModel.cancel()
        advanceUntilIdle()

        verify { terminalPaymentService.rearmUnresolvedCharge("req-1") }
    }

    @Test
    fun `cancelar antes de que la terminal haga nada sigue cancelando limpio`() = runTest {
        // El camino feliz. El server contesta 409 'Cancelado' ⇒ consta que NO hubo cargo:
        // ni referencia colgada ni pantalla de "Cobro sin confirmar" fantasma en la venta
        // siguiente. Si esto se rompe, cada cancelación normal deja un fantasma.
        stubOrderCreation()
        terminalRespondsLate(TerminalPaymentResult.Error("Cancelado"))

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000)
        viewModel.cancel()
        advanceUntilIdle()

        verify { terminalPaymentService.rearmUnresolvedCharge(null) }
        assertFalse(
            "una cancelación limpia no deja pantalla de cobro sin confirmar",
            viewModel.state.value is PaymentFlowState.Undetermined,
        )
    }

    @Test
    fun `un desenlace tardio NO pisa la llave del cobro que el cajero mando despues`() = runTest {
        // La venta ya avanzó a otra cosa: hay un cobro POSTERIOR gobernando el disco. El
        // rezagado no puede robarle la única ranura — ese otro es el que todavía puede tener
        // dinero encima.
        stubOrderCreation()
        terminalRespondsLate(TerminalPaymentResult.Success(paymentId = "pay-viejo", requestId = "req-viejo"))
        // La llave arranca libre (si no, la venta ni siquiera empezaría) y se ocupa mientras
        // el rezagado sigue en vuelo.
        var armada: String? = null
        every { terminalPaymentService.unresolvedRequestId } answers { armada }

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000)
        armada = "req-nuevo" // el cajero ya mandó OTRO cobro y ése quedó sin confirmar
        viewModel.cancel()
        advanceUntilIdle()

        verify { terminalPaymentService.rearmUnresolvedCharge("req-nuevo") }
    }

    @Test
    fun `un desenlace no confirmado NO se pinta como Error`() = runTest {
        stubOrderCreation()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        // Ni Success ni Error: el estado honesto.
        assertTrue(viewModel.state.value is PaymentFlowState.Undetermined)
    }

    @Test
    fun `retry con un cobro sin resolver NO cobra — primero consulta`() = runTest {
        stubOrderCreation()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1")
        coEvery {
            terminalPaymentService.resolveOutcome("req-1")
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        // 🔴 Lo que produjo el doble cobro: retry() mandaba a cobrar de nuevo a ciegas.
        // Ahora sólo se consulta — el cargo sigue siendo UNO solo.
        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { terminalPaymentService.resolveOutcome("req-1") }
        assertTrue(viewModel.state.value is PaymentFlowState.Undetermined)
    }

    @Test
    fun `si la re-consulta dice que SI se cobro, el cajero ve exito y no un error`() = runTest {
        stubOrderCreation()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1")
        // El escenario exacto del incidente: la terminal SÍ cobró, la app se enteró tarde.
        coEvery {
            terminalPaymentService.resolveOutcome("req-1")
        } returns TerminalPaymentResult.Success(paymentId = "pay-tarde")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.recheckCardCharge()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("debe terminar en éxito, sin error a la vista", state is PaymentFlowState.Success)
        assertEquals("pay-tarde", (state as PaymentFlowState.Success).paymentId)
        assertEquals(PaymentMethod.CARD, state.method)
        // Y jamás un segundo cargo.
        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `si consta que NO se cobro, recien ahi se ofrece cobrar`() = runTest {
        stubOrderCreation()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-1")
        coEvery {
            terminalPaymentService.resolveOutcome("req-1")
        } returns TerminalPaymentResult.Error("El cobro fue rechazado. No se cobró la tarjeta.")
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(
            listOf(OnlineTerminal(terminalId = "t1", name = "Caja 1")),
        )

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        // Rechazo confirmado ⇒ consta que no hubo cargo ⇒ es seguro volver a cobrar.
        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `un cobro sin resolver BLOQUEA la siguiente venta hasta resolverlo`() = runTest {
        // 🔴 El agujero que hacía inútil toda la ceremonia: el cajero ve "Cobro sin confirmar",
        // se va a Transacciones a comprobar si el pago entró, vuelve y cobra — pantalla nueva,
        // cero advertencia, segundo cargo. La llave vive en DISCO justo para esto.
        stubOrderCreation()
        every { terminalPaymentService.unresolvedRequestId } returns "req-1"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("la venta nueva debe toparse con el cobro pendiente", state is PaymentFlowState.Undetermined)
        assertTrue(
            "debe declararse que viene de otra venta",
            (state as PaymentFlowState.Undetermined).fromPreviousSale,
        )
        // Y nadie cobró nada por el camino.
        coVerify(exactly = 0) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `confirmar el cobro de la venta ANTERIOR no marca como pagada la venta actual`() = runTest {
        // Distinción crítica: la llave pendiente era de otra venta. Que aquel cobro sí haya
        // pasado NO paga la venta que el cajero tiene ahora en el carrito.
        stubOrderCreation()
        every { terminalPaymentService.unresolvedRequestId } returns "req-vieja"
        coEvery {
            terminalPaymentService.resolveOutcome("req-vieja")
        } returns TerminalPaymentResult.Success(paymentId = "pay-vieja")

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse(
            "jamás dar por pagada la venta actual con el cobro de otra",
            viewModel.state.value is PaymentFlowState.Success,
        )
        // El desenlace se anuncia por el canal que CIERRA el flujo: el cajero vino a resolver
        // un pendiente, no a cobrar, así que vuelve a su carrito con el mensaje en pantalla.
        // Antes se quedaba dentro del flujo y el aviso se desvanecía mientras ya le pedían la
        // calificación de la venta nueva — el desenlace de un cobro real pasaba volando.
        assertNotNull(
            "resolver un cobro ajeno tiene que avisar y devolver al cajero a donde estaba",
            viewModel.previousChargeResolved.value,
        )
    }

    @Test
    fun `old POST joining current recovery cannot rearm resolved previous sale`() = runTest {
        stubOrderCreation()
        var pending: String? = null
        every { secureStorage.accessToken } returns "token"
        every { secureStorage.pendingCardChargeRequestId } answers { pending }
        every { secureStorage.pendingCardChargeRequestId = any() } answers { pending = firstArg() }
        every { secureStorage.persistPendingCardCharge(any(), any()) } answers { pending = firstArg(); true }
        val server = okhttp3.mockwebserver.MockWebServer()
        val postStarted = java.util.concurrent.CountDownLatch(1)
        val getStarted = java.util.concurrent.CountDownLatch(1)
        val releasePost = java.util.concurrent.CountDownLatch(1)
        val releaseGet = java.util.concurrent.CountDownLatch(1)
        val gets = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (request.method == "POST") {
                    postStarted.countDown()
                    releasePost.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    return okhttp3.mockwebserver.MockResponse().setBody("""{"status":"unknown"}""")
                }
                gets.incrementAndGet()
                getStarted.countDown()
                releaseGet.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return okhttp3.mockwebserver.MockResponse().setBody("""{"status":"COMPLETED","inProgress":false,"paymentId":"old-payment"}""")
            }
        }
        server.start()
        val realService = TerminalPaymentService(secureStorage, okhttp3.OkHttpClient())
        realService.baseUrl = server.url("/api/v1").toString().trimEnd('/')
        every { terminalPaymentService.unresolvedRequestId } answers { realService.unresolvedRequestId }
        every { terminalPaymentService.rearmUnresolvedCharge(any()) } answers { realService.rearmUnresolvedCharge(firstArg()) }
        // El cobro en vuelo lo conoce el servicio REAL: es a quien apunta «Cancelar».
        every { terminalPaymentService.intentoEnVuelo() } answers { realService.intentoEnVuelo() }
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } coAnswers {
            realService.sendPaymentToTerminal("t1", 1500)
        }
        coEvery { terminalPaymentService.resolveOutcome(any()) } coAnswers { realService.resolveOutcome(firstArg()) }
        try {
            viewModel.startPaymentFlow(cardCart())
            viewModel.selectPaymentMethod(PaymentMethod.CARD)
            viewModel.selectTerminalAndPay("t1")
            runCurrent()
            assertTrue(postStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            viewModel.cancel()
            viewModel.startPaymentFlow(cardCart().copy(items = cardCart().items.map { it.copy(id = "B", unitPrice = 9900) }))
            viewModel.recheckCardCharge()
            runCurrent()
            assertTrue(getStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            releasePost.countDown()
            // Pump only ready work; never advance virtual financial deadlines while real HTTP waits.
            repeat(20) { Thread.sleep(10); runCurrent() }
            releaseGet.countDown()
            repeat(50) { Thread.sleep(10); runCurrent() }
            assertEquals(1, gets.get())
            assertNotNull(viewModel.previousChargeResolved.value)
            assertNull(pending)
            assertFalse(viewModel.state.value is PaymentFlowState.Success)
        } finally {
            releasePost.countDown()
            releaseGet.countDown()
            server.shutdown()
        }
    }

    @Test
    fun `blocked send cannot adopt a pending request that appeared after checkout entry`() = runTest {
        stubOrderCreation()
        var armed: String? = null
        every { terminalPaymentService.unresolvedRequestId } answers { armed }
        val realGuard = TerminalPaymentService(secureStorage, okhttp3.OkHttpClient())
        every { secureStorage.pendingCardChargeRequestId } returns "old-request"
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } coAnswers {
            armed = "old-request"
            realGuard.sendPaymentToTerminal("t1", 1500)
        }
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns TerminalPaymentResult.Success(paymentId = "old-payment")
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertTrue((viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertNotNull(viewModel.previousChargeResolved.value)
    }

    @Test
    fun `two recovery callbacks share one current cycle`() = runTest {
        every { terminalPaymentService.unresolvedRequestId } returns "pending"
        val result = CompletableDeferred<TerminalPaymentResult>()
        coEvery { terminalPaymentService.resolveOutcome("pending") } coAnswers { result.await() }
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        viewModel.recheckCardCharge()
        advanceTimeBy(1)
        result.complete(TerminalPaymentResult.Success(paymentId = "paid", requestId = "pending"))
        advanceUntilIdle()
        coVerify(exactly = 1) { terminalPaymentService.resolveOutcome("pending") }
        assertNotNull(viewModel.previousChargeResolved.value)
    }

    @Test
    fun `retained checkout never adopts an inherited request after reopening`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.unresolvedRequestId } returns "old-request"
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns TerminalPaymentResult.Success(paymentId = "old-payment")
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.cancel()
        viewModel.startPaymentFlow(cardCart().copy(items = cardCart().items.map { it.copy(id = "other-line", unitPrice = 9900) }))
        advanceUntilIdle()
        assertTrue((viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertNotNull(viewModel.previousChargeResolved.value)
    }

    @Test
    fun `recovery completing after cancel cannot pay the new checkout`() = runTest {
        stubOrderCreation()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returns TerminalPaymentResult.Undetermined("Pending", "req-1")
        val result = CompletableDeferred<TerminalPaymentResult>()
        coEvery { terminalPaymentService.resolveOutcome("req-1") } coAnswers { result.await() }
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        advanceTimeBy(1)
        viewModel.cancel()
        viewModel.startPaymentFlow(cardCart().copy(items = cardCart().items.map { it.copy(id = "other-line", unitPrice = 9900) }))
        advanceTimeBy(1)
        val stateBeforeResult = viewModel.state.value
        result.complete(TerminalPaymentResult.Success(paymentId = "old-payment", requestId = "req-1"))
        advanceUntilIdle()
        assertEquals(stateBeforeResult, viewModel.state.value)
        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        verify { terminalPaymentService.rearmUnresolvedCharge("req-1") }
    }

    @Test
    fun `inherited ACTIVE recovery preserves terminal confirmation instruction`() = runTest {
        every { terminalPaymentService.unresolvedRequestId } returns "old-request"
        val instruction = "El cobro sigue activo. Confirma en la terminal antes de intentar otro cobro."
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns TerminalPaymentResult.Undetermined(instruction, "old-request")
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        val state = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(state.fromPreviousSale)
        assertTrue(state.message.contains(instruction))
    }

    @Test
    fun `el aviso de la venta anterior no repite la frase del cobro sin confirmar`() = runTest {
        every { terminalPaymentService.unresolvedRequestId } returns "old-request"
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "old-request")
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        val state = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(state.fromPreviousSale)
        assertTrue(state.message, state.message.startsWith("Quedó un cobro sin confirmar de una venta anterior."))
        val veces = Regex(Regex.escape(CardChargeDecision.UNDETERMINED_MESSAGE)).findAll(state.message).count()
        assertEquals("la instrucción se lee UNA vez: ${state.message}", 1, veces)
    }

    @Test
    fun `cobrar de todos modos conserva la llave y no permite otra autorizacion`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.unresolvedRequestId } returns "req-1"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.chargeAgainDespiteUndetermined()
        advanceUntilIdle()

        // El cajero asumió el riesgo tras la advertencia: la llave deja de gobernar.
        verify(exactly = 0) { terminalPaymentService.forgetUnresolvedCharge() }
    }

    @Test
    fun `start payment flow resets previous tip before next custom payment`() = runTest {
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "fast-pay-2", receiptAccessKey = null))

        val firstCart = CartState(
            items = listOf(
                CartItem(
                    id = "custom-1",
                    type = CartItemType.CustomAmount,
                    name = "Importe 1",
                    unitPrice = 1000,
                ),
            ),
        )
        val secondCart = CartState(
            items = listOf(
                CartItem(
                    id = "custom-2",
                    type = CartItemType.CustomAmount,
                    name = "Importe 2",
                    unitPrice = 500,
                ),
            ),
        )

        viewModel.startPaymentFlow(firstCart)
        viewModel.submitTip(200)

        viewModel.startPaymentFlow(secondCart)
        viewModel.confirmCashCustom(500)
        advanceUntilIdle()

        verify(exactly = 1) { cashPaymentRepository.processCashPayment(500, 500) }
        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(500, "user-456", 0, "FULLPAYMENT", any()) }
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `area checkout propagates delivery code to paid receipt`() = runTest {
        val openCheckout = AreaTicketCheckout(
            id = "checkout-area-1",
            status = "OPEN",
            version = 1,
            expiresAt = "2026-07-30T00:00:00.000Z",
            createdAt = "2026-07-29T00:00:00.000Z",
            totals = AreaTicketCheckoutTotals(
                subtotal = "1.00",
                discountAmount = "0.00",
                total = "1.00",
            ),
        )
        val deliveryCode = "8427993264"
        val materializedCheckout = openCheckout.copy(
            status = "MATERIALIZED",
            order = AreaTicketCheckoutOrder(
                id = "order-area-1",
                orderNumber = "AREA-101",
                paymentStatus = "PENDING",
                status = "OPEN",
                total = "1.00",
                remainingBalance = "1.00",
                areaDeliveryCode = deliveryCode,
            ),
        )
        every { areaTicketRepository.session.current() } returns openCheckout
        coEvery {
            areaTicketRepository.materialize(any(), any(), any())
        } returns materializedCheckout
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "payment-area-1",
                receiptAccessKey = null,
            ),
        )
        val printedReceipt = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(printedReceipt)) } returns Unit

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "area-line-1",
                    type = CartItemType.ProductItem("product-1"),
                    name = "Jamón",
                    unitPrice = 100,
                    areaTicketId = "ticket-1",
                    areaTicketLineId = "ticket-line-1",
                    locked = true,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(100)
        advanceUntilIdle()

        assertEquals(deliveryCode, printedReceipt.captured.areaDeliveryCode)
        coVerify(exactly = 0) { kdsRepository.createOrder(any(), any(), any(), any()) }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
    }

    @Test
    fun `no print stations configured falls back to the legacy single kitchen ticket`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-legacy")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-legacy", receiptAccessKey = null))
        // Default from setup(): printConfigRepository.getCurrentConfig() returns PrintConfig() (no stations)

        val cart = CartState(
            items = listOf(
                CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Taco", unitPrice = 1000),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        coVerify(exactly = 1) { printerService.autoPrintKitchenTicket(any()) }
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    /**
     * 🔴 NO REGRESIÓN del ticket legado, renglón por renglón. El de arriba prueba QUE se imprime;
     * este prueba QUÉ se imprime — encabezado "En tienda", número de orden, y cada [KitchenItem]
     * con su cantidad, sus modificadores, su nota y su `category` (que sale del `subtitle` del
     * carrito y el ruteo por estaciones NO lleva). Es el renglón que se pierde primero si alguien
     * "simplifica" la extracción.
     */
    @Test
    fun `el ticket legado sale identico renglon por renglon, con categoria y modificadores`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-abcd1234")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-legacy-2", receiptAccessKey = null))

        val ticketSlot = slot<KitchenTicketData>()
        coEvery { printerService.autoPrintKitchenTicket(capture(ticketSlot)) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Taco",
                    subtitle = "Antojitos",
                    unitPrice = 1000,
                    quantity = 2,
                    selectedModifiers = listOf(
                        SelectedModifier(
                            groupId = "g1",
                            groupName = "Extras",
                            modifierId = "m1",
                            modifierName = "Sin cebolla",
                            priceInCents = 0,
                        ),
                    ),
                    itemNote = "bien dorado",
                ),
                // Un importe personalizado NUNCA va a cocina — no es un producto.
                CartItem(id = "line-2", type = CartItemType.CustomAmount, name = "Propina de la casa", unitPrice = 500),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(2500)
        advanceUntilIdle()

        val ticket = ticketSlot.captured
        assertEquals("1234", ticket.orderNumber) // últimos 4 del orderId
        assertEquals("En tienda", ticket.orderType)
        assertEquals(
            listOf(KitchenItem("Taco", 2, listOf("Sin cebolla"), "bien dorado", "Antojitos")),
            ticket.items,
        )
    }

    @Test
    fun `active print stations route the kitchen ticket through the comanda printer instead of the legacy fan-out`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-routed")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-routed", receiptAccessKey = null))

        val station = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true)
        val config = PrintConfig(stations = listOf(station), defaultStationId = "st_cocina")
        every { printConfigRepository.getCurrentConfig() } returns config

        val plansSlot = slot<List<TicketPlan>>()
        coEvery {
            comandaPrinter.printComandas(capture(plansSlot), config, any(), any(), any())
        } returns ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        val cart = CartState(
            items = listOf(
                CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Taco", unitPrice = 1000),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        coVerify(exactly = 1) { printConfigRepository.refresh("venue-1") }
        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), config, any(), any(), any()) }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
        assertEquals(listOf("Taco"), plansSlot.captured.single().lines.map { it.productName })
    }

    /**
     * 🔴 El cobro NUNCA se frena por una impresora — pero callar el fallo deja al barista
     * sin enterarse del pedido. Testarudo (2026-08-31) cobró cafés durante días sin que
     * saliera la comanda de barra y nadie vio un solo error: el camino automático tiraba
     * el Result del ruteo.
     */
    @Test
    fun `cuando la comanda de una estacion no sale, el cajero recibe el aviso con la estacion`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-aviso")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-aviso", receiptAccessKey = null))

        val station = StationInfo(id = "st_barra", name = "Barra", printerId = "pr_1", active = true)
        val config = PrintConfig(stations = listOf(station), defaultStationId = "st_barra")
        every { printConfigRepository.getCurrentConfig() } returns config
        coEvery { comandaPrinter.printComandas(any(), config, any(), any(), any()) } returns
            ComandaPrinter.Result(
                attempted = 1,
                printed = 0,
                skippedNoPrinter = 0,
                lastError = "printer offline",
                failedStations = listOf("Barra"),
            )

        val cart = CartState(
            items = listOf(
                CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Chai", unitPrice = 1000),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(1000)
        advanceUntilIdle()

        // El cobro salió bien — el aviso es informativo, jamás bloquea el dinero.
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        val aviso = viewModel.comandaWarning.value
        assertTrue(
            "el aviso debe nombrar la estación: $aviso",
            aviso is EstadoDeComanda.NoSalio && aviso.estaciones.contains("Barra"),
        )
    }

    @Test
    fun `cuando todas las comandas salen no hay aviso`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-ok")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-ok", receiptAccessKey = null))

        val station = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true)
        val config = PrintConfig(stations = listOf(station), defaultStationId = "st_cocina")
        every { printConfigRepository.getCurrentConfig() } returns config
        coEvery { comandaPrinter.printComandas(any(), config, any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        val cart = CartState(
            items = listOf(
                CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Taco", unitPrice = 1000),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(1000)
        advanceUntilIdle()

        assertTrue(viewModel.comandaWarning.value == null)
    }

    // MARK: - Task 4: el aviso trae la causa REAL del server y no frena el cobro

    /** Config con UNA estación activa ("Cocina") — fuerza el camino de ruteo, no el legado. */
    private val cocinaActivaConfig = PrintConfig(
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true)),
        defaultStationId = "st_cocina",
    )

    /** Un carrito DISTINTO del de [cartConUnProducto] — sirve para probar que el reintento NO
     *  lee el carrito vigente. */
    private fun cartConOtroProducto() = CartState(
        items = listOf(
            CartItem(id = "line-9", type = CartItemType.ProductItem("prod-9"), name = "Concha", unitPrice = 2500),
        ),
    )

    private fun cartConUnProducto() = CartState(
        items = listOf(
            CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Café", unitPrice = 1000),
        ),
    )

    /**
     * Arranca el cobro en efectivo de [cartConUnProducto] — a propósito SIN `advanceUntilIdle()`,
     * para que quien llama decida si deja avanzar el reloj virtual (y con él, el reintento de
     * la comanda) o si comprueba el estado justo como quedó al volver de este método.
     */
    private fun completarCobroEnEfectivo() {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-task4")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-task4", receiptAccessKey = null))

        viewModel.startPaymentFlow(cartConUnProducto())
        viewModel.confirmCashCustom(1000)
    }

    /**
     * La impresora de "Cocina" TRUENA siempre — cada intento (el primero y los cinco
     * reintentos) devuelve la MISMA causa que reportaría el server real, así que
     * [com.avoqado.pos.printing.data.ReintentoDeComanda] agota los 6 intentos de
     * [com.avoqado.pos.printing.data.PoliticaDeReintento] y se rinde con esa causa.
     */
    private fun laImpresoraFallaSiempre() {
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            ComandaPrinter.Result(
                attempted = plans.size,
                printed = 0,
                skippedNoPrinter = 0,
                lastError = "failed to connect to /192.168.1.141 (port 9100)",
                failedStations = listOf("Cocina"),
                failedPlans = plans,
            )
        }
    }

    @Test
    fun `el aviso lleva la causa REAL del servidor, no un texto generico`() = runTest {
        laImpresoraFallaSiempre()

        completarCobroEnEfectivo()
        advanceUntilIdle()

        val aviso = viewModel.comandaWarning.value as EstadoDeComanda.NoSalio
        assertEquals(listOf("Cocina"), aviso.estaciones)
        assertEquals("failed to connect to /192.168.1.141 (port 9100)", aviso.causa)
    }

    /**
     * 🔴 El corazón de la Tarea 4: con `ReintentoDeComanda` una comanda que no sale puede tardar
     * hasta ~1 minuto en rendirse. Si esa espera viviera en la coroutine del cobro, el cajero
     * vería la caja congelada con el cliente enfrente. Por eso esta prueba NO llama a
     * `advanceUntilIdle()`: verifica el estado tal como queda al volver de
     * `confirmCashCustom(...)`, con el reintento genuinamente SUSPENDIDO a media espera (bajo
     * `UnconfinedTestDispatcher`, el primer `delay()` real del reintento es lo único que puede
     * devolver el control aquí sin que la prueba lo pida).
     */
    @Test
    fun `el cobro NUNCA se frena porque la comanda este reintentando`() = runTest {
        laImpresoraFallaSiempre()

        completarCobroEnEfectivo()

        // El cobro ya es Success...
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        // ...y la prueba de que no fue casualidad: la comanda sigue insistiendo en este
        // instante exacto, no ha terminado. Si `dispatch` se hubiera esperado desde el camino
        // del cobro, `state` seguiría en Loading/Processing y esto ni se alcanzaría a leer.
        assertTrue(viewModel.comandaWarning.value is EstadoDeComanda.Insistiendo)
    }

    @Test
    fun `una comanda que salio no deja aviso`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } returns ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        completarCobroEnEfectivo()
        advanceUntilIdle()

        assertNull(viewModel.comandaWarning.value)
    }

    // MARK: - Ronda de arreglo 1: dos ventas en vuelo a la vez (el reintento estiró la ventana)

    /**
     * P1 nuevo del revisor: con el reintento la ventana en la que DOS ventas pueden estar
     * reintentando a la vez subió de segundos a ~50s. Simula el caso real: la venta A falla y
     * queda esperando su reintento; ANTES de que resuelva, el cajero ya cobró la venta B (que
     * también falla y queda esperando). Cuando el reintento de A —programado ANTES— por fin
     * resuelve y sale bien, ese `Salio` NO debe borrar el aviso de B, que sigue sin resolver.
     */
    @Test
    fun `un Salio tardio de una venta no limpia el aviso de OTRA venta que sigue reintentando`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig

        var intentoA = 0
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, "AAAA", any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            intentoA++
            if (intentoA == 1) {
                ComandaPrinter.Result(
                    attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                    lastError = "timeout A", failedStations = listOf("Cocina"), failedPlans = plans,
                )
            } else {
                ComandaPrinter.Result(attempted = plans.size, printed = plans.size, skippedNoPrinter = 0, lastError = null)
            }
        }
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, "BBBB", any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "timeout B", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        // Venta A: falla en el primer intento y queda esperando su reintento (a los 2s virtuales).
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-AAAA")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-A", receiptAccessKey = null))
        viewModel.startPaymentFlow(cartConUnProducto())
        viewModel.confirmCashCustom(1000)
        val avisoTrasA = viewModel.comandaWarning.value as EstadoDeComanda.Insistiendo
        assertEquals("AAAA", avisoTrasA.orderNumber)

        // Antes de que A reintente, el cajero ya cobró la venta B. Avanza el reloj un poco para
        // que las dos esperas de 2s NO caigan en el mismo instante virtual.
        advanceTimeBy(500)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-BBBB")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "payment-B", receiptAccessKey = null))
        viewModel.startPaymentFlow(cartConUnProducto())
        viewModel.confirmCashCustom(1000)
        val avisoTrasB = viewModel.comandaWarning.value as EstadoDeComanda.Insistiendo
        assertEquals("BBBB", avisoTrasB.orderNumber)

        // Avanza hasta DESPUÉS de que el reintento de A (programado antes, a los 2000ms) resuelve
        // — y sale bien. `advanceTimeBy` no ejecuta lo agendado EXACTO en el nuevo límite (hace
        // falta pasarse un poco o llamar `runCurrent()`), así que se avanza con margen — sin
        // llegar a los 2500ms en que despierta el propio reintento de B.
        advanceTimeBy(1600)

        val avisoFinal = viewModel.comandaWarning.value
        assertTrue(
            "un Salio de A no debe limpiar el aviso de B, que sigue reintentando: $avisoFinal",
            avisoFinal is EstadoDeComanda.Insistiendo && avisoFinal.orderNumber == "BBBB",
        )
    }

    // MARK: - Ronda de arreglo 1: doble toque en "Volver a imprimir" no duplica el ticket

    /**
     * P2 del revisor: un doble tap sobre "Volver a imprimir" mientras el primero sigue en vuelo
     * podía disparar DOS ciclos de reintento en paralelo — y ambos imprimirían la misma comanda.
     */
    @Test
    fun `un segundo toque de Volver a imprimir mientras el primero sigue en vuelo no dispara otro ciclo`() = runTest {
        var llamadas = 0
        val puertaDelManual = CompletableDeferred<Unit>()
        var enManual = false
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            llamadas++
            // 🔴 La puerta es lo que hace que esta prueba SIGA guardando algo. El reintento
            // manual hace UN intento, así que sin un punto de suspensión terminaría antes del
            // segundo toque y el guard nunca se ejercitaría — la prueba pasaría por el motivo
            // equivocado.
            if (enManual) puertaDelManual.await()
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "sigue caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        completarCobroEnEfectivo()
        advanceUntilIdle() // agota los 6 intentos automáticos y se rinde con NoSalio
        val llamadasTrasElAutomatico = llamadas
        assertTrue(viewModel.comandaWarning.value is EstadoDeComanda.NoSalio)

        enManual = true
        viewModel.reintentarComanda() // entra y se queda esperando en la puerta
        viewModel.reintentarComanda() // NO-OP: el primero sigue en vuelo
        assertEquals("el segundo toque disparó otro ciclo", llamadasTrasElAutomatico + 1, llamadas)

        puertaDelManual.complete(Unit)
        advanceUntilIdle()
        assertEquals("tras soltar la puerta se coló un ciclo de más", llamadasTrasElAutomatico + 1, llamadas)
    }

    @Test
    fun `reintentandoComandaManualmente refleja si el reintento manual sigue en vuelo`() = runTest {
        val puertaDelManual = CompletableDeferred<Unit>()
        var enManual = false
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            if (enManual) puertaDelManual.await()
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "sigue caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        completarCobroEnEfectivo()
        advanceUntilIdle()
        assertFalse(viewModel.reintentandoComandaManualmente.value)

        enManual = true
        viewModel.reintentarComanda()
        assertTrue("no marcó 'en vuelo' con el reintento suspendido", viewModel.reintentandoComandaManualmente.value)

        puertaDelManual.complete(Unit)
        advanceUntilIdle()
        assertFalse("quedó marcado 'en vuelo' tras terminar", viewModel.reintentandoComandaManualmente.value)
    }

    /**
     * P1 #2/#3 de la auditoría de Codex (2026-09-07). El botón «Volver a imprimir» leía
     * `cartState` y reconstruía la comanda desde el carrito que el cajero tuviera ENFRENTE.
     *
     * 🔴 Esta prueba lo caza por donde duele: se BORRA el aviso y se deja el carrito intacto.
     * Con el defecto, el botón seguía teniendo "algo que imprimir" y mandaba la comanda otra
     * vez — la de una venta que nadie reclamó. Con el arreglo, sin aviso no hay trabajo
     * pendiente, y sin trabajo pendiente no se manda NADA.
     */
    @Test
    fun `P1 sin aviso de fallo, Volver a imprimir NO manda nada aunque el carrito siga cargado`() = runTest {
        var llamadas = 0
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            llamadas++
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        completarCobroEnEfectivo()
        advanceUntilIdle()
        assertTrue(viewModel.comandaWarning.value is EstadoDeComanda.NoSalio)

        viewModel.clearComandaWarning()
        val antes = llamadas
        viewModel.reintentarComanda()
        advanceUntilIdle()

        assertEquals("reimprimió leyendo el carrito en vez del aviso", antes, llamadas)
    }

    /**
     * P1 #3. Lo que se reenvía sale del AVISO —su folio y sus planes— y de ningún otro lado.
     * Si esto se rompiera, el botón del aviso de la venta A imprimiría la venta que el cajero
     * tenga abierta, con el folio de ESA venta, y A se quedaría igual de pendiente.
     */
    @Test
    fun `P1 Volver a imprimir manda el folio y los planes del AVISO`() = runTest {
        val foliosMandados = mutableListOf<String>()
        val planesMandados = mutableListOf<List<TicketPlan>>()
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            foliosMandados += thirdArg<String>()
            planesMandados += plans
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        completarCobroEnEfectivo()
        advanceUntilIdle()
        val aviso = viewModel.comandaWarning.value as EstadoDeComanda.NoSalio

        // 🔴 Lo que hace que esta prueba GUARDE algo: el cajero empieza otra venta, con OTRO
        // producto. A partir de aquí el carrito vigente y el trabajo congelado son DISTINTOS —
        // antes eran idénticos, así que una regresión que volviera a leer `cartState` habría
        // pasado igual de verde (P2 #15 de la 2ª auditoría de Codex, 2026-09-07).
        viewModel.startPaymentFlow(cartConOtroProducto())
        assertEquals("el aviso de la venta anterior se perdió", aviso, viewModel.comandaWarning.value)
        foliosMandados.clear()
        planesMandados.clear()

        viewModel.reintentarComanda()
        advanceUntilIdle()

        assertEquals("no se mandó ni un lote", 1, planesMandados.size)
        assertEquals("el folio salió del carrito vigente, no del aviso", listOf(aviso.orderNumber), foliosMandados)
        assertEquals("los planes salieron del carrito vigente", aviso.trabajo?.planes, planesMandados.single())
        // Y la prueba definitiva de que NO vino del carrito de B:
        assertTrue(
            "se imprimió el producto de la venta NUEVA",
            planesMandados.single().flatMap { it.lines }.none { it.productName == "Concha" },
        )
    }

    /**
     * P1 #6 de la auditoría de Codex (2026-09-07). El reintento tarda hasta ~1 minuto y el
     * cajero cierra el cobro en cuanto entrega el cambio, así que el caso NORMAL es que el
     * fallo llegue después. Empezar la venta siguiente BORRABA el aviso: la comanda que no
     * salió quedaba fuera del alcance de nadie, para siempre.
     *
     * Un `NoSalio` sin resolver sobrevive; se va con «Ya la canté», que es una persona
     * decidiendo — no un efecto secundario de cobrar otra cosa.
     */
    @Test
    fun `P1 empezar otra venta NO borra una comanda que no salio`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }

        completarCobroEnEfectivo()
        advanceUntilIdle()
        val aviso = viewModel.comandaWarning.value as EstadoDeComanda.NoSalio

        // El cajero empieza la venta siguiente.
        viewModel.startPaymentFlow(cartConUnProducto())

        assertEquals(
            "la comanda que no salió se volvió inalcanzable al empezar otra venta",
            aviso,
            viewModel.comandaWarning.value,
        )

        // Y sí se va cuando una PERSONA lo decide.
        viewModel.clearComandaWarning()
        assertNull(viewModel.comandaWarning.value)
    }

    /**
     * P2 #16 de la 2ª auditoría de Codex: las pruebas del almacén se guardaban a sí mismas, así
     * que nadie comprobaba CUÁNDO se persiste. Esto lo fija donde vive la decisión.
     */
    @Test
    fun `P1 una comanda que no salio queda guardada en el aparato, sin que nadie la guarde a mano`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns cocinaActivaConfig
        coEvery {
            comandaPrinter.printComandas(any(), cocinaActivaConfig, any(), any(), any())
        } coAnswers {
            val plans = firstArg<List<TicketPlan>>()
            ComandaPrinter.Result(
                attempted = plans.size, printed = 0, skippedNoPrinter = 0,
                lastError = "caída", failedStations = listOf("Cocina"), failedPlans = plans,
            )
        }
        assertNull("el almacén no arrancó vacío", almacenDePendientes.leer())

        completarCobroEnEfectivo()
        advanceUntilIdle()

        assertNotNull(
            "el fallo no se persistió: si la app muere aquí, la comanda desaparece",
            almacenDePendientes.leer(),
        )
    }

    @Test
    fun `buildCompletion for split by product returns remaining balance and paid item ids`() {
        val paidItem = CartItem(
            id = "paid-1",
            type = CartItemType.ProductItem("prod-1"),
            name = "Cafe",
            unitPrice = 600,
        )
        val remainingItem = CartItem(
            id = "remain-1",
            type = CartItemType.CustomAmount,
            name = "Saldo",
            unitPrice = 400,
        )
        val cart = CartState(items = listOf(paidItem, remainingItem))

        viewModel.setSplitConfig(type = "BYPRODUCT", selectedItemIds = listOf("paid-1"))
        viewModel.startPaymentFlow(cart)

        val completion = viewModel.buildCompletion()

        assertEquals("BYPRODUCT", completion.splitType)
        assertEquals(setOf("paid-1"), completion.paidItemIds)
        assertEquals(400, completion.remainingBalanceCents)
    }

    @Test
    fun `successful payment exposes completion once before receipt screen is dismissed`() = runTest {
        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "paid-line-1",
                    type = CartItemType.CustomAmount,
                    name = "Venta",
                    unitPrice = 500,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashCustom(500)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        assertNotNull(
            "El checkout debe consumir el carrito al confirmarse el pago, no al salir del recibo",
            viewModel.consumeCompletion(),
        )
        assertNull("La misma venta no debe consumirse dos veces", viewModel.consumeCompletion())
    }

    @Test
    fun `send receipt email uses receiptAccessKey fallback when paymentId is null`() = runTest {
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Success(
            transactionId = "tx-1",
            paymentId = null,
            receiptAccessKey = "rak-123",
        )
        coEvery {
            orderRepository.sendReceiptEmail(
                paymentId = any(),
                email = any(),
                receiptAccessKey = any(),
            )
        } returns Result.success(Unit)

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "custom-1",
                    type = CartItemType.CustomAmount,
                    name = "Pago rapido",
                    unitPrice = 1000,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.sendReceiptEmail("cliente@correo.com")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.sendReceiptEmail(
                paymentId = null,
                email = "cliente@correo.com",
                receiptAccessKey = "rak-123",
            )
        }
        assertEquals("Recibo enviado por correo", viewModel.emailResult.value)
    }

    @Test
    fun `send receipt whatsapp sends both paymentId and receiptAccessKey when available`() = runTest {
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Success(
            transactionId = "tx-2",
            paymentId = "pay-2",
            receiptAccessKey = "rak-456",
        )
        coEvery {
            orderRepository.sendReceiptWhatsApp(
                paymentId = any(),
                phone = any(),
                receiptAccessKey = any(),
            )
        } returns Result.success(Unit)

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "custom-2",
                    type = CartItemType.CustomAmount,
                    name = "Pago rapido",
                    unitPrice = 1500,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        viewModel.sendReceiptWhatsApp("+525511112222")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.sendReceiptWhatsApp(
                paymentId = "pay-2",
                phone = "+525511112222",
                receiptAccessKey = "rak-456",
            )
        }
        assertEquals("Recibo enviado por WhatsApp", viewModel.whatsAppResult.value)
    }

    @Test
    fun `tip percentage base excludes tax when includeTaxInTipBase is disabled`() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(
            showReviewScreen = false,
            showTipScreen = true,
            includeTaxInTipBase = false,
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Producto",
                    unitPrice = 1000,
                ),
            ),
            orderTaxPercent = 16,
        )

        viewModel.startPaymentFlow(cart)

        assertEquals(1000, viewModel.currentTipPercentageBaseCents())
    }

    @Test
    fun `tip percentage base includes tax when includeTaxInTipBase is enabled`() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(
            showReviewScreen = false,
            showTipScreen = true,
            includeTaxInTipBase = true,
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Producto",
                    unitPrice = 1000,
                ),
            ),
            orderTaxPercent = 16,
        )

        viewModel.startPaymentFlow(cart)

        assertEquals(1160, viewModel.currentTipPercentageBaseCents())
    }

    @Test
    fun `tip percentage base removes proportional tax for equal parts split when disabled`() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(
            showReviewScreen = false,
            showTipScreen = true,
            includeTaxInTipBase = false,
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Producto",
                    unitPrice = 1000,
                ),
            ),
            orderTaxPercent = 16,
        )

        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(cart)

        assertEquals(500, viewModel.currentTipPercentageBaseCents())
    }

    /**
     * Un cobro con terminal ajena NO entró al cajón. Si se registra como venta en
     * efectivo, al cerrar el turno el cajero aparece con un faltante por ese monto
     * — el descuadre exacto que el método manual vino a evitar. Se vio en hardware:
     * la caja decía "Venta en efectivo +$8.00" tras cobrar con tarjeta externa.
     */
    @Test
    fun `cobro declarado a mano NO entra al arqueo de efectivo`() = runTest {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        // El stub del setup cubre la sobrecarga sin manualMethod (5 args); el cobro
        // manual la llama con 6.
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "manual-1", receiptAccessKey = null))

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.CustomAmount,
                    name = "Venta rápida",
                    unitPrice = 800,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        // `confirmManualMethod` se renombró a `confirmManualChoice` (commit ajeno 09b3ef6):
        // ahora la elección puede ser una de las 3 fijas o un tipo del catálogo. La
        // intención del test no cambia — sigue siendo "tarjeta de terminal ajena".
        viewModel.confirmManualChoice(
            com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(ManualPaymentMethod.CARD_EXTERNAL),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any()) }
    }

    /** El efectivo real SÍ tiene que seguir entrando al arqueo. */
    @Test
    fun `cobro en efectivo si entra al arqueo`() = runTest {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.CustomAmount,
                    name = "Venta rápida",
                    unitPrice = 800,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 800)
        advanceUntilIdle()

        coVerify(exactly = 1) { cashDrawerRepository.addCashSale(800, any()) }
    }

    @Test
    fun `una carrera de cobro usa importe y cambio autoritativos del server`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-race")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "payment-race",
                receiptAccessKey = null,
                recordedAmountCents = 350,
                recordedTipCents = 50,
                authoritativeChangeCents = 100,
                remainingBalanceCents = 0,
                orderPaymentStatus = "PAID",
            ),
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-race",
                    type = CartItemType.ProductItem("prod-race"),
                    name = "Venta con carrera",
                    unitPrice = 500,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 500)
        advanceUntilIdle()

        val success = viewModel.state.value as PaymentFlowState.Success
        assertEquals(100, success.changeAmount)
        assertEquals(400, success.totalAmount)
        coVerify(exactly = 1) { cashDrawerRepository.addCashSale(400, "order-race") }
    }

    @Test
    fun `un resultado autoritativo desbordado conserva el total local seguro`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-overflow")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "payment-overflow",
                receiptAccessKey = null,
                recordedAmountCents = Int.MAX_VALUE,
                recordedTipCents = Int.MAX_VALUE,
                authoritativeChangeCents = 0,
                remainingBalanceCents = 0,
                orderPaymentStatus = "PAID",
            ),
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-overflow",
                    type = CartItemType.ProductItem("prod-overflow"),
                    name = "Venta segura",
                    unitPrice = 500,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 500)
        advanceUntilIdle()

        val success = viewModel.state.value as PaymentFlowState.Success
        assertEquals(500, success.totalAmount)
        coVerify(exactly = 1) { cashDrawerRepository.addCashSale(500, "order-overflow") }
    }

    /**
     * P1 — EL CAMBIO QUE SE LE DEVUELVE AL CLIENTE NO ES EL `changeCents` DEL SERVER.
     *
     * Son dos cosas distintas que se llamaban igual, y por eso el ticket de Testarudo
     * (10-sep-2026) imprimió «Recibido: $544.50» sobre una venta donde el cliente
     * entregó $550:
     *
     *  - Para el POS, cambio = **recibido − lo que se cobró** (lo que sale del cajón).
     *  - Para el server, `changeCents` = lo que sobró del importe ENVIADO sobre el
     *    saldo de la orden. Como el POS le manda exactamente el saldo, en un cobro
     *    normal SIEMPRE vale 0.
     *
     * Dejar ganar al del server borraba el cambio real de TODAS las ventas en efectivo
     * con productos (el camino de venta rápida nunca lo consultó, por eso ahí sí salía).
     */
    @Test
    fun `P1 el cambio real sobrevive aunque el server responda changeCents cero`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-cambio")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "payment-cambio",
                receiptAccessKey = null,
                recordedAmountCents = 54450,
                recordedTipCents = 0,
                // Lo que de verdad contesta el server en un cobro normal.
                authoritativeChangeCents = 0,
                remainingBalanceCents = 0,
                orderPaymentStatus = "PAID",
            ),
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-cambio",
                    type = CartItemType.ProductItem("prod-cambio"),
                    name = "Cuenta Testarudo",
                    unitPrice = 54450,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 55000)
        advanceUntilIdle()

        val success = viewModel.state.value as PaymentFlowState.Success
        assertEquals("el cajero devuelve 550.00 - 544.50", 550, success.changeAmount)
        assertEquals(54450, success.totalAmount)
    }

    /**
     * P1 — EL TICKET IMPRIME EL BILLETE REAL, NO UNA RESTA AL REVÉS.
     *
     * El recibo deducía el recibido como `total + cambio`. Con el cambio en 0 (defecto
     * de arriba) eso imprimía el total como si fuera el billete que entregó el cliente.
     * El dato verdadero ya lo tenía la app guardado desde que el cajero lo tecleó.
     *
     * Se fija además el invariante que hace que el ticket cuadre consigo mismo:
     * **Recibido − Cambio = TOTAL**.
     */
    @Test
    fun `P1 el ticket imprime el efectivo recibido y el cambio reales`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-ticket")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "payment-ticket",
                receiptAccessKey = null,
                recordedAmountCents = 54450,
                recordedTipCents = 0,
                authoritativeChangeCents = 0,
                remainingBalanceCents = 0,
                orderPaymentStatus = "PAID",
            ),
        )
        val printedReceipt = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(printedReceipt)) } returns Unit

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-ticket",
                    type = CartItemType.ProductItem("prod-ticket"),
                    name = "Cuenta Testarudo",
                    unitPrice = 54450,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 55000)
        advanceUntilIdle()

        val recibo = printedReceipt.captured
        assertEquals("recibido = el billete que entregó el cliente", 55000, recibo.cashTendered)
        assertEquals(550, recibo.changeAmount)
        assertEquals(
            "el ticket tiene que cuadrar consigo mismo",
            recibo.total,
            recibo.cashTendered!! - recibo.changeAmount!!,
        )
    }

    /**
     * 🔴 UNA VENTA EN CERO NO MUEVE EFECTIVO, ASÍ QUE NO ENTRA AL CAJÓN.
     *
     * Es la misma regla que el server ya aplica y documenta
     * (`shared/cashDrawerPosting.postCashSaleToDrawer`: *"Un cobro en $0 (cuenta
     * cortesiada al 100%) es una venta legítima que NO movió efectivo: un movimiento
     * de caja en cero sólo ensucia el listado del corte"*, y devuelve
     * `NOT_DRAWER_CASH`). Escribirla del lado del cliente creaba una fila que **ninguna
     * confirmación puede limpiar nunca**, porque el gemelo del server no va a existir.
     *
     * Y de paso cierra la asimetría del camino encolado del cobro rápido: ahí
     * `recordCashSale` vive FUERA del `if (cart != null)` que encola, así que sin
     * carrito la fila entraba al cajón sin nadie en la cola que la respaldara. Sin
     * carrito el total es forzosamente 0 —`currentBaseAmount()` se apoya en
     * `cartState`/`splitBaseAmountOverride`, y los dos nacen del carrito—, o sea que
     * con esta regla los dos guards coinciden POR CONSTRUCCIÓN y no por casualidad:
     * la única fila que el `if` dejaba pasar sin cola es exactamente la que ahora no se
     * escribe.
     */
    @Test
    fun `una venta en CERO no entra al arqueo de efectivo`() = runTest {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-1",
                    type = CartItemType.CustomAmount,
                    name = "Cortesía 100%",
                    unitPrice = 0,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart)
        viewModel.confirmCashPreset(tenderedCents = 0)
        advanceUntilIdle()

        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any()) }
    }

    @Test
    fun `el cliente elegido en el carrito nace con la orden`() = runTest {
        // 🔴 El cajero elegía "Juan Perez" en el encabezado del carrito, cobraba,
        // y la orden se creaba SIN customerId: venta anonima en el server (sin
        // historial, sin lealtad, sin a quien facturar) y la pantalla de recibo
        // le volvia a ofrecer "Agregar cliente". El ticket salia perfecto, asi
        // que nadie se enteraba.
        val customerIdSlot = slot<String>()
        coEvery {
            orderRepository.createOrder(any(), any(), capture(customerIdSlot), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1")),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-product",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Hamburguesa de Pollo",
                    unitPrice = 11900,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart, customerId = "cus_juan_perez", customerName = "Juan Perez")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("cus_juan_perez", customerIdSlot.captured)
        // Y la pantalla de recibo lo muestra puesto, en vez de pedirlo otra vez.
        assertEquals("Juan Perez", viewModel.attachedCustomerName.value)
    }

    @Test
    fun `una venta nueva no arrastra al cliente de la anterior`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1")),
        )

        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "line-product",
                    type = CartItemType.ProductItem("prod-1"),
                    name = "Hamburguesa",
                    unitPrice = 11900,
                ),
            ),
        )

        viewModel.startPaymentFlow(cart, customerId = "cus_juan_perez", customerName = "Juan Perez")
        assertEquals("Juan Perez", viewModel.attachedCustomerName.value)

        // Siguiente cliente en la fila: la sesion arranca limpia. Cobrarle a uno
        // a nombre de otro es peor que no tener cliente.
        viewModel.startPaymentFlow(cart)
        assertNull(viewModel.attachedCustomerName.value)
    }
}
