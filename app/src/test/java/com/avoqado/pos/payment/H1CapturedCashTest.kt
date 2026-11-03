package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.payment.data.model.PaymentMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.ColaDelCobro
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** H1: real VM → CashPaymentRepository → saved row → real replay body. */
@OptIn(ExperimentalCoroutinesApi::class)
class H1CapturedCashTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val secureStorageForRepository = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val rows = linkedMapOf<String, PendingPaymentEntity>()
    private val writes = mutableListOf<PendingPaymentEntity>()
    private val cashPaymentRepository = CashPaymentRepository(secureStorageForRepository, dao)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = secureStorageForRepository
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val estadoEnCola = MutableStateFlow<String?>("PENDING")

    private lateinit var viewModel: PaymentFlowViewModel

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)),
    )

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = true)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        coEvery { dao.findForVenue(any(), any()) } coAnswers {
            rows[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() }
        }
        coEvery { dao.insert(any()) } coAnswers {
            val row = firstArg<PendingPaymentEntity>()
            rows[row.id] = row
            writes += row
        }
        coEvery { dao.borrarSiEnVuelo(any()) } coAnswers {
            val id = firstArg<String>()
            if (rows[id]?.syncStatus == "EN_VUELO") { rows.remove(id); 1 } else 0
        }
        every { dao.observarEstado(any()) } answers { flowOf(rows[firstArg<String>()]?.syncStatus) }
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.failure(IOException("sin red"))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } returns Result.failure(IOException("sin red"))
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.failure(IOException("sin red"))
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null

        viewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = ComandaDispatcher(
                mockk<PrintConfigRepository>(relaxed = true),
                ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    private fun open(products: Boolean = true, orderId: String? = null, part: Int = 4000, tip: Int = 500) {
        val cart = if (products) CartState(items = listOf(
            CartItem(id = "p40", type = CartItemType.ProductItem("product-40"), name = "Producto A", unitPrice = 4000),
            CartItem(id = "p60", type = CartItemType.ProductItem("product-60"), name = "Producto B", unitPrice = 6000),
        )) else otroImporte()
        viewModel.setSplitConfig("CUSTOMAMOUNT", customAmountCents = part)
        viewModel.startPaymentFlow(cart, customerId = "customer-A", resumeOrderId = orderId)
        viewModel.submitTip(tip)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
    }

    private fun captured(row: PendingPaymentEntity, amount: Int = 4000, tip: Int = 500, fullOrder: Boolean? = true) {
        assertEquals(amount, row.amountCents)
        assertEquals(tip, row.tipCents)
        assertEquals("CUSTOMAMOUNT", row.splitType)
        assertEquals("venue-1", row.venueId)
        assertEquals("customer-A", row.customerId)
        val body = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(row, row.venueId, row.paymentType == "ORDER")).jsonObject
        assertEquals(amount, body.getValue("amount").jsonPrimitive.int)
        assertEquals(tip, body.getValue("tip").jsonPrimitive.int)
        assertEquals("CUSTOMAMOUNT", body.getValue("splitType").jsonPrimitive.content)
        assertEquals(row.id, body.getValue("idempotencyKey").jsonPrimitive.content)
        // null = el pedido lo revisa la prueba (p. ej. el renglón «Otro importe» de la venta rápida dividida).
        if (fullOrder == true) {
            val order = Json.parseToJsonElement(requireNotNull(row.orderRequestJson)).jsonObject
            assertEquals(10000, order.getValue("subtotal").jsonPrimitive.int)
            assertEquals(10000 + tip, order.getValue("total").jsonPrimitive.int)
            assertEquals(setOf("product-40", "product-60"), order.getValue("items").jsonArray
                .map { it.jsonObject.getValue("productId").jsonPrimitive.content }.toSet())
        } else if (fullOrder == false) assertNull(row.orderRequestJson)
    }

    @Test fun `reservation persists the captured part before creating the full order`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
            waiting.await(); Result.failure(IOException("sin red"))
        }
        open()
        viewModel.processCashPayment(5000)
        runCurrent()
        try {
            val reserved = rows.values.single()
            assertEquals("EN_VUELO", reserved.syncStatus)
            captured(reserved)
            assertEquals(5000, reserved.cashTenderedCents)
            assertEquals(500, reserved.changeCents)
        } finally { waiting.complete(Unit); advanceUntilIdle() }
    }

    @Test fun `confirmPayment creation failure queues only the captured part`() = runTest {
        open()
        viewModel.confirmPayment()
        advanceUntilIdle()
        captured(rows.values.single())
        assertTrue((viewModel.state.value as PaymentFlowState.Success).isQueued)
    }

    @Test fun `confirmPayment existing order post failure queues only the captured part`() = runTest {
        open(orderId = "order-A")
        viewModel.confirmPayment()
        advanceUntilIdle()
        val row = rows.values.single()
        captured(row)
        assertEquals("order-A", row.orderId)
    }

    @Test fun `cash creation failure replaces the reservation with identical capture and key`() = runTest {
        open()
        viewModel.processCashPayment(5000)
        advanceUntilIdle()
        val reserved = writes.single { it.syncStatus == "EN_VUELO" }
        val queued = rows.values.single()
        assertEquals("PENDING", queued.syncStatus)
        assertEquals(reserved.id, queued.id)
        assertEquals(reserved.orderRequestJson, queued.orderRequestJson)
        captured(reserved); captured(queued)
    }

    // D2 etapa 2 (27-oct): una venta rápida DIVIDIDA lleva orden, así que su parte sin red se encola como ORDER con el pedido
    // «Otro importe» (antes era FAST y la venta terminaba en dos). La venta rápida de una sola parte sigue FAST.
    @Test fun `split quick sale post failure queues the captured part as an order with its custom line`() = runTest {
        open(products = false)
        viewModel.processCashPayment(5000)
        advanceUntilIdle()
        val queued = rows.values.single()
        captured(queued, fullOrder = null)
        assertEquals("ORDER", queued.paymentType)
        val line = Json.parseToJsonElement(requireNotNull(queued.orderRequestJson)).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertFalse(line.containsKey("productId"))
        assertEquals("Otro importe", line.getValue("name").jsonPrimitive.content)
        assertEquals(10_000, line.getValue("unitPrice").jsonPrimitive.int)
    }

    @Test fun `existing order fallback keeps its already correct amount and now preserves the whole order`() = runTest {
        open(orderId = "order-A")
        viewModel.processCashPayment(5000)
        advanceUntilIdle()
        captured(rows.values.single())
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
    }

    @Test fun `two parts keep distinct keys and retry reproduces the same capture`() = runTest {
        open(orderId = "order-A")
        viewModel.processCashPayment(5000)
        advanceUntilIdle()
        val first = rows.values.single()
        open(orderId = "order-A", part = 6000, tip = 0)
        viewModel.processCashPayment(6000)
        advanceUntilIdle()
        val second = rows.values.single { it.id != first.id }
        assertNotEquals(first.id, second.id)
        captured(first); captured(second, amount = 6000, tip = 0)
        assertEquals(10000, rows.values.sumOf { it.amountCents })
        val initialBody = PaymentSyncService.buildReplayBody(first, first.venueId, true)
        coEvery { dao.incrementRetry(first.id, any()) } coAnswers {
            rows[first.id] = rows.getValue(first.id).copy(retryCount = first.retryCount + 1, lastRetryAt = secondArg())
        }
        val replay = PaymentSyncService(dao, secureStorage, mockk(relaxed = true), mockk(relaxed = true),
            cashDrawerRepository, mockk(relaxed = true), io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true))
        try {
            assertTrue(replay.handleSyncResult(first, 503, "sin red"))
            val retried = rows.getValue(first.id)
            assertEquals(first.retryCount + 1, retried.retryCount)
            assertEquals(initialBody, PaymentSyncService.buildReplayBody(retried, retried.venueId, true))
            assertEquals(first.id, retried.id)
        } finally { replay.stop() }
    }

    @Test fun `split mutation while the payment post waits cannot relabel the captured payment`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } coAnswers {
            started.complete(Unit); release.await(); Result.failure(IOException("sin red"))
        }
        open(orderId = "order-A")
        viewModel.processCashPayment(5000)
        runCurrent(); started.await()
        try { viewModel.setSplitType("FULLPAYMENT") }
        finally { release.complete(Unit); advanceUntilIdle() }
        captured(rows.values.single())
    }
    @Test fun `legacy caller and retry retain the exact original replay values without inferring a partial capture`() = runTest {
        val request = com.avoqado.pos.payment.data.model.CreateOrderRequest(
            items = emptyList(), subtotal = 10000, tip = 500, total = 10500, paymentMethod = "CASH")
        cashPaymentRepository.queueCashPayment(request, "staff", 5000, 500, null,
            orderId = "order-A", idempotencyKey = "legacy-id")
        val row = rows.getValue("legacy-id")
        assertEquals(10000, row.amountCents) // A legacy amount is preserved, not reconstructed from cash/change.
        assertEquals(500, row.tipCents)
        assertEquals("FULLPAYMENT", row.splitType)
        val expected = Json.parseToJsonElement("""{"venueId":"venue-1","amount":10000,"tip":500,"status":"COMPLETED","method":"CASH","splitType":"FULLPAYMENT","staffId":"staff","source":"AVOQADO_ANDROID","idempotencyKey":"legacy-id","isOfflineReplay":true}""")
        assertEquals(expected, Json.parseToJsonElement(PaymentSyncService.buildReplayBody(row, row.venueId, true)))
    }


    @Test fun `recrear la pantalla con otro carrito conserva la copia del cobro original en todos los splits`() = runTest {
        for (split in listOf("FULLPAYMENT", "BYPRODUCT", "EQUALPARTS", "CUSTOMAMOUNT")) {
            val apertura = "apertura-$split"
            val a = otroImporte().copy(carritoGuardadoId = "A")
            val b = otroImporte(20_000).copy(carritoGuardadoId = "B")
            viewModel.setSplitConfig(split, selectedItemIds = listOf("linea"), numberOfParts = 2, customAmountCents = 4000)
            viewModel.startPaymentFlow(a, apertura = apertura)
            viewModel.submitTip(0)
            viewModel.selectPaymentMethod(PaymentMethod.CASH)
            viewModel.confirmPayment()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is PaymentFlowState.Success)
            // La Activity recompone con B, pero la misma apertura no reinicia el VM de A.
            viewModel.startPaymentFlow(b, apertura = apertura)
            val completion = viewModel.consumeCompletion()!!
            assertEquals(split, completion.splitType)
            assertEquals("A", completion.carritoGuardadoDelCobro)
            assertNull(viewModel.consumeCompletion())
        }
    }

    @Test fun `completion conserva venta sin guardar original y consumidor real rechaza otra venta sin guardar`() = runTest {
        val prefs = com.avoqado.pos.pos.presentation.cart.PrefsDeDosCapas()
        val repo = com.avoqado.pos.pos.data.SavedCartsRepository(prefs.context)
        val cart = com.avoqado.pos.pos.presentation.cart.CartPromotionTest().apply { setup() }.createViewModel(repo)
        cart.addCustomAmount(name = "A", amountCents = 10000)
        val a = cart.cartState.value
        viewModel.startPaymentFlow(a, apertura = "venta-A")
        viewModel.submitTip(500)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(10500)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        cart.clearCart()
        cart.addCustomAmount(name = "B", amountCents = 20000)
        val b = cart.cartState.value
        viewModel.startPaymentFlow(b, apertura = "venta-A")
        val completion = viewModel.consumeCompletion()!!
        assertEquals(a.identidadDeVenta, completion.identidadDeVenta)
        assertEquals(10000, rows.values.single().amountCents)
        assertEquals(500, rows.values.single().tipCents)
        val result = cart.aplicarCobroConfirmado(completion.splitType, completion.paidItemIds,
            completion.remainingBalanceCents, completion.premioConfirmadoCents,
            completion.carritoGuardadoDelCobro, completion.identidadDeVenta)
        assertFalse(result.aplicadoAlCarritoActual)
        assertEquals(b, cart.cartState.value)
        assertNull(viewModel.consumeCompletion())
    }
}
