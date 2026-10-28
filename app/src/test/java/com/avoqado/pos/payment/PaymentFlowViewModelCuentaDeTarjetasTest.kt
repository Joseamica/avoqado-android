package com.avoqado.pos.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.payment.data.model.*
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import com.avoqado.pos.tables.data.*
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowViewModelCuentaDeTarjetasTest {
    @get:Rule val main = MainDispatcherRule()
    private val conectado = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val cash = mockk<CashPaymentRepository>(relaxed = true)
    private val order = mockk<OrderRepository>(relaxed = true)
    private val terminal = mockk<TerminalPaymentService>(relaxed = true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conectado
        every { isServerReachable } returns servidor
    }
    private val cart = CartState(items = listOf(CartItem(type = CartItemType.CustomAmount,
        name = "Tarjetas de regalo", unitPrice = 50000)))
    private val almacen = ViewModelStore()

    private fun vm(monitor: ConnectivityMonitor? = red, real: Q16PrecioCapturadoTest.Fixture? = null): PaymentFlowViewModel {
        every { terminal.intentoEnVuelo() } returns null
        every { terminal.pendienteDeLaVenta(any()) } returns null
        every { terminal.pendientesDeOtrasVentas(any()) } returns emptyList()
        coEvery { terminal.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        val settings = mockk<TpvSettingsRepository>()
        every { settings.getCurrentSettings() } returns TpvSettings(showReviewScreen = true, showTipScreen = true)
        val area = mockk<AreaTicketRepository>(relaxed = true)
        every { area.session.current() } returns null
        val storage = real?.storage ?: mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns "c123456789012345678901234"
            every { it.userId } returns "c223456789012345678901234"
        }
        every { cash.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { order.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(OrderRepository.ServerException(403, "Se alcanzó el transporte sin la guarda Q34"))
        // Si la guarda llega tarde, el fallo de reserva impide red pero deja evidencia de la llamada indebida.
        coEvery { cash.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IOException("reserva alcanzada")
        return PaymentFlowViewModel(tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            orderRepository = real?.let { OrderRepository(it.storage, it.client, it.dao) } ?: order,
            cashPaymentRepository = real?.let { CashPaymentRepository(it.storage, it.dao) } ?: cash,
            tenderTypeRepository = mockk(relaxed = true), terminalPaymentService = terminal,
            tpvSettingsRepository = settings, paymentSyncService = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true), kdsRepository = mockk(relaxed = true),
            kdsOrderBus = mockk(relaxed = true), printerService = mockk(relaxed = true), secureStorage = storage,
            comandaDispatcher = mockk(relaxed = true), comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true), customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = area, cancelacionDeCobro = mockk(relaxed = true), savedStateHandle = SavedStateHandle(),
            connectivityMonitor = monitor).also { almacen.put(java.util.UUID.randomUUID().toString(), it) }
    }

    @Test fun `Q14 cuenta propia omite resena y propina y venta normal las conserva`() = runTest {
        try {
            val m = vm()
            m.startPaymentFlow(cart, resumeOrderId = "orden", cuentaDeTarjetas = true)
            assertEquals(PaymentFlowState.SelectingPaymentMethod(50000), m.state.value)
            m.submitRating(5); m.submitTip(3000)
            assertEquals(PaymentFlowState.SelectingPaymentMethod(50000), m.state.value)
            m.startPaymentFlow(cart)
            assertTrue(m.state.value is PaymentFlowState.CollectingRating)
            m.submitRating(5)
            assertTrue(m.state.value is PaymentFlowState.CollectingTip)
        } finally { almacen.clear() }
    }

    private fun sinRed(m: PaymentFlowViewModel) {
        val estado = m.state.value
        assertTrue("Q34 debe explicar la falta de red antes de reservar", estado is PaymentFlowState.Error)
        assertEquals(TextosDeTarjetas.CUENTA_SIN_RED, (estado as PaymentFlowState.Error).message)
        coVerify(exactly = 0) { cash.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cash.processCashPayment(any(), any()) }
        coVerify(exactly = 0) { order.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `Q34 sin ruta o sin servidor ninguna entrada reserva efectivo`() = runTest {
        try {
            for ((ruta, api) in listOf(false to true, true to false)) {
                conectado.value = ruta; servidor.value = api
                for (entrada in 0..3) {
                    val m = vm()
                    m.startPaymentFlow(cart, resumeOrderId = "orden", cuentaDeTarjetas = true)
                    m.selectPaymentMethod(PaymentMethod.CASH)
                    when (entrada) {
                        0 -> m.confirmPayment()
                        1 -> m.confirmCashPreset(50000)
                        2 -> m.confirmCashCustom(60000)
                        else -> m.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER))
                    }
                    runCurrent()
                    sinRed(m)
                }
            }
        } finally { almacen.clear() }
    }

    @Test fun `Q34 monitor ausente es sin red solo para cuenta propia`() = runTest {
        try {
            val m = vm(null)
            m.startPaymentFlow(cart, resumeOrderId = "orden", cuentaDeTarjetas = true)
            m.confirmCashPreset(50000); runCurrent(); sinRed(m)
            m.startPaymentFlow(cart)
            assertTrue(m.state.value is PaymentFlowState.CollectingRating)
        } finally { almacen.clear() }
    }

    @Test fun `control venta normal sin monitor sigue entrando al motor de efectivo`() = runTest {
        try {
            val m = vm(null)
            m.startPaymentFlow(cart)
            m.confirmCashPreset(50000); runCurrent()
            verify(exactly = 1) { cash.processCashPayment(50000, 50000) }
        } finally { almacen.clear() }
    }

    @Test fun `Q14 terminal de cuenta propia recibe propina cero y Q34 terminal no sale sin red`() = runTest {
        try {
            val m = vm()
            coEvery { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
                TerminalPaymentResult.Error("Rechazo de prueba")
            m.startPaymentFlow(cart, resumeOrderId = "orden", cuentaDeTarjetas = true)
            m.submitTip(3000)
            m.selectPaymentMethod(PaymentMethod.CARD)
            m.selectTerminalAndPay("terminal")
            runCurrent()
            coVerify(exactly = 1) { terminal.sendPaymentToTerminal("terminal", 50000, 0, any(), "orden", any(), any()) }
            clearMocks(terminal, answers = false)
            conectado.value = false
            m.startPaymentFlow(cart, resumeOrderId = "orden", cuentaDeTarjetas = true)
            m.selectPaymentMethod(PaymentMethod.CARD)
            m.selectTerminalAndPay("terminal")
            runCurrent()
            sinRed(m)
            coVerify(exactly = 0) { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
        } finally { almacen.clear() }
    }

    @Test fun `Q50 saldo canonico incluye propina y sin balance con pago no abre cobro`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = """{"id":"o","total":830,"payments":[{"id":"p","amount":300,"tipAmount":30,"status":"COMPLETED"}],"preBillBalance":{"orderTotalCents":83000,"totalPaidCents":33000,"remainingBalanceCents":50000,"tipAmountCents":3000}}"""
        val d = json.decodeFromString<OrderDetail>(original)
        assertEquals(50000, restanteCents(d))
        assertNull(restanteCents(d.copy(preBillBalance = null)))
        assertEquals(83000, restanteCents(d.copy(preBillBalance = null, payments = emptyList())))
        assertEquals(83000, restanteCents(d.copy(preBillBalance = null,
            payments = listOf(OrderDetailPayment(status = "FAILED")))))
    }

    @Test fun `Q67 Q80 Q86 lista solo permite cuentas sin pago con saldo o por terminar aqui`() {
        val d = com.avoqado.pos.orders.data.model.OrderDetail(id = "o", payments = emptyList())
        val pagada = d.copy(payments = listOf(com.avoqado.pos.orders.data.model.OrderPaymentInfo("p", status = "COMPLETED")))
        assertTrue(com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(null, d))
        assertFalse(com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(null, pagada))
        assertTrue(com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(1, pagada))
        assertTrue(com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(0, pagada, porTerminarAqui = true))
        assertTrue(com.avoqado.pos.orders.data.model.puedeCobrarseDesdeLaLista(null,
            d.copy(payments = listOf(com.avoqado.pos.orders.data.model.OrderPaymentInfo("p", status = "FAILED")))))
    }

    @Test fun `Q28 marca de pago muestra tarjeta de regalo y conserva metodo viejo sin marca`() {
        val pago = com.avoqado.pos.orders.data.model.OrderPaymentInfo("p", esSaldoDeTarjeta = true, method = "OTHER")
        assertEquals(TextosDeTarjetas.FILA_COBRO, pago.methodLabel)
        assertEquals("Otro", pago.copy(esSaldoDeTarjeta = null).methodLabel)
    }

    @Test fun `factory generada de Hilt entrega el monitor proporcionado sin usar default Kotlin`() {
        try {
            val plantilla = vm()
            val tipo = PaymentFlowViewModel::class.java
            val constructor = tipo.declaredConstructors.single { it.isAnnotationPresent(javax.inject.Inject::class.java) }
            val providers = constructor.parameterTypes.map { parametro ->
                val campo = tipo.declaredFields.single { it.type == parametro }.apply { isAccessible = true }
                dagger.internal.Provider<Any?> { campo.get(plantilla) }
            }.toTypedArray()
            val factory = Class.forName("com.avoqado.pos.payment.presentation.PaymentFlowViewModel_Factory")
            val generada = factory.methods.single { it.name == "create" }.invoke(null, *providers)
            val m = factory.getMethod("get").invoke(generada) as PaymentFlowViewModel
            almacen.put("hilt-generada", m)
            val monitor = tipo.getDeclaredField("connectivityMonitor").apply { isAccessible = true }.get(m)
            assertSame(red, monitor)
        } finally { almacen.clear() }
    }

    @Test fun `Q34 perdida de red tras reserva sobrevive reabrir SQLite y replay conserva fila llave y orden`() = runTest {
        // Fixture existente: SQL de dinero en archivo SQLite, repositorios/HTTP/replay reales;
        // el relay JDBC del DAO no ejecuta el generador Room de Android.
        for (manual in listOf(false, true)) Q16PrecioCapturadoTest.Fixture().use { f ->
            val venue = "c123456789012345678901234"
            val orden = "c423456789012345678901234"
            every { f.storage.venueId } returns venue
            every { f.storage.userId } returns "c223456789012345678901234"
            val ruta = MutableStateFlow(true)
            every { f.network.isConnected } returns ruta
            every { f.network.isServerReachable } returns MutableStateFlow(true)
            coEvery { f.dao.enVueloDelVenue(any()) } coAnswers {
                val v = firstArg<String>()
                f.query("SELECT id FROM pending_payments WHERE venueId=:v AND syncStatus='EN_VUELO'", mapOf("v" to v))
                    .mapNotNull { f.dao.findForVenue(v, it["id"] as String) }
            }
            val requests = java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
            val falloDelTransporte = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            var llave: String? = null
            var captura: Any? = null
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    try {
                    assertTrue(request.path!!.endsWith("/orders/$orden/pay"))
                    val body = org.json.JSONObject(request.body.clone().readUtf8())
                    requests.add(body)
                    if (requests.size == 1) {
                        llave = body.getString("idempotencyKey")
                        f.reopen()
                        val row = f.query("SELECT * FROM pending_payments").single()
                        assertEquals(llave, row["id"]); assertEquals("EN_VUELO", row["syncStatus"])
                        assertEquals(orden, row["orderId"])
                        captura = row["q15CaptureJson"]; assertNotNull(captura)
                        kotlinx.coroutines.runBlocking {
                            assertEquals(listOf(llave), CashPaymentRepository(f.storage, f.dao).cobrosSinConfirmar().map { it.id })
                        }
                        ruta.value = false
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
                    }
                    return okhttp3.mockwebserver.MockResponse().setBody(
                        """{"success":true,"payment":{"paymentId":"c523456789012345678901234","orderId":"$orden","amount":50000,"tipAmount":0,"status":"COMPLETED","remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}""")
                    } catch (t: Throwable) {
                        falloDelTransporte.set(t)
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("{}")
                    }
                }
            }
            try {
                val m = vm(f.network, f)
                m.startPaymentFlow(cart, resumeOrderId = orden, cuentaDeTarjetas = true)
                assertEquals(PaymentFlowState.SelectingPaymentMethod(50000), m.state.value)
                if (manual) m.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER))
                else m.confirmCashPreset(50000)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(5000) {
                        while (m.state.value !is PaymentFlowState.Success && m.state.value !is PaymentFlowState.Error) {
                            kotlinx.coroutines.delay(10)
                        }
                    }
                }
                val resultado = m.state.value
                falloDelTransporte.get()?.let { throw it }
                assertTrue("El tramo posterior a reservar conserva su cola: $resultado", resultado is PaymentFlowState.Success)
                assertTrue((resultado as PaymentFlowState.Success).isQueued)
                f.reopen()
                val antes = f.query("SELECT * FROM pending_payments").single()
                assertEquals(llave, antes["id"]); assertEquals("PENDING", antes["syncStatus"])
                assertEquals(captura, antes["q15CaptureJson"])
                ruta.value = true
                f.service.usarContextoDeSincronizacion(kotlinx.coroutines.Dispatchers.Unconfined)
                f.service.start(); f.service.esperarSincronizacionActualQ16()
                val despues = f.query("SELECT * FROM pending_payments").single()
                falloDelTransporte.get()?.let { throw it }
                assertEquals("SYNCED", despues["syncStatus"])
                assertEquals(llave, despues["id"]); assertEquals(captura, despues["q15CaptureJson"])
                assertEquals(2, requests.size)
                requests.forEach { body ->
                    assertEquals(llave, body.getString("idempotencyKey"))
                    assertEquals(50000, body.getInt("amount")); assertEquals(0, body.getInt("tip"))
                }
                assertTrue(requests.last().getBoolean("isOfflineReplay"))
            } finally { almacen.clear() }
        }
    }

    @Test fun `marcas de orden y de pago se leen del contrato sin inferir DINE_IN`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val raw = """{"id":"o","type":"DINE_IN","esCuentaDeTarjetas":true,"esDeMesa":false,"cobrosConSaldo":2}"""
        val legacy = json.decodeFromString<com.avoqado.pos.orders.data.model.OrderDetail>("""{"id":"o","type":"DINE_IN"}""")
        assertFalse(legacy.esCuentaDeTarjetas)
        assertNull(legacy.esDeMesa)
        assertNull(legacy.cobrosConSaldo)
        val summary = json.encodeToJsonElement(json.decodeFromString<com.avoqado.pos.orders.data.model.OrderSummary>(raw)).jsonObject
        assertEquals(JsonPrimitive(true), summary["esCuentaDeTarjetas"])
        assertEquals(JsonPrimitive(false), summary["esDeMesa"])
        assertEquals(JsonPrimitive(2), summary["cobrosConSaldo"])
        val detail = json.encodeToJsonElement(json.decodeFromString<com.avoqado.pos.orders.data.model.OrderDetail>(raw)).jsonObject
        assertEquals(JsonPrimitive(true), detail["esCuentaDeTarjetas"])
        assertEquals(JsonPrimitive(false), detail["esDeMesa"])
        assertEquals(JsonPrimitive(2), detail["cobrosConSaldo"])
        val table = json.encodeToJsonElement(json.decodeFromString<OrderDetail>(raw)).jsonObject
        assertEquals(JsonPrimitive(true), table["esCuentaDeTarjetas"])
        assertEquals(JsonPrimitive(false), table["esDeMesa"])
        assertEquals(JsonPrimitive(2), table["cobrosConSaldo"])
        val payment = json.encodeToJsonElement(json.decodeFromString<com.avoqado.pos.orders.data.model.OrderPaymentInfo>(
            """{"id":"p","esSaldoDeTarjeta":true}""")).jsonObject
        assertEquals(JsonPrimitive(true), payment["esSaldoDeTarjeta"])
    }

    @Test fun `Q30 wrapper relee marca y Q92 no cancela con red conocida ausente o respuesta perdida`() = runTest {
        val repo = mockk<TableServiceRepository>()
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v"
        every { storage.giftCardRequestsJson } returns "[]"
        val roles = mockk<com.avoqado.pos.core.domain.RoleManager> { every { hasVenuePermission(any()) } returns true }
        val detalle = OrderDetail(id = "o", esCuentaDeTarjetas = true, total = 830.0,
            preBillBalance = PreBillBalance(83000, 33000, 50000, 3000))
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(detalle)
        val m = com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel(repo, storage, red, roles,
            com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage))
        m.cargar("o")
        assertEquals(detalle, m.detalle.value)
        for (bandera in listOf(conectado, servidor)) {
            bandera.value = false
            assertFalse(m.cancelar("Prueba"))
            assertEquals(TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED, m.mensaje.value)
            coVerify(exactly = 0) { repo.cancelarEnLinea(any(), any(), any()) }
            bandera.value = true
        }
        coEvery { repo.cancelarEnLinea("v", "o", "Prueba") } returns Result.failure(IOException("sin respuesta"))
        assertFalse(m.cancelar("Prueba"))
        assertEquals(detalle, m.detalle.value)
        assertEquals(TextosDeTarjetas.CUENTA_NO_SE_CANCELO_SIN_RED, m.mensaje.value)
        coEvery { repo.cancelarEnLinea("v", "o", "Prueba") } returns Result.success(Unit)
        assertTrue(m.cancelar("Prueba"))
        assertNull(m.detalle.value)
    }

    @Test fun `cancelacion confirmada quita solo su SALE y un borrado no confirmado conserva el diario`() = runTest {
        for (escrituraOk in listOf(false, true)) {
            val solicitud = com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(
                tipo = "GIFT_CARD_SALE", llave = "venta", venueId = "v", creadaEn = 1, orderId = "o", cuerpo = "{}")
            var raw = com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.agregar(null, solicitud)
            raw = com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.agregar(raw, solicitud.copy(llave = "otra", orderId = "otra"))
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { venueId } returns "v"
                every { giftCardRequestsJson } answers { raw }
                every { escribirSolicitudesDeTarjeta(any()) } answers {
                    if (escrituraOk) { raw = firstArg<(String?) -> String?>().invoke(raw); true } else false
                }
            }
            val store = com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage)
            val repo = mockk<TableServiceRepository>()
            val roles = mockk<com.avoqado.pos.core.domain.RoleManager> { every { hasVenuePermission(any()) } returns true }
            coEvery { repo.getOrderDetail("v", "o") } returns Result.success(OrderDetail("o", esCuentaDeTarjetas = true))
            coEvery { repo.cancelarEnLinea("v", "o", "Prueba") } returns Result.success(Unit)
            val m = com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel(repo, storage, red, roles, store)
            m.cargar("o")
            assertEquals(escrituraOk, m.cancelar("Prueba"))
            assertEquals(if (escrituraOk) listOf("otra") else listOf("venta", "otra"), store.delNegocio("v").map { it.llave })
            if (!escrituraOk) assertEquals("La cuenta se canceló. No se pudo actualizar el registro del aparato; vuelve a consultarla.", m.mensaje.value)
        }
    }

    @Test fun `wrapper no ofrece otra vez una cuenta que el servidor ya cerro`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { venueId } returns "v"; every { giftCardRequestsJson } returns "[]"
        }
        val repo = mockk<TableServiceRepository>()
        val roles = mockk<com.avoqado.pos.core.domain.RoleManager> { every { hasVenuePermission(any()) } returns true }
        val m = com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel(repo, storage, red, roles,
            com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage))
        for ((estado, pago) in listOf("CANCELLED" to "PENDING", "DELETED" to "PENDING", "COMPLETED" to "PAID", "PENDING" to "PAID")) {
            coEvery { repo.getOrderDetail("v", "o") } returns Result.success(
                OrderDetail("o", status = estado, paymentStatus = pago, total = 500.0, esCuentaDeTarjetas = true))
            m.cargar("o")
            assertNull("No ofrecer el total viejo de una cuenta $estado/$pago", m.detalle.value)
            assertEquals("Esta cuenta no tiene saldo pendiente.", m.mensaje.value)
        }
        val parcial = OrderDetail("o", status = "COMPLETED", paymentStatus = "PARTIAL", total = 500.0, esCuentaDeTarjetas = true)
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(parcial)
        m.cargar("o")
        assertEquals("El estado operativo no sustituye al estado del dinero", parcial, m.detalle.value)
    }

    @Test fun `wrapper no publica una cuenta de la sucursal anterior ni disfraza un rechazo de cancelacion`() = runTest {
        var venue = "v"
        val storage = mockk<SecureStorage>(relaxed = true) {
            every { venueId } answers { venue }; every { giftCardRequestsJson } returns "[]"
        }
        val repo = mockk<TableServiceRepository>()
        val roles = mockk<com.avoqado.pos.core.domain.RoleManager> { every { hasVenuePermission(any()) } returns true }
        val m = com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel(repo, storage, red, roles,
            com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore(storage))
        coEvery { repo.getOrderDetail("v", "o") } coAnswers { venue = "otro"; Result.success(OrderDetail("o", esCuentaDeTarjetas = true)) }
        m.cargar("o")
        assertNull(m.detalle.value)
        assertEquals(TextosDeTarjetas.CUENTA_NO_ENCONTRADA, m.mensaje.value)
        venue = "v"
        coEvery { repo.getOrderDetail("v", "o") } returns Result.success(OrderDetail("o", esCuentaDeTarjetas = true))
        m.cargar("o")
        val respuesta = retrofit2.Response.error<Unit>(409,
            """{"message":"Hay un cobro en curso"}""".toResponseBody("application/json".toMediaType()))
        coEvery { repo.cancelarEnLinea("v", "o", "Prueba") } returns Result.failure(retrofit2.HttpException(respuesta))
        assertFalse(m.cancelar("Prueba"))
        assertEquals("Hay un cobro en curso", m.mensaje.value)
        assertNotNull(m.detalle.value)
    }

    @Test fun `Q92 cancelar cuenta exige respuesta en linea y nunca encola mesas conserva cola`() = runTest {
        val api = mockk<ApiService>()
        val outbox = mockk<SyncOutbox>(relaxed = true)
        val repo = TableServiceRepository(api, mockk(relaxed = true), outbox, TableSession(),
            mockk(relaxed = true), mockk(relaxed = true))
        coEvery { api.cancelOrder("v", "o", any()) } returns SimpleSuccessResponse(success = true)
        assertTrue(repo.cancelarEnLinea("v", "o", "Prueba").isSuccess)
        coVerify(exactly = 1) { api.cancelOrder("v", "o", CancelOrderRequest("Prueba")) }
        coEvery { api.cancelOrder("v", "o", any()) } throws IOException("sin respuesta")
        assertTrue(repo.cancelarEnLinea("v", "o", "Prueba").isFailure)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(repo.cancelOrder("v", "o", "Mesa").isSuccess)
        coVerify(exactly = 1) { outbox.enqueue(any(), "CANCEL_ORDER", any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
