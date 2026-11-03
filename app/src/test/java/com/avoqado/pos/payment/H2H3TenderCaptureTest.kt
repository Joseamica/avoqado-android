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
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import com.avoqado.pos.cashdrawer.*
import com.avoqado.pos.payment.domain.*
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.core.data.sync.*
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import io.mockk.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertFalse

/** H2/H3: real VM + cash repository/drawer and outbox serialization/replay. */
@OptIn(ExperimentalCoroutinesApi::class)
class H2H3TenderCaptureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val secureStorageForRepository = mockk<SecureStorage>(relaxed = true)
    private val sql = mockk<PendingPaymentDao>(relaxed = true)
    // Los defaults suspendidos son reales: callOriginal de MockK puede perder la espera de un retorno Unit.
    private val dao = object : PendingPaymentDao by sql {
        override suspend fun insert(payment: PendingPaymentEntity) = super<PendingPaymentDao>.insert(payment)
        override suspend fun insertTransactionQ16(payment: PendingPaymentEntity) = super<PendingPaymentDao>.insertTransactionQ16(payment)
        override suspend fun insertRaw(payment: PendingPaymentEntity) = super<PendingPaymentDao>.insertRaw(payment)
    }
    private val rows = linkedMapOf<String, PendingPaymentEntity>()
    private val writes = mutableListOf<PendingPaymentEntity>()
    private val pendingWritten = CompletableDeferred<Unit>()
    private val compuertasPropias = mutableListOf<CompletableDeferred<Unit>>()
    // Rediseño r9: la salida de `EN_VUELO` anota el cajón (el mismo cajón de verdad que el ViewModel usaba antes).
    private val salida by lazy { com.avoqado.pos.payment.data.SalidaDelApartado(dao, drawerDao, secureStorageForRepository) }
    private val cashPaymentRepository by lazy { CashPaymentRepository(secureStorageForRepository, dao, salida) }
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = secureStorageForRepository
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val estadoEnCola = MutableStateFlow<String?>("PENDING")

    private val tableSession = TableSession()
    private val intentDao = mockk<SyncIntentDao>(relaxed = true)
    private val api = mockk<ApiService>(relaxed = true)
    init { com.avoqado.pos.core.data.sync.vincularCallFixtureQ16(api) }
    private val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
    private val online = MutableStateFlow(false)
    private val intents = CopyOnWriteArrayList<SyncIntentEntity>()
    private val replayRequests = CopyOnWriteArrayList<SyncIntentsRequest>()
    private lateinit var outbox: SyncOutbox
    private val temporary = Files.createTempDirectory("h2-h3-").toFile()
    private val drawerDao = FakeCashDrawerDao()
    private val drawerPushes = CopyOnWriteArrayList<okhttp3.Request>()
    private var drawerStarted = false
    private var drawerFinished = CompletableDeferred<Unit>()
    private lateinit var customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState
    private lateinit var viewModel: PaymentFlowViewModel

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)),
    )

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = true)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        coEvery { sql.insertRawSqlQ16(any()) } coAnswers {
            val row = firstArg<PendingPaymentEntity>()
            rows[row.id] = row
            writes += row
            if (row.syncStatus == "PENDING") pendingWritten.complete(Unit)
        }
        coEvery { sql.borrarSiEnVuelo(any()) } coAnswers {
            val id = firstArg<String>()
            if (rows[id]?.syncStatus == "EN_VUELO") { rows.remove(id); 1 } else 0
        }
        every { sql.observarEstado(any()) } answers { flowOf(rows[firstArg<String>()]?.syncStatus) }
        coEvery { sql.findByIdQ15(any()) } coAnswers { rows[firstArg<String>()] }
        coEvery { sql.findForVenue(any(), any()) } coAnswers {
            rows[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() }
        }
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.failure(IOException("sin red"))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } returns Result.failure(IOException("sin red"))
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns Result.failure(IOException("sin red"))
        val client = mockk<okhttp3.OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<okhttp3.Request>()
            drawerPushes += request
            mockk<okhttp3.Call> {
                every { execute() } returns okhttp3.Response.Builder().request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
        }
        val realDrawer = CashDrawerRepository(drawerDao, secureStorage, client, sinCobrosEnCola(), conectividadDePrueba())
        drawerDao.sessions["drawer-A"] = sesionLocal("drawer-A", openedAt = 1L)
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } coAnswers {
            drawerStarted = true
            try { realDrawer.addCashSale(firstArg(), secondArg(), arg(2), arg(3), arg(4), arg(5)) }
            finally { drawerFinished.complete(Unit) }
        }
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null

        val context = mockk<android.content.Context>(relaxed = true)
        val preferences = mockk<android.content.SharedPreferences>(relaxed = true)
        every { preferences.getString(any(), any()) } returns "h2-device"
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { context.filesDir } returns temporary
        every { connectivity.isConnected } returns online
        every { connectivity.isServerReachable } returns online
        coEvery { cashDrawerRepository.sincronizarCajonPrimero() } returns true
        coEvery { intentDao.insertWithNextSeq(any()) } coAnswers {
            val seq = intents.size.toLong() + 1
            intents += firstArg<SyncIntentEntity>().copy(seq = seq)
            seq
        }
        coEvery { intentDao.pendingFifo(any(), any()) } coAnswers { intents.filter { it.status == "PENDING" } }
        coEvery { api.syncIntents(any(), any()) } coAnswers {
            replayRequests += secondArg<SyncIntentsRequest>()
            throw IOException("respuesta perdida") // keep the same durable row/key for retry
        }
        outbox = SyncOutbox(intentDao, api, connectivity, secureStorage, cashDrawerRepository,
            context, mockk(relaxed = true), tableSession)
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState()
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
            tableSession = tableSession,
            syncOutbox = outbox,
            customerDisplay = customerDisplay,
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

    @After fun cleanup() = runBlocking {
        try {
            compuertasPropias.forEach { it.complete(Unit) }
            if (::viewModel.isInitialized) viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        } finally {
            if (::customerDisplay.isInitialized) customerDisplay.idle()
            if (::outbox.isInitialized) outbox.stop()
            temporary.deleteRecursively()
        }
    }

    private fun tender(physical: Boolean? = false, system: Boolean = false, base: String = "OTHER") =
        TenderTypeOption("tender-A", 7, "Medio A", system, base, true, "MORE", 1, physical)

    private suspend fun TestScope.finishCash() {
        runCurrent()
        if (drawerStarted) drawerFinished.await()
        advanceUntilIdle()
    }

    private fun assertDrawer(count: Int, pushes: Int = count) {
        val events = drawerDao.events.values.filter { it.type == "CASH_SALE" }
        assertEquals(count, events.size)
        assertEquals(pushes, drawerPushes.size)
        events.forEach { assertEquals(4500, it.amountCents); assertEquals("venue-1", it.venueId) }
    }

    private fun provisional() {
        tableSession.start(TableSession.Active("table-A", "5", null, "local-A", "LOCAL-A", 0, 4000,
            TableSession.Mode.PAYING, isProvisional = true))
        open(orderId = "local-A")
    }

    @Test fun `provisional captures catalog identity and revision through real outbox replay retry`() = runTest {
        provisional()
        viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender()))
        finishCash()
        val row = intents.single()
        val payload = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertEquals("tender-A", payload["tenderTypeId"]?.jsonPrimitive?.content)
        assertEquals(7, payload["tenderRevision"]?.jsonPrimitive?.int)
        assertEquals(4000, payload.getValue("amountCents").jsonPrimitive.int)
        assertEquals(500, payload.getValue("tipCents").jsonPrimitive.int)
        assertFalse(payload.containsKey("method")); assertFalse(payload.containsKey("externalSource"))
        // D1: la parte de una mesa abierta sin red va por el intent PAY_CASH, que NUNCA lleva la copia del ticket (fuera de la promesa).
        assertFalse(payload.containsKey("lineasDelTicket"))
        assertDrawer(0)
        // Enqueue wrote while offline. Start owns the real producer; two failed replays keep bytes/key.
        val started = CompletableDeferred<Unit>()
        coEvery { intentDao.deleteOldAcked(any()) } coAnswers { started.complete(Unit); Unit }
        outbox.start("venue-1"); started.await()
        online.value = true
        outbox.replayNow("venue-1"); outbox.replayNow("venue-1")
        assertTrue(replayRequests.size >= 2)
        replayRequests.forEach { request ->
            val wire = request.intents.single()
            assertEquals(row.id, wire.id); assertEquals(payload, wire.payload)
            assertEquals(row.seq, wire.seq)
        }
        assertEquals(row, intents.single())
        outbox.stop()
    }

    /**
     * P1 (A2, efectivo sin red en el turno correcto) — la mesa abierta sin red se cobra por el outbox (`PAY_CASH`): su payload
     * lleva la caja que estaba abierta al confirmar, para que el servidor sepa el turno de la venta aunque suba tarde.
     */
    @Test fun `P1 el PAY_CASH de una mesa provisional lleva la caja abierta al confirmar`() = runTest {
        coEvery { cashDrawerRepository.getOpenSession() } returns sesionLocal("caja-A-local", openedAt = 1L)
        provisional()
        viewModel.processCashPayment(4500); finishCash()
        val payload = Json.parseToJsonElement(intents.single().payloadJson).jsonObject
        assertEquals("caja-A-local", payload["cashDrawerSessionId"]?.jsonPrimitive?.content)
    }

    @Test fun `provisional legacy manual keeps method without fabricated tender`() = runTest {
        provisional()
        val manual = ManualPaymentMethod.values().first()
        viewModel.confirmManualChoice(ManualPaymentChoice.Fixed(manual)); finishCash()
        val body = Json.parseToJsonElement(intents.single().payloadJson).jsonObject
        assertEquals(manual.serverMethod, body.getValue("method").jsonPrimitive.content)
        assertFalse(body.containsKey("tenderTypeId")); assertFalse(body.containsKey("tenderRevision"))
        assertDrawer(0)
    }

    /** Rediseño r9: con apartado, la venta la anota su salida de `EN_VUELO` (aquí, al encolar sin red), sin el POST de compatibilidad (N3). */
    @Test fun `plain cash still creates one drawer event from its EN_VUELO exit and no compatibility push`() = runTest {
        coEvery { cashDrawerRepository.getOpenSession() } returns sesionLocal("drawer-A", openedAt = 1L)
        open(); viewModel.processCashPayment(4500); finishCash(); assertDrawer(1, pushes = 0)
        assertEquals("drawer-A", drawerDao.events.values.single().sessionId)
    }

    @Test fun `catalog physical explicit true creates one drawer event`() = runTest {
        open(); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(true)))
        finishCash(); assertDrawer(1)
    }

    @Test fun `catalog non physical explicit false creates no drawer event or push`() = runTest {
        open(); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false)))
        finishCash(); assertDrawer(0)
        assertEquals("tender-A", rows.values.single().tenderTypeId)
    }

    @Test fun `old server absent physical flag uses SYSTEM CASH fallback only`() = runTest {
        open(); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(null, true, "CASH")))
        finishCash(); assertDrawer(1)
    }

    @Test fun `old server custom tender without physical flag creates no drawer event`() = runTest {
        open(); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(null)))
        finishCash(); assertDrawer(0)
    }

    @Test fun `catalog false overrides even SYSTEM CASH`() = runTest {
        open(); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false, true, "CASH")))
        finishCash(); assertDrawer(0)
    }

    @Test fun `held order post keeps non physical captured tender after selection changes to cash`() = runTest {
        val hold = CompletableDeferred<Unit>().also { compuertasPropias += it }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } coAnswers {
            hold.await(); Result.failure(IOException("sin red"))
        }
        open(orderId = "order-A")
        viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false))); runCurrent()
        assertEquals("tender-A", rows.values.single().tenderTypeId) // EN_VUELO snapshot before POST
        try { viewModel.confirmCashPreset(4500) } // isProcessing prevents second payment, selector changes
        finally { hold.complete(Unit) }
        finishCash(); assertDrawer(0)
        val row = rows.values.single()
        assertEquals("tender-A", row.tenderTypeId); assertEquals(7, row.tenderRevision)
        val body = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(row, row.venueId, true)).jsonObject
        assertEquals("tender-A", body.getValue("tenderTypeId").jsonPrimitive.content)
        assertFalse(body.containsKey("method"))
        coVerify(exactly = 1) { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) }
    }

    @Test fun `held cash post still records physical cash after later noncash selection`() = runTest {
        coEvery { cashDrawerRepository.getOpenSession() } returns sesionLocal("drawer-A", openedAt = 1L)
        val hold = CompletableDeferred<Unit>().also { compuertasPropias += it }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } coAnswers {
            hold.await(); Result.failure(IOException("sin red"))
        }
        open(orderId = "order-A"); viewModel.processCashPayment(4500); runCurrent()
        try { viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false))) }
        finally { hold.complete(Unit) }
        finishCash(); assertDrawer(1, pushes = 0) // rediseño r9: la anota su salida de `EN_VUELO`, sin POST de compatibilidad
        assertNull(rows.values.single().tenderTypeId)
    }

    /**
     * Rediseño r9: el 2xx de A llega con B ya abierta. La venta de A la anota su salida de `EN_VUELO` con los datos de SU fila (negocio,
     * orden, la caja capturada) y la cajera que el flujo de A capturó (agregado 2 de la ronda corta), no el ViewModel con los de la
     * pantalla. Sin POST de compatibilidad (N3).
     */
    @Test fun `physical sale A held POST keeps venue order and actor after opening sale B`() = runTest {
        coEvery { cashDrawerRepository.getOpenSession() } returns sesionLocal("drawer-A", openedAt = 1L)
        val hold = CompletableDeferred<Unit>().also { compuertasPropias += it }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), lineasDelTicket = any()) } coAnswers {
            hold.await()
            // Lo que hace el `OrderRepository` de verdad con un 2xx comprobado (`guardarRespuestaDeCaptura`).
            val llave = arg<String>(5)
            salida.salir(llave, conDosXx = true, delFlujo = true, importe = { 4500 }) { rows[llave] = rows.getValue(llave).copy(syncStatus = "SYNCED"); true }
            Result.success(OrderRepository.CashPayResult("payment-A", null))
        }
        every { secureStorage.userFirstName } returns "Ana"
        every { secureStorage.userLastName } returns "Original"
        open(orderId = "order-A"); viewModel.processCashPayment(4500); runCurrent()
        try {
            drawerDao.sessions["drawer-B"] = sesionLocal("drawer-B", openedAt = 2L).copy(venueId = "venue-B")
            every { secureStorage.venueId } returns "venue-B"
            every { secureStorage.userId } returns "actor-B"
            every { secureStorage.userFirstName } returns "Bea"
            every { secureStorage.userLastName } returns "Nueva"
            viewModel.startPaymentFlow(otroImporte(), resumeOrderId = "order-B")
        } finally { hold.complete(Unit) }
        finishCash()
        val event = drawerDao.events.values.single { it.type == "CASH_SALE" }
        assertEquals("venue-1", event.venueId); assertEquals("drawer-A", event.sessionId)
        assertEquals("order-A", event.orderId); assertEquals("user-456", event.staffId)
        assertEquals("la cajera de A, aunque la sesión ya sea de Bea", "Ana Original", event.staffName); assertEquals(4500, event.amountCents)
        assertTrue(drawerDao.events.values.none { it.venueId == "venue-B" })
        assertTrue("sin el POST de compatibilidad (N3)", drawerPushes.isEmpty())
        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
    }
    @Test fun `zero cash still creates no event or push`() = runTest {
        viewModel.processCashPayment(0); finishCash(); assertDrawer(0)
    }
    @Test fun `held failing POST preserves reservation payment and replay A after opening B`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { compuertasPropias += it }
        val sent = CopyOnWriteArrayList<okhttp3.Request>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); sent += request
            started.complete(Unit)
            kotlinx.coroutines.runBlocking { release.await() }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503).message("respuesta perdida").body("{}".toResponseBody()).build()
        }.build()
        val realOrders = OrderRepository(secureStorage, client)
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), lineasDelTicket = any()) } coAnswers {
            realOrders.recordCashPayment(firstArg(), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7), arg(8))
        }
        open(orderId = "order-A")
        viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false)))
        viewModel.processCashPayment(4500)
        val reserved = try {
            started.await()
            val row = writes.single { it.syncStatus == "EN_VUELO" }
            assertEquals("venue-1", row.venueId); assertEquals("order-A", row.orderId)
            assertEquals(4000, row.amountCents); assertEquals(500, row.tipCents)
            every { secureStorage.venueId } returns "venue-B"
            every { secureStorage.userId } returns "actor-B"
            viewModel.startPaymentFlow(otroImporte(9900), customerId = "customer-B", resumeOrderId = "order-B")
            row
        } finally { release.complete(Unit) }
        pendingWritten.await(); finishCash()
        val pending = rows.values.single { it.syncStatus == "PENDING" }
        assertEquals(reserved.id, pending.id); assertEquals("venue-1", pending.venueId)
        assertEquals("order-A", pending.orderId); assertEquals("user-456", pending.staffId)
        assertEquals("customer-A", pending.customerId); assertEquals("CUSTOMAMOUNT", pending.splitType)
        assertEquals(4000, pending.amountCents); assertEquals(500, pending.tipCents)
        assertEquals("tender-A", pending.tenderTypeId); assertEquals(7, pending.tenderRevision)
        assertEquals(reserved.orderRequestJson, pending.orderRequestJson)
        val originalOrder = Json.parseToJsonElement(requireNotNull(pending.orderRequestJson)).jsonObject
        assertEquals(10000, originalOrder.getValue("subtotal").jsonPrimitive.int)
        assertEquals(10500, originalOrder.getValue("total").jsonPrimitive.int)
        assertEquals(2, originalOrder.getValue("items").jsonArray.size)
        assertEquals("customer-A", originalOrder.getValue("customerId").jsonPrimitive.content)
        assertTrue(rows.values.none { it.venueId == "venue-B" })
        val original = sent.single(); assertTrue(original.url.encodedPath.endsWith("/venues/venue-1/orders/order-A/pay"))
        val buffer = okio.Buffer(); original.body!!.writeTo(buffer)
        val originalBody = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(reserved.id, originalBody.getValue("idempotencyKey").jsonPrimitive.content)
        val replay = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(pending, pending.venueId, true)).jsonObject
        assertEquals("venue-1", replay.getValue("venueId").jsonPrimitive.content)
        assertEquals(reserved.id, replay.getValue("idempotencyKey").jsonPrimitive.content)
        assertEquals(4000, replay.getValue("amount").jsonPrimitive.int)
        assertEquals(500, replay.getValue("tip").jsonPrimitive.int)
        assertEquals("tender-A", replay.getValue("tenderTypeId").jsonPrimitive.content)
        val replayed = CompletableDeferred<Unit>()
        coEvery { sql.getPendingPayments(any()) } returns listOf(pending)
        every { sql.getPendingCount() } returns flowOf(1)
        every { sql.getFailedCount() } returns flowOf(0)
        coEvery { sql.incrementRetry(pending.id, any()) } coAnswers { replayed.complete(Unit); Unit }
        val work = mockk<SyncOutbox>(relaxed = true)
        coEvery { work.trabajoSinResolverDeLaOrden("venue-1", setOf("order-A")) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        val red = mockk<ConnectivityMonitor>(relaxed = true)
        every { red.isConnected } returns MutableStateFlow(true)
        every { red.isServerReachable } returns MutableStateFlow(true)
        val replayService = PaymentSyncService(dao, secureStorage, client, red, cashDrawerRepository, work, io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true))
        replayService.usarContextoDeSincronizacion(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            replayService.start(); replayed.await()
            assertEquals(2, sent.size)
            val replayRequest = sent.last()
            assertTrue(replayRequest.url.encodedPath.endsWith("/venues/venue-1/orders/order-A/pay"))
            val bytes = okio.Buffer(); replayRequest.body!!.writeTo(bytes)
            assertEquals(replay, Json.parseToJsonElement(bytes.readUtf8()).jsonObject)
        } finally { replayService.stop() }
        assertDrawer(0)
    }

    @Test fun `reservation await cannot move the original order request payment or tenant to B`() = runTest {
        val reserved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { compuertasPropias += it }
        coEvery { sql.insertRawSqlQ16(any()) } coAnswers {
            val row = firstArg<PendingPaymentEntity>(); rows[row.id] = row; writes += row
            if (row.syncStatus == "PENDING") pendingWritten.complete(Unit)
            if (row.syncStatus == "EN_VUELO") { reserved.complete(Unit); release.await() }
        }
        val sent = CopyOnWriteArrayList<okhttp3.Request>()
        val finished = CompletableDeferred<Unit>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); sent += request; finished.complete(Unit)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503).message("respuesta perdida").body("{}".toResponseBody()).build()
        }.build()
        val realOrders = OrderRepository(secureStorage, client)
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), lineasDelTicket = any()) } coAnswers {
            realOrders.recordCashPayment(firstArg(), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7), arg(8))
        }
        open(orderId = "order-A"); viewModel.confirmManualChoice(ManualPaymentChoice.Tender(tender(false)))
        viewModel.processCashPayment(4500)
        try {
            reserved.await()
            assertEquals("La escritura de reserva bloqueada debe impedir el POST", 0, sent.size)
            every { secureStorage.venueId } returns "venue-B"; every { secureStorage.userId } returns "actor-B"
            viewModel.startPaymentFlow(otroImporte(9900), customerId = "customer-B", resumeOrderId = "order-B")
        } finally { release.complete(Unit) }
        finished.await(); pendingWritten.await(); finishCash()
        assertEquals("Filas: ${rows.map { (key, value) -> "$key/${value.venueId}/${value.orderId}/${value.syncStatus}" }}", 1, rows.values.count { it.syncStatus == "PENDING" })
        val row = rows.values.single { it.syncStatus == "PENDING" }
        assertEquals("venue-1", row.venueId); assertEquals("order-A", row.orderId)
        assertEquals("customer-A", row.customerId); assertEquals("user-456", row.staffId)
        assertEquals(writes.first().orderRequestJson, row.orderRequestJson)
        assertEquals(writes.first().id, row.id)
        assertTrue(sent.single().url.encodedPath.endsWith("/venues/venue-1/orders/order-A/pay"))
        assertDrawer(0)
    }

    @Test fun `explicit missing tenant cannot become the later selected tenant`() = runTest {
        every { secureStorage.venueId } returns "venue-B"
        val request = com.avoqado.pos.payment.data.model.CreateOrderRequest(
            subtotal = 10000, total = 10000, tip = 0, items = emptyList(), paymentMethod = "CASH",
        )
        val queued = runCatching {
            cashPaymentRepository.queueCashPayment(request, "actor-A", 4500, 0, null,
                idempotencyKey = "missing-venue", amountCents = 4000, tipCents = 500, venueId = "")
        }
        assertTrue(queued.isFailure); assertTrue(rows.isEmpty())
        val reserved = runCatching {
            cashPaymentRepository.reservarCobro(request, "actor-A", 4500, 0, null,
                idempotencyKey = "missing-reservation", amountCents = 4000, tipCents = 500, venueId = "")
        }
        assertTrue(reserved.isFailure); assertTrue(rows.isEmpty())
        val posts = CopyOnWriteArrayList<okhttp3.Request>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            posts += chain.request()
            throw IOException("must not reach transport")
        }.build()
        val actual = OrderRepository(secureStorage, client)
        assertTrue(actual.recordCashPayment("order-A", 4000, "actor-A", capturedVenueId = "").isFailure)
        assertTrue(actual.recordFastCashPayment(4000, "actor-A", capturedVenueId = "").isFailure)
        assertTrue(actual.createOrder(request, "actor-A", capturedVenueId = "").isFailure)
        assertTrue(posts.isEmpty())
    }

}
