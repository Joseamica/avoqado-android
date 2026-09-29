package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.StampRewardOnOrder
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 DINERO — el premio de la cartilla se COBRA descontado.
 *
 * Defecto encontrado el 2026-09-27: la caja mandaba `stampRewardId`, el servidor quemaba el
 * premio y bajaba la cuenta, pero la caja cobraba el precio de lista (sólo adoptaba el total
 * del servidor en ventas con promoción). Con tarjeta el cliente pagaba de más; en efectivo el
 * premio ni siquiera viajaba.
 *
 * Estándar de Square y Toast: el premio es un descuento en la cuenta ANTES de elegir cómo se
 * paga, el servidor calcula cuánto vale y se cobra el total que ya lo trae. Aquí: el carrito
 * resta un ESTIMADO con la regla del servidor, y al crear la venta manda lo que el servidor
 * CONFIRMA (`stampReward.discountAmount`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremioDeCartillaCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val cafe = CartItem(
        id = "l-cafe",
        type = CartItemType.ProductItem("p-cafe"),
        name = "Café de especialidad",
        unitPrice = 10000,
    )

    private val premio30 = PremioPorAplicar(id = "rw1", etiqueta = "\$30 de premio", tipo = "FIXED_AMOUNT", valor = 30.0)

    /** $100 de café con el premio de $30 aplicado: el carrito dice $70. */
    private val carritoConPremio = CartState(items = listOf(cafe), pendingStampReward = premio30)

    private fun respuesta(premio: StampRewardOnOrder?) = Result.success(
        // El total del servidor NO se usa para esto (tras el canje hoy pierde la propina y el
        // descuento de cuenta — arreglo pendiente en el servidor). Por eso va un número absurdo:
        // si alguien vuelve a leerlo aquí, estas pruebas lo cachan.
        CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 1.0, stampReward = premio)),
    )

    // MARK: - El caso que originó esto

    @Test
    fun `una venta con premio aplicado cobra el total con el premio descontado`() = runTest {
        val premioEnviado = slot<String?>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), captureNullable(premioEnviado), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 30.0, rewardLabel = "\$30 de premio"))
        val monto = slot<Int>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(monto), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("el premio viaja en la creación", "rw1", premioEnviado.captured)
        assertEquals("se cobra \$100 − \$30 que confirmó el servidor", 7000, monto.captured)
    }

    @Test
    fun `P1 en efectivo el premio SI viaja y se cobra descontado`() = runTest {
        // Antes, el camino de efectivo creaba la orden SIN el premio: se perdía en silencio.
        every { cashPaymentRepository.processCashPayment(7000, 10000) } returns CashPaymentResult.Success(changeCents = 3000)
        val premioEnviado = slot<String?>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), captureNullable(premioEnviado), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 30.0))
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals("rw1", premioEnviado.captured)
        assertEquals(7000, cobrado.captured)
        val exito = paymentViewModel.state.value as PaymentFlowState.Success
        assertEquals("con \$100 en la mano, el cambio es de \$30", 3000, exito.changeAmount)
    }

    @Test
    fun `P1 si el servidor confirma otro monto, manda el servidor`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 25.0))
        val monto = slot<Int>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(monto), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("el estimado era \$30; el servidor aplicó \$25", 7500, monto.captured)
    }

    @Test
    fun `P1 si el servidor NO aplico el premio se cobra completo y la caja lo dice`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = false, reason = "Este premio ya fue canjeado."))
        val monto = slot<Int>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(monto), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("el premio no se quemó: se cobra el precio completo", 10000, monto.captured)
        val aviso = paymentViewModel.avisoDelPremio.value
        assertNotNull("nunca en silencio", aviso)
        assertTrue(aviso!!.contains("Este premio ya fue canjeado."))
    }

    @Test
    fun `P1 en efectivo, si el premio no se aplico y el dinero no alcanza, NO se registra y se pide el total real`() = runTest {
        // El cliente dio $70 contra el total con premio, pero el premio se rechazó (hallazgo P1 de
        // Codex, 27-sep): registrar $70 dejaba la cuenta debiendo $30 y la caja la daba por
        // liquidada. Ahora no se registra nada: se vuelve a elegir cómo paga, con el total real.
        every { cashPaymentRepository.processCashPayment(7000, 7000) } returns CashPaymentResult.Success(changeCents = 0)
        every { cashPaymentRepository.processCashPayment(10000, 10000) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = false, reason = "Este premio ya venció."))
        val cobrado = mutableListOf<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(7000)
        advanceUntilIdle()

        assertTrue("no se registra un cobro corto", cobrado.isEmpty())
        val estado = paymentViewModel.state.value
        assertTrue("se vuelve a elegir cómo paga: $estado", estado is PaymentFlowState.SelectingPaymentMethod)
        assertEquals(10000, (estado as PaymentFlowState.SelectingPaymentMethod).amount)
        val aviso = paymentViewModel.avisoDelPremio.value!!
        assertTrue(aviso, aviso.contains("Este premio ya venció."))
        assertTrue(aviso, aviso.contains("\$100.00"))

        // El cajero cobra el total real: se registra contra la MISMA orden, sin crear otra.
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()
        assertEquals(listOf(10000), cobrado)
        io.mockk.coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si el premio fue rechazado y el pago se encola, la cola guarda lo que de verdad se cobro`() = runTest {
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = false, reason = "Este premio ya fue canjeado."))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(java.net.UnknownHostException())
        val encolado = slot<CreateOrderRequest>()
        coEvery {
            cashPaymentRepository.queueCashPayment(
                capture(encolado), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns "queued-1"

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals("el pago encolado vale lo cobrado ($100), no el estimado del carrito ($70)", 10000, encolado.captured.total)
    }

    @Test
    fun `P1 en pago dividido con premio rechazado el resto se mide contra el precio completo`() = runTest {
        every { cashPaymentRepository.processCashPayment(5000, 5000) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = false, reason = "Este premio ya fue canjeado."))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(5000)
        advanceUntilIdle()

        val completion = paymentViewModel.consumeCompletion()!!
        assertEquals("$100 − $50 cobrados = $50, no los $20 del estimado", 5000, completion.remainingBalanceCents)
    }

    @Test
    fun `P1 un premio que deja la cuenta en cero cierra la venta sin mandar $0 a la terminal`() = runTest {
        val premioTotal = PremioPorAplicar(id = "rw1", etiqueta = "Café gratis", tipo = "FIXED_AMOUNT", valor = 100.0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 100.0))
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.startPaymentFlow(CartState(items = listOf(cafe), pendingStampReward = premioTotal))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
        assertEquals("la cuenta en $0 se cierra con un registro de $0", 0, cobrado.captured)
        assertTrue(paymentViewModel.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `P1 el ticket IMPRESO cuadra - el premio sale como descuento`() = runTest {
        every { cashPaymentRepository.processCashPayment(7000, 7000) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 30.0))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<com.avoqado.pos.printing.data.model.ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impreso)) } returns mockk(relaxed = true)

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(7000)
        advanceUntilIdle()
        paymentViewModel.reprintReceipt()
        advanceUntilIdle()

        assertEquals(10000, impreso.captured.subtotal)
        assertEquals(3000, impreso.captured.discountAmount)
        assertEquals(7000, impreso.captured.total)
    }

    @Test
    fun `P1 sin red, una venta con premio NO se encola ni se registra - el premio necesita internet`() = runTest {
        // Regla offline §5 (y Square): el canje de lealtad es online a propósito. Encolar el premio
        // abría huecos de dinero al reconectar (hallazgos de Codex, rondas 1 y 2). Sin red se dice y
        // el cajero reintenta o quita el premio.
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(java.net.UnknownHostException())

        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(7000)
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        io.mockk.coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any()) }
        val estado = paymentViewModel.state.value
        assertTrue("$estado", estado is PaymentFlowState.Error)
        assertTrue((estado as PaymentFlowState.Error).message.contains("necesita internet"))
    }

    @Test
    fun `en pago dividido la parte que eligio el cajero no se toca`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 30.0))
        val monto = slot<Int>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(monto), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(carritoConPremio)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("el resto lo dice el saldo del servidor", 5000, monto.captured)
    }

    @Test
    fun `P1 el ticket cuadra - el premio sale como descuento con el monto que confirmo el servidor`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any())
        } returns respuesta(StampRewardOnOrder(applied = true, discountAmount = 25.0))
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(carritoConPremio)
        // Antes de crear la venta, el ticket usa el estimado del carrito.
        paymentViewModel.buildPaymentContext().let {
            assertEquals(3000, it.discountCents)
            assertEquals(it.subtotalCents - it.discountCents, it.totalCents)
        }
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        // Ya creada: manda lo que el servidor confirmó ($25), y subtotal − descuento = total.
        val ticket = paymentViewModel.buildPaymentContext()
        assertEquals(2500, ticket.discountCents)
        assertEquals(7500, ticket.totalCents)
        assertEquals(ticket.subtotalCents - ticket.discountCents, ticket.totalCents)
    }

    @Test
    fun `sin premio, la venta se cobra exactamente como antes`() = runTest {
        val premioEnviado = slot<String?>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), captureNullable(premioEnviado), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 1.0)))
        val monto = slot<Int>()
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(monto), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(CartState(items = listOf(cafe)))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertNull(premioEnviado.captured)
        assertEquals(10000, monto.captured)
        assertNull(paymentViewModel.avisoDelPremio.value)
    }

    // MARK: - El contrato con el servidor

    @Test
    fun `P1 con premio el pedido declara que la caja lo descuenta`() {
        val conPremio = Json.parseToJsonElement(
            OrderRepository.buildCreateOrderPayload(
                request = CreateOrderRequest(items = emptyList(), subtotal = 0, total = 0, paymentMethod = "CASH"),
                staffId = "s1",
                stampRewardId = "rw1",
                stampRewardExpectedDiscount = 3000,
            ),
        ).jsonObject
        val sinPremio = Json.parseToJsonElement(
            OrderRepository.buildCreateOrderPayload(
                request = CreateOrderRequest(items = emptyList(), subtotal = 0, total = 0, paymentMethod = "CASH"),
                staffId = "s1",
            ),
        ).jsonObject

        // Sin `stampRewardAware` el servidor ignora el premio: es el candado para las cajas viejas.
        assertEquals("rw1", conPremio["stampRewardId"]!!.jsonPrimitive.content)
        assertTrue(conPremio["stampRewardAware"]!!.jsonPrimitive.boolean)
        assertEquals(3000, conPremio["stampRewardExpectedDiscount"]!!.jsonPrimitive.content.toInt())
        assertFalse(sinPremio.containsKey("stampRewardExpectedDiscount"))
        assertFalse(sinPremio.containsKey("stampRewardId"))
        assertFalse("sin premio el cuerpo queda como siempre", sinPremio.containsKey("stampRewardAware"))
    }

    @Test
    fun `el resultado del premio se lee de la respuesta real del servidor`() {
        val json = """{"id":"order-1","orderNumber":"ORD-1","total":75,
            "stampReward":{"applied":true,"discountAmount":30,"rewardLabel":"${'$'}30 de premio"}}"""
        val orden = Json { ignoreUnknownKeys = true }.decodeFromString(OrderData.serializer(), json)

        assertEquals(true, orden.stampReward?.applied)
        assertEquals(3000, orden.stampReward?.discountCents)
    }

    // MARK: - Andamiaje

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

    private lateinit var paymentViewModel: PaymentFlowViewModel

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns
            TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(
                listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)),
            )
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 0, printed = 0, skippedNoPrinter = 0, lastError = null)

        paymentViewModel = PaymentFlowViewModel(
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
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
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
}
