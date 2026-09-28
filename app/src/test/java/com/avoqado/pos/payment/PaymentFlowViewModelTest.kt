package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.model.ObjetivoDeLaDeclaracion
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.ReplayDeComandasPendientes
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.EntregaPorWifi
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutOrder
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
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
import com.avoqado.pos.printing.routing.ProductOverride
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.domain.TenderTypeOption
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter

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
    /** Etapa 3 del KDS (3.4): la cola donde el despachador deja la marca del papel de respaldo. */
    private val colaDeMarcas = mockk<SyncOutbox>(relaxed = true)
    /**
     * Etapa 3 del KDS (3.5): la pantalla de Barra ACUSA — sin acuse D6 la sacaría de respaldo y la marcaría. Lo que
     * estas pruebas miden es el reparto del ViewModel, no D6 (D6 vive en `ComandaDispatcherTest`). Una prueba que
     * simula que la pantalla NO contesta lo re-stubbea (`P1 venta en efectivo SIN RED`).
     */
    private val entregaPorWifi = mockk<EntregaPorWifi>(relaxed = true) {
        coEvery { entregar(any(), any()) } returns setOf("st_barra")
        every { deviceId } returns "tablet-1"
    }
    /** El MISMO dispatcher que ve el ViewModel y el reintento periódico — ver más abajo. */
    private val comandaDispatcherReal by lazy {
        ComandaDispatcher(
            printConfigRepository,
            ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
            colaDeMarcas,
            entregaPorWifi,
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
        // Desde el 25-sep la tarjeta pregunta por el pendiente de ESTA venta y avisa de los de las demás. Sin pendientes,
        // salvo donde la prueba diga: un mock relajado devolvería "" como si hubiera uno.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns emptyList()
        // H6: el lote de la revisión de fondo lo elige el servicio (rota por turnos). Aquí, los más nuevos, como antes; la
        // rotación de verdad se prueba con el servicio REAL (`AvisoDuraderoDeOtrasVentasTest`).
        every { terminalPaymentService.loteDeRevision(any(), any()) } answers {
            firstArg<List<ContextoDeCobro>>().take(secondArg())
        }

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
        // La venta arranca SIN cobro pendiente propio, como en producción. Explícito a propósito: es
        // una entrada del camino del dinero y no puede depender del valor por defecto de un mock relajado.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
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

        // La referencia queda en la lista DURABLE: la próxima venta se topa con ella
        // y el cajero puede resolverla desde "Cobro sin confirmar".
        // 🔴 `aunSiFueDeclarado = true`: el cobro SÍ pasó. Un éxito tardío manda sobre una
        // declaración ya aceptada — si no, la app silenciaría dinero real (P2 de Codex, 20-sep).
        verify {
            terminalPaymentService.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-tarde"), aunSiFueDeclarado = true)
        }
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

        // Sigue sin saberse: NO se pisa una declaración aceptada con un desenlace incierto.
        verify {
            terminalPaymentService.aplicarDesenlaceTardio(
                "req-1", CardChargeOutcome.Undetermined("No pudimos confirmar el cobro."), aunSiFueDeclarado = false,
            )
        }
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

        // Consta que no hubo cargo (y el servicio ya soltó su entrada al contestar): nada que re-armar.
        verify(exactly = 0) { terminalPaymentService.aplicarDesenlaceTardio(any(), any(), any()) }
        assertFalse(
            "una cancelación limpia no deja pantalla de cobro sin confirmar",
            viewModel.state.value is PaymentFlowState.Undetermined,
        )
    }

    @Test
    fun `un desenlace tardio sólo toca SU cobro y conserva la entrada del que el cajero mando despues`() = runTest {
        // La venta ya avanzó a otra cosa: hay un cobro POSTERIOR sin confirmar. 🔴 Desde el 25-sep
        // cada cobro conserva su entrada: el rezagado deja la SUYA (con dinero encima) y no toca
        // la del otro. Antes la ranura era una sola y el rezagado se quedaba sin lugar.
        stubOrderCreation()
        terminalRespondsLate(TerminalPaymentResult.Success(paymentId = "pay-viejo", requestId = "req-viejo"))
        // La venta arranca sin pendiente propio (si no, la tarjeta ni siquiera saldría) y el otro cobro
        // aparece mientras el rezagado sigue en vuelo.
        var armada: String? = null
        every { terminalPaymentService.pendienteDeLaVenta(any()) } answers { armada }

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceTimeBy(1_000)
        armada = "req-nuevo" // el cajero ya mandó OTRO cobro y ése quedó sin confirmar
        viewModel.cancel()
        advanceUntilIdle()

        verify {
            terminalPaymentService.aplicarDesenlaceTardio("req-viejo", CardChargeOutcome.Charged("pay-viejo"), aunSiFueDeclarado = true)
        }
        verify(exactly = 0) { terminalPaymentService.aplicarDesenlaceTardio("req-nuevo", any(), any()) }
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
    fun `un cobro sin resolver de ESTA venta frena su TARJETA, nunca el efectivo`() = runTest {
        // 🔴 El agujero que esta prueba cierra: el cajero ve "Cobro sin confirmar", se va a
        // Transacciones a comprobar si el pago entró, vuelve y cobra — pantalla nueva, cero
        // advertencia, SEGUNDO CARGO. La llave vive en DISCO justo para eso, y sigue vigente.
        //
        // 🔴 Lo que cambió el 21-sep (founder, viéndolo en la D3): antes esto cortaba la venta
        // ENTERA y el negocio no podía cobrar ni en efectivo. Un cargo de tarjeta no se duplica
        // con efectivo: son instrumentos distintos. Se bloquea lo que de verdad puede duplicarlo.
        //
        // 🔴 Y el 25-sep (founder): sólo espera la MISMA venta. El pendiente de otra venta avisa y no frena.
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-1"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        // La caja NO se cierra: se puede llegar a elegir cómo cobrar.
        assertFalse(
            "el pendiente no puede dejar al negocio sin vender (fue ${viewModel.state.value})",
            viewModel.state.value is PaymentFlowState.Undetermined,
        )

        // Pero mandar ESTA venta a una terminal sí obliga a resolver su cobro pendiente.
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue("con TARJETA sí se topa con el cobro pendiente", state is PaymentFlowState.Undetermined)
        // Controlador (26-sep): no lo mandó ESTE flujo ⇒ se revisa sin adoptarlo, y se dice de quién es.
        assertTrue("no lo mandó ESTE flujo: se revisa", (state as PaymentFlowState.Undetermined).fromPreviousSale)
        assertEquals("Quedó un cobro sin confirmar de esta venta. ${CardChargeDecision.UNDETERMINED_MESSAGE}", state.message)
        // Y nadie cobró nada por el camino.
        coVerify(exactly = 0) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `confirmar el cobro de la venta ANTERIOR no marca como pagada la venta actual`() = runTest {
        // Distinción crítica: la llave pendiente era de otra venta. Que aquel cobro sí haya
        // pasado NO paga la venta que el cajero tiene ahora en el carrito.
        // 🔴 Desde el 25-sep ese pendiente ya no frena la tarjeta: se llega a él con «Revisar», desde el aviso
        // de la selección de terminal.
        stubOrderCreation()
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("req-vieja", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("req-vieja") } returnsMany listOf(
            // La revisión de fondo todavía no sabe nada: el aviso ofrece «Revisar».
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "req-vieja"),
            TerminalPaymentResult.Success(paymentId = "pay-vieja"),
        )

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
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
        var pendientes: String? = null
        every { secureStorage.accessToken } returns "token"
        every { secureStorage.pendingCardChargesJson } answers { pendientes }
        every { secureStorage.persistPendingCardCharge(any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg()); true
        }
        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg(), cobrado = true, cobradoEn = arg(3)); true
        }
        every { secureStorage.removePendingCardCharge(any()) } answers {
            pendientes = PendientesDeTarjeta.quitar(pendientes, firstArg())
        }
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
        every { terminalPaymentService.pendienteDeLaVenta(any()) } answers { realService.pendienteDeLaVenta(firstArg()) }
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } answers { realService.pendientesDeOtrasVentas(firstArg()) }
        every { terminalPaymentService.aplicarDesenlaceTardio(any(), any(), any()) } answers {
            realService.aplicarDesenlaceTardio(firstArg(), secondArg(), thirdArg())
        }
        every { terminalPaymentService.reconocerCobro(any()) } answers { realService.reconocerCobro(firstArg()) }
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
            // 🔴 25-sep: el pendiente de la venta A ya no frena la tarjeta de B. Elegirla lanza la revisión de fondo
            // (su GET es EL ÚNICO) y «Revisar» se une a esa misma consulta. Sólo `runCurrent`: avanzar el reloj
            // vencería los plazos del dinero mientras el HTTP real espera.
            viewModel.selectPaymentMethod(PaymentMethod.CARD)
            runCurrent()
            assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
            viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
            runCurrent()
            assertTrue(getStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            releasePost.countDown()
            // Pump only ready work; never advance virtual financial deadlines while real HTTP waits.
            repeat(20) { Thread.sleep(10); runCurrent() }
            releaseGet.countDown()
            repeat(50) { Thread.sleep(10); runCurrent() }
            assertEquals(1, gets.get())
            assertNotNull(viewModel.previousChargeResolved.value)
            // H2 (26-sep): el cobro probado de la venta A queda MARCADO —no vuelve a la duda— hasta su «Entendido».
            assertEquals(listOf(true), PendientesDeTarjeta.leer(pendientes).map { it.cobrado })
            viewModel.reconocerCobroAnteriorResuelto()
            assertTrue(PendientesDeTarjeta.leer(pendientes).isEmpty())
            assertFalse(viewModel.state.value is PaymentFlowState.Success)
        } finally {
            releasePost.countDown()
            releaseGet.countDown()
            server.shutdown()
        }
    }

    @Test
    fun `P1 un pendiente de ESTA orden que frena el envio con el MISMO importe tampoco se adopta`() = runTest {
        // 🔴 Founder, 25-sep: la guarda del servicio frena sólo el pendiente de ESTA venta (la orden `order-1` que
        // crea `stubOrderCreation`), así que el pendiente se siembra para ella y la orden viaja tal cual. La orden
        // nace DESPUÉS de elegir tarjeta, así que la puerta de la pantalla no lo ve: lo frena el servicio
        // (`inherited`). Nunca «de otra venta». Y controlador (26-sep): aunque registró el MISMO importe ($15.00), no lo
        // mandó ESTE flujo ⇒ se revisa: si pasó, se dice, sin dar por pagada esta venta.
        stubOrderCreation()
        val realGuard = TerminalPaymentService(secureStorage, okhttp3.OkHttpClient())
        every { secureStorage.pendingCardChargesJson } returns PendientesDeTarjeta.agregar(
            null, "old-request", "order-1", """{"requestId":"old-request","orderId":"order-1","amountCents":1500,"tipCents":0}""",
        )
        every { terminalPaymentService.pendienteDeLaVenta(any()) } answers { realGuard.pendienteDeLaVenta(firstArg()) }
        // El importe registrado queda a la vista: ni así se adopta.
        every { terminalPaymentService.contextoDe(any()) } answers { realGuard.contextoDe(firstArg()) }
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } coAnswers {
            realGuard.sendPaymentToTerminal("t1", 1500, orderId = arg<String?>(4))
        }
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns TerminalPaymentResult.Success(paymentId = "old-payment")
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue("es de ESTA orden, pero no lo mandó este flujo: se revisa", espera.fromPreviousSale)
        assertEquals("Quedó un cobro sin confirmar de esta venta. ${CardChargeDecision.UNDETERMINED_MESSAGE}", espera.message)
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
    }

    @Test
    fun `two recovery callbacks share one current cycle`() = runTest {
        // 25-sep: la tarjeta sólo espera el pendiente de ESTA venta, así que la doble consulta se prueba sobre él. Sin
        // importe registrado no se adopta (controlador, 26-sep): se revisa, y el desenlace se anuncia sin pagar esta venta.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "pending"
        val result = CompletableDeferred<TerminalPaymentResult>()
        coEvery { terminalPaymentService.resolveOutcome("pending") } coAnswers { result.await() }
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        // 🔴 La puerta del pendiente ya no es arrancar la venta —el efectivo dejó de bloquearse
        // (founder, 21-sep)—: es elegir TARJETA, que es lo único que puede duplicar el cargo.
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
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
        // 25-sep: el pendiente de la venta anterior no frena la tarjeta de ésta; se abre con «Revisar».
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("old-request", "orden-vieja", haceMin = 1))
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returnsMany listOf(
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "old-request"),
            TerminalPaymentResult.Success(paymentId = "old-payment"),
        )
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.cancel()
        viewModel.startPaymentFlow(cardCart().copy(items = cardCart().items.map { it.copy(id = "other-line", unitPrice = 9900) }))
        advanceUntilIdle()
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
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
        // El cobro se aplicó: el dinero manda sobre la declaración.
        verify {
            terminalPaymentService.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("old-payment"), aunSiFueDeclarado = true)
        }
    }

    @Test
    fun `inherited ACTIVE recovery preserves terminal confirmation instruction`() = runTest {
        // 25-sep: el cobro de otra venta se abre con «Revisar» desde el aviso; la pantalla de hoy conserva la instrucción.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("old-request", "orden-vieja", haceMin = 1))
        val instruction = "El cobro sigue activo. Confirma en la terminal antes de intentar otro cobro."
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns TerminalPaymentResult.Undetermined(instruction, "old-request")
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
        advanceUntilIdle()
        val state = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(state.fromPreviousSale)
        assertTrue(state.message.contains(instruction))
    }

    @Test
    fun `el aviso de la venta anterior no repite la frase del cobro sin confirmar`() = runTest {
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("old-request", "orden-vieja", haceMin = 1))
        coEvery { terminalPaymentService.resolveOutcome("old-request") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "old-request")
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
        advanceUntilIdle()
        val state = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(state.fromPreviousSale)
        // 25-sep: «de otra venta», el mismo vocabulario que el aviso que llevó hasta aquí.
        assertTrue(state.message, state.message.startsWith("Quedó un cobro sin confirmar de otra venta."))
        val veces = Regex(Regex.escape(CardChargeDecision.UNDETERMINED_MESSAGE)).findAll(state.message).count()
        assertEquals("la instrucción se lee UNA vez: ${state.message}", 1, veces)
    }

    @Test
    fun `cobrar de todos modos conserva la llave y no permite otra autorizacion`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-1"

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
        coVerify(exactly = 1) { printConfigRepository.refreshConTope("venue-1", any()) }
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

    /** Cobra [cartConUnProducto] con lo que elija [elegir] y devuelve el recibo que llegó a la impresora. */
    private fun TestScope.reciboDelCobro(elegir: () -> Unit): ReceiptData {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-recibo")))
        // 🔴 Los OCHO argumentos: con seis, Kotlin rellena `manualMethod` y `tenderType` con su default
        // (null) y el stub sólo atrapa el efectivo — la transferencia y el catálogo caerían al mock relajado.
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-recibo", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        viewModel.startPaymentFlow(cartConUnProducto())
        elegir()
        advanceUntilIdle()
        return impreso.captured
    }

    @Test
    fun `efectivo imprime Efectivo con el billete recibido`() = runTest {
        val recibo = reciboDelCobro { viewModel.confirmCashCustom(2000) }
        assertEquals("Efectivo", recibo.paymentMethod)
        assertEquals(2000, recibo.cashTendered)
        assertEquals("pay-recibo", recibo.transactionId)
    }

    @Test
    fun `un metodo manual fijo imprime su nombre y sin billete recibido`() = runTest {
        val recibo = reciboDelCobro { viewModel.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER)) }
        coVerify { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), ManualPaymentMethod.TRANSFER, null) }
        assertEquals("Transferencia", recibo.paymentMethod)
        assertNull(recibo.cashTendered)
        assertEquals("pay-recibo", recibo.transactionId)
    }

    @Test
    fun `P1 un metodo del catalogo del negocio imprime SU nombre - no Efectivo con Recibido`() = runTest {
        val uber = TenderTypeOption(
            id = "t-uber", revision = 1, name = "Uber Eats", isSystem = false,
            baseMethod = "OTHER", captureTip = false, posSection = "PRIMARY", displayOrder = 1,
        )
        val recibo = reciboDelCobro { viewModel.confirmManualChoice(ManualPaymentChoice.Tender(uber)) }
        coVerify { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), null, uber) }
        assertEquals("Uber Eats", recibo.paymentMethod)
        assertNull(recibo.cashTendered)
        assertFalse(recibo.isCashPayment)
        assertEquals("pay-recibo", recibo.transactionId)
    }

    // MARK: - Cajón de dinero (Sunmi D3 + NC010, 2026-09-17)

    private fun conImpresoraDeTickets(autoOpenCashDrawer: Boolean = true): SavedPrinter {
        val integrada = SavedPrinter(
            name = "Impresora integrada",
            connectionType = "internal",
            address = "internal",
            autoOpenCashDrawer = autoOpenCashDrawer,
        )
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns integrada
        coEvery { printerService.openCashDrawer(any()) } returns Unit
        return integrada
    }

    private fun cartConImporteTecleado() = CartState(
        items = listOf(
            CartItem(id = "custom-1", type = CartItemType.CustomAmount, name = "Importe personalizado", unitPrice = 1000),
        ),
    )

    /** El defecto medido en la D3: el importe tecleado cobrado en efectivo no abría el cajón. */
    @Test
    fun `efectivo de un importe tecleado abre el cajon`() = runTest {
        val integrada = conImpresoraDeTickets()

        viewModel.startPaymentFlow(cartConImporteTecleado())
        viewModel.confirmCashCustom(2000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        coVerify(exactly = 1) { printerService.openCashDrawer(integrada) }
    }

    /** Sin red el cajón también se abre: el pulso sale del aparato, no del servidor. */
    @Test
    fun `efectivo de un importe tecleado sin red abre el cajon`() = runTest {
        val integrada = conImpresoraDeTickets()
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))

        viewModel.startPaymentFlow(cartConImporteTecleado())
        viewModel.confirmCashCustom(2000)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue(estado is PaymentFlowState.Success && estado.isQueued)
        coVerify(exactly = 1) { printerService.openCashDrawer(integrada) }
    }

    @Test
    fun `efectivo con productos abre el cajon`() = runTest {
        val integrada = conImpresoraDeTickets()
        reciboDelCobro { viewModel.confirmCashCustom(2000) }
        coVerify(exactly = 1) { printerService.openCashDrawer(integrada) }
    }

    // MARK: - Etapa 3 del KDS (fase 3.3): la comanda de pantalla la arma el SERVIDOR al cobrar

    @Test
    fun `P1 un cobro con productos ya no crea la comanda de pantalla desde la app y el ticket sale igual`() = runTest {
        val recibo = reciboDelCobro { viewModel.confirmCashCustom(2000) }

        assertEquals("el ticket sale como siempre", "Efectivo", recibo.paymentMethod)
        coVerify(exactly = 0) { kdsRepository.createOrder(any(), any(), any(), any()) }
        coVerify(exactly = 0) { kdsOrderBus.publish(any()) }
    }

    // MARK: - Etapa 3 del KDS (fase 3.4): la caja decide por estación

    private val configConBarraSoloPantalla = PrintConfig(
        stations = listOf(
            StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true),
            StationInfo(id = "st_barra", name = "Barra", printerId = null, active = true, hasKitchenDisplay = true),
        ),
        productOverrides = listOf(ProductOverride(productId = "prod-cafe", printStationId = "st_barra")),
        defaultStationId = "st_cocina",
    )
    private val cartConCafe = CartState(
        items = listOf(CartItem(id = "line-cafe", type = CartItemType.ProductItem("prod-cafe"), name = "Café", unitPrice = 3000)),
    )

    @Test
    fun `P1 venta en efectivo EN LINEA - la estacion solo pantalla no se imprime ni se marca`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-en-linea")))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-en-linea", receiptAccessKey = null))
        every { printConfigRepository.getCurrentConfig() } returns configConBarraSoloPantalla

        viewModel.startPaymentFlow(cartConCafe)
        viewModel.confirmCashCustom(3000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { colaDeMarcas.enqueue(any(), "KDS_TICKET_MARK", any(), any(), any()) }
    }

    @Test
    fun `P1 venta en efectivo SIN RED - respaldo en papel y marca con el externalId de la orden`() = runTest {
        // Etapa 3 del KDS (3.5, D6): el papel lo decide el ACUSE, no el internet. Aquí la pantalla tampoco contesta por
        // el WiFi del local, que es el caso en que la caja imprime el respaldo.
        coEvery { entregaPorWifi.entregar(any(), any()) } returns emptySet()
        val llave = slot<String>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), capture(llave)) } returns
            Result.failure(java.net.UnknownHostException("sin red"))
        every { printConfigRepository.getCurrentConfig() } returns configConBarraSoloPantalla
        val planes = slot<List<TicketPlan>>()
        coEvery { comandaPrinter.printComandas(capture(planes), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        val marca = slot<JsonObject>()
        coEvery { colaDeMarcas.enqueue("venue-1", "KDS_TICKET_MARK", capture(marca), any(), any()) } returns "m-1"

        viewModel.startPaymentFlow(cartConCafe)
        viewModel.confirmCashCustom(3000)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue(estado is PaymentFlowState.Success && estado.isQueued)
        assertEquals(listOf("st_barra"), planes.captured.map { it.stationId })
        assertEquals("sale:${llave.captured}:st_barra", marca.captured["sourceKey"]!!.jsonPrimitive.content)

        // m-6 de la revisión final: el folio es POR VENTA. Una segunda venta que NO crea orden (retoma "order-2") y
        // cuyo efectivo también se encola no puede heredar el folio de la anterior: su marca escondería la comanda
        // de OTRA venta en la pantalla. Sin folio propio, el papel sale igual pero sin marca.
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.UnknownHostException("sin red"))
        viewModel.startPaymentFlow(cartConCafe, resumeOrderId = "order-2")
        viewModel.confirmCashCustom(3000)
        advanceUntilIdle()

        val segunda = viewModel.state.value
        assertTrue(segunda is PaymentFlowState.Success && segunda.isQueued)
        coVerify(exactly = 2) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { colaDeMarcas.enqueue(any(), "KDS_TICKET_MARK", any(), any(), any()) }
    }

    @Test
    fun `un pago declarado como transferencia no abre el cajon`() = runTest {
        conImpresoraDeTickets()
        reciboDelCobro { viewModel.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER)) }
        coVerify(exactly = 0) { printerService.openCashDrawer(any()) }
    }

    @Test
    fun `un importe tecleado declarado como transferencia no abre el cajon`() = runTest {
        conImpresoraDeTickets()
        // Los OCHO argumentos: el stub de setup() sólo atrapa manualMethod = null.
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "fast-transfer", receiptAccessKey = null))
        viewModel.startPaymentFlow(cartConImporteTecleado())
        viewModel.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        coVerify(exactly = 0) { printerService.openCashDrawer(any()) }
    }

    @Test
    fun `con el cajon automatico apagado el efectivo no lo abre`() = runTest {
        conImpresoraDeTickets(autoOpenCashDrawer = false)
        viewModel.startPaymentFlow(cartConImporteTecleado())
        viewModel.confirmCashCustom(2000)
        advanceUntilIdle()
        coVerify(exactly = 0) { printerService.openCashDrawer(any()) }
    }

    /** Un cajón que falla nunca tumba un cobro que ya ocurrió. */
    @Test
    fun `si el cajon falla el cobro sigue siendo exitoso`() = runTest {
        conImpresoraDeTickets()
        coEvery { printerService.openCashDrawer(any()) } throws RuntimeException("impresora sin respuesta")

        viewModel.startPaymentFlow(cartConImporteTecleado())
        viewModel.confirmCashCustom(2000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
    }

    /**
     * Ronda de arreglo 1 (revisión de Task 10): un cobro con TARJETA heredaba el método
     * manual/catálogo de la venta ANTERIOR porque `startPaymentFlow` nunca limpiaba
     * `manualMethod`/`selectedTender` — el ViewModel sobrevive a más de una venta (split, o
     * dos ventas seguidas del mismo turno). Cobra la venta 1 con un tipo del catálogo y,
     * SIN volver a pasar por `confirmCashCustom`/`confirmManualChoice`, cobra la venta 2 con
     * tarjeta: el ticket debe decir «Tarjeta» y conservar la marca y los últimos 4 que la
     * terminal sí entregó — las dos mitades importan, la segunda es la consecuencia nueva
     * que esta tarea introdujo al leer `lastCardBrand`/`lastCardLastFour` sólo cuando
     * `manualMethodLabel == null`.
     */
    @Test
    fun `P1 tarjeta despues de un metodo del catalogo NO hereda el metodo de la venta anterior`() = runTest {
        // Venta 1: un tipo del catálogo, sin llegar a ella con `advanceUntilIdle` del helper
        // (no importa su recibo, sólo que `manualMethod`/`selectedTender` quedaron puestos).
        val uber = TenderTypeOption(
            id = "t-uber", revision = 1, name = "Uber Eats", isSystem = false,
            baseMethod = "OTHER", captureTip = false, posSection = "PRIMARY", displayOrder = 1,
        )
        reciboDelCobro { viewModel.confirmManualChoice(ManualPaymentChoice.Tender(uber)) }

        // Venta 2, MISMO ViewModel: tarjeta directa, sin tocar `confirmCash*`/`confirmManualChoice`.
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-tarjeta")))
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Success(paymentId = "pay-tarjeta", cardBrand = "VISA", cardLastFour = "4242")
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        viewModel.startPaymentFlow(cartConUnProducto())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        val recibo = impreso.captured
        assertEquals("Tarjeta", recibo.paymentMethod)
        assertEquals("VISA", recibo.cardBrand)
        assertEquals("4242", recibo.cardLastFour)
        assertNull(recibo.cashTendered)
    }

    /**
     * Arreglo final (I1, revisión de conjunto de la Fase 3): «Atendió: X» = el vendedor
     * elegido en «Vendiendo» cuando lo hay, con respaldo a quien inició sesión — decisión de
     * producto ya tomada. Antes `buildReceiptSnapshot` no ponía `cashierName` en absoluto, así
     * que el bloque `staff` de la receta salía vacío en Android para toda venta de mostrador,
     * aunque el iPad SÍ lo imprimiera para la misma venta.
     */
    @Test
    fun `P1 el ticket imprime a quien atendio - el vendedor elegido en Vendiendo`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-atendio")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-atendio", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        val carritoConVendedor = CartState(
            items = listOf(CartItem(id = "line-1", type = CartItemType.ProductItem("prod-1"), name = "Café", unitPrice = 1000)),
            selectedStaffId = "staff-ana",
            selectedStaffName = "Ana",
        )
        viewModel.startPaymentFlow(carritoConVendedor)
        viewModel.confirmCashCustom(2000)
        advanceUntilIdle()

        assertEquals("Ana", impreso.captured.cashierName)
    }

    @Test
    fun `P1 imprimir otra vez desde la pantalla de exito conserva la fecha de la venta`() = runTest {
        val primero = reciboDelCobro { viewModel.confirmCashCustom(1000) }
        val otraVez = slot<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(otraVez)) } returns PrinterService.PrintOutcome.Printed(1)
        Thread.sleep(5) // un `Date()` nuevo ya sería distinto
        viewModel.reprintReceipt()
        advanceUntilIdle()
        assertEquals(primero.date, otraVez.captured.date)
    }

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

    /**
     * P1 · La base de la propina NUNCA lleva IVA — decision del founder, 2026-09-18.
     *
     * El ajuste `includeTaxInTipBase` se retiro: era una convencion fiscal de EE.UU. (alla el
     * impuesto se suma aparte y el cliente lo ve), no de Mexico, donde el precio en pantalla ya
     * lo incluye. Fudo, el POS nativo de LatAm, ni siquiera ofrece esa opcion. Ademas se guardaba
     * solo en la memoria del aparato: dos cajas del mismo negocio podian sugerir propinas
     * distintas y el dashboard no lo veia.
     *
     * Esta prueba es el candado: aunque llegue un ajuste viejo en `true` —de un cache en disco de
     * una version anterior, o del servidor algun dia— la base se calcula SIN IVA.
     */
    @Test
    fun `P1 la base de propina excluye el IVA aunque venga el ajuste viejo encendido`() {
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

        assertEquals(1000, viewModel.currentTipPercentageBaseCents())
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
    // ══════════════════════════════════════════════════════════════════════════════════════
    // La declaración del cajero: «ya revisé la terminal: no se cobró» (etapa 2, 19-sep).
    //
    // 🔴 Es ONLINE-ONLY A PROPÓSITO, y ése es el corazón de estas pruebas. Una declaración
    // encolada y reproducida tarde caería sobre una venta que entretanto SÍ se cobró: el cajero
    // afirma lo que ve AHORA en la pantalla de la terminal, y esa afirmación caduca. Por eso sin
    // red no se guarda nada y se pide de nuevo.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /** Deja al ViewModel exactamente en «Cobro sin confirmar», que es donde vive el botón. */
    private suspend fun TestScope.enCobroSinConfirmar(requestId: String = "req-1") {
        stubOrderCreation()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", requestId)
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertTrue("montaje: debe quedar en Undetermined", viewModel.state.value is PaymentFlowState.Undetermined)
    }

    @Test
    fun `declarar no cobrado libera la venta y deja volver a cobrar`() = runTest {
        enCobroSinConfirmar()
        coEvery { terminalPaymentService.declararNoCobrado("req-1") } returns ResultadoDeDeclaracion.Liberada

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado("req-1") }
        // Ya no hay cobro sin confirmar: el cajero puede cobrar otra vez, que es todo el punto.
        assertTrue(
            "tras liberar se vuelve a elegir terminal, no se queda en la pantalla honesta",
            viewModel.state.value is PaymentFlowState.SelectingTerminal,
        )
    }

    @Test
    fun `sin conexion la declaracion NO se encola y se pide de nuevo`() = runTest {
        enCobroSinConfirmar()
        coEvery { terminalPaymentService.declararNoCobrado("req-1") } returns ResultadoDeDeclaracion.SinConexion

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        // 🔴 El cobro sin confirmar SIGUE ahí. Una declaración reproducida tarde podría caer sobre
        // una venta que entretanto sí se cobró: es online-only a propósito.
        val estado = viewModel.state.value
        assertTrue("el cobro sin confirmar NO se resuelve sin red", estado is PaymentFlowState.Undetermined)
        assertEquals(CancelacionDeCobro.DECLARACION_SIN_RED, (estado as PaymentFlowState.Undetermined).message)
    }

    @Test
    fun `un rechazo por evidencia positiva NO libera y muestra el mensaje del SERVIDOR`() = runTest {
        enCobroSinConfirmar()
        val delServidor = "Este cobro sí tiene señales de haber pasado. No lo declares: consulta su resultado."
        coEvery { terminalPaymentService.declararNoCobrado("req-1") } returns
            ResultadoDeDeclaracion.Rechazada(mensaje = delServidor, code = "POSITIVE_EVIDENCE_EXISTS")

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue("un rechazo conserva el pendiente", estado is PaymentFlowState.Undetermined)
        // 🔴 El texto lo escribe el SERVIDOR: cada código tiene el suyo y uno local mentiría.
        assertEquals(delServidor, (estado as PaymentFlowState.Undetermined).message)
    }

    @Test
    fun `sin llave del cobro NO se declara nada`() = runTest {
        // Nadie tiene un cobro sin confirmar: declarar aquí escribiría sobre una solicitud ajena.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        coVerify(exactly = 0) { terminalPaymentService.declararNoCobrado(any()) }
    }

    @Test
    fun `la declaracion no dispara ningun cobro`() = runTest {
        enCobroSinConfirmar()
        coEvery { terminalPaymentService.declararNoCobrado("req-1") } returns ResultadoDeDeclaracion.Liberada

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        // 🔴 La regla que gobierna TODA esta pantalla: declarar libera, nunca cobra.
        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // Ronda de Codex sobre la etapa 2 (19-sep). El OBJETIVO de la declaración se congela desde
    // el contexto durable del COBRO PENDIENTE, no del carrito que el cajero tiene enfrente.
    //
    // 🔴 P1 de Codex: queda pendiente A por $100, se abre una venta B por $500, y el diálogo
    // afirmaba «el cobro de $500 no pasó» mientras la declaración iba sobre A. El cajero FIRMA
    // CON SU NOMBRE una afirmación sobre el importe equivocado.
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `el objetivo declara el importe del COBRO PENDIENTE, no el de la venta nueva`() = runTest {
        // El pendiente A: $100.00 + $5.00 de propina. La venta nueva: $15.00. 🔴 Desde el 25-sep el pendiente de
        // otra venta ya no frena la tarjeta: el cajero lo abre con «Revisar» y declara desde ahí.
        val pendienteA = ContextoDeCobro(
            requestId = "req-viejo", venueId = "venue-del-cobro", terminalId = "t9",
            orderId = "orden-a", amountCents = 10_000, tipCents = 500,
        )
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendienteA)
        every { terminalPaymentService.contextoDe("req-viejo") } returns pendienteA
        coEvery { terminalPaymentService.resolveOutcome("req-viejo") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "req-viejo")
        stubOrderCreation()
        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta(viewModel.avisoDeOtroCobro.value!!.requestId)
        advanceUntilIdle()

        val objetivo = viewModel.objetivoDeLaDeclaracion()
        assertEquals("req-viejo", objetivo?.requestId)
        assertEquals(10_500, objetivo?.montoCentavos)
        assertEquals("venue-del-cobro", objetivo?.venueId)
    }

    @Test
    fun `sin contexto del cobro el objetivo NO inventa un importe`() = runTest {
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-sin-contexto"
        every { terminalPaymentService.contextoDe("req-sin-contexto") } returns null
        stubOrderCreation()
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        val objetivo = viewModel.objetivoDeLaDeclaracion()
        assertEquals("req-sin-contexto", objetivo?.requestId)
        // 🔴 `null`, NO el total del carrito: un importe inventado es peor que ninguno.
        assertNull(objetivo?.montoCentavos)
        assertNull(objetivo?.venueId)
    }

    @Test
    fun `la declaracion viaja al venue del COBRO, no al de la sesion`() = runTest {
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-viejo"
        every { terminalPaymentService.contextoDe("req-viejo") } returns ContextoDeCobro(
            requestId = "req-viejo", venueId = "venue-del-cobro", terminalId = "t9",
            orderId = null, amountCents = 10_000, tipCents = 500,
        )
        coEvery { terminalPaymentService.declararNoCobrado(any(), any()) } returns ResultadoDeDeclaracion.Liberada
        stubOrderCreation()
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        // 🔴 Un usuario con acceso a dos sucursales que cambia de venue dejaría el pendiente
        // irrecuperable: el servidor rechaza liberar una fila ajena, y con razón.
        coVerify(exactly = 1) { terminalPaymentService.declararNoCobrado("req-viejo", "venue-del-cobro") }
    }

    @Test
    fun `una respuesta de declaracion NO pisa un flujo que ya se reinicio`() = runTest {
        // 🔴 P2 de Codex: declarar → rotar la pantalla antes de la respuesta → `startPaymentFlow`
        // limpia método y orden → llegaba el `Liberada` viejo y ponía SelectingTerminal sobre un
        // flujo que ya no tenía método: «Método de pago no seleccionado» al elegir terminal.
        enCobroSinConfirmar()
        val puerta = CompletableDeferred<ResultadoDeDeclaracion>()
        coEvery { terminalPaymentService.declararNoCobrado(any(), any()) } coAnswers { puerta.await() }

        viewModel.declararNoCobrado()
        runCurrent()
        // La rotación: el flujo arranca de nuevo y la generación avanza.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        val estadoTrasReiniciar = viewModel.state.value

        puerta.complete(ResultadoDeDeclaracion.Liberada)
        advanceUntilIdle()

        assertEquals(
            "la respuesta vieja no puede gobernar la pantalla nueva",
            estadoTrasReiniciar::class,
            viewModel.state.value::class,
        )
    }

    @Test
    fun `una sesion vencida NO se disfraza de falta de conexion`() = runTest {
        // 🔴 P1 de Codex: el transporte refrescaba el token y REENVIABA la declaración sola. Ahora
        // la sesión se renueva pero el POST no se repite: se le pide al cajero que confirme otra
        // vez. Decir «sin conexión» sería falso — la red funcionó.
        enCobroSinConfirmar()
        coEvery { terminalPaymentService.declararNoCobrado(any(), any()) } returns ResultadoDeDeclaracion.SesionRenovada

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue(estado is PaymentFlowState.Undetermined)
        assertEquals(CancelacionDeCobro.DECLARACION_SESION_RENOVADA, (estado as PaymentFlowState.Undetermined).message)
        assertNotEquals(CancelacionDeCobro.DECLARACION_SIN_RED, estado.message)
    }

    @Test
    fun `tras declarar, un desenlace indeterminado viejo NO vuelve a bloquear la siguiente venta`() = runTest {
        // 🔴 P2 de Codex: el POST original seguía vivo; el cajero declaraba, la llave se soltaba, y
        // llegaba aquel resultado diciendo UNKNOWN → re-armaba la llave → la venta siguiente volvía
        // a bloquearse por el cobro recién liberado. El cajero de vuelta al callejón sin salida.
        //
        // La regla vive en el SERVICIO (es quien posee la llave), así que se comprueba ahí: una vez
        // declarado, `armarLlave` no lo vuelve a poner.
        enCobroSinConfirmar()
        coEvery { terminalPaymentService.declararNoCobrado(any(), any()) } returns ResultadoDeDeclaracion.Liberada
        every { terminalPaymentService.yaDeclaradoSinCobro("req-1") } returns true

        viewModel.declararNoCobrado()
        advanceUntilIdle()

        // Lo declarado queda marcado: es lo que impide que una consulta anterior lo reponga.
        assertTrue(terminalPaymentService.yaDeclaradoSinCobro("req-1"))
        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
    }

    // --- Un cobro pendiente NO puede dejar al negocio sin vender (founder, 21-sep) ---

    @Test
    fun `P1 con un cobro de tarjeta pendiente el EFECTIVO sigue disponible`() = runTest {
        // 🔴 MEDIDO EN LA D3: `startPaymentFlow` cortaba con `return` y se saltaba la pantalla de
        // métodos de pago, así que un cargo de tarjeta sin resolver dejaba al negocio sin poder
        // cobrar NI EN EFECTIVO. Un cargo de tarjeta no se duplica cobrando en efectivo: son
        // instrumentos distintos. Lo único que choca es mandar otra venta a la TERMINAL.
        // 25-sep: ni siquiera el pendiente de ESTA venta —el único que todavía frena la tarjeta— cierra la caja.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-viejo"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        assertFalse(
            "con un cobro de tarjeta pendiente, la caja NO se cierra (fue ${viewModel.state.value})",
            viewModel.state.value is PaymentFlowState.Undetermined,
        )

        // Y no basta con "no bloquea": el efectivo tiene que llegar a cobrar de verdad.
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        advanceUntilIdle()
        assertTrue(
            "el efectivo debe seguir cobrando (fue ${viewModel.state.value})",
            viewModel.state.value is PaymentFlowState.CollectingCashAmount,
        )
    }

    @Test
    fun `P1 resolver el pendiente a media venta desbloquea la TARJETA sin salir`() = runTest {
        // 🔴 MEDIDO EN LA D3 (21-sep): el cajero resolvía el cobro pendiente con «Volver a
        // consultar» —la llave quedaba libre de verdad, el servidor decía CANCELLED/ACCEPTED sin
        // Payment— y al elegir TARJETA volvía a bloquearse, porque el ViewModel guardaba una COPIA
        // de la llave al arrancar la venta. Sólo se destrababa saliendo y empezando otra: la guarda
        // sobrevivía a su propia causa.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-viejo"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()

        // Se resuelve el pendiente MIENTRAS la venta sigue abierta.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null

        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertFalse(
            "resuelto el pendiente, la TARJETA debe abrirse sin salir de la venta (fue $estado)",
            estado is PaymentFlowState.Undetermined,
        )
    }

    @Test
    fun `P1 pero mandar esa venta a la TERMINAL sigue bloqueado`() = runTest {
        // El control que protege el dinero: lo que sí puede duplicar un cargo es otro cargo.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "req-viejo"

        viewModel.startPaymentFlow(cardCart())
        advanceUntilIdle()
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue("elegir TARJETA con un pendiente obliga a resolverlo (fue $estado)", estado is PaymentFlowState.Undetermined)
        // Controlador (26-sep): no lo mandó este flujo ⇒ se revisa; y se dice que es de ESTA venta, no de otra.
        assertTrue((estado as PaymentFlowState.Undetermined).fromPreviousSale)
        assertTrue(estado.message, estado.message.startsWith("Quedó un cobro sin confirmar de esta venta."))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // Founder, 25-sep: sólo la MISMA venta espera. El cobro pendiente de OTRA venta no frena la
    // tarjeta: se avisa en la selección de terminal, y «Revisar» lleva a la pantalla de siempre.
    // ══════════════════════════════════════════════════════════════════════════════════════

    private fun pendiente(id: String, orden: String, haceMin: Long, centavos: Int? = 1500, cobrado: Boolean = false) = ContextoDeCobro(
        requestId = id, venueId = "v", terminalId = "t1", orderId = orden, amountCents = centavos, tipCents = 0,
        desdeMillis = System.currentTimeMillis() - haceMin * 60_000, cobrado = cobrado,
    )

    @Test fun `P1 un cobro pendiente de OTRA venta no frena la tarjeta de esta`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-viejo", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-viejo") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-viejo")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertTrue("la tarjeta de esta venta NO se frena: ${viewModel.state.value}",
            viewModel.state.value is PaymentFlowState.SelectingTerminal)
        val aviso = viewModel.avisoDeOtroCobro.value
        assertNotNull(aviso)
        assertTrue(aviso!!.texto.startsWith("Quedó un cobro de"))
        assertTrue(aviso.texto.contains("en otra venta"))
    }

    @Test fun `P1 un cobro pendiente de ESTA venta sigue esperando`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-de-esta"

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Undetermined)
        // Controlador (26-sep): sigue esperando; como no lo mandó ESTE flujo, se revisa sin adoptarlo.
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(espera.fromPreviousSale)
        assertTrue(espera.message, espera.message.startsWith("Quedó un cobro sin confirmar de esta venta."))
    }

    @Test fun `el cobro de otra venta que SI paso se avisa y no se pierde`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-cobrado", "orden-7", haceMin = 3))
        coEvery { terminalPaymentService.resolveOutcome("r-cobrado") } returns TerminalPaymentResult.Success(paymentId = "pay-7")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val aviso = viewModel.avisoDeOtroCobro.value!!
        assertTrue(aviso.yaCobrado)
        assertTrue(aviso.texto.contains("SÍ pasó"))
    }

    @Test fun `el aviso de otra venta se calla a los 10 min`() = runTest {
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-viejo", "orden-7", haceMin = 11))
        coEvery { terminalPaymentService.resolveOutcome("r-viejo") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-viejo")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertNull(viewModel.avisoDeOtroCobro.value)
        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
    }

    @Test
    fun `P1 el aviso de otra venta sale AL INSTANTE, antes de que la consulta conteste`() = runTest {
        // 🔴 Consultar puede tardar decenas de segundos (3 consultas con pausas por pendiente) y el aviso es la ÚNICA
        // protección contra volver a cobrar la duda de otra venta: si llega después de que el cajero toca la terminal,
        // no sirvió. Sale de la lista guardada, sin red; la consulta sólo lo afina.
        stubOrderCreation()
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-viejo", "orden-7", haceMin = 2))
        val consulta = CompletableDeferred<TerminalPaymentResult>()
        coEvery { terminalPaymentService.resolveOutcome("r-viejo") } coAnswers { consulta.await() }

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        // Ni una vuelta del planificador: el aviso ya está al regresar de elegir tarjeta.
        assertEquals("r-viejo", viewModel.avisoDeOtroCobro.value?.requestId)
        runCurrent()

        val provisional = viewModel.avisoDeOtroCobro.value
        assertNotNull("el aviso no puede esperar a la red", provisional)
        assertEquals("r-viejo", provisional!!.requestId)
        assertFalse(provisional.yaCobrado)
        assertEquals(
            "Quedó un cobro de \$15.00 sin confirmar hace 2 min en otra venta. Si es esta misma venta, no la cobres otra vez.",
            provisional.texto,
        )

        consulta.complete(TerminalPaymentResult.Success(paymentId = "pay-7"))
        advanceUntilIdle()

        // Al contestar, el definitivo manda: un cobro que SÍ pasó se dice.
        assertTrue(viewModel.avisoDeOtroCobro.value!!.yaCobrado)
    }

    @Test
    fun `P1 sin importe conocido el aviso nunca inventa cero pesos`() = runTest {
        // Un pendiente guardado antes del 25-sep (o con el contexto ilegible) no trae importe: decir «$0.00» sería
        // inventar un dato de dinero.
        stubOrderCreation()
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns
            listOf(pendiente("r-sin-importe", "orden-7", haceMin = 2, centavos = null))
        coEvery { terminalPaymentService.resolveOutcome("r-sin-importe") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-sin-importe")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertEquals(
            "Quedó un cobro sin confirmar hace 2 min en otra venta. Si es esta misma venta, no la cobres otra vez.",
            viewModel.avisoDeOtroCobro.value?.texto,
        )
    }

    @Test
    fun `P1 Volver a consultar en la revision de otra venta pregunta por ESE cobro`() = runTest {
        // La revisión habla de un cobro que NO es el pendiente de esta venta: «Volver a consultar» tiene que seguir
        // preguntando por él, no por el de esta venta (que no hay) ni volver al inicio sin consultar nada.
        stubOrderCreation()
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-viejo", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-viejo") } returnsMany listOf(
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-viejo"), // revisión de fondo
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-viejo"), // «Revisar»
            TerminalPaymentResult.Success(paymentId = "pay-7"),                                     // «Volver a consultar»
        )

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        viewModel.revisarCobroDeOtraVenta("r-viejo")
        advanceUntilIdle()
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        coVerify(exactly = 3) { terminalPaymentService.resolveOutcome("r-viejo") }
        assertFalse("el cobro de otra venta no paga ésta", viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
    }

    @Test
    fun `P1 al resolver el pendiente de ESTA venta, la terminal se ofrece CON el aviso de las otras`() = runTest {
        // Toda entrada a la selección de terminal lleva el aviso: también la que llega tras resolver el cobro propio. El
        // propio es el que mandó ESTE flujo (controlador, 26-sep): uno encontrado en la lista se revisaría y cerraría el
        // flujo en vez de ofrecer la terminal.
        stubOrderCreation()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "r-de-esta")
        coEvery { terminalPaymentService.resolveOutcome("r-de-esta") } returns
            TerminalPaymentResult.Error("El cobro fue rechazado. No se cobró la tarjeta.")
        // El pendiente de la otra venta aparece DESPUÉS: el aviso final sólo puede salir de la revisión al volver a ofrecer.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returnsMany listOf(
            emptyList(),
            listOf(pendiente("r-otra", "orden-7", haceMin = 1)),
        )
        coEvery { terminalPaymentService.resolveOutcome("r-otra") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-otra")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PaymentFlowState.Undetermined)
        assertNull("montaje: todavía sin aviso", viewModel.avisoDeOtroCobro.value)
        viewModel.recheckCardCharge() // consta que NO se cobró: ahora sí se ofrece cobrar
        advanceUntilIdle()

        coVerify(exactly = 1) { terminalPaymentService.resolveOutcome("r-de-esta") }
        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
        assertEquals("r-otra", viewModel.avisoDeOtroCobro.value?.requestId)
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // Controlador, 26-sep: el pendiente de ESTA orden se ADOPTA como este cobro sólo si su importe
    // registrado consta y es IGUAL al que se cobra ahora. Si no, la venta sigue esperando (misma
    // cerca), pero se abre como revisión: jamás se da por pagada con el cobro de otra parte.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /** Una cuenta de un solo renglón por [centavos]. */
    private fun cuentaDe(centavos: Int) = CartState(
        items = listOf(CartItem(id = "cuenta", type = CartItemType.ProductItem("prod-1"), name = "Cuenta", unitPrice = centavos)),
    )

    @Test
    fun `P1 el pendiente de ESTA orden con el MISMO importe tampoco se adopta`() = runTest {
        // Misma orden, $15.00 = $15.00, pago completo: aun así no lo mandó ESTE flujo (controlador, 26-sep: los importes no
        // distinguen partes ni intentos). Se revisa; si pasó, se dice, y el servidor no deja cobrar dos veces una orden pagada.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-misma"
        every { terminalPaymentService.contextoDe("r-misma") } returns pendiente("r-misma", "order-1", haceMin = 3)
        coEvery { terminalPaymentService.resolveOutcome("r-misma") } returns TerminalPaymentResult.Success(paymentId = "pay-misma")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue("espera, pero como REVISIÓN", espera.fromPreviousSale)
        assertEquals("Quedó un cobro sin confirmar de esta venta. ${CardChargeDecision.UNDETERMINED_MESSAGE}", espera.message)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse("un cobro que este flujo no mandó jamás da por pagada esta venta", viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 la parte 2 de una cuenta dividida en mostrador no adopta el cobro de la parte 1`() = runTest {
        // Mostrador: la parte 2 llega como pago COMPLETO del saldo ($250) sobre la misma orden, y la parte 1 ($250) quedó
        // sin confirmar. Mismo importe y pago completo: la regla de importes lo habría adoptado. No lo mandó ESTE flujo.
        every { terminalPaymentService.pendienteDeLaVenta("orden-mostrador") } returns "r-parte-1"
        every { terminalPaymentService.contextoDe("r-parte-1") } returns
            pendiente("r-parte-1", "orden-mostrador", haceMin = 3, centavos = 25_000)
        coEvery { terminalPaymentService.resolveOutcome("r-parte-1") } returns TerminalPaymentResult.Success(paymentId = "pay-parte-1")

        viewModel.startPaymentFlow(cuentaDe(25_000), resumeOrderId = "orden-mostrador")
        assertEquals("montaje: la parte 2 se cobra como pago completo", "FULLPAYMENT", viewModel.splitType.value)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue("la parte 2 espera a la 1, pero como REVISIÓN", espera.fromPreviousSale)
        assertEquals("Quedó un cobro sin confirmar de esta venta. ${CardChargeDecision.UNDETERMINED_MESSAGE}", espera.message)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse("la parte 2 no queda pagada con el cobro de la 1", viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 el cobro que mando este flujo sigue siendo suyo al volver a elegir tarjeta`() = runTest {
        // Controlador (26-sep): sólo se adopta lo que ESTE flujo mandó. Su propio POST quedó sin confirmar; al volver a
        // elegir tarjeta la puerta encuentra ESE cobro: sigue siendo suyo (sin encabezado), como antes de este plan.
        stubOrderCreation()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-propio")
        every { terminalPaymentService.pendienteDeLaVenta("order-1") } returns "req-propio"
        coEvery { terminalPaymentService.resolveOutcome("req-propio") } returns
            TerminalPaymentResult.Success(paymentId = "pay-propio-gate", requestId = "req-propio")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertFalse("montaje: su propio cobro sin confirmar", (viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)

        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertFalse("es el cobro que mandó ESTE flujo: sigue siendo suyo", espera.fromPreviousSale)
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, espera.message)

        viewModel.recheckCardCharge()
        advanceUntilIdle()
        assertEquals("su propio cobro paga esta venta", "pay-propio-gate", (viewModel.state.value as PaymentFlowState.Success).paymentId)
        assertNull(viewModel.previousChargeResolved.value)
        coVerify(exactly = 1) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 el servicio frena el reenvio por el cobro de este flujo y sigue siendo suyo`() = runTest {
        // Su propio POST quedó sin confirmar; el cajero vuelve a mandar la MISMA venta y el servicio la frena (`inherited`)
        // por ESE cobro, sin tocar la red. Sigue siendo suyo: si pasó, esta venta queda pagada.
        stubOrderCreation()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            TerminalPaymentResult.Undetermined("No pudimos confirmar el cobro.", "req-propio"),
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "req-propio", inherited = true),
        )
        coEvery { terminalPaymentService.resolveOutcome("req-propio") } returns
            TerminalPaymentResult.Success(paymentId = "pay-propio-reenvio", requestId = "req-propio")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        viewModel.selectTerminalAndPay("t1") // la misma venta otra vez: el servicio la frena por su propio cobro
        advanceUntilIdle()

        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertFalse("el cobro que frena el reenvío es de ESTE flujo: sigue siendo suyo", espera.fromPreviousSale)
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, espera.message)
        viewModel.recheckCardCharge()
        advanceUntilIdle()
        assertEquals("pay-propio-reenvio", (viewModel.state.value as PaymentFlowState.Success).paymentId)
        assertNull(viewModel.previousChargeResolved.value)
    }

    @Test
    fun `P1 el pendiente de ESTA orden con OTRO importe no da por pagada esta parte`() = runTest {
        // Cuenta dividida de $500: la parte A ($300) quedó sin confirmar y ahora se cobra la B ($200) sobre la MISMA
        // orden. Espera igual (misma cerca), pero si A resulta cobrada, B NO queda pagada con ese cobro.
        viewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 20_000)
        every { terminalPaymentService.pendienteDeLaVenta("orden-dividida") } returns "r-parte-a"
        every { terminalPaymentService.contextoDe("r-parte-a") } returns
            pendiente("r-parte-a", "orden-dividida", haceMin = 3, centavos = 30_000)
        coEvery { terminalPaymentService.resolveOutcome("r-parte-a") } returns TerminalPaymentResult.Success(paymentId = "pay-parte-a")

        viewModel.startPaymentFlow(cuentaDe(50_000), resumeOrderId = "orden-dividida")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue("B espera a A (misma orden), pero como REVISIÓN", espera.fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse("jamás dar por pagada esta parte con el cobro de otra", viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 el pendiente de ESTA orden sin importe conocido no se adopta`() = runTest {
        // Un contexto de antes del 25-sep, o ilegible: no consta cuánto cobró, así que no se puede afirmar que sea ESTE cobro.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-sin-importe"
        every { terminalPaymentService.contextoDe("r-sin-importe") } returns
            pendiente("r-sin-importe", "order-1", haceMin = 3, centavos = null)
        coEvery { terminalPaymentService.resolveOutcome("r-sin-importe") } returns TerminalPaymentResult.Success(paymentId = "pay-x")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertTrue((viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
    }

    @Test
    fun `P1 un pendiente de ESTA orden con OTRO importe que frena el envio tampoco se adopta`() = runTest {
        // La orden nace al mandar, así que la puerta de la pantalla no ve el pendiente: lo frena el servicio
        // (`inherited`). Misma regla: con otro importe se revisa, no se adopta.
        stubOrderCreation()
        val realGuard = TerminalPaymentService(secureStorage, okhttp3.OkHttpClient())
        every { secureStorage.pendingCardChargesJson } returns PendientesDeTarjeta.agregar(
            null, "r-parte-a", "order-1", """{"requestId":"r-parte-a","orderId":"order-1","amountCents":30000,"tipCents":0}""",
        )
        every { terminalPaymentService.pendienteDeLaVenta(any()) } answers { realGuard.pendienteDeLaVenta(firstArg()) }
        every { terminalPaymentService.contextoDe(any()) } answers { realGuard.contextoDe(firstArg()) }
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } coAnswers {
            realGuard.sendPaymentToTerminal("t1", 1500, orderId = arg<String?>(4))
        }
        coEvery { terminalPaymentService.resolveOutcome("r-parte-a") } returns TerminalPaymentResult.Success(paymentId = "pay-parte-a")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertTrue((viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
    }

    @Test
    fun `P1 Volver a consultar un pendiente de ESTA orden que la pantalla no fijo aplica la misma regla`() = runTest {
        // `recheckCardCharge` es público: un pendiente de esta orden que ninguna pantalla fijó (lo encontró la lista) no lo
        // mandó ESTE flujo, así que no se adopta — ni con el MISMO importe ($15.00 = $15.00), que la regla de importes adoptaba.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-parte-a"
        every { terminalPaymentService.contextoDe("r-parte-a") } returns
            pendiente("r-parte-a", "order-1", haceMin = 3, centavos = 1_500)
        coEvery { terminalPaymentService.resolveOutcome("r-parte-a") } returns TerminalPaymentResult.Success(paymentId = "pay-parte-a")

        viewModel.startPaymentFlow(cardCart())
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse(viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
    }

    // --- Ronda 2 (26-sep): una cuenta DIVIDIDA nunca adopta; el encabezado dice de quién es el cobro ---

    @Test
    fun `P1 en una cuenta dividida en partes IGUALES el pendiente de la otra parte no se adopta`() = runTest {
        // $500 en 2 partes iguales: la parte A ($250) quedó sin confirmar y la B ($250) elige tarjeta sobre la MISMA
        // orden. Mismo importe, otra persona: comparar importes no las distingue. Una cuenta dividida SIEMPRE revisa.
        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        every { terminalPaymentService.pendienteDeLaVenta("orden-dividida") } returns "r-parte-a"
        every { terminalPaymentService.contextoDe("r-parte-a") } returns
            pendiente("r-parte-a", "orden-dividida", haceMin = 3, centavos = 25_000)
        coEvery { terminalPaymentService.resolveOutcome("r-parte-a") } returns TerminalPaymentResult.Success(paymentId = "pay-parte-a")

        viewModel.startPaymentFlow(cuentaDe(50_000), resumeOrderId = "orden-dividida")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertEquals("la parte B cobra \$250, igual que la A", 25_000, espera.totalAmount)
        assertTrue("B espera a A, pero como REVISIÓN aunque el importe sea igual", espera.fromPreviousSale)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        assertFalse("B no queda pagada sin pasar su tarjeta", viewModel.state.value is PaymentFlowState.Success)
        assertEquals("El cobro anterior sí se había realizado", viewModel.previousChargeResolved.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 el encabezado de un pendiente de ESTA venta no adoptado dice de esta venta, tambien tras consultar`() = runTest {
        // Es de esta orden (otra parte, o sin importe registrado): decir «de otra venta» sería falso.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-sin-importe"
        every { terminalPaymentService.contextoDe("r-sin-importe") } returns
            pendiente("r-sin-importe", "order-1", haceMin = 3, centavos = null)
        coEvery { terminalPaymentService.resolveOutcome("r-sin-importe") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-sin-importe")
        val esperado = "Quedó un cobro sin confirmar de esta venta. ${CardChargeDecision.UNDETERMINED_MESSAGE}"

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals(esperado, (viewModel.state.value as PaymentFlowState.Undetermined).message)
        viewModel.recheckCardCharge()
        advanceUntilIdle()

        val tras = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(tras.fromPreviousSale)
        assertEquals("la consulta conserva el encabezado, sin repetir la instrucción", esperado, tras.message)
    }

    @Test
    fun `P1 una revision vieja que contesta tarde no pisa el aviso de la nueva`() = runTest {
        // «No hay terminales» → «Reintentar» vuelve a revisar mientras la primera consulta sigue colgada. La vieja contesta
        // DESPUÉS: su «SÍ pasó» describe una lista que ya cambió y no puede tapar el aviso vigente.
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returnsMany listOf(
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"))), // sonda al arrancar
            TerminalListResult.Success(emptyList()),                                                     // «No hay terminales»
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"))), // «Reintentar»
        )
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returnsMany listOf(
            listOf(pendiente("r-uno", "orden-7", haceMin = 2)),
            listOf(pendiente("r-dos", "orden-8", haceMin = 1)),
        )
        val primera = CompletableDeferred<TerminalPaymentResult>()
        coEvery { terminalPaymentService.resolveOutcome("r-uno") } coAnswers { primera.await() }
        coEvery { terminalPaymentService.resolveOutcome("r-dos") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-dos")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        // Paridad con iOS (26-sep): sin terminales se dice DENTRO de la selección, con las líneas ámbar encima.
        assertEquals("montaje: sin terminales", "No hay terminales conectadas",
            (viewModel.state.value as? PaymentFlowState.SelectingTerminal)?.sinLista)
        viewModel.retry()
        runCurrent()
        assertEquals("r-dos", viewModel.avisoDeOtroCobro.value?.requestId)

        primera.complete(TerminalPaymentResult.Success(paymentId = "pay-uno"))
        advanceUntilIdle()

        val aviso = viewModel.avisoDeOtroCobro.value!!
        assertEquals("la revisión vieja no manda", "r-dos", aviso.requestId)
        assertFalse(aviso.yaCobrado)
    }

    @Test
    fun `P1 el pendiente de otra venta que CONSTA como no cobrado deja de avisarse`() = runTest {
        // El más nuevo consta como NO cobrado (Error): se calla, y el aviso pasa al siguiente que sigue en duda.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(
            pendiente("r-no-cobrado", "orden-8", haceMin = 1),
            pendiente("r-en-duda", "orden-7", haceMin = 2),
        )
        coEvery { terminalPaymentService.resolveOutcome("r-no-cobrado") } returns
            TerminalPaymentResult.Error("El cobro fue rechazado. No se cobró la tarjeta.")
        coEvery { terminalPaymentService.resolveOutcome("r-en-duda") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-en-duda")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertEquals("r-en-duda", viewModel.avisoDeOtroCobro.value?.requestId)
    }

    @Test
    fun `P1 un cobro SIN orden con un pendiente de otra venta sale y se avisa`() = runTest {
        // Cobro rápido (importe tecleado): no hay orden que cercar. Controlador: sólo aviso, sin espera extra.
        every { terminalPaymentService.pendientesDeOtrasVentas(null) } returns listOf(pendiente("r-viejo", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-viejo") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-viejo")
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Success(paymentId = "pay-rapido", requestId = "req-rapido")
        val importeSuelto = CartState(
            items = listOf(CartItem(id = "suelto", type = CartItemType.CustomAmount, name = "Importe", unitPrice = 1500)),
        )

        viewModel.startPaymentFlow(importeSuelto)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertTrue("sin orden no hay venta que esperar", viewModel.state.value is PaymentFlowState.SelectingTerminal)
        assertEquals("r-viejo", viewModel.avisoDeOtroCobro.value?.requestId)
        verify { terminalPaymentService.pendienteDeLaVenta(null) }

        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(
                terminalId = "t1", amountCents = 1500, tipCents = 0, rating = null, orderId = null, processedByStaffId = any(),
            )
        }
        assertEquals("pay-rapido", (viewModel.state.value as PaymentFlowState.Success).paymentId)
    }

    // --- Ronda 4 (26-sep, paridad con iOS): el «SÍ pasó» no se pierde; la propina pasa por la misma puerta ---

    @Test
    fun `P1 un SI paso que el cajero no ha cerrado no lo borra otra revision`() = runTest {
        // H2 (26-sep): la consulta que prueba el cobro ya NO suelta su entrada: la MARCA en disco. La revisión siguiente la
        // lee marcada, no la vuelve a consultar, y el «SÍ pasó» sigue. Sólo «Entendido» de ESE cobro la quita del disco.
        // (Con el disco REAL, venta nueva y reinicio: `AvisoDuraderoDeOtrasVentasTest`.)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returnsMany listOf(
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"))), // sonda al arrancar
            TerminalListResult.Success(emptyList()),                                                     // «No hay terminales»
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"))), // «Reintentar»
        )
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returnsMany listOf(
            listOf(pendiente("r-cobrado", "orden-7", haceMin = 2)),                 // al instante: todavía en duda
            listOf(pendiente("r-cobrado", "orden-7", haceMin = 2, cobrado = true)), // de ahí en adelante: la consulta lo marcó
        )
        coEvery { terminalPaymentService.resolveOutcome("r-cobrado") } returns TerminalPaymentResult.Success(paymentId = "pay-7")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        // Paridad con iOS (26-sep): sin terminales se dice DENTRO de la selección — la pantalla de error tapaba este aviso.
        assertEquals("montaje: sin terminales", "No hay terminales conectadas",
            (viewModel.state.value as? PaymentFlowState.SelectingTerminal)?.sinLista)
        assertTrue("montaje: la revisión probó el cobro", viewModel.avisoDeOtroCobro.value!!.yaCobrado)

        viewModel.retry() // vuelve a la selección de terminal: otra revisión
        assertEquals("al instante, desde el disco", "r-cobrado", viewModel.avisoDeOtroCobro.value?.requestId)
        advanceUntilIdle()

        val aviso = viewModel.avisoDeOtroCobro.value
        assertEquals("el «SÍ pasó» sigue en pantalla", "r-cobrado", aviso?.requestId)
        assertTrue(aviso!!.yaCobrado)
        coVerify(exactly = 1) { terminalPaymentService.resolveOutcome("r-cobrado") } // lo probado no se vuelve a consultar

        viewModel.descartarAvisoDeOtroCobro("r-cobrado") // «Entendido»
        verify(exactly = 1) { terminalPaymentService.reconocerCobro("r-cobrado") }
    }

    // --- Lote final (26-sep): H7 el aviso vence con la pantalla abierta · M-5 título · B7b-5 sin red ---

    @Test
    fun `H7 el aviso sin confirmar se calla solo a los 10 min con la pantalla abierta, sin red y sin tocar el disco`() = runTest {
        // Reloj VIRTUAL: la pantalla se abre con una duda de 9 min 55 s y se queda abierta.
        val base = System.currentTimeMillis()
        viewModel.reloj = { base + testScheduler.currentTime }
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(
            ContextoDeCobro("r-casi", "v", "t1", "orden-7", 1500, 0, desdeMillis = base - (10 * 60_000L - 5_000)),
        )
        coEvery { terminalPaymentService.resolveOutcome("r-casi") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-casi")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        assertEquals("montaje: se avisa", "r-casi", viewModel.avisoDeOtroCobro.value?.requestId)

        advanceTimeBy(4_000); runCurrent()
        assertEquals("a los 9:59 sigue", "r-casi", viewModel.avisoDeOtroCobro.value?.requestId)
        advanceTimeBy(2_000); runCurrent()
        assertNull("a los 10:01 se calla, con la pantalla abierta", viewModel.avisoDeOtroCobro.value)

        assertTrue(viewModel.state.value is PaymentFlowState.SelectingTerminal)
        coVerify(exactly = 1) { terminalPaymentService.resolveOutcome("r-casi") } // vencer no consulta la red
        verify(exactly = 0) { terminalPaymentService.reconocerCobro(any()) }        // ni borra nada del disco
        verify(exactly = 0) { terminalPaymentService.soltarLlaveSiEs(any()) }
    }

    @Test
    fun `H7 un SI paso vence a los 10 min como las dudas`() = runTest {
        // Founder, 26-sep: el «SÍ pasó» sigue la MISMA ventana que las dudas y nunca es pegajoso (reemplaza «hasta Entendido»).
        val base = System.currentTimeMillis()
        viewModel.reloj = { base + testScheduler.currentTime }
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(
            ContextoDeCobro("r-si", "v", "t1", "orden-7", 1500, 0, desdeMillis = base - (10 * 60_000L - 5_000), cobrado = true),
        )

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        assertTrue("montaje: se ve el «SÍ pasó»", viewModel.avisoDeOtroCobro.value!!.yaCobrado)

        advanceTimeBy(4_000); runCurrent()
        assertEquals("a los 9:59 sigue", "r-si", viewModel.avisoDeOtroCobro.value?.requestId)
        advanceTimeBy(2_000); runCurrent()
        assertNull("a los 10:01 se calla, con la pantalla abierta, sin tocar nada", viewModel.avisoDeOtroCobro.value)
        coVerify(exactly = 0) { terminalPaymentService.resolveOutcome(any()) } // lo probado no se vuelve a consultar
    }

    @Test
    fun `I-2 el SI paso no tapa la duda nueva - se ven las dos lineas`() = runTest {
        // Re-revisión (I-2): el «SÍ pasó» ocupaba la ÚNICA línea y tapaba la duda nueva de otra venta — la única protección
        // contra volver a cobrarla.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(
            pendiente("r-duda", "orden-9", haceMin = 1),
            pendiente("r-si", "orden-7", haceMin = 3, cobrado = true),
        )
        coEvery { terminalPaymentService.resolveOutcome("r-duda") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-duda")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertEquals("r-si", viewModel.avisoDeOtroCobro.value?.requestId)
        assertTrue(viewModel.avisoDeOtroCobro.value!!.yaCobrado)
        assertEquals("y debajo, la duda vigente más nueva", "r-duda", viewModel.segundoAviso.value?.requestId)
        assertFalse(viewModel.segundoAviso.value!!.yaCobrado)
    }

    @Test
    fun `N3 la revision no oculta un cobro que ya consta aunque su propia consulta diga que no`() = runTest {
        // Codex r2 (N3): la consulta de la revisión vio «no se cobró», pero mientras otro escritor probó que SÍ pasó. La
        // publicación filtraba por el negativo de su consulta y escondía una entrada ya confirmada. El positivo gana.
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returnsMany listOf(
            listOf(pendiente("r-a", "orden-7", haceMin = 2)),
            listOf(pendiente("r-a", "orden-7", haceMin = 2, cobrado = true)),
        )
        coEvery { terminalPaymentService.resolveOutcome("r-a") } returns
            TerminalPaymentResult.Error("No se confirmó el cobro en 30 s.")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertEquals("r-a", viewModel.avisoDeOtroCobro.value?.requestId)
        assertTrue(viewModel.avisoDeOtroCobro.value!!.yaCobrado)
    }

    @Test
    fun `M-5 el titulo dice anterior solo cuando el cobro es de OTRA venta`() = runTest {
        // «Cobro anterior sin confirmar» encima de «Quedó un cobro sin confirmar de esta venta.» se contradecía.
        stubOrderCreation()
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns "r-de-esta"
        coEvery { terminalPaymentService.resolveOutcome(any()) } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-de-esta")

        viewModel.startPaymentFlow(cardCart(), resumeOrderId = "order-1")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertTrue("montaje: revisión de ESTA venta", (viewModel.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        assertFalse("de esta venta: «Cobro sin confirmar»", viewModel.revisaCobroDeOtraVenta())

        viewModel.startPaymentFlow(cardCart())
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.revisarCobroDeOtraVenta("r-otra")
        advanceUntilIdle()
        assertTrue("de otra venta: «Cobro anterior sin confirmar»", viewModel.revisaCobroDeOtraVenta())
    }

    @Test
    fun `B7b-5 sin red la seleccion de terminal dice que la tarjeta necesita internet y conserva el aviso`() = runTest {
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Error("Error de conexión", sinRed = true)
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-otra", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-otra") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-otra")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue("no es «Error en el pago»: $estado", estado is PaymentFlowState.SelectingTerminal)
        assertEquals("estado sin red", PaymentFlowViewModel.TARJETA_SIN_RED, (estado as PaymentFlowState.SelectingTerminal).sinLista)
        assertEquals("la línea ámbar de la otra venta sigue, desde el disco", "r-otra", viewModel.avisoDeOtroCobro.value?.requestId)
        assertEquals(
            "Sin conexión: el cobro con tarjeta necesita internet. Cobra en efectivo o espera a que vuelva la red.",
            PaymentFlowViewModel.TARJETA_SIN_RED,
        )

        // «Reintentar» con la red de vuelta: la lista.
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1")))
        viewModel.retry()
        advanceUntilIdle()

        val conRed = viewModel.state.value as PaymentFlowState.SelectingTerminal
        assertNull(conRed.sinLista)
        assertEquals(listOf("t1"), viewModel.onlineTerminals.value.map { it.terminalId })
    }

    @Test
    fun `N6 un error del SERVIDOR al traer las terminales no esconde el aviso de otras ventas`() = runTest {
        // Codex r2 (N6): el error del servidor cambiaba a la pantalla de error y escondía la línea ámbar. Se dice igual, en
        // lugar de la lista, con «Reintentar», y el aviso sigue arriba.
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Error("Error al buscar terminales (503)")
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-otra", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-otra") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-otra")

        viewModel.startPaymentFlow(cardCart())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val estado = viewModel.state.value as PaymentFlowState.SelectingTerminal
        assertEquals("Error al buscar terminales (503)", estado.sinLista)
        assertEquals("la línea ámbar sigue a la vista", "r-otra", viewModel.avisoDeOtroCobro.value?.requestId)
    }

    @Test
    fun `P1 con propina el pendiente de ESTA venta tambien frena la tarjeta`() = runTest {
        // Con la pantalla de propina encendida (lo normal en el ICP) la tarjeta pasa por la MISMA puerta: se elige la
        // propina y después el método. Espera como revisión, con el total que incluye la propina.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = true)
        every { terminalPaymentService.pendienteDeLaVenta("orden-propina") } returns "r-propina-esta"

        viewModel.startPaymentFlow(cuentaDe(5_000), resumeOrderId = "orden-propina")
        assertTrue("montaje: pide propina", viewModel.state.value is PaymentFlowState.CollectingTip)
        viewModel.submitTip(750)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val espera = viewModel.state.value as PaymentFlowState.Undetermined
        assertTrue(espera.fromPreviousSale)
        assertTrue(espera.message, espera.message.startsWith("Quedó un cobro sin confirmar de esta venta."))
        assertEquals("el total incluye la propina", 5_750, espera.totalAmount)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 con propina el pendiente de otra venta no frena y se avisa`() = runTest {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = true)
        every { terminalPaymentService.pendientesDeOtrasVentas(any()) } returns listOf(pendiente("r-propina-otra", "orden-7", haceMin = 2))
        coEvery { terminalPaymentService.resolveOutcome("r-propina-otra") } returns
            TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, "r-propina-otra")

        viewModel.startPaymentFlow(cardCart())
        assertTrue("montaje: pide propina", viewModel.state.value is PaymentFlowState.CollectingTip)
        viewModel.submitTip(750)
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        assertEquals("el aviso sale al instante", "r-propina-otra", viewModel.avisoDeOtroCobro.value?.requestId)
        advanceUntilIdle()

        assertTrue("la otra venta no frena la tarjeta", viewModel.state.value is PaymentFlowState.SelectingTerminal)
        assertEquals("y el aviso sigue tras la revisión", "r-propina-otra", viewModel.avisoDeOtroCobro.value?.requestId)
    }

    @Test
    fun `los textos del aviso dicen la antiguedad como la TPV y nunca un importe inventado`() {
        val ahora = 1_000_000_000L
        assertEquals(
            "Quedó un cobro de \$15.00 sin confirmar hace unos segundos en otra venta. Si es esta misma venta, no la cobres otra vez.",
            PaymentFlowViewModel.textoCobroSinConfirmar(1500, ahora - 30_000, ahora),
        )
        assertTrue(PaymentFlowViewModel.textoCobroSinConfirmar(1500, ahora - 5 * 60_000, ahora).contains(" hace 5 min "))
        assertTrue(PaymentFlowViewModel.textoCobroSinConfirmar(1500, ahora - 125 * 60_000, ahora).contains(" hace 2 h "))
        // Un reloj que se movió hacia atrás nunca da una antigüedad negativa.
        assertTrue(PaymentFlowViewModel.textoCobroSinConfirmar(1500, ahora + 60_000, ahora).contains(" hace unos segundos "))
        // Sin importe ni antigüedad no se inventa ninguno de los dos.
        assertEquals(
            "Quedó un cobro sin confirmar en otra venta. Si es esta misma venta, no la cobres otra vez.",
            PaymentFlowViewModel.textoCobroSinConfirmar(null, null, ahora),
        )
        assertEquals(
            "El cobro de \$15.00 de otra venta SÍ pasó. Si es esta misma venta, no la cobres otra vez.",
            PaymentFlowViewModel.textoCobroQueSiPaso(1500),
        )
        assertEquals(
            "El cobro de otra venta SÍ pasó. Si es esta misma venta, no la cobres otra vez.",
            PaymentFlowViewModel.textoCobroQueSiPaso(null),
        )
        assertFalse(PaymentFlowViewModel.textoCobroQueSiPaso(0).contains("\$0.00"))
    }
}
