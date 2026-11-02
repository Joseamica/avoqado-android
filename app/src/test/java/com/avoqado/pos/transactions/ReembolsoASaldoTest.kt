package com.avoqado.pos.transactions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.core.data.network.AuthInterceptor
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.receiptlayout.LogicalLine
import com.avoqado.pos.printing.receiptlayout.ReceiptBlock
import com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.toReceiptData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * A3: el Reembolso a saldo a favor que no se pierde ni se duplica (spec 2026-10-30 §4, §5.5, §7). Reglas puras de la hoja,
 * el JSON que viaja (repositorio real con MockWebServer), lo pendiente en disco y su recuperación al abrir la venta.
 * Espejo exacto de ReembolsoASaldoTests.swift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReembolsoASaldoTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repo: RefundRepository
    private lateinit var transaccionesDePrueba: TransactionRepository
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: RefundPendingStore
    private val secure = mockk<SecureStorage>(relaxed = true)
    private val archivo get() = temporary.root.resolve("refund_pending.preferences_pb")

    private val ok = """{"success":true,"data":{"refundId":"refund","amount":500.0,"remainingRefundable":0.0,"status":"COMPLETED",""" +
        """"storeCredit":{"customerId":"lucia","customerName":"Lucía","amountCents":50000,"newBalanceCents":50000}}}"""

    @Before fun setup() {
        every { secure.venueId } returns "venue"
        every { secure.userId } returns "actor"
        every { secure.userFirstName } returns "Lupita"
        every { secure.userLastName } returns null
        server = MockWebServer(); server.dispatcher = ComoElServidor(); server.start()
        repo = RefundRepository(secure, clienteDePrueba().build())
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
        store = nuevoStore()
    }
    /**
     * Los ViewModel de cada prueba se apagan al terminarla: si no, una lectura del disco que acaba tarde despierta un colector de
     * `viewModelScope` cuando `Dispatchers.Main` ya se restauró, y la excepción la cobra la siguiente clase con `runTest`.
     */
    private val modelos = mutableListOf<androidx.lifecycle.ViewModel>()
    private fun <T : androidx.lifecycle.ViewModel> apagarAlFinal(vm: T): T = vm.also { modelos += it }

    @After fun cleanup() {
        modelos.forEach { it.viewModelScope.cancel() }
        scope.cancel(); server.shutdown()
    }

    /**
     * Sin reintentos, para contar cada envío. Con una sola dirección: tras cortar una conexión, OkHttp prueba primero la OTRA dirección
     * de `localhost` (::1, donde en el CI existe y el servidor no escucha) y, sin reintentos, el siguiente envío no llegaba.
     */
    private fun clienteDePrueba() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1")) })

    private fun nuevoStore() = RefundPendingStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { archivo }))

    /** «Se mata la app»: se cierra el DataStore y se abre uno nuevo sobre el MISMO archivo, como un proceso nuevo. */
    private fun reiniciarApp() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = nuevoStore()
    }

    private fun respuesta(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)


    private fun cuerpo(llave: String = "llave-del-reembolso", customerId: String = "lucia", defective: Boolean? = null) =
        repo.cuerpoDeReembolso(reason = "RETURNED_GOODS", amountCents = 50000, refundMethod = DEVOLVER_CON_SALDO,
            customerId = customerId, idempotencyKey = llave, defective = defective)

    /** Lo pendiente que deja la cajera de esta sesión (Lupita): su nombre, para el aviso. */
    private fun pendiente(llave: String = "llave-del-reembolso", staffName: String? = "Lupita") =
        PendingRefund("venue", "payment", cuerpo(llave), llave, staffName)

    // MARK: - La pastilla y sus estados

    @Test fun `la pastilla Saldo a favor - sin dato no aparece, sin llaves en gris donde aplica, sin red en gris, con todo se ofrece`() {
        val ofrecido = StoreCreditOption(true)
        val sinLlaves = StoreCreditOption(false, "Pide a Avoqado que active el saldo a favor.")
        assertNull(pastillaDeSaldo(null, true, aplica = true, online = true))
        assertNull(pastillaDeSaldo(ofrecido, null, aplica = true, online = true))
        assertEquals(OpcionDeDevolucion(DEVOLVER_CON_SALDO, "Saldo a favor", "Pide a Avoqado que active el saldo a favor"),
            pastillaDeSaldo(sinLlaves, false, aplica = true, online = true))
        assertNull(pastillaDeSaldo(sinLlaves, false, aplica = false, online = true))
        assertEquals(OpcionDeDevolucion(DEVOLVER_CON_SALDO, "Saldo a favor", "El saldo a favor necesita conexión"),
            pastillaDeSaldo(ofrecido, true, aplica = true, online = false))
        assertEquals(OpcionDeDevolucion(DEVOLVER_CON_SALDO, "Saldo a favor"), pastillaDeSaldo(ofrecido, true, aplica = true, online = true))
        assertNull(pastillaDeSaldo(StoreCreditOption(false, "Lo pagado con tarjeta de regalo regresa a su misma tarjeta."), true,
            aplica = false, online = true))
    }

    @Test fun `una tarjeta de la terminal gana la pastilla del mismo medio y la del saldo a favor`() {
        val saldo = OpcionDeDevolucion(DEVOLVER_CON_SALDO, "Saldo a favor")
        val original = opcionesParaDevolver("CREDIT_CARD", null).first()
        assertEquals(listOf(OpcionDeDevolucion(null, "Tarjeta de crédito"), saldo), opcionesConSaldo(emptyList(), original, saldo, false))
        val efectivo = opcionesParaDevolver("CASH", null)
        assertEquals(efectivo + saldo, opcionesConSaldo(efectivo, efectivo.first(), saldo, false))
        assertEquals(efectivo, opcionesConSaldo(efectivo, efectivo.first(), null, false))
    }

    @Test fun `con sólo saldo y devolución de mercancía, Otro o sin motivo sólo queda el saldo a favor`() {
        val saldo = OpcionDeDevolucion(DEVOLVER_CON_SALDO, "Saldo a favor")
        val efectivo = opcionesParaDevolver("CASH", null)
        for (motivo in listOf("RETURNED_GOODS", "OTHER", null)) {
            assertTrue(ofrecerEsPorDefecto(true, motivo))
            assertTrue(restringe(true, motivo, esPorDefecto = false))
            assertEquals(listOf(saldo), opcionesConSaldo(efectivo, efectivo.first(), saldo, restringe(true, motivo, esPorDefecto = false)))
            // «Es por defecto» marcado: salen todas.
            assertFalse(restringe(true, motivo, esPorDefecto = true))
            assertEquals(efectivo + saldo, opcionesConSaldo(efectivo, efectivo.first(), saldo, restringe(true, motivo, esPorDefecto = true)))
        }
        // Un motivo libre: salen todas, con o sin «Es por defecto».
        for (libre in listOf("ACCIDENTAL_CHARGE", "FRAUDULENT_CHARGE", "CANCELLED_ORDER")) {
            assertFalse(ofrecerEsPorDefecto(true, libre))
            assertFalse(restringe(true, libre, esPorDefecto = false))
            assertEquals(efectivo + saldo, opcionesConSaldo(efectivo, efectivo.first(), saldo, restringe(true, libre, esPorDefecto = false)))
        }
        // Sin «sólo saldo» nunca restringe.
        assertFalse(restringe(false, "RETURNED_GOODS", esPorDefecto = false))
        // Sin «sólo saldo» no se restringe ni se ofrece «Es por defecto».
        assertFalse(ofrecerEsPorDefecto(false, "RETURNED_GOODS"))
        // «Sólo saldo» efectivo = el interruptor y las llaves (D-1 del SERVIDOR).
        assertTrue(soloSaldoEfectivo(true, true))
        assertFalse(soloSaldoEfectivo(true, false))
        assertFalse(soloSaldoEfectivo(true, null))
        assertFalse(soloSaldoEfectivo(false, true))
        // Lo escogido cae a la primera opción permitida si ya no se ofrece; sin opciones, se queda como está.
        assertEquals(DEVOLVER_CON_SALDO, devolucionEscogida(listOf(saldo), null))
        assertEquals(DEVOLVER_CON_SALDO, devolucionEscogida(listOf(saldo), "CASH"))
        assertEquals("BANK_TRANSFER", devolucionEscogida(efectivo + saldo, "BANK_TRANSFER"))
        assertNull(devolucionEscogida(emptyList(), null))
    }

    @Test fun `defective sólo viaja cuando cuenta y la terminal recibe la excepción de sólo saldo`() {
        assertEquals(true, defectoParaEnvio(true, "RETURNED_GOODS", true))
        assertNull(defectoParaEnvio(true, "RETURNED_GOODS", false))
        assertNull(defectoParaEnvio(false, "RETURNED_GOODS", true))
        assertNull(defectoParaEnvio(true, "ACCIDENTAL_CHARGE", true))
        assertEquals("DEFECTIVE", excepcionDeSoloSaldo(true, "RETURNED_GOODS", true))
        assertEquals("DEFECTIVE", excepcionDeSoloSaldo(true, "OTHER", true))
        assertEquals("ACCIDENTAL_CHARGE", excepcionDeSoloSaldo(true, "ACCIDENTAL_CHARGE", false))
        assertEquals("FRAUDULENT_CHARGE", excepcionDeSoloSaldo(true, "FRAUDULENT_CHARGE", true))
        assertEquals("CANCELLED_ORDER", excepcionDeSoloSaldo(true, "CANCELLED_ORDER", false))
        assertNull(excepcionDeSoloSaldo(true, "RETURNED_GOODS", false))
        assertNull(excepcionDeSoloSaldo(false, "ACCIDENTAL_CHARGE", true))
    }

    @Test fun `el crédito de cambio manda STORE_CREDIT tal cual y su bloqueo no aplica al saldo a favor`() {
        assertEquals(DEVOLVER_CON_SALDO, refundMethodForSubmit("EXCHANGE_CREDIT", DEVOLVER_CON_SALDO))
        assertNull(refundMethodForSubmit("EXCHANGE_CREDIT", "CASH"))
        assertNull(refundMethodForSubmit("EXCHANGE_CREDIT", null))
        val cadena = refundChainRules("EXCHANGE_CREDIT", "CREDIT_CARD", "Tarjeta", "EXCHANGE_CHAIN_NEEDS_TERMINAL")
        assertEquals(TextosCambio.EXCHANGE_CHAIN_NEEDS_TERMINAL, bloqueoDeLaCadena(cadena, null))
        assertNull(bloqueoDeLaCadena(cadena, DEVOLVER_CON_SALDO))
    }

    @Test fun `al saldo a favor le faltan red, a quién abonar y su teléfono`() {
        val lucia = com.avoqado.pos.transactions.presentation.exchange.ExchangeStoreCreditCustomer("lucia", "Lucía", "+525512345678", true)
        assertEquals("El saldo a favor necesita conexión", faltaParaSaldo(false, lucia))
        assertEquals("Elige a quién se le abona el saldo a favor", faltaParaSaldo(true, null))
        assertEquals("Para darle saldo a favor a Lucía hace falta su teléfono: con él lo usa. Agrégalo a su ficha.",
            faltaParaSaldo(true, lucia.copy(phone = null)))
        assertNull(faltaParaSaldo(true, lucia))
    }

    // MARK: - El JSON que viaja

    @Test fun `el cuerpo a saldo a favor lleva refundMethod, customerId, idempotencyKey y defective sólo cuando cuenta`() {
        val con = Json.parseToJsonElement(cuerpo(defective = true)).jsonObject
        assertEquals("STORE_CREDIT", con["refundMethod"]!!.jsonPrimitive.content)
        assertEquals("lucia", con["customerId"]!!.jsonPrimitive.content)
        assertEquals("llave-del-reembolso", con["idempotencyKey"]!!.jsonPrimitive.content)
        assertEquals(true, con["defective"]!!.jsonPrimitive.boolean)
        assertEquals(50000, con["amount"]!!.jsonPrimitive.int)
        val sin = Json.parseToJsonElement(cuerpo()).jsonObject
        assertFalse(sin.containsKey("defective"))
        // Un reembolso de siempre no cambia: sin las llaves nuevas.
        val deSiempre = Json.parseToJsonElement(repo.cuerpoDeReembolso(reason = "RETURNED_GOODS", amountCents = 100, refundMethod = "CASH")).jsonObject
        assertEquals(setOf("amount", "reason", "refundMethod"), deSiempre.keys)
    }

    @Test fun `compra con tarjeta, cambio parejo y reembolso a saldo - el JSON lleva STORE_CREDIT y customerId`() = runBlocking {
        server.enqueue(respuesta(201, ok))
        val credito = Transaction("payment", method = "OTHER", tenderSystemKind = "EXCHANGE_CREDIT", chainRefundMethod = "CREDIT_CARD",
            chainRefundLabel = "Tarjeta", chainRefundBlockedCode = "EXCHANGE_CHAIN_NEEDS_TERMINAL", customerId = "lucia")
        val metodo = refundMethodForSubmit(credito.tenderSystemKind, DEVOLVER_CON_SALDO)
        val llave = "llave-de-la-cadena"
        val body = repo.cuerpoDeReembolso(reason = "RETURNED_GOODS", amountCents = 50000, refundMethod = metodo,
            customerId = credito.customerId, idempotencyKey = llave)
        val desenlace = enviarReembolsoPendiente(repo, store, PendingRefund("venue", credito.id, body, llave).also { store.guardar(it) })
        assertTrue(desenlace is DesenlaceDeSaldo.Hecho)
        val enviado = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("STORE_CREDIT", enviado["refundMethod"]!!.jsonPrimitive.content)
        assertEquals("lucia", enviado["customerId"]!!.jsonPrimitive.content)
        assertEquals(llave, enviado["idempotencyKey"]!!.jsonPrimitive.content)
    }

    @Test fun `la terminal recibe storeCreditOnlyException con defecto y con cobro por error, y sin sólo saldo el cuerpo no cambia`() = runBlocking {
        val terminal = TerminalPaymentService(mockk(relaxed = true) {
            every { venueId } returns "venue"; every { accessToken } returns "token"
        }, OkHttpClient())
        terminal.baseUrl = server.url("/api/v1").toString().trimEnd('/')
        repeat(3) { server.enqueue(respuesta(200, """{"success":true,"status":"opened"}""")) }
        fun enviado() = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        terminal.requestRefundOnTerminal("t1", "payment", "Productos devueltos",
            storeCreditOnlyException = excepcionDeSoloSaldo(true, "RETURNED_GOODS", defective = true)).getOrThrow()
        assertEquals("DEFECTIVE", enviado()["storeCreditOnlyException"]!!.jsonPrimitive.content)
        terminal.requestRefundOnTerminal("t1", "payment", "Cargo accidental",
            storeCreditOnlyException = excepcionDeSoloSaldo(true, "ACCIDENTAL_CHARGE", defective = false)).getOrThrow()
        assertEquals("ACCIDENTAL_CHARGE", enviado()["storeCreditOnlyException"]!!.jsonPrimitive.content)
        terminal.requestRefundOnTerminal("t1", "payment", "Productos devueltos",
            storeCreditOnlyException = excepcionDeSoloSaldo(false, "RETURNED_GOODS", defective = true)).getOrThrow()
        assertEquals(setOf("requestId", "paymentId", "reason"), enviado().keys)
    }

    // MARK: - Lo pendiente: se guarda antes, se borra sólo con éxito o con un rechazo definitivo

    @Test fun `se guarda en disco ANTES del POST y se borra con el éxito`() = runBlocking {
        var alLlegar: PendingRefund? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                alLlegar = runBlocking { store.pendiente("venue", "payment") }
                return respuesta(201, ok)
            }
        }
        val desenlace = SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso", "Lupita")
        assertEquals(pendiente(), alLlegar)
        assertEquals("Lucía", (desenlace as DesenlaceDeSaldo.Hecho).data.storeCredit?.customerName)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `un STORE_CREDIT_REJECTED libera lo pendiente y muestra el mensaje del servidor`() = runBlocking {
        store.guardar(pendiente())
        server.enqueue(respuesta(400, """{"success":false,"code":"STORE_CREDIT_REJECTED","message":"No se puede devolver ${'$'}600.00",""" +
            """"details":{"originalCode":null}}"""))
        val d = enviarReembolsoPendiente(repo, store, pendiente())
        assertEquals(DesenlaceDeSaldo.Rechazado("No se puede devolver \$600.00", "STORE_CREDIT_REJECTED"), d)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `un 400 sin code también libera lo pendiente`() = runBlocking {
        store.guardar(pendiente())
        server.enqueue(respuesta(400, """{"success":false,"message":"customerId debe ser texto"}"""))
        assertTrue(enviarReembolsoPendiente(repo, store, pendiente()) is DesenlaceDeSaldo.Rechazado)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `IDEMPOTENCY_KEY_REUSED conserva lo pendiente`() = runBlocking {
        store.guardar(pendiente())
        server.enqueue(respuesta(409, """{"success":false,"code":"IDEMPOTENCY_KEY_REUSED","message":"Esta llave ya se usó."}"""))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_LLAVE_REUSADA), enviarReembolsoPendiente(repo, store, pendiente()))
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
    }

    @Test fun `los códigos sin prefijo que no son definitivos conservan lo pendiente`() = runBlocking {
        store.guardar(pendiente())
        for (code in listOf("REFUND_AUTHORITY_UNAVAILABLE", "REFUND_AUTHORITY_CHANGED", "ACTOR_MISMATCH", "ORDER_LOCK_SCOPE_TOO_LARGE")) {
            server.enqueue(respuesta(409, """{"success":false,"code":"$code","message":"Reintenta en un momento."}"""))
            assertEquals(DesenlaceDeSaldo.Pendiente("Reintenta en un momento."), enviarReembolsoPendiente(repo, store, pendiente()))
            assertEquals(pendiente(), store.pendiente("venue", "payment"))
        }
        // Un 403 SIN el eco de su petición (un proxy, Cloudflare) no prueba nada: se conserva (con eco, también: Opción A).
        server.enqueue(sinEco(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}""")))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_NO_CONFIRMADO), enviarReembolsoPendiente(repo, store, pendiente()))
        for (status in listOf(401, 408, 429)) {
            server.enqueue(respuesta(status, """{"success":false,"message":"x"}"""))
            assertTrue(enviarReembolsoPendiente(repo, store, pendiente()) is DesenlaceDeSaldo.Pendiente)
        }
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
    }

    @Test fun `un 503 y una respuesta perdida conservan lo pendiente`() = runBlocking {
        store.guardar(pendiente())
        server.enqueue(respuesta(503, """{"success":false,"message":"Servicio no disponible"}"""))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_NO_CONFIRMADO), enviarReembolsoPendiente(repo, store, pendiente()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_NO_CONFIRMADO), enviarReembolsoPendiente(repo, store, pendiente()))
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
    }

    // MARK: - Opción A (founder, 2-nov): nada se reenvía solo; sólo «Confirmar estado», un toque humano

    @Test fun `Opción A - al abrir la venta no sale ningún reenvío, ni con la sesión de quien lo pidió ni con la de otra persona`() = runBlocking {
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        for (pidio in listOf(pendiente(), pendiente(staffName = "Beatriz López"))) {
            store.guardar(pidio)
            val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 0.0))
            vm.selectTransaction("payment")
            withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
            repeat(20) { yield(); kotlinx.coroutines.delay(10) }
            assertEquals("al abrir la venta no sale nada (${pidio.staffName})", 0, server.requestCount)
            assertNull(vm.avisoDeReembolso.value)
            assertTrue(vm.reembolsoBloqueado.value)
            assertNotNull(vm.motivoParaNoReembolsar("payment"))
            store.borrar(pidio)
        }
        assertEquals("Reembolso a saldo a favor sin confirmar (lo pidió Lupita)", avisoDelPendiente(pendiente()))
    }

    @Test fun `Confirmar estado - lo decide quien está, con SU sesión, la misma llave y en primer plano`() = runBlocking {
        val deBeatriz = pendiente(staffName = "Beatriz López")
        store.guardar(deBeatriz)
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 0.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertEquals(0, server.requestCount)
        assertEquals("Reembolso a saldo a favor sin confirmar (lo pidió Beatriz López)", avisoDelPendiente(vm.reembolsoPendiente.value!!))
        assertTrue(vm.reembolsoBloqueado.value)
        server.enqueue(respuesta(201, ok))
        vm.confirmarReembolsoPendiente()
        val confirmado = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(deBeatriz.body, confirmado.body.readUtf8())
        // Lo pide una persona: puede salir el PIN de gerente (su consentimiento); nunca va marcado de fondo.
        assertNull(confirmado.getHeader("X-Avoqado-Background"))
        assertEquals("Se confirmó el reembolso a saldo a favor\nSaldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00",
            withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } })
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `un pendiente de una versión anterior - sin nombre, o con el staffId que ya no se usa - se lee y no sale nada solo`() = runBlocking {
        val base = """"venueId":"venue","paymentId":"payment","body":${kotlinx.serialization.json.JsonPrimitive(cuerpo())},"idempotencyKey":"llave-del-reembolso""""
        // Con `staffId` y `staffName` (la versión de «Quién lo pidió»): el `staffId` se ignora.
        assertEquals(pendiente(), jsonReembolsos.decodeFromString(PendingRefund.serializer(), """{$base,"staffId":"actor","staffName":"Lupita"}"""))
        // Sin ninguno de los dos: el aviso genérico.
        val viejo = jsonReembolsos.decodeFromString(PendingRefund.serializer(), """{$base}""")
        assertNull(viejo.staffName)
        store.guardar(viejo)
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 0.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertEquals(0, server.requestCount)
        assertEquals(TextosCambio.REEMBOLSO_SIN_CONFIRMAR, avisoDelPendiente(vm.reembolsoPendiente.value!!))
    }

    @Test fun `Opción A - un 403 NUNCA borra lo pendiente, ni con eco - se conserva y dice que lo confirme un gerente`() = runBlocking {
        // El SERVIDOR revisa el permiso ANTES de reconocer la llave: un 403 con eco no prueba que no se escribió (Codex apps r7 #2).
        server.enqueue(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}"""))
        val desenlace = SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso", "Lupita")
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_PIDE_GERENTE), desenlace)
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        server.enqueue(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}"""))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_PIDE_GERENTE), enviarReembolsoPendiente(repo, store, pendiente()))
        // Sin eco (un proxy) tampoco prueba nada: se conserva.
        server.enqueue(sinEco(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}""")))
        assertEquals(DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_NO_CONFIRMADO), enviarReembolsoPendiente(repo, store, pendiente()))
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        assertEquals(TextosCambio.REEMBOLSO_PIDE_GERENTE, mensajeDeReembolsoPendiente(403, null, "Permission 'payments:refund' required"))
        // Ningún envío va marcado de fondo.
        assertTrue(List(3) { server.takeRequest() }.all { it.getHeader("X-Avoqado-Background") == null })
    }

    // MARK: - La venta que no se pudo abrir dice por qué (hz:459741ee:D3)

    @Test fun `sin red, tocar una venta dice por qué y aun así muestra lo pendiente, y Confirmar estado no manda nada`() = runBlocking {
        store.guardar(pendiente())
        val sinRed = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns false }
        val vm = vm(sinRed, null)
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        assertEquals(TextosCambio.VENTA_SIN_RED, vm.detalleNoDisponible.value)
        assertNull(vm.selectedTransaction.value)
        assertTrue(withTimeout(5_000) { vm.reembolsoBloqueado.first { it } })
        vm.confirmarReembolsoPendiente()
        assertEquals(TextosCambio.SALDO_SIN_RED, withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } })
        assertEquals(0, server.requestCount)
        assertNotNull(store.pendiente("venue", "payment"))
    }

    @Test fun `con red, si el detalle falla dice que no se pudo abrir y se puede volver a tocar`() = runBlocking {
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, null)
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.detalleNoDisponible.value == null) yield() }
        assertEquals(TextosCambio.VENTA_NO_ABRIO, vm.detalleNoDisponible.value)
        assertFalse(vm.isLoadingDetail.value)
        vm.clearSelection()
        assertNull(vm.detalleNoDisponible.value)
        repeat(20) { yield(); kotlinx.coroutines.delay(10) } // Que termine la lectura de lo pendiente antes de restaurar Main.
    }

    @Test fun `una venta que sí abre no trae aviso, y tocar otra borra el de la anterior`() = runBlocking {
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val detalle = Transaction("payment", amount = 500.0, remainingRefundable = 500.0)
        val vm = vm(online, null)
        vm.selectTransaction("otra")
        withTimeout(5_000) { while (vm.detalleNoDisponible.value == null) yield() }
        coEvery { transaccionesDePrueba.fetchTransactionDetail("payment", "actor", "venue") } returns detalle
        vm.selectTransaction("payment")
        assertNull(vm.detalleNoDisponible.value)
        withTimeout(5_000) { while (vm.selectedTransaction.value == null) yield() }
        assertNull(vm.detalleNoDisponible.value)
        repeat(20) { yield(); kotlinx.coroutines.delay(10) } // Que termine la lectura de lo pendiente antes de restaurar Main.
    }

    // MARK: - Codex apps r8 #1: el envío sale con la sesión de quien tocó, o no sale

    /** El cliente con el [AuthInterceptor] de verdad: es ahí donde la petición toma su credencial. */
    private fun conLaCredencialDeVerdad() {
        repo = RefundRepository(secure, clienteDePrueba().addInterceptor(AuthInterceptor(secure)).build())
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    /** Lupita toca con la sesión 1; cuando la petición va a salir, un relevo por PIN ya instaló a Beatriz (sesión 2). */
    private fun entroOtraPersonaAlSalir() {
        every { secure.generacion } returns 1L
        every { secure.selloYAccess() } returns (2L to "token-de-beatriz")
    }

    private suspend fun ventaAbierta(): TransactionsViewModel {
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        return vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 500.0)).also { it.selectTransaction("payment") }
    }

    @Test fun `Codex r8 1 - si entra otra persona entre Confirmar estado y el envío, no sale con su sesión y lo pendiente se conserva`() = runBlocking {
        conLaCredencialDeVerdad(); entroOtraPersonaAlSalir()
        store.guardar(pendiente())
        val vm = ventaAbierta()
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        vm.confirmarReembolsoPendiente()
        assertEquals(TextosCambio.REEMBOLSO_NO_CONFIRMADO, withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } })
        assertEquals(0, server.requestCount)
        assertNotNull(store.pendiente("venue", "payment"))
    }

    @Test fun `Codex r8 1 - el primer envío de la hoja tampoco sale con la sesión de otra persona y queda sin confirmar`() = runBlocking {
        conLaCredencialDeVerdad(); entroOtraPersonaAlSalir()
        val vm = ventaAbierta()
        val desenlace = withTimeout(10_000) { vm.enviarReembolsoASaldo("payment", cuerpo(), "llave-del-reembolso").await() }
        assertTrue(desenlace is DesenlaceDeSaldo.Pendiente)
        assertEquals(0, server.requestCount)
        assertNotNull(store.pendiente("venue", "payment"))
    }

    @Test fun `Codex r9 - frenado por el cambio de sesión, el siguiente toque ya sale con la sesión de quien está y la misma llave`() = runBlocking {
        conLaCredencialDeVerdad(); entroOtraPersonaAlSalir()
        val pidio = pendiente()
        store.guardar(pidio)
        val vm = ventaAbierta()
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        vm.confirmarReembolsoPendiente()
        withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } }
        assertEquals(0, server.requestCount)
        // Beatriz ya está en la pantalla y toca ella: su sesión es la de ahora.
        every { secure.generacion } returns 2L
        server.enqueue(respuesta(201, ok))
        vm.confirmarReembolsoPendiente()
        val confirmado = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer token-de-beatriz", confirmado.getHeader("Authorization"))
        assertEquals(pidio.body, confirmado.body.readUtf8())
        withTimeout(10_000) { while (store.pendiente("venue", "payment") != null) yield() }
    }

    @Test fun `Codex r8 1 - con la misma sesión, Confirmar estado sale con la credencial de quien tocó`() = runBlocking {
        conLaCredencialDeVerdad()
        every { secure.generacion } returns 1L
        every { secure.selloYAccess() } returns (1L to "token-de-lupita")
        store.guardar(pendiente())
        val vm = ventaAbierta()
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        server.enqueue(respuesta(201, ok))
        vm.confirmarReembolsoPendiente()
        assertEquals("Bearer token-de-lupita", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } }
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `Codex r7 #2 - se escribió y se perdió la respuesta, un 403 al confirmar no lo borra y el gerente lo confirma con la misma llave`() = runBlocking {
        // 1) La cajera manda (el gerente autorizó con PIN): el servidor lo escribe y la respuesta se pierde.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso", "Lupita")
            is DesenlaceDeSaldo.Pendiente)
        val enviado = server.takeRequest().body.readUtf8()
        // 2) Se reabre la venta: no sale nada y «Emitir reembolso» queda apagado.
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 400.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertEquals(1, server.requestCount)
        assertTrue(vm.reembolsoBloqueado.value)
        // 3) «Confirmar estado» y un rechazo sin eco (un proxy): se conserva.
        server.enqueue(sinEco(respuesta(400, """{"success":false,"code":"STORE_CREDIT_REJECTED","message":"x"}""")))
        vm.confirmarReembolsoPendiente()
        assertEquals(TextosCambio.REEMBOLSO_NO_CONFIRMADO,
            withTimeout(10_000) { vm.avisoDeReembolso.first { it == TextosCambio.REEMBOLSO_NO_CONFIRMADO } })
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        assertTrue(vm.reembolsoBloqueado.value)
        // 4) Quien está no tiene permiso: 403 con eco. NO se borra (el permiso se revisa antes de reconocer la llave).
        server.enqueue(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}"""))
        vm.confirmarReembolsoPendiente()
        assertEquals(TextosCambio.REEMBOLSO_PIDE_GERENTE,
            withTimeout(10_000) { vm.avisoDeReembolso.first { it == TextosCambio.REEMBOLSO_PIDE_GERENTE } })
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        assertNotNull(vm.reembolsoPendiente.value)
        assertTrue(vm.reembolsoBloqueado.value)
        assertEquals(TextosCambio.REEMBOLSO_SIN_CONFIRMAR, vm.motivoParaNoReembolsar("payment"))
        // 5) El gerente toca «Confirmar estado»: misma llave; el servidor reconoce la repetición y contesta lo de la primera vez.
        server.enqueue(respuesta(201, ok))
        vm.confirmarReembolsoPendiente()
        assertEquals("Se confirmó el reembolso a saldo a favor\nSaldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00",
            withTimeout(10_000) { vm.avisoDeReembolso.first { it?.startsWith("Se confirmó") == true } })
        assertNull(store.pendiente("venue", "payment"))
        assertFalse(withTimeout(5_000) { vm.reembolsoBloqueado.first { !it } })
        // Los tres envíos llevan los MISMOS bytes (misma llave), ninguno de fondo, y no salió ninguno más.
        val confirmaciones = List(3) { server.takeRequest(5, TimeUnit.SECONDS)!! }
        assertTrue(confirmaciones.all { it.body.readUtf8() == enviado && it.getHeader("X-Avoqado-Background") == null })
        assertEquals(4, server.requestCount)
    }

    @Test fun `un pendiente de otra llave no se pisa, se dice que hay uno sin confirmar y no se borra con la llave equivocada`() = runBlocking {
        store.guardar(pendiente())
        assertEquals(DesenlaceDeSaldo.OtroPendiente,
            SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo("otra-llave-123"), "otra-llave-123", "Lupita"))
        assertEquals(0, server.requestCount)
        store.borrar(pendiente("otra-llave-123"))
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        assertNull(store.pendiente("otra-sucursal", "payment"))
    }

    // MARK: - La recuperación al abrir la venta (no la hoja), tras matar la app

    private fun vm(monitor: ConnectivityMonitor, detail: Transaction?, pendientes: RefundPendingStore = store): TransactionsViewModel {
        val transactions = mockk<TransactionRepository>(relaxed = true)
        transaccionesDePrueba = transactions
        every { transactions.transactions } returns MutableStateFlow(emptyList())
        every { transactions.isLoading } returns MutableStateFlow(false)
        every { transactions.isLoadingMore } returns MutableStateFlow(false)
        coEvery { transactions.fetchTransactionDetail("payment", "actor", "venue") } returns detail
        coEvery { transactions.fetchTransactions(any(), any()) } returns Result.success(Unit)
        val factory = mockk<RefreshGateFactory> { every { create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }) }
        return apagarAlFinal(TransactionsViewModel(transactions, repo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), secure, factory, monitor,
            mockk(relaxed = true), pendientes))
    }

    @Test fun `Reembolso completo a saldo, se pierde la respuesta, se mata la app y al reabrir la venta Confirmar estado manda la misma llave`() = runBlocking {
        // 1) La cajera manda el reembolso completo a saldo: se guarda y el servidor SÍ lo escribe, pero la respuesta se pierde.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val primero = SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso", "Lupita")
        assertTrue(primero is DesenlaceDeSaldo.Pendiente)
        val enviado = server.takeRequest().body.readUtf8()
        // 2) Se mata la app: el pendiente sobrevive en disco.
        reiniciarApp()
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        // 3) Se reabre la venta por la navegación real (con todo devuelto el botón de Reembolso sale apagado): no sale nada solo.
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val venta = Transaction("payment", amount = 500.0, remainingRefundable = 0.0, customerName = "Mamá")
        val vm = vm(online, venta)
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertEquals(1, server.requestCount)
        // 4) La cajera toca «Confirmar estado»: se reenvía lo MISMO (misma llave), se muestra el resultado y lo pendiente se borra.
        server.enqueue(respuesta(201, ok))
        vm.confirmarReembolsoPendiente()
        val aviso = withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } }
        assertEquals(enviado, server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("Se confirmó el reembolso a saldo a favor\nSaldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00", aviso)
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `sin red, al abrir la venta sólo se muestra lo pendiente y Confirmar estado avisa que necesita conexión`() = runBlocking {
        store.guardar(pendiente())
        val sinRed = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns false }
        val vm = vm(sinRed, Transaction("payment", amount = 500.0, remainingRefundable = 500.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { while (vm.reembolsoPendiente.value == null) yield() }
        repeat(20) { yield(); kotlinx.coroutines.delay(10) }
        assertNull(vm.avisoDeReembolso.value)
        assertEquals(pendiente(), vm.reembolsoPendiente.value)
        // «Confirmar estado» sin red: lo dice y no toca la red.
        vm.confirmarReembolsoPendiente()
        assertEquals("El saldo a favor necesita conexión", withTimeout(5_000) { vm.avisoDeReembolso.first { it != null } })
        assertEquals(0, server.requestCount)
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        // Vuelve la red y la cajera toca «Confirmar estado»: un rechazo definitivo (con eco) libera.
        every { sinRed.isOnline() } returns true
        server.enqueue(respuesta(422, """{"success":false,"code":"STORE_CREDIT_OVER_CAP","message":"El saldo a favor de Lucía pasaría de ${'$'}10,000."}"""))
        vm.confirmarReembolsoPendiente()
        assertEquals("El saldo a favor de Lucía pasaría de \$10,000.",
            withTimeout(10_000) { vm.avisoDeReembolso.first { it?.startsWith("El saldo a favor de") == true } })
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `si borrar falla tras el éxito no se cae - se borra a lo mejor posible`() = runBlocking {
        val roto = io.mockk.mockk<RefundPendingStore>()
        io.mockk.coEvery { roto.borrar(any()) } throws java.io.IOException("disco lleno")
        server.enqueue(respuesta(201, ok))
        assertTrue(enviarReembolsoPendiente(repo, roto, pendiente()) is DesenlaceDeSaldo.Hecho)
    }

    private val conTelefonoDistinto = ok.replace(""""newBalanceCents":50000}""", """"newBalanceCents":50000,"phoneLast4":"5678","phoneMismatch":true}""")

    @Test fun `el envío directo que sale bien le dice a la cajera a quién, cuánto, el saldo nuevo y el teléfono que no coincide`() = runBlocking {
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 500.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { vm.selectedTransaction.first { it != null } }
        server.enqueue(respuesta(201, conTelefonoDistinto))
        val desenlace = vm.enviarReembolsoASaldo("payment", cuerpo(), "llave-del-reembolso").await()
        assertTrue(desenlace is DesenlaceDeSaldo.Hecho)
        // La hoja se cierra (su `onDismiss`): el aviso sigue en el detalle, no lo borra nada.
        vm.dismissIssueRefundSheet()
        assertEquals("Saldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00\n" +
            "El saldo de Lucía se usa con el teléfono que termina en 5678, no con el de su ficha. Un gerente lo cambia en el DASHBOARD.",
            vm.avisoDeReembolso.value)
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `cerrar la hoja con el envío en vuelo - el detalle muestra lo pendiente y el envío sigue hasta el final`() = runBlocking {
        val llego = java.util.concurrent.CountDownLatch(1)
        val liberar = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                llego.countDown()
                liberar.await(10, TimeUnit.SECONDS)
                return respuesta(201, ok)
            }
        }
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 500.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { vm.selectedTransaction.first { it != null } }
        val envio = vm.enviarReembolsoASaldo("payment", cuerpo(), "llave-del-reembolso")
        // La hoja espera el desenlace en SU ámbito; la cajera la cierra con el POST en vuelo.
        val hoja = CoroutineScope(Dispatchers.Default).launch { envio.await() }
        assertTrue(llego.await(5, TimeUnit.SECONDS))
        hoja.cancel()
        vm.dismissIssueRefundSheet()
        // El detalle se entera: lo pendiente aparece y «Emitir reembolso» se apaga (sale con `reembolsoPendiente != null`).
        assertEquals(pendiente(), withTimeout(5_000) { vm.reembolsoPendiente.first { it != null } })
        assertTrue("el envío no se cancela con la hoja", envio.isActive)
        // El servidor contesta: el envío termina solo y el detalle queda con el abono.
        liberar.countDown()
        assertTrue(envio.await() is DesenlaceDeSaldo.Hecho)
        assertNull(vm.reembolsoPendiente.value)
        assertEquals("Saldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00", vm.avisoDeReembolso.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `al abrir la hoja otra vez vuelve la clienta de la venta`() {
        val saldo = apagarAlFinal(com.avoqado.pos.transactions.presentation.SaldoAFavorDelReembolsoViewModel(mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        val venta = Transaction("payment", customerId = "mama", customerName = "Mamá")
        saldo.abrir(venta)
        saldo.escoger(com.avoqado.pos.customers.data.model.Customer("lucia", firstName = "Lucía", phone = "+525512345678"))
        assertEquals("lucia", saldo.cliente.value?.id)
        saldo.abrir(venta)
        assertEquals("mama", saldo.cliente.value?.id)
        assertNull(saldo.mensaje.value)
    }

    // MARK: - Ronda 2: la guarda de TODA devolución de la hoja

    private val okEfectivo = """{"success":true,"data":{"refundId":"refund-cash","amount":1.0,"remainingRefundable":0.0,"status":"COMPLETED"}}"""

    @Test fun `con un saldo a favor sin confirmar de A, un reembolso en efectivo de A se rechaza sin tocar la red, y el de B sí pasa`() = runBlocking {
        val sinRed = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns false }
        val vm = vm(sinRed, Transaction("payment", amount = 500.0, remainingRefundable = 500.0))
        vm.selectTransaction("payment")
        withTimeout(5_000) { vm.selectedTransaction.first { it != null } }
        store.guardar(pendiente())
        // Lo que hace la hoja antes de CUALQUIER envío: la guarda; si da motivo, no se manda.
        suspend fun efectivo(pago: String): Result<AssociatedRefundData>? = if (vm.motivoParaNoReembolsar(pago) != null) null
            else repo.issueAssociatedRefund(pago, "RETURNED_GOODS", amountCents = 100, refundMethod = "CASH")
        assertEquals(TextosCambio.REEMBOLSO_SIN_CONFIRMAR, vm.motivoParaNoReembolsar("payment"))
        assertNull(efectivo("payment"))
        assertEquals(0, server.requestCount)
        // El detalle se entera: lo pendiente aparece y «Emitir reembolso» se apaga.
        assertEquals(pendiente(), vm.reembolsoPendiente.value)
        assertTrue(vm.reembolsoBloqueado.value)
        // Otra venta no se frena.
        server.enqueue(respuesta(201, okEfectivo))
        assertNull(vm.motivoParaNoReembolsar("otro"))
        assertTrue(efectivo("otro")!!.isSuccess)
        assertEquals("/api/v1/mobile/venues/venue/payments/otro/refund", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test fun `con un envío a saldo en vuelo que aún no llega al disco, cerrar la hoja deja Emitir apagado y la guarda frena otro medio`() = runBlocking {
        val escribir = CompletableDeferred<Unit>()
        val lento = mockk<RefundPendingStore>()
        coEvery { lento.pendiente(any(), any()) } returns null
        coEvery { lento.guardar(any()) } coAnswers { escribir.await() }
        coEvery { lento.borrar(any()) } returns Unit
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val vm = vm(online, Transaction("payment", amount = 500.0, remainingRefundable = 500.0), lento)
        vm.selectTransaction("payment")
        withTimeout(5_000) { vm.selectedTransaction.first { it != null } }
        val envio = vm.enviarReembolsoASaldo("payment", cuerpo(), "llave-del-reembolso")
        // La cajera cierra la hoja mientras el aparato todavía escribe: la relectura no ve nada en disco…
        vm.dismissIssueRefundSheet()
        assertNull(withTimeout(5_000) { vm.reembolsoPendiente.first { it == null } })
        // …pero el envío en vuelo cuenta: «Emitir reembolso» sigue apagado y la guarda frena el efectivo.
        assertTrue(withTimeout(5_000) { vm.reembolsoBloqueado.first { it } })
        assertEquals(TextosCambio.REEMBOLSO_SIN_CONFIRMAR, vm.motivoParaNoReembolsar("payment"))
        assertEquals(0, server.requestCount)
        // Termina de escribir y el servidor contesta: se libera.
        server.enqueue(respuesta(201, ok))
        escribir.complete(Unit)
        assertTrue(envio.await() is DesenlaceDeSaldo.Hecho)
        assertFalse(withTimeout(5_000) { vm.reembolsoBloqueado.first { !it } })
        assertNull(vm.motivoParaNoReembolsar("payment"))
    }

    private fun fuente(ruta: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) { java.io.File(dir, ruta).takeIf { it.isFile }?.let { return it.readText() }; dir = dir.parentFile }
        error("No se encontró $ruta")
    }

    @Test fun `toda devolución de la hoja pasa primero por la guarda del saldo a favor`() {
        val hoja = fuente("app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt")
        val terminal = hoja.substring(hoja.indexOf("fun mandarATerminal("), hoja.indexOf("fun abrirEnTerminal()"))
        val reembolsar = hoja.substring(hoja.indexOf("text = \"Reembolsar\""), hoja.indexOf("enabled = canSubmit", hoja.indexOf("text = \"Reembolsar\"")))
        for (trozo in listOf(terminal, reembolsar)) {
            val guarda = trozo.indexOf("antesDeReembolsar()")
            assertTrue("falta la guarda del saldo a favor", guarda > 0)
            assertTrue("la guarda va antes de tocar la red", guarda < trozo.indexOf("conexion.iniciar("))
        }
        val detalle = fuente("app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionDetailSheet.kt")
        assertTrue(detalle.contains("antesDeReembolsar = { viewModel.motivoParaNoReembolsar(transaction.id) }"))
        assertTrue(detalle.contains("transaction.remainingRefundable > 0.001 && !reembolsoBloqueado"))
    }

    // MARK: - Codex apps r1

    // MARK: - Codex apps r2: la prueba de origen es el X-Correlation-ID devuelto

    @Test fun `sólo un rechazo con el eco de su X-Correlation-ID libera lo pendiente - sin eco, con otro eco, HTML o JSON de un proxy se conserva`() = runBlocking {
        store.guardar(pendiente())
        val nuestro400 = """{"success":false,"message":"customerId debe ser texto"}"""
        val rechazado = """{"success":false,"code":"STORE_CREDIT_REJECTED","message":"No se puede devolver ${'$'}600.00"}"""
        val seConservan = listOf(
            sinEco(MockResponse().setResponseCode(400).setHeader("Content-Type", "text/html").setBody("<html><body>Bad Request</body></html>")),
            sinEco(MockResponse().setResponseCode(400)),
            sinEco(respuesta(400, """{"message":"Bad Request"}""")),
            sinEco(respuesta(400, """{"error":"bad request"}""")),
            sinEco(respuesta(400, nuestro400)),
            sinEco(respuesta(400, rechazado)),
            conEcoAjeno(respuesta(400, nuestro400)),
            conEcoAjeno(respuesta(400, rechazado)),
        )
        for ((n, r) in seConservan.withIndex()) {
            server.enqueue(r)
            assertEquals("respuesta $n", DesenlaceDeSaldo.Pendiente(TextosCambio.REEMBOLSO_NO_CONFIRMADO), enviarReembolsoPendiente(repo, store, pendiente()))
            assertEquals(pendiente(), store.pendiente("venue", "payment"))
        }
        // Cada intento (el primero y cada reenvío con la MISMA llave) lleva su propio X-Correlation-ID.
        val ids = List(seConservan.size) { server.takeRequest().getHeader(ECO) }
        assertTrue(ids.toString(), ids.all { !it.isNullOrBlank() })
        assertEquals(ids.size, ids.toSet().size)
        // Con el eco de su petición, el 400 sin código y el STORE_CREDIT_ sí son definitivos.
        server.enqueue(respuesta(400, nuestro400))
        assertEquals(DesenlaceDeSaldo.Rechazado("customerId debe ser texto", null), enviarReembolsoPendiente(repo, store, pendiente()))
        assertNull(store.pendiente("venue", "payment"))
        store.guardar(pendiente())
        server.enqueue(respuesta(400, rechazado))
        assertEquals(DesenlaceDeSaldo.Rechazado("No se puede devolver \$600.00", "STORE_CREDIT_REJECTED"), enviarReembolsoPendiente(repo, store, pendiente()))
        assertNull(store.pendiente("venue", "payment"))
        // El eco sólo decide si un RECHAZO es definitivo: un 2xx sin eco sigue siendo el éxito.
        store.guardar(pendiente())
        server.enqueue(sinEco(respuesta(201, ok)))
        assertTrue(enviarReembolsoPendiente(repo, store, pendiente()) is DesenlaceDeSaldo.Hecho)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `Reembolso - girar o cerrar con el alta en vuelo, escoger a Beatriz y que llegue Ana - sigue Beatriz`() = runBlocking {
        for (girando in listOf(true, false)) {
            val llegaAna = CompletableDeferred<Unit>()
            val clientes = apagarAlFinal(clientesConAltaEnVuelo(llegaAna, "ana", "Ana"))
            val saldo = apagarAlFinal(com.avoqado.pos.transactions.presentation.SaldoAFavorDelReembolsoViewModel(mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
            saldo.abrir(Transaction("payment", customerId = "mama", customerName = "Mamá"))
            // La hoja guarda su bandera y el número del alta con `remember(transaction.id)`: al girar no vuelven.
            val hoja = PantallaConAlta(clientes, sobreviveAlGiro = false) { saldo.escoger(it); cerrarFormulario() }
            hoja.abrirFormulario(); hoja.guardar("Ana")
            if (girando) hoja.girar() else hoja.cerrarFormulario()
            saldo.escoger(com.avoqado.pos.customers.data.model.Customer("beatriz", firstName = "Beatriz", phone = "+525533334444"))
            llegaAna.complete(Unit)
            withTimeout(5_000) { while (clientes.isSaving.value) yield() }
            hoja.componer(); hoja.abrirFormulario(); hoja.componer()
            assertEquals("girando=$girando", "beatriz", saldo.cliente.value?.id)
            // La clienta sí quedó creada (arriba de la lista); sólo no se liga.
            assertEquals("ana", clientes.customers.value.first().id)
        }
    }

    @Test fun `O-3 en el Reembolso la clienta de la venta muestra el teléfono que manda el servidor, salvo que ya se escogió a otra`() = runBlocking {
        fun ana() = com.avoqado.pos.customers.data.model.Customer("ana", firstName = "Ana", lastName = "López", phone = "+525511112222")
        var llega = CompletableDeferred<Unit>()
        val customers = mockk<com.avoqado.pos.customers.data.CustomersRepository>(relaxed = true)
        coEvery { customers.buscarClientes("Ana López") } coAnswers {
            llega.await(); Result.success(com.avoqado.pos.customers.data.model.PaginaDeClientes(listOf(ana()), 1))
        }
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val saldo = apagarAlFinal(com.avoqado.pos.transactions.presentation.SaldoAFavorDelReembolsoViewModel(customers,
            mockk(relaxed = true), mockk(relaxed = true), online))
        val venta = Transaction("payment", customerId = "ana", customerName = "Ana López")
        saldo.abrir(venta)
        llega.complete(Unit)
        withTimeout(5_000) { while (saldo.cliente.value?.phone == null) yield() }
        assertEquals("Saldo a favor para: Ana López · +525511112222",
            com.avoqado.pos.transactions.presentation.exchange.ExchangeSettlement.storeCreditCustomerLabel(saldo.cliente.value))
        // La cajera escoge a Beatriz antes de que conteste: se queda Beatriz.
        llega = CompletableDeferred()
        saldo.abrir(venta)
        saldo.escoger(com.avoqado.pos.customers.data.model.Customer("beatriz", firstName = "Beatriz", phone = "+525533334444"))
        llega.complete(Unit)
        repeat(20) { yield() }
        assertEquals("beatriz", saldo.cliente.value?.id)
    }

    @Test fun `una respuesta tardía de guardar el teléfono no cambia a la destinataria de la hoja reabierta`() = runBlocking {
        val respuesta = CompletableDeferred<Unit>()
        val customers = mockk<com.avoqado.pos.customers.data.CustomersRepository>()
        coEvery { customers.updateCustomer(any(), any()) } coAnswers {
            respuesta.await()
            Result.success(com.avoqado.pos.customers.data.model.Customer("ana", firstName = "Ana", phone = "+525511112222"))
        }
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val saldo = apagarAlFinal(com.avoqado.pos.transactions.presentation.SaldoAFavorDelReembolsoViewModel(customers,
            mockk(relaxed = true), mockk(relaxed = true), online))
        saldo.abrir(Transaction("venta-ana", customerId = "ana", customerName = "Ana"))
        saldo.pedirTelefono()
        saldo.guardarTelefono("5511112222")
        assertTrue(saldo.guardando.value)
        // Se cierra la hoja con el guardado en vuelo y se abre el reembolso de Beatriz.
        saldo.abrir(Transaction("venta-beatriz", customerId = "beatriz", customerName = "Beatriz"))
        respuesta.complete(Unit)
        withTimeout(5_000) { while (saldo.cliente.value?.id != "beatriz" || saldo.guardando.value) yield() }
        assertEquals("beatriz", saldo.cliente.value?.id)
        assertNull(saldo.mensaje.value)
        assertFalse(saldo.guardando.value)
    }

    // MARK: - La reimpresión

    @Test fun `la reimpresión dice Devuelto con - Saldo a favor de la destinataria, con los datos recién bajados`() {
        val json = Json { ignoreUnknownKeys = true }
        val tx = json.decodeFromString(TransactionDetailResponse.serializer(),
            """{"success":true,"transaction":{"id":"refund","method":"OTHER","amount":-500.0,"tipAmount":0,"customerName":"Mamá",""" +
                """"ticketTotals":{"kind":"DEVOLUCION","desglosado":true,"subtotal":500,"serviceCharge":0,"addedTax":0,"tip":0,"total":500},""" +
                """"storeCredit":{"customerName":"Lucía","amountCents":50000}}}""").transaction!!
        val r = tx.toReceiptData(venueName = "IQ", reprintedAt = Date(0))
        val lineas = ReceiptLayoutInterpreter.interpret(listOf(ReceiptBlock.Payment()),
            ReceiptInputMapper.map(r, info = null, hasLogo = false, timezone = "America/Mexico_City", appVersion = null),
            PaperWidth.MM80.charsPerLine).filterIsInstance<LogicalLine.Text>().map { it.text }
        val renglon = lineas.single { it.startsWith("Devuelto con:") }
        assertTrue(renglon, renglon.endsWith("Saldo a favor (Lucía)"))
        assertFalse(lineas.any { it.contains("Mamá") || it.startsWith("Pago:") })
        // Una devolución de siempre sigue diciendo «Pago:».
        val deSiempre = Transaction("r2", method = "CASH", amount = -10.0).toReceiptData(venueName = "IQ", reprintedAt = Date(0))
        assertNull(deSiempre.paymentRowLabel)
    }
}

internal const val ECO = "X-Correlation-ID"
internal const val SIN_ECO = "X-Prueba-Sin-Eco"

/**
 * Como NUESTRO SERVIDOR (`requestLogger`): cada respuesta devuelve el `X-Correlation-ID` de su petición. [sinEco] es un proxy, un
 * portal o un servidor anterior al 9-ago-2026; [conEcoAjeno], el eco de otra petición (Codex apps r2). Lo usan el Reembolso y el Cambio.
 */
internal class ComoElServidor : QueueDispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val r = super.dispatch(request)
        if (r.headers[SIN_ECO] != null) return r.removeHeader(SIN_ECO)
        if (r.headers[ECO] == null) request.getHeader(ECO)?.let { r.setHeader(ECO, it) }
        return r
    }
}
internal fun sinEco(r: MockResponse) = r.setHeader(SIN_ECO, "1")
internal fun conEcoAjeno(r: MockResponse) = r.setHeader(ECO, "id-de-otra-peticion")

/** Un CustomersViewModel real con el alta de [id] detenida hasta [llega] (o rechazada con [falla]): la tienda con la red lenta. */
internal fun clientesConAltaEnVuelo(llega: CompletableDeferred<Unit>, id: String, nombre: String, falla: String? = null):
    com.avoqado.pos.customers.presentation.CustomersViewModel {
    val repo = mockk<com.avoqado.pos.customers.data.CustomersRepository>(relaxed = true)
    every { repo.clientesGuardados() } returns emptyList()
    coEvery { repo.createCustomer(any()) } coAnswers {
        llega.await()
        if (falla != null) Result.failure(Exception(falla))
        else Result.success(com.avoqado.pos.customers.data.model.Customer(id, firstName = nombre, phone = "+525511112222"))
    }
    return com.avoqado.pos.customers.presentation.CustomersViewModel(repo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
}
