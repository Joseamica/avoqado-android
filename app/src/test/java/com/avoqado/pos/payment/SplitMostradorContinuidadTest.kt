package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
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
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
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
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 DINERO — Split de MOSTRADOR: la parte 2 se cobra contra la orden de la parte 1.
 *
 * El bug que cierra esta suite: al quedar saldo, `CheckoutScreen` sustituía el
 * carrito por una línea "Saldo pendiente" y `startPaymentFlow` borraba
 * `createdOrderId` SIEMPRE (sólo lo resembraba con sesión de mesa en `PAYING`).
 * Resultado: la parte 2 nacía sin orden → cobro rápido suelto o —peor, en
 * BYPRODUCT— una SEGUNDA orden con las líneas duplicadas. La venta quedaba
 * partida en dos, la orden original PARTIAL para siempre y **el stock nunca se
 * descontaba** (sólo se descuenta al llegar a PAID).
 *
 * La otra mitad del contrato, tan importante como la primera: el id **jamás**
 * se filtra a la venta siguiente. Un id que sobrevive de más cobra la venta
 * nueva contra la orden vieja — el mismo bug, con el signo cambiado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitMostradorContinuidadTest {

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
    private val tableSession = TableSession()

    private lateinit var viewModel: PaymentFlowViewModel

    /** El carrito de la parte 2 en EQUALPARTS/CUSTOMAMOUNT: sólo el saldo. */
    private fun carritoDeSaldo(cents: Int = 5000) = CartState(
        items = listOf(
            CartItem(
                id = "line-saldo",
                type = CartItemType.CustomAmount,
                name = "Saldo pendiente",
                unitPrice = cents,
            ),
        ),
    )

    /** Carrito con mercancía real (la parte 2 de un BYPRODUCT, o una venta nueva). */
    private fun carritoConProductos(id: String = "line-refresco", cents: Int = 3000) = CartState(
        items = listOf(
            CartItem(
                id = id,
                type = CartItemType.ProductItem("prod-refresco"),
                name = "Refresco",
                unitPrice = cents,
            ),
        ),
    )

    @Before
    fun setup() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(
            listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        every { cashPaymentRepository.processCashPayment(any(), any()) } returns
            CashPaymentResult.Success(changeCents = 0)

        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-nueva")))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "fast-1", receiptAccessKey = null))

        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns Unit
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null

        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        viewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandaDispatcher = ComandaDispatcher(printConfigRepository, comandaPrinter, printerService),
            tableSession = tableSession,
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    // ── 1. Mostrador, split por importe: la parte 2 cobra contra la orden vieja ──

    @Test
    fun `parte 2 de un split de mostrador cobra contra la orden de la parte 1 y NO crea otra`() = runTest {
        viewModel.startPaymentFlow(carritoDeSaldo(5000), resumeOrderId = "order-parte-1")
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.recordCashPayment("order-parte-1", 5000, any(), any(), any(), any())
        }
        // Ni una segunda orden ni un cobro suelto: los dos caminos por los que
        // hoy se parte la venta.
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `parte 2 con tarjeta manda a la terminal la orden de la parte 1`() = runTest {
        val orderIdSlot = slot<String>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), capture(orderIdSlot), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        viewModel.startPaymentFlow(carritoDeSaldo(5000), resumeOrderId = "order-parte-1")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("order-parte-1", orderIdSlot.captured)
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
    }

    // ── 2. BYPRODUCT: el carrito conserva productos REALES ──────────────────────

    @Test
    fun `parte 2 por articulos NO crea una segunda orden con las lineas duplicadas`() = runTest {
        viewModel.startPaymentFlow(carritoConProductos(cents = 3000), resumeOrderId = "order-parte-1")
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(3000)
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            orderRepository.recordCashPayment("order-parte-1", 3000, any(), any(), any(), any())
        }
    }

    // ── 3. El id NO se filtra a la venta siguiente ──────────────────────────────

    @Test
    fun `tras cerrar la venta, un cobro nuevo crea SU propia orden`() = runTest {
        val idsCobrados = mutableListOf<String>()
        coEvery {
            orderRepository.recordCashPayment(capture(idsCobrados), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        // Venta 1, parte 2 — contra la orden vieja.
        viewModel.startPaymentFlow(carritoDeSaldo(5000), resumeOrderId = "order-parte-1")
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        // Venta NUEVA: sin continuidad. La pantalla ya no manda resumeOrderId.
        viewModel.startPaymentFlow(carritoConProductos(cents = 3000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(3000)
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
        assertEquals(listOf("order-parte-1", "order-nueva"), idsCobrados)
    }

    // ── 4. Regresión: cobro de mostrador NORMAL (sin split) ─────────────────────

    @Test
    fun `un cobro de mostrador normal sigue creando su orden como hoy`() = runTest {
        viewModel.startPaymentFlow(carritoConProductos(cents = 3000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(3000)
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            orderRepository.recordCashPayment("order-nueva", 3000, any(), any(), any(), any())
        }
    }

    @Test
    fun `una venta rapida de importe custom sigue yendo al cobro rapido`() = runTest {
        viewModel.startPaymentFlow(
            CartState(
                items = listOf(
                    CartItem(
                        id = "line-otro",
                        type = CartItemType.CustomAmount,
                        name = "Otro importe",
                        unitPrice = 2500,
                    ),
                ),
            ),
        )
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(2500)
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(2500, any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
    }

    // ── 5. Regresión mesa: el camino de mesa manda y no cambia ──────────────────

    @Test
    fun `con sesion de mesa PAYING se cobra la orden de la mesa, igual que hoy`() = runTest {
        tableSession.start(
            TableSession.Active(
                tableId = "mesa-1",
                tableNumber = "4",
                areaName = null,
                orderId = "order-mesa",
                orderNumber = "A-4",
                version = 1,
                totalCents = 5000,
                mode = TableSession.Mode.PAYING,
            ),
        )

        viewModel.startPaymentFlow(carritoDeSaldo(5000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.recordCashPayment("order-mesa", 5000, any(), any(), any(), any())
        }
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `la orden de la mesa gana sobre cualquier continuidad de mostrador`() = runTest {
        tableSession.start(
            TableSession.Active(
                tableId = "mesa-1",
                tableNumber = "4",
                areaName = null,
                orderId = "order-mesa",
                orderNumber = "A-4",
                version = 1,
                totalCents = 5000,
                mode = TableSession.Mode.PAYING,
            ),
        )

        viewModel.startPaymentFlow(carritoDeSaldo(5000), resumeOrderId = "order-mostrador-viejo")
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.recordCashPayment("order-mesa", 5000, any(), any(), any(), any())
        }
    }

    // ── 6. El saldo AUTORITATIVO lo dice el server cuando lo manda ──────────────

    @Test
    fun `buildCompletion prefiere el remainingBalance del server sobre la aritmetica local`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(
                OrderRepository.CashPayResult(
                    paymentId = "pay-1",
                    receiptAccessKey = null,
                    // El server dice que quedan $42.00 — el carrito diría $50.00.
                    remainingBalanceCents = 4200,
                ),
            )

        // Split en 2 partes sobre $100: la aritmética local daría 5000 de resto.
        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        assertEquals(4200, viewModel.buildCompletion().remainingBalanceCents)
    }

    @Test
    fun `buildCompletion cae al calculo local cuando el server no manda remainingBalance`() = runTest {
        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        assertEquals(5000, viewModel.buildCompletion().remainingBalanceCents)
    }

    @Test
    fun `buildCompletion conserva los articulos pagados aunque el server mande el saldo`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(
                OrderRepository.CashPayResult(
                    paymentId = "pay-1",
                    receiptAccessKey = null,
                    // 🔴 DISTINTO del resto local ($30 del Agua) a propósito: con
                    // 3000 el test pasaba aunque se borrara la preferencia del
                    // server, porque la aritmética local da lo mismo.
                    remainingBalanceCents = 2500,
                ),
            )

        val cart = CartState(
            items = listOf(
                CartItem(id = "pagado-1", type = CartItemType.ProductItem("p1"), name = "Taco", unitPrice = 2000),
                CartItem(id = "pendiente-1", type = CartItemType.ProductItem("p2"), name = "Agua", unitPrice = 3000),
            ),
        )
        viewModel.setSplitConfig(type = "BYPRODUCT", selectedItemIds = listOf("pagado-1"))
        viewModel.startPaymentFlow(cart, resumeOrderId = null)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(2000)
        advanceUntilIdle()

        val completion = viewModel.buildCompletion()
        assertEquals(2500, completion.remainingBalanceCents)
        assertEquals(setOf("pagado-1"), completion.paidItemIds)
    }

    /**
     * 🔴 EL ESTADO MANDA, NO EL MONTO. El server tolera hasta un centavo, así que
     * puede dejar la orden **PAID con un residuo**. Creerle al número dejaría un
     * "Saldo pendiente" que ya nadie puede cobrar —la orden está cerrada— y la
     * venta jamás cerraría.
     */
    @Test
    fun `una orden PAID no deja saldo aunque el numero diga otra cosa`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(
                OrderRepository.CashPayResult(
                    paymentId = "pay-1",
                    receiptAccessKey = null,
                    // A propósito NO es un centavo: así este test aísla el guard
                    // del ESTADO y no lo tapa el perdón del residuo de abajo.
                    // Si el server cerró la orden, cobrarle más la rechazaría y
                    // el cajero se quedaría con una venta que no cierra.
                    remainingBalanceCents = 250,
                    orderPaymentStatus = "PAID",
                ),
            )

        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()

        assertEquals(0, viewModel.buildCompletion().remainingBalanceCents)
    }

    /** Cobra un split de 2 partes sobre $100 y devuelve el resto que quedó. */
    private suspend fun saldoTrasCobrarMitad(resultado: OrderRepository.CashPayResult): Int {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(resultado)

        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        return viewModel.buildCompletion().remainingBalanceCents
    }

    /**
     * 🔴 EL MONTO NO DISTINGUE. El mismo "1 centavo" significa dos cosas opuestas
     * según de qué resta salga, y sólo el ESTADO las separa:
     *
     *     50.00 − 49.99 = 0.00999999999999801  → PAID
     *     35.70 − 35.69 = 0.010000000000005116 → PARTIAL
     *
     * Por eso el perdón del residuo sólo vale con un server viejo, que no manda
     * estado y donde no hay forma de distinguirlos.
     */
    /**
     * 🔴 `orderPaymentStatus` es un `String` libre y la normalización vive en el
     * extractor: un `"paid"` armado por otro camino (cola offline, un mock) haría
     * fallar el guard EN SILENCIO y dejaría un saldo fantasma imposible de cobrar.
     * Clase de bug ya vista 3 veces en el workspace.
     */
    @Test
    fun `el estado PAID se reconoce sin importar mayusculas`() = runTest {
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 250,
                orderPaymentStatus = "paid",
            ),
        )
        advanceUntilIdle()

        assertEquals(0, saldo)
    }

    @Test
    fun `un residuo de un centavo se perdona SOLO si el server no manda el estado`() = runTest {
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 1,
                orderPaymentStatus = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(0, saldo)
    }

    @Test
    fun `con PARTIAL explicito, un centavo SI es deuda y se conserva`() = runTest {
        // 🔴 Perdonarlo aquí cerraría el carrito con la orden abierta y **el stock
        // sin descontar** — el bug que esta tarea cierra, a escala de un centavo.
        // Y sería regresión: la aritmética local dejaba "Saldo pendiente $0.01" y
        // el cajero lo cobraba, cerrando la orden.
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 1,
                orderPaymentStatus = "PARTIAL",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, saldo)
    }

    @Test
    fun `con PENDING explicito tampoco se perdona el centavo`() = runTest {
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 1,
                orderPaymentStatus = "PENDING",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, saldo)
    }

    @Test
    fun `dos centavos SI son deuda — el perdon es de uno, no de cualquier resto`() = runTest {
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 2,
                orderPaymentStatus = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(2, saldo)
    }

    @Test
    fun `noventa y nueve centavos son deuda, con estado o sin el`() = runTest {
        val saldo = saldoTrasCobrarMitad(
            OrderRepository.CashPayResult(
                paymentId = "pay-1",
                receiptAccessKey = null,
                remainingBalanceCents = 99,
                orderPaymentStatus = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(99, saldo)
    }

    // ── El saldo del server NO manda en el pago COMPLETO ────────────────────────

    /**
     * 🔴 Acotado a propósito a las ramas de split. En pago completo preferirlo
     * (a) revertiría la decisión de cobrar el estimado local cuando el efectivo no
     * alcanza —"en vez de dejar al cajero pidiendo centavos de vuelta"— y (b)
     * desviaría el commit a la rama de saldo pendiente, donde `pendingPackGrant`
     * NO se otorga: un cliente pagaría su membresía y no recibiría un solo crédito.
     */
    @Test
    fun `en pago completo el saldo del server NO se usa`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(
                OrderRepository.CashPayResult(
                    paymentId = "pay-1",
                    receiptAccessKey = null,
                    remainingBalanceCents = 1100,
                    orderPaymentStatus = "PARTIAL",
                ),
            )

        viewModel.setSplitConfig(type = "FULLPAYMENT")
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals(0, viewModel.buildCompletion().remainingBalanceCents)
    }

    @Test
    fun `el saldo del server de una venta NO se arrastra a la siguiente`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any()) } returns
            Result.success(
                OrderRepository.CashPayResult(
                    paymentId = "pay-1",
                    receiptAccessKey = null,
                    remainingBalanceCents = 4200,
                ),
            )

        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 10000))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()
        assertEquals(4200, viewModel.buildCompletion().remainingBalanceCents)

        // Venta nueva, sin cobrar todavía: el saldo de la anterior no puede seguir
        // puesto. Sigue siendo un SPLIT a propósito — en pago completo el saldo del
        // server no se usa y el 0 saldría solo, sin probar que se limpió.
        viewModel.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        viewModel.startPaymentFlow(carritoConProductos(cents = 4000))
        // Aritmética local pura: $40 de carrito − $20 de la primera parte.
        assertEquals(2000, viewModel.buildCompletion().remainingBalanceCents)
    }
}
