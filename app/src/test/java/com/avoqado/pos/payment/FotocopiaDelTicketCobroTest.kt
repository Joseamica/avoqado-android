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
import com.avoqado.pos.payment.domain.OrigenDelCobro
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * D1 «fotocopia del ticket» (28-oct): el cobro de UNA parte de una venta dividida manda la copia de los renglones de su papel —la
 * misma regla de `buildReceiptSnapshot`— en `/pay`, la guarda con su reserva ANTES de la red y con su fila de la cola si no hay red.
 * Una venta de un solo cobro no la manda. Lo que se manda es lo que el ViewModel le pasa a los repositorios (los dos de verdad tienen
 * su prueba en `CopiaDelTicketEnElCobroTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FotocopiaDelTicketCobroTest {

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

    private val cafe = CartItem(id = "cafe", type = CartItemType.ProductItem("prod-cafe"), name = "Café", unitPrice = 3000)
    private val agua = CartItem(id = "agua", type = CartItemType.ProductItem("prod-agua"), name = "Agua", unitPrice = 2000)

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
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
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
            tableSession = TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    private fun cobrarEnEfectivo(
        cart: CartState,
        tipo: String = "FULLPAYMENT",
        elegidos: List<String> = emptyList(),
        partes: Int? = null,
        resumeOrderId: String? = null,
        origen: OrigenDelCobro = OrigenDelCobro.NORMAL,
        recibido: Int = 100_000,
    ) {
        viewModel.setSplitConfig(tipo, selectedItemIds = elegidos, numberOfParts = partes)
        viewModel.startPaymentFlow(cart, resumeOrderId = resumeOrderId, origen = origen)
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.processCashPayment(recibido)
    }

    private fun leer(json: String?): JsonObject = Json.parseToJsonElement(requireNotNull(json) { "no viajó copia" }).jsonObject
    private fun JsonObject.nombres() = getValue("lineas").jsonArray.map { it.jsonObject.getValue("nombre").jsonPrimitive.content }
    private fun JsonObject.cents(llave: String) = getValue(llave).jsonPrimitive.int

    @Test
    fun `por producto - Cafe sola de Cafe 30 y Agua 20 manda Cafe 3000, 3000 y 0 en la reserva y en el pay`() = runTest {
        cobrarEnEfectivo(CartState(items = listOf(cafe, agua)), "BYPRODUCT", elegidos = listOf("cafe"))
        advanceUntilIdle()

        val copia = leer(enviadas.single())
        assertEquals(listOf("Café"), copia.nombres())
        assertEquals(3000, copia.getValue("lineas").jsonArray.single().jsonObject.cents("totalCents"))
        assertEquals(3000, copia.cents("subtotalCents"))
        assertEquals(0, copia.cents("descuentoCents"))
        // ANTES de la red: la reserva ya la llevaba.
        assertEquals(enviadas.single(), reservadas.single())
    }

    @Test
    fun `por producto con 10 por ciento de cuenta - Cafe 3000, subtotal 3000 y descuento 300`() = runTest {
        val cart = CartState(items = listOf(cafe, agua), orderDiscount = Discount(id = "d10", name = "Diez", value = 10.0, type = "PERCENTAGE"))
        cobrarEnEfectivo(cart, "BYPRODUCT", elegidos = listOf("cafe"))
        advanceUntilIdle()

        val copia = leer(enviadas.single())
        assertEquals(listOf("Café"), copia.nombres())
        assertEquals(3000, copia.cents("subtotalCents"))
        assertEquals(300, copia.cents("descuentoCents"))
    }

    @Test
    fun `partes iguales - el renglon Pago parcial que imprime el papel`() = runTest {
        cobrarEnEfectivo(CartState(items = listOf(cafe, agua)), "EQUALPARTS", partes = 2)
        advanceUntilIdle()

        val copia = leer(enviadas.single())
        assertEquals(listOf("Pago parcial"), copia.nombres())
        assertEquals(2500, copia.cents("subtotalCents"))
        assertEquals(0, copia.cents("descuentoCents"))
    }

    @Test
    fun `producto pesado con nota - el peso y la nota viajan en su renglon`() = runTest {
        val queso = CartItem(id = "queso", type = CartItemType.ProductItem("prod-queso"), name = "Queso", unitPrice = 42000, weightKg = 0.435,
            itemNote = "Rebanado")
        cobrarEnEfectivo(CartState(items = listOf(queso, agua)), "BYPRODUCT", elegidos = listOf("queso"))
        advanceUntilIdle()

        val linea = leer(enviadas.single()).getValue("lineas").jsonArray.single().jsonObject
        assertEquals(queso.weightSummary, linea.getValue("peso").jsonPrimitive.content)
        assertEquals("Rebanado", linea.getValue("nota").jsonPrimitive.content)
        assertEquals(queso.grossPrice, linea.cents("totalCents"))
    }

    @Test
    fun `combo - el renglon del combo con su precio y sus piezas en 0`() = runTest {
        val hamburguesa = CartItem(id = "h", type = CartItemType.ProductItem("prod-h"), name = "Hamburguesa", unitPrice = 8000,
            promotionInstanceId = "c1", promotionName = "Combo del día")
        val refresco = CartItem(id = "r", type = CartItemType.ProductItem("prod-r"), name = "Refresco", unitPrice = 2000,
            promotionInstanceId = "c1", promotionName = "Combo del día")
        // Elegir una pieza arrastra a todo su combo (D4).
        cobrarEnEfectivo(CartState(items = listOf(hamburguesa, refresco, agua)), "BYPRODUCT", elegidos = listOf("h"))
        advanceUntilIdle()

        val copia = leer(enviadas.single())
        assertEquals(listOf("Combo del día", "Hamburguesa", "Refresco"), copia.nombres())
        val lineas = copia.getValue("lineas").jsonArray.map { it.jsonObject }
        assertEquals(listOf(10000, 0, 0), lineas.map { it.cents("totalCents") })
        assertEquals(listOf(null, "true", "true"), lineas.map { it["pieza"]?.jsonPrimitive?.content })
        assertEquals(10000, copia.cents("subtotalCents"))
    }

    @Test
    fun `una copia que no cabe en 8 KB no se manda y el cobro sale igual`() = runTest {
        val muchos = (1..90).map { CartItem(id = "p$it", type = CartItemType.ProductItem("prod-$it"), name = "x".repeat(117) + "%03d".format(it),
            unitPrice = 100) }
        cobrarEnEfectivo(CartState(items = muchos + agua), "BYPRODUCT", elegidos = muchos.map { it.id })
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), enviadas)
        assertEquals(listOf<String?>(null), reservadas)
    }

    @Test
    fun `una venta de un solo cobro no manda copia`() = runTest {
        cobrarEnEfectivo(CartState(items = listOf(cafe, agua)))
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), enviadas)
        assertEquals(listOf<String?>(null), reservadas)
    }

    @Test
    fun `la ultima parte (continua la venta que ya tiene un abono) manda la suya`() = runTest {
        cobrarEnEfectivo(CartState(items = listOf(agua)), resumeOrderId = "order-parte-1")
        advanceUntilIdle()

        val copia = leer(enviadas.single())
        assertEquals(listOf("Agua"), copia.nombres())
        assertEquals(2000, copia.cents("subtotalCents"))
        assertEquals(0, copia.cents("descuentoCents"))
    }

    @Test
    fun `el pedido en linea llega con su orden pero no manda copia`() = runTest {
        cobrarEnEfectivo(CartState(items = listOf(agua)), resumeOrderId = "pedido-web", origen = OrigenDelCobro.PEDIDO_EN_LINEA)
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), enviadas)
    }

    @Test
    fun `Cuentas abiertas llega con su orden pero no manda copia - la cuenta puede no tener ningun abono`() = runTest {
        val cuenta = CartState(items = listOf(CartItem(id = "s", type = CartItemType.CustomAmount, name = "Saldo pendiente", unitPrice = 5000)))
        viewModel.configurarSaldo(ventaViva = { cuenta }, desdeCuentas = true)
        cobrarEnEfectivo(cuenta, resumeOrderId = "cuenta-abierta")
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), enviadas)
    }

    @Test
    fun `sin red la fila de la cola guarda la copia`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.failure(IOException("sin red"))
        cobrarEnEfectivo(CartState(items = listOf(cafe, agua)), "BYPRODUCT", elegidos = listOf("cafe"))
        advanceUntilIdle()

        assertEquals(listOf("Café"), leer(encoladas.single()).nombres())
        assertEquals(reservadas.single(), encoladas.single())
        assertEquals(emptyList<String?>(), enviadas)
    }

    @Test
    fun `Confirmar en efectivo de una parte tambien manda su copia`() = runTest {
        viewModel.setSplitConfig("BYPRODUCT", selectedItemIds = listOf("cafe"))
        viewModel.startPaymentFlow(CartState(items = listOf(cafe, agua)))
        viewModel.selectPaymentMethod(PaymentMethod.CASH)
        viewModel.confirmPayment()
        advanceUntilIdle()

        assertNotNull(enviadas.singleOrNull())
        assertEquals(listOf("Café"), leer(enviadas.single()).nombres())
        assertNull(reservadas.singleOrNull()) // «Confirmar» no aparta efectivo (como hoy)
    }
}
