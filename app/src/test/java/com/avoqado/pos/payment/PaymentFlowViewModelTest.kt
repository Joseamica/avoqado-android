package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
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
import com.avoqado.pos.printing.data.PrinterService
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private lateinit var viewModel: PaymentFlowViewModel

    @Before
    fun setup() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        coEvery {
            terminalPaymentService.fetchOnlineTerminals()
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
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns Unit
        coEvery { printerService.manualPrintReceipt(any()) } returns 1
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null

        // PRINT_STATIONS — default to "no stations configured" so existing tests keep
        // exercising the legacy single-ticket path unless a test overrides this.
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns Unit

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
            // 🔴 NO REGRESIÓN: el despachador va REAL, armado con los mismos mocks de siempre.
            // Mockearlo escondería justo lo que hay que probar — los tests de abajo siguen
            // verificando `printerService.autoPrintKitchenTicket` y `comandaPrinter.printComandas`
            // tal cual los verificaban antes de que ComandaDispatcher existiera, así que si la
            // extracción cambiara UNA llamada del camino post-pago, truenan.
            comandaDispatcher = ComandaDispatcher(printConfigRepository, comandaPrinter, printerService),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
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
        coEvery { printerService.autoPrintKitchenTicket(capture(ticketSlot)) } returns Unit

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
        } returns Unit

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
        viewModel.confirmManualMethod(ManualPaymentMethod.CARD_EXTERNAL)
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
}
