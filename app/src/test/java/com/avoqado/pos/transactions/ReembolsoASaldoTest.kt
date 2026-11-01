package com.avoqado.pos.transactions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: RefundPendingStore
    private val secure = mockk<SecureStorage>(relaxed = true)
    private val archivo get() = temporary.root.resolve("refund_pending.preferences_pb")

    private val ok = """{"success":true,"data":{"refundId":"refund","amount":500.0,"remainingRefundable":0.0,"status":"COMPLETED",""" +
        """"storeCredit":{"customerId":"lucia","customerName":"Lucía","amountCents":50000,"newBalanceCents":50000}}}"""

    @Before fun setup() {
        every { secure.venueId } returns "venue"
        every { secure.userId } returns "actor"
        server = MockWebServer(); server.start()
        repo = RefundRepository(secure, OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
        store = nuevoStore()
    }
    @After fun cleanup() { scope.cancel(); server.shutdown() }

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

    private fun pendiente(llave: String = "llave-del-reembolso") = PendingRefund("venue", "payment", cuerpo(llave), llave)

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
            assertEquals(listOf(saldo), opcionesConSaldo(efectivo, efectivo.first(), saldo, ofrecerEsPorDefecto(true, motivo)))
        }
        // «Es por defecto» o un motivo libre: salen todas.
        assertEquals(efectivo + saldo, opcionesConSaldo(efectivo, efectivo.first(), saldo, ofrecerEsPorDefecto(true, "RETURNED_GOODS") && !true))
        for (libre in listOf("ACCIDENTAL_CHARGE", "FRAUDULENT_CHARGE", "CANCELLED_ORDER")) {
            assertFalse(ofrecerEsPorDefecto(true, libre))
            assertEquals(efectivo + saldo, opcionesConSaldo(efectivo, efectivo.first(), saldo, ofrecerEsPorDefecto(true, libre)))
        }
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
        val desenlace = SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso")
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
        server.enqueue(respuesta(403, """{"success":false,"message":"Permission 'payments:refund' required"}"""))
        assertEquals(DesenlaceDeSaldo.Pendiente("No tienes permiso para emitir reembolsos"), enviarReembolsoPendiente(repo, store, pendiente()))
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

    @Test fun `no se pisa un pendiente de otra llave ni se borra con la llave equivocada`() = runBlocking {
        store.guardar(pendiente())
        assertTrue(SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo("otra-llave-123"), "otra-llave-123")
            is DesenlaceDeSaldo.GuardadoFallido)
        assertEquals(0, server.requestCount)
        store.borrar(pendiente("otra-llave-123"))
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        assertNull(store.pendiente("otra-sucursal", "payment"))
    }

    // MARK: - La recuperación al abrir la venta (no la hoja), tras matar la app

    private fun vm(monitor: ConnectivityMonitor, detail: Transaction): TransactionsViewModel {
        val transactions = mockk<TransactionRepository>(relaxed = true)
        every { transactions.transactions } returns MutableStateFlow(emptyList())
        every { transactions.isLoading } returns MutableStateFlow(false)
        every { transactions.isLoadingMore } returns MutableStateFlow(false)
        coEvery { transactions.fetchTransactionDetail("payment", "actor", "venue") } returns detail
        coEvery { transactions.fetchTransactions(any(), any()) } returns Result.success(Unit)
        val factory = mockk<RefreshGateFactory> { every { create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }) }
        return TransactionsViewModel(transactions, repo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), secure, factory, monitor,
            mockk(relaxed = true), store)
    }

    @Test fun `Reembolso completo a saldo, se pierde la respuesta, se mata la app y al reabrir la venta se reenvía con la misma llave`() = runBlocking {
        // 1) La cajera manda el reembolso completo a saldo: se guarda y el servidor SÍ lo escribe, pero la respuesta se pierde.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val primero = SaldoAFavorEnvio(repo, store).enviar("venue", "payment", cuerpo(), "llave-del-reembolso")
        assertTrue(primero is DesenlaceDeSaldo.Pendiente)
        val enviado = server.takeRequest().body.readUtf8()
        // 2) Se mata la app: el pendiente sobrevive en disco.
        reiniciarApp()
        assertEquals(pendiente(), store.pendiente("venue", "payment"))
        // 3) Se reabre la venta por la navegación real; con todo devuelto el botón de Reembolso sale apagado.
        server.enqueue(respuesta(201, ok))
        val online = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns true }
        val venta = Transaction("payment", amount = 500.0, remainingRefundable = 0.0, customerName = "Mamá")
        val vm = vm(online, venta)
        vm.selectTransaction("payment")
        val aviso = withTimeout(10_000) { vm.avisoDeReembolso.first { it != null } }
        // 4) Se reenvió lo MISMO (misma llave) y se muestra el resultado; lo pendiente se borró.
        assertEquals(enviado, server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("Se confirmó el reembolso a saldo a favor\nSaldo a favor para Lucía: \$500.00 · saldo nuevo \$500.00", aviso)
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
    }

    @Test fun `sin red el detalle no reenvía, avisa y deja el pendiente para Confirmar estado`() = runBlocking {
        store.guardar(pendiente())
        val sinRed = mockk<ConnectivityMonitor>(relaxed = true) { every { isOnline() } returns false }
        val vm = vm(sinRed, Transaction("payment", amount = 500.0, remainingRefundable = 500.0))
        vm.selectTransaction("payment")
        assertEquals("El saldo a favor necesita conexión", withTimeout(5_000) { vm.avisoDeReembolso.first { it != null } })
        assertEquals(pendiente(), vm.reembolsoPendiente.value)
        assertEquals(0, server.requestCount)
        // Vuelve la red y la cajera toca «Confirmar estado»: un rechazo definitivo libera.
        every { sinRed.isOnline() } returns true
        server.enqueue(respuesta(422, """{"success":false,"code":"STORE_CREDIT_OVER_CAP","message":"El saldo a favor de Lucía pasaría de ${'$'}10,000."}"""))
        vm.confirmarReembolsoPendiente()
        assertEquals("El saldo a favor de Lucía pasaría de \$10,000.",
            withTimeout(10_000) { vm.avisoDeReembolso.first { it?.startsWith("El saldo a favor de") == true } })
        assertNull(vm.reembolsoPendiente.value)
        assertNull(store.pendiente("venue", "payment"))
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
