package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
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
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.routing.PrintConfig
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
            orderRepository.recordFastCashPayment(any(), any(), any(), any())
        } returns Result.success("fast-pay-1")

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
            printConfigRepository = printConfigRepository,
            comandaPrinter = comandaPrinter,
        )
    }

    @Test
    fun `mixed cart includes custom amount in create order request`() = runTest {
        val requestSlot = slot<CreateOrderRequest>()
        coEvery {
            orderRepository.createOrder(capture(requestSlot), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any())
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

        coVerify(exactly = 1) { orderRepository.createOrder(any(), "user-456", any(), any()) }
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
            orderRepository.recordFastCashPayment(any(), any(), any(), any())
        } returns Result.success("fast-pay-2")

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
        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(500, "user-456", 0, "FULLPAYMENT") }
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `no print stations configured falls back to the legacy single kitchen ticket`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-legacy")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success("payment-legacy")
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

    @Test
    fun `active print stations route the kitchen ticket through the comanda printer instead of the legacy fan-out`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-routed")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any())
        } returns Result.success("payment-routed")

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
}
