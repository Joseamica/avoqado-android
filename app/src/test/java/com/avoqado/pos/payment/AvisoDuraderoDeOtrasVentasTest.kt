package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReplayDeComandasPendientes
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * H2 y H6 (lote final, 26-sep) con el servicio REAL y un disco que persiste de verdad.
 *
 * 🔴 Codex (26-sep): las pruebas del «SÍ pasó» simulaban la lista con respuestas del doble, así que no podían ver lo que
 * se perdía de verdad: la consulta BORRABA la entrada y el aviso vivía sólo en memoria — la venta siguiente, o matar la
 * app, lo callaban sin «Entendido». Aquí la lista es la de producción (`PendientesDeTarjeta` sobre una variable que
 * sobrevive a un servicio y a una pantalla nuevos) y el servidor contesta sin red, en el hilo de la prueba.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvisoDuraderoDeOtrasVentasTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // MARK: - El «disco»: la lista durable, con la MISMA lógica pura de producción

    private var disco: String? = null
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private fun ids() = PendientesDeTarjeta.leer(disco).map { it.requestId }
    private fun marcas() = PendientesDeTarjeta.leer(disco).map { it.cobrado }

    private fun sembrar(id: String, orden: String?, centavos: Int, haceMin: Long, cobrado: Boolean = false) {
        val contexto = JSONObject().put("requestId", id).put("venueId", "venue-1").put("terminalId", "t1").put("orderId", orden)
            .put("amountCents", centavos).put("tipCents", 0).put("creadoEn", System.currentTimeMillis() - haceMin * 60_000)
        disco = PendientesDeTarjeta.agregar(disco, id, orden, contexto.toString(), cobrado)
    }

    // MARK: - El servidor, sin red

    /** requestId → cuerpo del GET de su estado. Sin entrada: sigue en duda (TIMED_OUT). */
    private val estados = mutableMapOf<String, String>()
    private val consultados = mutableListOf<String>()
    private val postsEnviados = mutableListOf<String>()
    private var respuestaDelPost = """{"status":"unknown"}"""

    private fun cobrado(paymentId: String) = """{"status":"COMPLETED","inProgress":false,"paymentId":"$paymentId"}"""

    private val servidor = Interceptor { chain ->
        val peticion = chain.request()
        val ruta = peticion.url.encodedPath
        val (codigo, cuerpo) = when {
            ruta.endsWith("/terminals/online") -> 200 to """{"terminals":[{"terminalId":"t1","name":"Terminal 1"}]}"""
            peticion.method == "POST" && ruta.endsWith("/terminal-payment") -> {
                val enviado = okio.Buffer().also { peticion.body!!.writeTo(it) }.readUtf8()
                postsEnviados += JSONObject(enviado).getString("requestId")
                200 to respuestaDelPost
            }
            peticion.method == "GET" && ruta.contains("/terminal-payment/") -> {
                val id = ruta.substringAfterLast('/')
                consultados += id
                200 to (estados[id] ?: """{"status":"TIMED_OUT","inProgress":false}""")
            }
            else -> 404 to "{}"
        }
        Response.Builder().request(peticion).protocol(Protocol.HTTP_1_1).code(codigo).message("falso")
            .body(cuerpo.toResponseBody("application/json".toMediaType())).build()
    }

    /** Un servicio NUEVO, como el que nace al reiniciar la app: sin nada en memoria, sobre el mismo disco. */
    private fun nuevoServicio() = TerminalPaymentService(secureStorage, OkHttpClient.Builder().addInterceptor(servidor).build())
        .also { it.io = Dispatchers.Unconfined }

    private lateinit var servicio: TerminalPaymentService

    // MARK: - La pantalla

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)

    private fun nuevaVenta(servicio: TerminalPaymentService = this.servicio): PaymentFlowViewModel {
        val store = ComandasPendientesStore(AlmacenEnMemoria())
        val despachador = ComandaDispatcher(
            printConfigRepository,
            ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
        )
        return PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true),
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = servicio,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = mockk<KDSOrderBus>(relaxed = true),
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = store,
            replayDeComandas = ReplayDeComandasPendientes(store, despachador),
            comandaDispatcher = despachador,
            tableSession = TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = CancelacionDeCobroCoordinator(
                store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar()),
                transporte = CancelacionTransportFalso(),
                conectado = MutableStateFlow(true),
                servidorAlcanzable = MutableStateFlow(true),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                reloj = { 0L },
                esperasDeSondeo = listOf(1_000L, 2_000L),
            ),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    private fun cuenta(centavos: Int = 1500) = CartState(
        items = listOf(CartItem(id = "cuenta", type = CartItemType.ProductItem("prod-1"), name = "Cuenta", unitPrice = centavos)),
    )

    @Before
    fun setUp() {
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.pendingCardChargesJson } answers { disco }
        every { secureStorage.persistPendingCardCharge(any(), any(), any()) } answers {
            disco = PendientesDeTarjeta.agregar(disco, firstArg(), secondArg(), thirdArg()); true
        }
        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } answers {
            disco = PendientesDeTarjeta.agregar(disco, firstArg(), secondArg(), thirdArg(), cobrado = true, cobradoEn = arg(3)); true
        }
        every { secureStorage.removePendingCardCharge(any()) } answers { disco = PendientesDeTarjeta.quitar(disco, firstArg()) }

        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        every { areaTicketRepository.session.current() } returns null
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1")))
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null

        servicio = nuevoServicio()
    }

    // MARK: - H2: el «SÍ pasó» es durable hasta «Entendido»

    @Test
    fun `H2 dos cobros confirmados de otras ventas llegan los dos a pantalla y Entendido quita solo el suyo`() = runTest {
        // Antes las dos consultas borraban las dos entradas y la pantalla sólo podía mostrar una.
        sembrar("r-a", "orden-a", 1500, haceMin = 3)
        sembrar("r-b", "orden-b", 2000, haceMin = 2)
        estados["r-a"] = cobrado("pay-a")
        estados["r-b"] = cobrado("pay-b")
        val vm = nuevaVenta()

        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        val primero = vm.avisoDeOtroCobro.value!!
        assertTrue(primero.yaCobrado)
        assertEquals("el más nuevo primero", "r-b", primero.requestId)
        assertEquals("las dos siguen en disco, marcadas", listOf(true, true), marcas())

        vm.descartarAvisoDeOtroCobro("r-b")
        assertEquals("la otra confirmación también llega a pantalla", "r-a", vm.avisoDeOtroCobro.value?.requestId)
        assertTrue(vm.avisoDeOtroCobro.value!!.yaCobrado)
        assertEquals("«Entendido» quitó SÓLO ése", listOf("r-a"), ids())

        vm.descartarAvisoDeOtroCobro("r-a")
        assertNull(vm.avisoDeOtroCobro.value)
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `el SI paso dura 10 min - sobrevive a la venta siguiente y a reiniciar la app, y el vencido sale del disco`() = runTest {
        // Founder, 26-sep: la MISMA ventana que las dudas, desde el cobro. Dentro es durable; al vencer se purga; nunca pegajoso.
        sembrar("r-a", "orden-a", 1500, haceMin = 3)
        estados["r-a"] = cobrado("pay-a")
        sembrar("r-viejo", "orden-v", 1500, haceMin = 11, cobrado = true) // un «SÍ pasó» de hace 11 min
        val vm = nuevaVenta()

        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertTrue("la consulta lo probó", vm.avisoDeOtroCobro.value!!.yaCobrado)
        assertEquals("r-a", vm.avisoDeOtroCobro.value?.requestId)
        assertFalse("el vencido ya salió del disco", "r-viejo" in ids())

        // La venta siguiente, en el MISMO proceso: el cajero no tocó «Entendido».
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        assertEquals("al instante, desde el disco", "r-a", vm.avisoDeOtroCobro.value?.requestId)
        advanceUntilIdle()
        assertTrue(vm.avisoDeOtroCobro.value!!.yaCobrado)

        // Reiniciar la app: servicio y pantalla NUEVOS sobre el mismo disco.
        val tras = nuevaVenta(nuevoServicio())
        tras.startPaymentFlow(cuenta())
        tras.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals("r-a", tras.avisoDeOtroCobro.value?.requestId)
        assertTrue(tras.avisoDeOtroCobro.value!!.yaCobrado)
        assertEquals("lo probado no se vuelve a consultar", listOf("r-a"), consultados)
    }

    @Test
    fun `H2 el cobro que mando ESTE flujo sigue terminando en exito y no deja nada en disco`() = runTest {
        respuestaDelPost = """{"status":"unknown"}""" // el POST no dice cómo quedó: se consulta, y sigue en duda
        val vm = nuevaVenta()
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        val propio = postsEnviados.single()
        assertFalse("montaje: su propio cobro sin confirmar", (vm.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        assertEquals(listOf(propio), ids())

        estados[propio] = cobrado("pay-propio")
        vm.recheckCardCharge()
        advanceUntilIdle()

        assertEquals("paga ESTA venta", "pay-propio", (vm.state.value as PaymentFlowState.Success).paymentId)
        assertTrue("ni pendiente ni «SÍ pasó» sobre su propio pago", ids().isEmpty())
    }

    @Test
    fun `H2 un cobro confirmado de ESTA orden no deja volver a cobrarla en silencio, y su Entendido la libera`() = runTest {
        // Mostrador dividido: la parte 1 se cobró con tarjeta (y quedó confirmada en disco); ahora se cobra la parte 2.
        sembrar("r-parte-1", "orden-mostrador", 25_000, haceMin = 3, cobrado = true)
        val vm = nuevaVenta()

        vm.startPaymentFlow(cuenta(25_000), resumeOrderId = "orden-mostrador")
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()

        assertEquals("el texto de siempre del «SÍ pasó» de esta venta", "El cobro anterior sí se había realizado", vm.previousChargeResolved.value)
        assertTrue("no salió ningún cobro", postsEnviados.isEmpty())
        assertTrue("ni se preguntó nada: ya consta", consultados.isEmpty())
        assertEquals("sigue en disco hasta «Entendido»", listOf("r-parte-1"), ids())

        vm.clearPreviousChargeResolved() // la pantalla del cobro le pasa el mensaje al checkout…
        vm.reconocerCobroAnteriorResuelto() // …y el cajero toca «Entendido» en «Cobro anterior resuelto»
        assertTrue(ids().isEmpty())

        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertTrue("sin callejón: la parte 2 ya se puede cobrar", vm.state.value is PaymentFlowState.SelectingTerminal)
    }

    // MARK: - H6: todos tienen turno en la revisión de fondo

    @Test
    fun `H6 con 4 pendientes el mas viejo tambien tiene turno - su SI paso aparece a la segunda revision`() = runTest {
        sembrar("r1", "orden-1", 1000, haceMin = 4) // el 4º, el más viejo: SÍ pasó
        sembrar("r2", "orden-2", 1000, haceMin = 3)
        sembrar("r3", "orden-3", 1000, haceMin = 2)
        sembrar("r4", "orden-4", 1000, haceMin = 1)
        estados["r1"] = cobrado("pay-1") // los tres más nuevos siguen en duda
        val vm = nuevaVenta()

        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertFalse("1ª revisión: los 3 más nuevos, en duda", vm.avisoDeOtroCobro.value!!.yaCobrado)
        assertFalse("r1" in consultados)

        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals("2ª revisión: le toca al que nunca se consultó", "r1", vm.avisoDeOtroCobro.value?.requestId)
        assertTrue(vm.avisoDeOtroCobro.value!!.yaCobrado)
    }

    // MARK: - R3-1 (Codex r3) y el camino común (re-revisión m-3)

    @Test
    fun `R3-1 Revisar unido a la consulta vieja - un positivo independiente que llega antes del negativo gana`() = runTest {
        // Codex r3: «Revisar» se une a la consulta de fondo; mientras, otro canal (la cancelación) confirma el cobro; la
        // respuesta vieja llega NEGATIVA y la revisión decía «El cobro anterior no se realizó» sobre dinero que SÍ salió.
        sembrar("r31-a", "orden-a", 1500, haceMin = 2)
        estados["r31-a"] = """{"status":"FAILED","inProgress":false,"outcome":"NOT_CHARGED","outcomeEvidence":"PROCESSOR_DECLINED"}"""
        servicio.io = StandardTestDispatcher(testScheduler) // el GET queda EN VUELO hasta que corra el planificador
        val vm = nuevaVenta()

        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD) // la revisión de fondo abre la consulta de r31-a…
        vm.revisarCobroDeOtraVenta("r31-a") // …«Revisar» se une a ella…
        var directo: TerminalPaymentResult? = null
        launch(start = CoroutineStart.UNDISPATCHED) { directo = servicio.resolveOutcome("r31-a") } // …y el servicio, también
        servicio.aplicarDesenlaceTardio("r31-a", CardChargeOutcome.Charged("pay-a"), aunSiFueDeclarado = true) // otro canal: SÍ
        advanceUntilIdle() // llega el negativo viejo

        assertTrue("el servicio devuelve el positivo vigente: $directo", directo is TerminalPaymentResult.Success)
        assertEquals("El cobro anterior sí se había realizado", vm.previousChargeResolved.value)
        assertEquals("una sola consulta: los tres se unieron", listOf("r31-a"), consultados)
        assertEquals("sigue marcado en disco", listOf(true), marcas())
    }

    @Test
    fun `m-3 el camino comun - POST exitoso, el flujo lo adopta, el disco queda vacio y la venta siguiente no ve ningun SI paso`() = runTest {
        // Re-revisión (m-3): si este camino dejara residuo, CADA venta con tarjeta mostraría un «SÍ pasó» falso en la siguiente.
        respuestaDelPost = """{"success":true,"status":"success","paymentId":"pay-directo"}"""
        val vm = nuevaVenta()
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("paga ESTA venta", "pay-directo", (vm.state.value as PaymentFlowState.Success).paymentId)
        assertTrue("la adopción la quitó del disco", ids().isEmpty())

        val siguiente = nuevaVenta() // la venta siguiente, sobre el mismo servicio y el mismo disco
        siguiente.startPaymentFlow(cuenta())
        siguiente.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertNull("ningún «SÍ pasó» falso", siguiente.avisoDeOtroCobro.value)
        assertNull(siguiente.segundoAviso.value)
        assertTrue("y nada que consultar", consultados.isEmpty())
    }
}
