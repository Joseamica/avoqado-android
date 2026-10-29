package com.avoqado.pos.payment

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * 🔴 D2, rediseño r9: dos cosas de la venta que ya no está en pantalla, con la API nueva.
 * - Codex r10 #1: el apartado lleva el importe que de verdad se cobra (precio corregido o total adoptado) ANTES de mandar el POST.
 * - Familia B, paridad con iOS: el cobro de la mesa de una venta vieja la libera con SU contexto, sin tocar la mesa de la nueva.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImporteYMesaDeLaVentaViejaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val filas = linkedMapOf<String, PendingPaymentEntity>()
    private var fallaAlActualizarElImporte = false
    /** El disco contesta «0 filas» (el apartado ya no estaba en vuelo). */
    private var actualizarNoEscribeNada = false
    private val dao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
        coEvery { dao.insert(any()) } coAnswers { val f = firstArg<PendingPaymentEntity>(); filas[f.id] = filaAlReemplazar(filas[f.id], f) }
        coEvery { dao.findForVenue(any(), any()) } coAnswers { filas[secondArg<String>()] }
        coEvery { dao.actualizarApartadoEnVuelo(any(), any(), any()) } coAnswers {
            if (fallaAlActualizarElImporte) throw IOException("disco lleno")
            if (actualizarNoEscribeNada) return@coAnswers 0
            val f = filas[firstArg()]?.takeIf { it.syncStatus == "EN_VUELO" } ?: return@coAnswers 0
            filas[f.id] = f.copy(amountCents = secondArg(), orderId = thirdArg()); 1
        }
        every { dao.observarEstado(any()) } answers { flowOf(filas[firstArg<String>()]?.syncStatus) }
        every { dao.getPendingCount() } returns flowOf(0)
        every { dao.getFailedCount() } returns flowOf(0)
    }
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val tableSession = TableSession()
    private val mesas = mockk<TableServiceRepository>(relaxed = true)
    private val lanHub = mockk<LanHubService>(relaxed = true)
    private lateinit var customerDisplay: CustomerDisplayState
    private lateinit var vm: PaymentFlowViewModel
    private var respuestaPendiente: CompletableDeferred<Result<OrderRepository.CashPayResult>>? = null

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)))
        every { terminalPaymentService.intentoEnVuelo() } returns null
        every { terminalPaymentService.contextoDe(any()) } returns null
        every { secureStorage.venueId } returns VENUE
        every { secureStorage.userId } returns "user-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { mesas.clearTable(any(), any()) } returns Result.success(Unit)
        customerDisplay = CustomerDisplayState()
        vm = PaymentFlowViewModel(
            tableSession = tableSession, syncOutbox = mockk(relaxed = true), orderRepository = orderRepository,
            cashPaymentRepository = CashPaymentRepository(secureStorage, dao), tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService, tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk(relaxed = true), cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true),
            kdsRepository = mockk(relaxed = true), kdsOrderBus = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = secureStorage, comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()), replayDeComandas = mockk(relaxed = true),
            customerDisplay = customerDisplay, areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true), savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            tableServiceRepository = mesas, lanHub = lanHub,
        )
    }

    @After
    fun cerrarRecursos() = runBlocking {
        // El VM termina antes de apagar su display para que ningún último estado reactive el timer antes de retirar Main.
        respuestaPendiente?.cancel()
        if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        if (::customerDisplay.isInitialized) customerDisplay.idle()
    }

    private val catalogo = "2026-10-17T15:00:00.000Z"
    private fun cafe(id: String) = CartItem(id = id, type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 5000, catalogAt = catalogo)
    private val dosCafes = CartState(items = listOf(cafe("e1"), cafe("e2")))
    private fun otroImporte(cents: Int) = CartState(items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)))

    // MARK: - Codex r10 #1

    @Test
    fun `con el precio corregido el apartado lleva el importe aceptado antes de mandar el cobro`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "orden-A", total = 80.0, priceChanges = listOf(
                CambioDePrecio("e1", "p1", "Café", catalogo, 5000, 4000), CambioDePrecio("e2", "p1", "Café", catalogo, 5000, 4000),
            ))),
        )
        val enDiscoAlCobrar = mutableListOf<Int?>()
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) } coAnswers {
            enDiscoAlCobrar += filas.values.single().amountCents
            Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
        }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()

        assertTrue(vm.state.value is PaymentFlowState.Success)
        assertEquals("el apartado ya decía 80 cuando salió el POST", listOf<Int?>(8_000), enDiscoAlCobrar)
    }

    /**
     * Agregado 1 de la ronda corta: una venta NUEVA con productos aparta sin orden (aún no existe). En cuanto la orden nace, el apartado
     * la lleva, ANTES del `/pay`: la venta que anote el 2xx o el barrido lleva su orden (la fusión la reclama por orden, la gemela la
     * compara).
     */
    @Test
    fun `la venta nueva con productos lleva su orden en el apartado antes de mandar el cobro`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "orden-A", total = 100.0)))
        val enDiscoAlCobrar = mutableListOf<Pair<String?, Int>>()
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) } coAnswers {
            enDiscoAlCobrar += filas.values.single().let { it.orderId to it.amountCents }
            Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
        }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()

        assertTrue(vm.state.value is PaymentFlowState.Success)
        assertEquals(listOf<Pair<String?, Int>>("orden-A" to 10_000), enDiscoAlCobrar)
    }

    /** Agregado 3: «0 filas» al actualizar el apartado cuenta como fallo: el cobro no sale. */
    @Test
    fun `si actualizar el apartado no escribe ninguna fila el cobro no sale`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "orden-A", total = 100.0)))
        actualizarNoEscribeNada = true

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()

        assertTrue((vm.state.value as PaymentFlowState.Error).message.contains("No se pudo guardar el cobro"))
        coVerify(exactly = 0) { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) }
    }

    @Test
    fun `si no se puede guardar el importe aceptado el cobro no sale`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "orden-A", total = 80.0, priceChanges = listOf(
                CambioDePrecio("e1", "p1", "Café", catalogo, 5000, 4000), CambioDePrecio("e2", "p1", "Café", catalogo, 5000, 4000),
            ))),
        )
        fallaAlActualizarElImporte = true

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()

        assertTrue((vm.state.value as PaymentFlowState.Error).message.contains("No se pudo guardar el cobro"))
        coVerify(exactly = 0) { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) }
    }

    // MARK: - Familia B: la mesa de la venta vieja

    @Test
    fun `el cobro de la mesa de una venta vieja libera SU mesa con su contexto y no toca la de la nueva`() = runTest {
        val mesa5 = TableSession.Active("mesa-5", "5", null, "orden-mesa-5", "M5", 1, 10_000, TableSession.Mode.PAYING)
        val mesa8 = TableSession.Active("mesa-8", "8", null, "orden-mesa-8", "M8", 1, 5_000, TableSession.Mode.PAYING)
        tableSession.start(mesa5, VENUE)
        val respuesta = CompletableDeferred<Result<OrderRepository.CashPayResult>>().also { respuestaPendiente = it }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) } coAnswers { respuesta.await() }

        vm.startPaymentFlow(otroImporte(10_000)) // el cobro de la mesa 5
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        tableSession.start(mesa8, VENUE)
        vm.startPaymentFlow(otroImporte(5_000)) // B: la mesa 8, mientras la 5 espera su respuesta
        respuesta.complete(Result.success(OrderRepository.CashPayResult(paymentId = "pago-5", receiptAccessKey = null, orderPaymentStatus = "PAID")))
        advanceUntilIdle()

        coVerify { mesas.clearTable(VENUE, "mesa-5") }
        coVerify { lanHub.release("mesa-5") }
        coVerify(exactly = 0) { mesas.clearTable(any(), "mesa-8") }
        assertEquals("la mesa de B sigue activa", "mesa-8", tableSession.current()?.tableId)
        assertTrue("la orden de la mesa 5 quedó finalizada", tableSession.requiereRevisarSaldo(VENUE, setOf("orden-mesa-5")))
    }

    /** Agregado 8: la mesa de una venta vieja también se libera cuando su cobro va por el `/pay` legado de «Confirmar». */
    @Test
    fun `el pay legado de la mesa de una venta vieja tambien libera SU mesa`() = runTest {
        val mesa5 = TableSession.Active("mesa-5", "5", null, "orden-mesa-5", "M5", 1, 10_000, TableSession.Mode.PAYING)
        val mesa8 = TableSession.Active("mesa-8", "8", null, "orden-mesa-8", "M8", 1, 5_000, TableSession.Mode.PAYING)
        tableSession.start(mesa5, VENUE)
        val respuesta = CompletableDeferred<Result<OrderRepository.CashPayResult>>().also { respuestaPendiente = it }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any(), lineasDelTicket = any()) } coAnswers { respuesta.await() }

        vm.startPaymentFlow(otroImporte(10_000)) // el cobro de la mesa 5, por «Confirmar» en efectivo
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        tableSession.start(mesa8, VENUE)
        vm.startPaymentFlow(otroImporte(5_000)) // B
        respuesta.complete(Result.success(OrderRepository.CashPayResult(paymentId = "pago-5", receiptAccessKey = null, orderPaymentStatus = "PAID")))
        advanceUntilIdle()

        coVerify { mesas.clearTable(VENUE, "mesa-5") }
        coVerify(exactly = 0) { mesas.clearTable(any(), "mesa-8") }
        assertEquals("mesa-8", tableSession.current()?.tableId)
    }

    private companion object { const val VENUE = "venue-mesa" }
}
