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
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import com.avoqado.pos.printing.data.model.ReceiptData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D6 (18-nov-2026): el PAPEL del cobro de ANDROID (y WINDOWS) suma la cortesía al subtotal y al descuento, como el recibo digital y la
 * reimpresión (D41 «C», founder 16-nov): Coca $25 + Agua $20 de cortesía + Café $30 con 10 % de cuenta ⇒ $75 · −$25.50 · $49.50, la Agua
 * a $20. Sólo en el cobro COMPLETO de una venta de un solo cobro (el caso en que el SERVIDOR también la desglosa); en Mesas con la cortesía
 * de la cuenta que dejó «Pagar» (`cortesiaCents`). Lo cobrado no cambia: sólo lo impreso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PapelDelCobroConCortesiaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    /** Lo que cada `/pay`, cada reserva y cada fila de la cola recibió como copia (null = no viajó). */
    private val enviadas = mutableListOf<String?>()
    private val reservadas = mutableListOf<String?>()
    private val encoladas = mutableListOf<String?>()

    private lateinit var viewModel: PaymentFlowViewModel
    private val mesas = TableSession()
    private val impreso = slot<ReceiptData>()

    private val cafe = CartItem(id = "cafe", type = CartItemType.ProductItem("prod-cafe"), name = "Café", unitPrice = 3000)

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-nueva")))
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(),
                importeAceptadoCents = any(), capturaPrevia = any(), lineasDelTicket = any())
        } answers {
            enviadas += arg<String?>(11)
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        }
        coEvery {
            cashPaymentRepository.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), lineasDelTicket = any())
        } answers { reservadas += arg<String?>(18) }
        coEvery {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), lineasDelTicket = any())
        } answers { encoladas += arg<String?>(17); "fila-1" }
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()

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
                printConfigRepository,
                ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
            tableSession = mesas,
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    private val coca = CartItem(id = "coca", type = CartItemType.ProductItem("prod-coca"), name = "Coca", unitPrice = 2500)
    private val aguaDeCortesia = CartItem(id = "agua-c", type = CartItemType.ProductItem("prod-agua"), name = "Agua", unitPrice = 2000,
        isCortesia = true)
    private val diezPorCiento = Discount(id = "d10", name = "Diez", value = 10.0, type = "PERCENTAGE")

    private fun cobrar(cart: CartState, tipo: String = "FULLPAYMENT", monto: Int? = null, resumeOrderId: String? = null, recibido: Int = 100_000) {
        viewModel.setSplitConfig(tipo, customAmountCents = monto)
        viewModel.startPaymentFlow(cart, resumeOrderId = resumeOrderId)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(recibido)
    }

    /** La mesa en PAYING como la deja «Pagar» (`preparePagar`), con su saldo, su cargo y su cortesía en $0. */
    private fun mesaEnCobro(saldo: Int, cargo: Int = 0, cortesia: Int = 0) = mesas.start(
        TableSession.Active(tableId = "mesa-5", tableNumber = "5", areaName = null, orderId = "order-mesa-5", orderNumber = "55", version = 1,
            totalCents = saldo, mode = TableSession.Mode.PAYING, serviceChargeCents = cargo, cortesiaCents = cortesia),
    )

    /** El carrito con que la pantalla de la mesa abre el cobro (`TableOrderScreen`): «Cuenta Mesa N» al subtotal y el descuento aparte. */
    private fun carritoDeLaMesa(subtotal: Int, descuento: Int = 0) = CartState(
        items = listOf(CartItem(id = "cuenta", type = CartItemType.CustomAmount, name = "Cuenta Mesa 5", unitPrice = subtotal)),
        orderDiscount = if (descuento > 0) Discount(id = "order-discount", name = "Descuento", value = descuento / 100.0, type = "FIXED") else null,
    )

    private fun cuadra(r: ReceiptData) = r.total == r.subtotal - (r.discountAmount ?: 0) + (r.serviceChargeAmount ?: 0) + r.taxAmount + (r.tipAmount ?: 0)

    // NEW FEATURE TESTS

    @Test
    fun `Mostrador - Coca 25, Agua 20 de cortesia y Cafe 30 con 10 por ciento - 75 menos 25_50, la Agua a 20 como el digital`() = runTest {
        cobrar(CartState(items = listOf(coca, aguaDeCortesia, cafe), orderDiscount = diezPorCiento))
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(7500, 2550, 4950), listOf(r.subtotal, r.discountAmount, r.total))
        val agua = r.items.single { it.name == "Agua" }
        assertEquals(2000, agua.totalPrice)
        assertFalse("su precio se imprime: el regalo va en el descuento", agua.isCortesia)
        assertEquals(r.subtotal, r.items.sumOf { it.totalPrice })
        assertTrue(cuadra(r))
    }

    @Test
    fun `Mesa sin abonos - Cuenta Mesa 5 a 75, descuento 25_50 y total 49_50, como el digital`() = runTest {
        mesaEnCobro(saldo = 4950, cortesia = 2000)
        cobrar(carritoDeLaMesa(subtotal = 5500, descuento = 550), recibido = 4950)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(7500, 2550, 4950), listOf(r.subtotal, r.discountAmount, r.total))
        assertEquals(7500, r.items.single().totalPrice)
        assertTrue(cuadra(r))
    }

    @Test
    fun `Mesa con cargo por servicio - conserva su renglon de cargo y suma la cortesia (Codex r1 4 de naec8c3f)`() = runTest {
        mesaEnCobro(saldo = 6000, cargo = 500, cortesia = 2000)
        cobrar(carritoDeLaMesa(subtotal = 6000), recibido = 6000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(500, r.serviceChargeAmount)
        assertEquals(listOf(7500, 2000, 6000), listOf(r.subtotal, r.discountAmount, r.total))
        assertEquals(7500, r.items.single().totalPrice)
        assertTrue(cuadra(r))
    }

    @Test
    fun `Mesa - una cortesia mayor que el saldo tambien entra (plato 80 regalado, bebida 20 cobrada) (Codex r1 3 de naec8c3f)`() = runTest {
        mesaEnCobro(saldo = 2000, cortesia = 8000)
        cobrar(carritoDeLaMesa(subtotal = 2000), recibido = 2000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(10000, 8000, 2000), listOf(r.subtotal, r.discountAmount, r.total))
        assertTrue(cuadra(r))
    }

    // REGRESSION TESTS

    @Test
    fun `Mostrador que continua una venta con abono - como hoy, la Agua CORTESIA fuera del subtotal`() = runTest {
        cobrar(CartState(items = listOf(aguaDeCortesia, cafe)), resumeOrderId = "order-parte-1")
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(3000, r.subtotal)
        assertTrue(r.items.single { it.name == "Agua" }.isCortesia)
        assertEquals(0, r.items.single { it.name == "Agua" }.totalPrice)
    }

    @Test
    fun `Mostrador partido por producto - como hoy`() = runTest {
        viewModel.setSplitConfig("BYPRODUCT", selectedItemIds = listOf("agua-c", "cafe"))
        viewModel.startPaymentFlow(CartState(items = listOf(coca, aguaDeCortesia, cafe)))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(100_000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(3000, r.subtotal)
        assertTrue(r.items.single { it.name == "Agua" }.isCortesia)
    }

    @Test
    fun `Mostrador - una pieza de combo dada de cortesia queda fuera, como en el digital`() = runTest {
        val pieza = aguaDeCortesia.copy(id = "agua-combo", promotionInstanceId = "c1", promotionName = "Combo")
        cobrar(CartState(items = listOf(coca, pieza)))
        advanceUntilIdle()

        assertEquals(2500, impreso.captured.subtotal)
    }

    @Test
    fun `Mesa sin el campo del SERVIDOR (cortesia 0) - como hoy`() = runTest {
        mesaEnCobro(saldo = 4950)
        cobrar(carritoDeLaMesa(subtotal = 5500, descuento = 550), recibido = 4950)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(5500, 550, 4950), listOf(r.subtotal, r.discountAmount, r.total))
    }

    @Test
    fun `Mesa - un pago parcial imprime solo lo cobrado, sin la cortesia`() = runTest {
        mesaEnCobro(saldo = 4950, cortesia = 2000)
        cobrar(carritoDeLaMesa(subtotal = 5500, descuento = 550), tipo = "CUSTOMAMOUNT", monto = 2000, recibido = 2000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(2000, r.subtotal)
        assertNull(r.discountAmount)
    }

    @Test
    fun `Mostrador sin cortesia - no cambia nada`() = runTest {
        cobrar(CartState(items = listOf(coca, cafe), orderDiscount = diezPorCiento))
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(5500, 550, 4950), listOf(r.subtotal, r.discountAmount, r.total))
    }
}
