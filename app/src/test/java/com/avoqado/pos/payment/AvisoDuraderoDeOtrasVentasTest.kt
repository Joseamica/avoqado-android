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
import kotlinx.coroutines.test.advanceTimeBy
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
    private var codigoDelPost = 200

    /** Fix round 1 de C1 (O1): la tablet se queda SIN RED — ninguna petición llega al servidor. */
    private var sinRed = false

    /** Las declaraciones «Ya revisé la terminal: no se cobró» que la app intentó mandar (con red o sin ella). */
    private var declaraciones = 0

    private fun cobrado(paymentId: String) = """{"status":"COMPLETED","inProgress":false,"paymentId":"$paymentId"}"""

    private val servidor = Interceptor { chain ->
        val peticion = chain.request()
        val ruta = peticion.url.encodedPath
        if (ruta.endsWith("/release")) declaraciones++
        if (sinRed) throw java.net.UnknownHostException("sin red en la prueba")
        val (codigo, cuerpo) = when {
            ruta.endsWith("/terminals/online") -> 200 to """{"terminals":[{"terminalId":"t1","name":"Terminal 1"}]}"""
            peticion.method == "POST" && ruta.endsWith("/terminal-payment") -> {
                val enviado = okio.Buffer().also { peticion.body!!.writeTo(it) }.readUtf8()
                postsEnviados += JSONObject(enviado).getString("requestId")
                codigoDelPost to respuestaDelPost
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
    /** La cola de sincronización sin red: la declaración NO puede terminar aquí (es online-only a propósito). */
    private val syncOutbox = mockk<com.avoqado.pos.core.data.sync.SyncOutbox>(relaxed = true)

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
            syncOutbox = syncOutbox,
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
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1")))
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null

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

    // MARK: - C1 (22-oct): «Terminal ocupada» con «Revisar ese cobro» — un cobro AJENO se muestra, no se hace propio

    /** El 409 del SERVIDOR nuevo: la terminal (o la venta, con `ORDER`) la ocupa la solicitud [bloqueador]. */
    private fun ocupadaPor(bloqueador: String, alcance: String = "TERMINAL"): String {
        val quien = """{"requestId":"$bloqueador","amountCents":12550,"senderDevice":"Sunmi D3","ageSeconds":200}"""
        return """{"success":false,"status":"failed","code":"TERMINAL_BUSY","message":"La terminal está ocupada procesando otro cobro","blockingRequest":$quien,"details":{"blockingRequest":$quien,"scope":"$alcance"}}"""
    }

    /** Una venta nueva cobrada con tarjeta en la terminal t1, hasta la respuesta del POST. */
    private fun kotlinx.coroutines.test.TestScope.cobrarConTarjeta(vm: PaymentFlowViewModel) {
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
    }

    /** La venta siguiente en este aparato: si el cobro ajeno se hubiera guardado, saldría aquí, en el aviso. */
    private fun kotlinx.coroutines.test.TestScope.sinAvisoEnLaVentaSiguiente(vm: PaymentFlowViewModel) {
        codigoDelPost = 200
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertNull("no aparece en el aviso de otras ventas", vm.avisoDeOtroCobro.value)
        assertNull(vm.segundoAviso.value)
    }

    @Test
    fun `P1 con requestId unknown no se ofrece Revisar ese cobro`() = runTest {
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        assertEquals("montaje: con el id del cobro que ocupa, sí se ofrece", "r-ajeno", (vm.state.value as PaymentFlowState.Error).revisarCobroId)

        // El servidor no sabe quién ocupa (una lápida que no lo guardó): manda `requestId: "unknown"`.
        respuestaDelPost = ocupadaPor("unknown")
        cobrarConTarjeta(vm)

        val error = vm.state.value as PaymentFlowState.Error
        assertTrue("sigue diciendo que ESTE cobro no se envió: ${error.message}", error.message.contains("NO se envió"))
        assertNull("sin un id real no hay a quién consultar", error.revisarCobroId)
        vm.revisarCobroQueOcupa() // aunque alguien lo llamara, no pregunta por «unknown»
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        assertTrue("nada se consultó: $consultados", consultados.isEmpty())
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `P1 revisar el cobro de otro aparato que sigue en duda no lo guarda en la lista durable ni en el aviso`() = runTest {
        // Review Focus #5: la terminal la ocupa un cobro de OTRO aparato que sigue sin confirmarse (el servidor contesta
        // TIMED_OUT). Este POS lo muestra, pero no lo hace suyo: no es su venta y su aparato ya lo cuida.
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        assertEquals("montaje", "r-ajeno", (vm.state.value as PaymentFlowState.Error).revisarCobroId)
        assertTrue("montaje: el propio no se creó y salió de la lista", ids().isEmpty())

        vm.revisarCobroQueOcupa()
        advanceUntilIdle()

        val duda = vm.state.value as PaymentFlowState.Undetermined
        assertTrue("se muestra como revisión, no como cobro de esta venta", duda.fromPreviousSale)
        assertTrue(duda.message, duda.message.startsWith("Quedó un cobro sin confirmar de otra venta."))
        assertEquals("se preguntó por ESE cobro", listOf("r-ajeno"), consultados)
        assertTrue("no entra a la lista durable de este aparato: ${ids()}", ids().isEmpty())
        sinAvisoEnLaVentaSiguiente(vm)
    }

    @Test
    fun `P1 la segunda consulta y la automatica de un cobro ajeno tampoco lo guardan`() = runTest {
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        assertTrue("montaje: la primera consulta no lo guardó: ${ids()}", ids().isEmpty())

        vm.recheckCardCharge() // «Volver a consultar»
        advanceUntilIdle()
        assertTrue((vm.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        assertTrue("la segunda consulta tampoco: ${ids()}", ids().isEmpty())

        vm.recheckCardCharge(automatica = true) // la que la pantalla lanza sola cada 5 s
        advanceUntilIdle()
        assertEquals("las tres preguntaron por ESE cobro", listOf("r-ajeno", "r-ajeno", "r-ajeno"), consultados)
        assertTrue("ni la automática: ${ids()}", ids().isEmpty())
        sinAvisoEnLaVentaSiguiente(vm)
    }

    @Test
    fun `P1 la respuesta de un cobro ajeno que llega tras cerrar el flujo no lo guarda`() = runTest {
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        servicio.io = StandardTestDispatcher(testScheduler) // la consulta queda EN VUELO hasta que corra el planificador

        vm.revisarCobroQueOcupa()
        vm.salirDejandoPendiente() // el cajero se va antes de que conteste
        advanceUntilIdle() // llega «sigue en duda» a un flujo que ya se cerró

        assertEquals("montaje: la consulta sí salió y contestó", listOf("r-ajeno"), consultados)
        assertTrue("el resultado obsoleto no lo hace suyo: ${ids()}", ids().isEmpty())
        sinAvisoEnLaVentaSiguiente(vm)
    }

    // MARK: - Fix round 1 de C1 (revisión de tarea): M1, M2 y O1

    @Test
    fun `P1 un cobro ajeno que SI paso no escribe SI paso en el disco de esta tablet ni en su aviso`() = runTest {
        // M1: la consulta resulta COMPLETED. La aserción central (`ids()` vacío, ANTES de cualquier «Entendido») depende
        // SÓLO de la guarda `if (ajeno)` de la rama `Charged` de `toResult`: sin ella, `marcarCobrado` escribe la marca
        // «SÍ pasó» de un cobro que nunca fue de esta tablet. La pantalla, por `fromPreviousSale`, no escribe nada.
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        estados["r-ajeno"] = cobrado("pay-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)

        vm.revisarCobroQueOcupa()
        advanceUntilIdle()

        assertEquals("montaje: se preguntó por ESE cobro", listOf("r-ajeno"), consultados)
        assertEquals("El cobro anterior sí se había realizado", vm.previousChargeResolved.value)
        assertFalse("confirmarlo NO paga esta venta: ${vm.state.value}", vm.state.value is PaymentFlowState.Success)
        assertTrue("ni «SÍ pasó» en el disco de esta tablet: ${ids()}", ids().isEmpty())
        sinAvisoEnLaVentaSiguiente(vm) // sin tocar «Entendido»: si estuviera en disco, saldría aquí
    }

    @Test
    fun `P1 un cobro ajeno que SI paso y contesta tras cerrar el flujo tampoco escribe SI paso`() = runTest {
        // M1, el COMPLETED tardío: aquí la aserción central depende de DOS guardas, y cada una basta para romperla — la del
        // servicio (`marcarCobrado` en `toResult`) y la de la pantalla (`handleStaleCardResult` → `aplicarDesenlaceTardio`
        // con `Charged`, que también marca).
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        estados["r-ajeno"] = cobrado("pay-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        servicio.io = StandardTestDispatcher(testScheduler) // la consulta queda EN VUELO

        vm.revisarCobroQueOcupa()
        vm.salirDejandoPendiente() // el cajero se va antes de que conteste
        advanceUntilIdle() // llega el COMPLETED a un flujo que ya se cerró

        assertEquals("montaje: la consulta sí salió y contestó", listOf("r-ajeno"), consultados)
        assertTrue("el COMPLETED tardío no lo marca en esta tablet: ${ids()}", ids().isEmpty())
        sinAvisoEnLaVentaSiguiente(vm)
    }

    @Test
    fun `P1 con alcance ORDER la revision dice de esta venta y con TERMINAL de otra venta`() = runTest {
        // M2: decisión del controlador (22-oct). Con ORDER el cobro que bloquea es de ESTA venta: «…de esta venta.» y el
        // título neutro «Cobro sin confirmar» (`revisaCobroDeOtraVenta() == false`). iOS (D1) copia esto.
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-orden", alcance = "ORDER")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        val error = vm.state.value as PaymentFlowState.Error
        assertEquals("Esta venta tiene un cobro pendiente de confirmar.", error.message)
        assertEquals("r-orden", error.revisarCobroId)

        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        val deLaOrden = vm.state.value as PaymentFlowState.Undetermined
        assertTrue(deLaOrden.message, deLaOrden.message.startsWith("Quedó un cobro sin confirmar de esta venta."))
        assertFalse("título «Cobro sin confirmar», no «Cobro anterior sin confirmar»", vm.revisaCobroDeOtraVenta())
        vm.recheckCardCharge() // «Volver a consultar» conserva el encabezado
        advanceUntilIdle()
        val trasConsultar = vm.state.value as PaymentFlowState.Undetermined
        assertTrue(trasConsultar.message, trasConsultar.message.startsWith("Quedó un cobro sin confirmar de esta venta."))

        // Con TERMINAL el cobro que ocupa es de otra venta: el encabezado y el título de siempre.
        respuestaDelPost = ocupadaPor("r-terminal", alcance = "TERMINAL")
        cobrarConTarjeta(vm)
        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        val deLaTerminal = vm.state.value as PaymentFlowState.Undetermined
        assertTrue(deLaTerminal.message, deLaTerminal.message.startsWith("Quedó un cobro sin confirmar de otra venta."))
        assertTrue("título «Cobro anterior sin confirmar»", vm.revisaCobroDeOtraVenta())
        assertTrue("ninguno de los dos entra a la lista: ${ids()}", ids().isEmpty())
    }

    @Test
    fun `P1 sin red Revisar ese cobro dice que la revision necesita internet`() = runTest {
        // O1 (spec §Sin red): se cae el WiFi entre el 409 y el toque. Ninguna consulta recibe respuesta: no se sabe nada
        // del cobro, y se dice por qué, con la salida que no se frena (el efectivo). Alcance TERMINAL = la duda es de OTRA
        // venta, así que sí se invita al efectivo (fix round 2: con ORDER o la duda propia, el otro texto).
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        sinRed = true

        vm.revisarCobroQueOcupa()
        advanceUntilIdle()

        val duda = vm.state.value as PaymentFlowState.Undetermined
        assertEquals(
            "Quedó un cobro sin confirmar de otra venta. ${com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION}",
            duda.message,
        )
        assertFalse(duda.checking)
        assertTrue("sin red tampoco se guarda nada: ${ids()}", ids().isEmpty())

        // Vuelve la red y el servidor contesta (sigue en duda): ya no dice «sin conexión».
        sinRed = false
        vm.recheckCardCharge()
        advanceUntilIdle()
        val conRed = vm.state.value as PaymentFlowState.Undetermined
        assertFalse(conRed.message, conRed.message.contains("Sin conexión"))
    }

    @Test
    fun `P1 sin red la declaracion no cobrado dice lo mismo y no se encola`() = runTest {
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        vm.revisarCobroQueOcupa() // con red: el servidor dice que sigue en duda
        advanceUntilIdle()
        sinRed = true

        vm.declararNoCobrado() // «Ya revisé la terminal: no se cobró», sin red
        advanceUntilIdle()

        val estado = vm.state.value as PaymentFlowState.Undetermined
        assertEquals(com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION, estado.message)
        assertTrue("la duda sigue ahí: no se resolvió nada", estado.fromPreviousSale)
        assertEquals("se intentó UNA vez", 1, declaraciones)

        // 🔴 Online-only A PROPÓSITO: al volver la red NO se manda sola (una afirmación de hace diez minutos podría caer
        // sobre un cobro que entretanto sí pasó), y no queda en la cola de sincronización.
        sinRed = false
        advanceTimeBy(10 * 60 * 1000L)
        advanceUntilIdle()
        assertEquals("nadie la reenvió", 1, declaraciones)
        io.mockk.coVerify(exactly = 0) {
            syncOutbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `P1 sin scope la duda ajena no invita a cobrar en efectivo`() = runTest {
        for (scope in listOf(null, "FUTURE_SCOPE")) {
            codigoDelPost = 409
            respuestaDelPost = if (scope == null) ocupadaPor("r-ajeno").replace(",\"scope\":\"TERMINAL\"", "") else ocupadaPor("r-ajeno", scope)
            val vm = nuevaVenta()
            cobrarConTarjeta(vm)
            assertTrue("montaje: bloqueador revisable", vm.state.value is PaymentFlowState.Error)
            sinRed = true
            vm.revisarCobroQueOcupa()
            advanceUntilIdle()
            val duda = vm.state.value as PaymentFlowState.Undetermined
            assertTrue(duda.message, duda.message.endsWith(com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA))
            vm.declararNoCobrado()
            advanceUntilIdle()
            assertEquals(com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA, (vm.state.value as PaymentFlowState.Undetermined).message)
            sinRed = false
        }
    }

    // MARK: - Fix round 2 de C1: dos textos sin red, según de QUIÉN es la duda

    @Test
    fun `P1 sin red la duda de esta venta no invita a cobrar en efectivo`() = runTest {
        // Controlador (22-oct): cobrar en efectivo la MISMA venta con la tarjeta en duda es un hueco sin decidir (D10).
        // Fix round 3: «puedes cobrar en efectivo» sólo cuando la duda es AJENA a la venta que se quiere cobrar ahora
        // («Revisar ese cobro» con TERMINAL). Todo lo demás —incluido «Revisar» desde el aviso— usa este texto.
        val estaVenta = com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA
        val vm = nuevaVenta()

        // 1) «Revisar» desde el AVISO: lo que se revisa ES esa venta, que puede seguir abierta. Va primero para que el
        //    aviso sólo muestre ese cobro sembrado.
        sembrar("r-otra", "orden-otra", 2000, haceMin = 2)
        vm.startPaymentFlow(cuenta())
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals("montaje: sale en el aviso", "r-otra", vm.avisoDeOtroCobro.value?.requestId)
        sinRed = true
        vm.revisarCobroDeOtraVenta("r-otra")
        advanceUntilIdle()
        assertEquals("Quedó un cobro sin confirmar de otra venta. $estaVenta", (vm.state.value as PaymentFlowState.Undetermined).message)
        vm.declararNoCobrado()
        advanceUntilIdle()
        assertEquals("su declaración sin red, lo mismo", estaVenta, (vm.state.value as PaymentFlowState.Undetermined).message)
        assertEquals("la duda de esa venta sigue guardada", listOf("r-otra"), ids())

        // 2) «Revisar ese cobro» con alcance ORDER: el cobro que bloquea es de ESTA venta.
        sinRed = false
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-orden", alcance = "ORDER")
        cobrarConTarjeta(vm)
        sinRed = true
        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        assertEquals("Quedó un cobro sin confirmar de esta venta. $estaVenta", (vm.state.value as PaymentFlowState.Undetermined).message)
        vm.declararNoCobrado()
        advanceUntilIdle()
        assertEquals("su declaración sin red, lo mismo", estaVenta, (vm.state.value as PaymentFlowState.Undetermined).message)

        // 3) El «Cobro sin confirmar» PROPIO: el cobro que mandó esta pantalla no se sabe cómo quedó.
        sinRed = false
        codigoDelPost = 200
        respuestaDelPost = """{"status":"unknown"}"""
        cobrarConTarjeta(vm)
        assertFalse("montaje: su propio cobro sin confirmar", (vm.state.value as PaymentFlowState.Undetermined).fromPreviousSale)
        sinRed = true
        vm.recheckCardCharge() // «Volver a consultar» sin red
        advanceUntilIdle()
        assertEquals(estaVenta, (vm.state.value as PaymentFlowState.Undetermined).message)
        vm.declararNoCobrado() // «Ya revisé la terminal: no se cobró» sin red
        advanceUntilIdle()
        val propia = vm.state.value as PaymentFlowState.Undetermined
        assertEquals(estaVenta, propia.message)
        assertFalse("nunca invita al efectivo sobre su propia duda: ${propia.message}", propia.message.contains("efectivo"))
        assertEquals("la duda propia y la de la otra venta siguen guardadas", setOf("r-otra", postsEnviados.last()), ids().toSet())
    }

    @Test
    fun `P1 sin red la duda de otra venta si dice que puedes cobrar en efectivo`() = runTest {
        // Fix round 3: el ÚNICO caso que invita al efectivo es «Revisar ese cobro» con alcance TERMINAL — el cobro que
        // ocupa es ajeno a la venta que el cajero quiere cobrar ahora (y casi siempre de otro aparato).
        val otraVenta = com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-ajeno")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        sinRed = true
        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        assertEquals("Quedó un cobro sin confirmar de otra venta. $otraVenta", (vm.state.value as PaymentFlowState.Undetermined).message)
        vm.declararNoCobrado()
        advanceUntilIdle()
        assertEquals(otraVenta, (vm.state.value as PaymentFlowState.Undetermined).message)
        assertTrue("el cobro ajeno no entra a la lista: ${ids()}", ids().isEmpty())
    }

    @Test
    fun `P1 sin red Revisar ese cobro de otra venta de esta tablet no invita a cobrar en efectivo`() = runTest {
        // Fix round 4 (m1 de la re-revisión): el cobro que ocupa la terminal es de OTRA venta, pero de ESTA tablet (sigue en
        // su lista). Esa venta puede seguir abierta —en un cobro rápido sin orden, pudo ser el mismo cliente vuelto a
        // teclear—, así que da el MISMO texto que desde el aviso: no invita al efectivo.
        val estaVenta = com.avoqado.pos.payment.domain.CancelacionDeCobro.SIN_CONEXION_REVISION_ESTA_VENTA
        sembrar("r-de-esta-tablet", "orden-otra", 2000, haceMin = 2)
        codigoDelPost = 409
        respuestaDelPost = ocupadaPor("r-de-esta-tablet")
        val vm = nuevaVenta()
        cobrarConTarjeta(vm)
        assertEquals("montaje: lo nombra el 409", "r-de-esta-tablet", (vm.state.value as PaymentFlowState.Error).revisarCobroId)
        sinRed = true

        vm.revisarCobroQueOcupa()
        advanceUntilIdle()
        assertEquals("Quedó un cobro sin confirmar de otra venta. $estaVenta", (vm.state.value as PaymentFlowState.Undetermined).message)
        vm.declararNoCobrado()
        advanceUntilIdle()
        assertEquals("su declaración sin red, lo mismo", estaVenta, (vm.state.value as PaymentFlowState.Undetermined).message)
        assertEquals("su duda sigue guardada", listOf("r-de-esta-tablet"), ids())
    }
}
