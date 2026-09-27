package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El PEGAMENTO donde vivía el bug: el `when (responseCode)` de `sendPaymentToTerminal` y el
 * mapeo de `getPaymentStatus`. La decisión pura ya está cubierta aparte; esto prueba que los
 * códigos HTTP reales entran por la puerta correcta — que es lo que falló el 2026-08-10, cuando
 * un 503 de ngrok cayó al `else` ciego y se declaró "Error en el pago".
 *
 * Va contra un MockWebServer real, no contra mocks del servicio.
 *
 * ⚠️ `runBlocking`, no `runTest`: el reloj VIRTUAL de `runTest` salta hacia adelante mientras la
 * llamada HTTP real está bloqueada, y dispara el tope de reconciliación (35 s) antes de que el
 * servidor alcance a contestar — daba `Undetermined` en todo. Aquí el tiempo tiene que ser real.
 */
class TerminalPaymentServiceHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var service: TerminalPaymentService
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    // La lista durable de verdad vive en disco; aquí se emula con una variable, con la MISMA lógica pura de producción.
    private var pendientes: String? = null
    private fun idsPendientes() = PendientesDeTarjeta.leer(pendientes).map { it.requestId }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        every { secureStorage.pendingCardChargesJson } answers { pendientes }
        every { secureStorage.persistPendingCardCharge(any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg()); true
        }
        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg(), cobrado = true, cobradoEn = arg(3)); true
        }
        every { secureStorage.removePendingCardCharge(any()) } answers {
            pendientes = PendientesDeTarjeta.quitar(pendientes, firstArg())
        }
        service = TerminalPaymentService(secureStorage, OkHttpClient())
        service.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(code: Int, body: String = "{}") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private suspend fun charge() = service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25)

    @Test
    fun `legacy cancellation or rejected cancellation does not prove no charge`() = runBlocking {
        for (status in listOf("CANCELLED", "FAILED")) {
            pendientes = PendientesDeTarjeta.agregar(null, "pending", null, "{}")
            enqueue(200, """{"status":"$status","inProgress":false,"cancelDisposition":"ACTIVE"}""")
            val result = service.resolveOutcome("pending")
            assertTrue(result is TerminalPaymentResult.Undetermined)
            assertEquals(listOf("pending"), idsPendientes())
            // Drain unused responses if a terminal disposition was resolved immediately.
        }
    }

    @Test
    fun `HTTP success carrying unknown status must not clear protection`() = runBlocking {
        enqueue(200, """{"success":false,"status":"unknown"}""")
        repeat(3) { enqueue(404) }
        assertTrue(charge() is TerminalPaymentResult.Undetermined)
        assertTrue(idsPendientes().isNotEmpty())
    }

    @Test
    fun `late original unknown cannot resurrect an authoritatively resolved request`() = runBlocking {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var queried = 0
        var requestId = ""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    requestId = org.json.JSONObject(request.body.readUtf8()).getString("requestId")
                    arrived.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"status":"unknown"}""")
                }
                queried++
                return MockResponse().setBody(if (queried == 1) """{"status":"COMPLETED","inProgress":false,"paymentId":"paid"}""" else """{"status":"UNKNOWN","inProgress":false}""")
            }
        }
        val posting = async(kotlinx.coroutines.Dispatchers.IO) { charge() }
        assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(service.resolveOutcome(requestId) is TerminalPaymentResult.Success)
        release.countDown()
        val late = posting.await()
        assertTrue(late is TerminalPaymentResult.Success)
        // H2 (26-sep): lo probado queda MARCADO hasta que el flujo lo aplique como su pago; lo que no puede es volver a la duda.
        assertEquals(listOf(true), cobrados())
        assertTrue(service.unresolvedRequestIds.isEmpty())
        assertEquals(1, queried)
    }

    @Test
    fun `late A completion preserves B cancellation handle`() = runBlocking {
        val aArrived = java.util.concurrent.CountDownLatch(1)
        val bArrived = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.CountDownLatch(1)
        val releaseB = java.util.concurrent.CountDownLatch(1)
        var aId = ""
        var bId = ""
        var cancelId: String? = null
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/cancel")) {
                    cancelId = org.json.JSONObject(request.body.readUtf8()).getString("requestId")
                    releaseB.countDown()
                    return MockResponse().setBody("{}")
                }
                if (request.method == "GET") return MockResponse().setBody("""{"status":"COMPLETED","inProgress":false,"paymentId":"paid-a"}""")
                val id = org.json.JSONObject(request.body.readUtf8()).getString("requestId")
                if (aId.isEmpty()) {
                    aId = id
                    aArrived.countDown()
                    releaseA.await(5, java.util.concurrent.TimeUnit.SECONDS)
                } else {
                    bId = id
                    bArrived.countDown()
                    releaseB.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return MockResponse().setBody("""{"status":"success","paymentId":"paid","requestId":"$id"}""")
            }
        }
        val a = async(kotlinx.coroutines.Dispatchers.IO) { charge() }
        assertTrue(aArrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
        service.resolveOutcome(aId)
        val b = async(kotlinx.coroutines.Dispatchers.IO) { service.sendPaymentToTerminal("t2", 50) }
        assertTrue(bArrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
        releaseA.countDown()
        a.await()
        service.cancelCurrentPayment()
        b.await()
        assertEquals(bId, cancelId)
    }

    @Test
    fun `concurrent service recovery shares one bounded GET cycle`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "pending", null, "{}")
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val queries = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                queries.incrementAndGet()
                arrived.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setBody("""{"status":"COMPLETED","inProgress":false,"paymentId":"paid"}""")
            }
        }
        val first = async(kotlinx.coroutines.Dispatchers.IO) { service.resolveOutcome("pending") }
        assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = async { service.resolveOutcome("pending") }
        kotlinx.coroutines.delay(100)
        release.countDown()
        assertTrue(first.await() is TerminalPaymentResult.Success)
        assertTrue(second.await() is TerminalPaymentResult.Success)
        assertEquals(1, queries.get())
    }

    @Test
    fun `success without payment evidence recovers status and retains journal`() = runBlocking {
        enqueue(200, """{"status":"success"}""")
        enqueue(200, """{"status":"UNKNOWN","inProgress":false}""")
        assertTrue(charge() is TerminalPaymentResult.Undetermined)
        assertTrue(idsPendientes().isNotEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancel after venue switch targets original attempt venue`() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val postSeen = java.util.concurrent.CountDownLatch(1)
        var cancelPath: String? = null
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/cancel")) {
                    cancelPath = request.path
                    gate.countDown()
                    return MockResponse().setBody("{}")
                }
                postSeen.countDown()
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setBody("""{"status":"success","paymentId":"paid"}""")
            }
        }
        val charging = async(kotlinx.coroutines.Dispatchers.IO) { charge() }
        assertTrue(postSeen.await(5, java.util.concurrent.TimeUnit.SECONDS))
        every { secureStorage.venueId } returns "other-venue"
        service.cancelCurrentPayment()
        charging.await()
        assertEquals("/api/v1/mobile/venues/venue-1/terminal-payment/cancel", cancelPath)
    }

    @Test
    fun `failed durable write sends no authorization`() = runBlocking {
        every { secureStorage.persistPendingCardCharge(any(), any(), any()) } returns false
        val result = charge()
        assertTrue(result is TerminalPaymentResult.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recovery after venue switch uses original venue`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(
            null, "original-request", null, """{"requestId":"original-request","venueId":"original-venue"}""",
        )
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"paid"}""")
        service.resolveOutcome("original-request")
        assertEquals("/api/v1/mobile/venues/original-venue/terminal-payment/original-request", server.takeRequest().path)
    }

    @Test
    fun `accepted cancellation permits a deliberate new attempt`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "cancelled-request", null, "{}")
        enqueue(200, """{"status":"CANCELLED","inProgress":false,"cancelDisposition":"ACCEPTED"}""")
        assertTrue(service.resolveOutcome("cancelled-request") is TerminalPaymentResult.Error)
        assertTrue(idsPendientes().isEmpty())
        enqueue(200, """{"success":true,"status":"success","paymentId":"new-payment"}""")
        assertTrue(charge() is TerminalPaymentResult.Success)
    }

    // MARK: - sendPaymentToTerminal: el `when (responseCode)`

    @Test
    fun `un 503 NO es fracaso — va a consultar el estado durable`() = runBlocking {
        // 1) el cobro: 503 (ngrok mientras el backend reiniciaba)
        enqueue(503, """{"message":"Service Unavailable"}""")
        // 2) la consulta: el server SÍ tenía el pago COMPLETED
        enqueue(200, """{"success":true,"inProgress":false,"status":"COMPLETED","paymentId":"pay-tarde"}""")

        val result = charge()

        assertTrue("un 503 con la terminal ya cobrada debe ser ÉXITO", result is TerminalPaymentResult.Success)
        assertEquals("pay-tarde", (result as TerminalPaymentResult.Success).paymentId)
        assertEquals(2, server.requestCount) // cobró una vez y preguntó una vez
        assertTrue("desenlace resuelto ⇒ entrada liberada", service.unresolvedRequestIds.isEmpty())
    }

    @Test
    fun `un 503 con el cobro rechazado se propaga como error real`() = runBlocking {
        enqueue(503)
        enqueue(200, """{"success":true,"inProgress":false,"status":"FAILED"}""")

        val result = charge()

        assertTrue(result is TerminalPaymentResult.Error)
        assertTrue("consta que no se cobró", service.unresolvedRequestIds.isEmpty())
    }

    @Test
    fun `un 503 con el server tambien caido queda INDETERMINADO y conserva la llave`() = runBlocking {
        enqueue(503)
        repeat(3) { enqueue(500) } // la consulta tampoco se puede responder

        val result = charge()

        assertTrue("sin poder saber, jamás fracaso", result is TerminalPaymentResult.Undetermined)
        // 🔴 La entrada SOBREVIVE: es lo que frena el siguiente cobro a ciegas de ESA venta.
        assertEquals(listOf((result as TerminalPaymentResult.Undetermined).requestId), service.unresolvedRequestIds)
    }

    @Test
    fun `404 and generic 409 after POST remain unknown`() = runBlocking {
        for (code in listOf(400, 404, 409, 422)) {
            pendientes = null
            enqueue(code)
            repeat(3) { enqueue(404) }
            val result = charge()
            assertTrue(result is TerminalPaymentResult.Undetermined)
            assertEquals(listOf((result as TerminalPaymentResult.Undetermined).requestId), idsPendientes())
        }
    }

    // MARK: - 409 TERMINAL_BUSY al CREAR el intento: correlacionado por request

    private fun busyBody(blockerId: String) =
        """{"success":false,"status":"failed","code":"TERMINAL_BUSY","errorMessage":"La terminal t1 está ocupada por un cobro de ${'$'}125.50 enviado hace 3 min desde Sunmi D3","message":"La terminal t1 está ocupada por un cobro de ${'$'}125.50 enviado hace 3 min desde Sunmi D3","blockingRequest":{"requestId":"$blockerId","amountCents":12550,"senderDevice":"Sunmi D3","ageSeconds":200}}"""

    @Test
    fun `un 409 TERMINAL_BUSY que nombra OTRA solicitud no es incertidumbre — se libera la llave y no se pregunta`() = runBlocking {
        enqueue(409, busyBody("otra-solicitud"))
        repeat(3) { enqueue(404) } // si el servicio fuera a preguntar, esto lo volvería Undetermined
        val result = charge()
        assertTrue("$result", result is TerminalPaymentResult.Error)
        val message = (result as TerminalPaymentResult.Error).message
        assertTrue(message, message.contains("\$125.50") && message.contains("3 min") && message.contains("Sunmi D3"))
        assertTrue(message, message.contains("NO se envió"))
        assertTrue(idsPendientes().isEmpty())
        assertEquals(1, server.requestCount) // ni una consulta de estado: consta que nada se envió
    }

    @Test
    fun `un 409 TERMINAL_BUSY que nombra ESTA MISMA solicitud sigue siendo incertidumbre`() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                val id = org.json.JSONObject(request.body.readUtf8()).getString("requestId")
                return MockResponse().setResponseCode(409).setBody(busyBody(id))
            }
        }
        val result = charge()
        assertTrue("$result", result is TerminalPaymentResult.Undetermined)
        assertEquals(listOf((result as TerminalPaymentResult.Undetermined).requestId), idsPendientes())
    }

    @Test
    fun `un 409 TERMINAL_BUSY sin solicitud nombrada (server viejo) sigue siendo incertidumbre`() = runBlocking {
        enqueue(409, """{"success":false,"status":"failed","code":"TERMINAL_BUSY","message":"ocupada"}""")
        repeat(3) { enqueue(404) }
        val result = charge()
        assertTrue("$result", result is TerminalPaymentResult.Undetermined)
        assertEquals(listOf((result as TerminalPaymentResult.Undetermined).requestId), idsPendientes())
    }

    @Test
    fun `tras cancelar, un 409 que nombra OTRA solicitud sigue sin ser incertidumbre — este intento nunca se creo`() = runBlocking {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/cancel")) {
                    release.countDown()
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                arrived.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setResponseCode(409).setBody(busyBody("otra-solicitud"))
            }
        }
        val inFlight = async(kotlinx.coroutines.Dispatchers.IO) { charge() }
        assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
        service.cancelCurrentPayment()
        val result = inFlight.await()
        assertTrue("$result", result is TerminalPaymentResult.Error)
        assertTrue(idsPendientes().isEmpty())
    }

    @Test
    fun `unresolved sale cannot POST again on another terminal after restart`() = runBlocking {
        // 🔴 Founder, 25-sep: lo que frena es el pendiente de ESA venta, en cualquier terminal. Antes CUALQUIER pendiente
        // frenaba CUALQUIER cobro (la llave era una sola); el de otra venta ya no — ver las pruebas de abajo.
        pendientes = PendientesDeTarjeta.agregar(null, "old-request", "order-9", """{"requestId":"old-request","orderId":"order-9"}""")
        enqueue(200, """{"success":true,"status":"success"}""")
        val result = service.sendPaymentToTerminal(terminalId = "t2", amountCents = 25, orderId = "order-9")
        assertTrue(result is TerminalPaymentResult.Undetermined)
        assertEquals(listOf("old-request"), idsPendientes())
        assertEquals(0, server.requestCount)
    }

    // MARK: - Varios pendientes: sólo espera la MISMA venta (founder, 25-sep)

    @Test
    fun `P1 un pendiente de OTRA venta no impide mandar el cobro de esta`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "r-viejo", "orden-7", """{"requestId":"r-viejo","orderId":"orden-7"}""")
        enqueue(200, """{"success":true,"status":"success","paymentId":"new-payment"}""")

        val resultado = service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, orderId = "orden-8")

        assertTrue("el cobro de ESTA venta salió", resultado is TerminalPaymentResult.Success)
        assertEquals("POST", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.method)
        assertTrue("el pendiente de la otra venta se conserva", "r-viejo" in idsPendientes())
    }

    @Test
    fun `P1 un pendiente de ESTA venta sí frena el cobro`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "r-viejo", "orden-8", """{"requestId":"r-viejo","orderId":"orden-8"}""")

        val resultado = service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, orderId = "orden-8")

        assertTrue(resultado is TerminalPaymentResult.Undetermined)
        assertEquals("r-viejo", (resultado as TerminalPaymentResult.Undetermined).requestId)
        assertEquals("no salió ningún POST", 0, server.requestCount)
    }

    @Test
    fun `P1 un desenlace tardío sólo toca SU cobro`() {
        pendientes = PendientesDeTarjeta.agregar(null, "r1", "orden-7", "{}")
        pendientes = PendientesDeTarjeta.agregar(pendientes, "r2", "orden-8", "{}")

        service.aplicarDesenlaceTardio("r1", com.avoqado.pos.payment.domain.CardChargeOutcome.NotCharged("no se cobró"))

        assertEquals(listOf("r2"), service.unresolvedRequestIds)
    }

    @Test
    fun `P1 un exito tardio re-armado sigue siendo de SU venta y conserva su importe`() = runBlocking {
        // 🔴 El pasillo 2 del 2026-08-10: el cajero canceló, la terminal cobró tarde. Desde N1 el éxito MARCA la entrada al
        // llegar (antes la soltaba y el ViewModel la volvía a poner); re-armarla conserva SU orden y SU importe — sin eso,
        // la MISMA venta ya no esperaba.
        enqueue(200, """{"success":true,"status":"success","paymentId":"pay-tarde"}""")
        val exito = service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, orderId = "orden-8")
        val rid = (exito as TerminalPaymentResult.Success).requestId!!
        assertEquals("N1: el éxito la MARCA hasta que el flujo la adopte", listOf(true), cobrados())

        service.aplicarDesenlaceTardio(rid, CardChargeOutcome.Charged("pay-tarde"), aunSiFueDeclarado = true)

        assertEquals("la MISMA venta vuelve a esperar", rid, service.pendienteDeLaVenta("orden-8"))
        assertEquals(25, service.contextoDe(rid)?.amountCents)
    }

    @Test
    fun `P2 una llave en blanco nunca se escribe`() {
        // No se puede consultar (el GET iría sin id) y la venta siguiente se quedaría con un aviso que nadie resuelve.
        service.aplicarDesenlaceTardio("   ", CardChargeOutcome.Charged("pay-1"), aunSiFueDeclarado = true)

        assertTrue(idsPendientes().isEmpty())
    }

    @Test
    fun `P1 un desenlace tardio INCIERTO re-arma SU entrada ya soltada, con su venta e importe`() = runBlocking {
        // Revisión de B2 (Minor 8): también el camino de la DUDA (no declarado, entrada ya soltada) re-arma, y el
        // contexto vuelve de lo que el proceso recordó al soltarla. (Desde N1 un éxito ya no suelta: la suelta un «no se cobró».)
        enqueue(503)
        enqueue(200, """{"success":true,"inProgress":false,"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"PROCESSOR_DECLINED"}""")
        val rechazo = service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, orderId = "orden-8")
        val rid = (rechazo as TerminalPaymentResult.Error).requestId!!
        assertTrue("consta que no se cobró: se suelta", idsPendientes().isEmpty())

        assertTrue(service.aplicarDesenlaceTardio(rid, CardChargeOutcome.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE)))

        assertTrue(rid in service.unresolvedRequestIds)
        assertEquals("orden-8", service.contextoDe(rid)?.orderId)
        assertEquals(25, service.contextoDe(rid)?.amountCents)
    }

    @Test
    fun `P2 si el disco no escribe, el desenlace tardio lo dice con false`() {
        every { secureStorage.persistPendingCardCharge(any(), any(), any()) } returns false

        assertFalse(service.aplicarDesenlaceTardio("r1", CardChargeOutcome.Undetermined("sin confirmar")))
        assertTrue(idsPendientes().isEmpty())
    }

    @Test
    fun `resolving an old request does not clear a newer pending charge`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "new-request", null, "{}")
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"old-payment"}""")
        service.resolveOutcome("old-request")
        assertEquals("el más nuevo sigue en duda", listOf("new-request"), service.unresolvedRequestIds)
        // N2 (Codex r2): ausencia no es «Entendido» — el viejo que SÍ pasó vuelve, confirmado, dentro de su ventana.
        assertEquals(listOf("new-request" to false, "old-request" to true), PendientesDeTarjeta.leer(pendientes).map { it.requestId to it.cobrado })
    }

    @Test
    fun `un cobro exitoso libera la llave y trae los datos del recibo`() = runBlocking {
        enqueue(
            200,
            """{"success":true,"status":"success","paymentId":"pay-1","transactionId":"tx-1",
               "cardDetails":{"lastFour":"4242","brand":"VISA"},
               "receipt":{"receiptUrl":"https://dash/r/abc","receiptAccessKey":"abc"}}""",
        )

        val result = charge()

        val success = result as TerminalPaymentResult.Success
        assertEquals("pay-1", success.paymentId)
        assertEquals("4242", success.cardLastFour)
        assertEquals("https://dash/r/abc", success.receiptUrl)
        assertTrue(service.unresolvedRequestIds.isEmpty())
    }

    // MARK: - getPaymentStatus: el mapeo

    @Test
    fun `200 se mapea a Known con su estado y paymentId`() = runBlocking {
        enqueue(200, """{"success":true,"inProgress":false,"status":"COMPLETED","paymentId":"pay-9"}""")

        val probe = service.getPaymentStatus("req-1")

        assertEquals(ChargeStatusProbe.Known("COMPLETED", inProgress = false, paymentId = "pay-9"), probe)
    }

    @Test
    fun `200 en curso se mapea a Known inProgress`() = runBlocking {
        enqueue(200, """{"success":true,"inProgress":true,"status":"SENT"}""")

        val probe = service.getPaymentStatus("req-1")

        assertEquals(ChargeStatusProbe.Known("SENT", inProgress = true, paymentId = null), probe)
    }

    @Test
    fun `404 se mapea a NotFound — y NO a Unreachable`() = runBlocking {
        enqueue(404, """{"success":false,"status":"NOT_FOUND"}""")

        assertEquals(ChargeStatusProbe.NotFound, service.getPaymentStatus("req-1"))
    }

    @Test
    fun `un 5xx en la consulta es Unreachable, NUNCA NotFound`() = runBlocking {
        // Colapsar los dos era el bug de fondo: un server caído parecía "no se cobró".
        enqueue(500)

        assertEquals(ChargeStatusProbe.Unreachable, service.getPaymentStatus("req-1"))
    }

    @Test
    fun `un 401 en la consulta tambien es Unreachable`() = runBlocking {
        enqueue(401)

        assertEquals(ChargeStatusProbe.Unreachable, service.getPaymentStatus("req-1"))
    }

    @Test
    fun `sin sesion no se inventa un desenlace`() = runBlocking {
        every { secureStorage.venueId } returns null

        assertEquals(ChargeStatusProbe.Unreachable, service.getPaymentStatus("req-1"))
    }

    // MARK: - resolveOutcome sobre HTTP real

    @Test
    fun `resolveOutcome insiste mientras la solicitud siga en curso`() = runBlocking {
        enqueue(200, """{"success":true,"inProgress":true,"status":"PENDING"}""")
        enqueue(200, """{"success":true,"inProgress":true,"status":"SENT"}""")
        enqueue(200, """{"success":true,"inProgress":false,"status":"COMPLETED","paymentId":"pay-3"}""")

        val result = service.resolveOutcome("req-1")

        assertEquals("pay-3", (result as TerminalPaymentResult.Success).paymentId)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `un 404 sostenido NO declara que no se cobro — queda indeterminado`() = runBlocking {
        repeat(3) { enqueue(404) }

        val result = service.resolveOutcome("req-1")

        // Ausencia ≠ prueba: entre que el request llega y la fila se escribe hay una ventana.
        assertTrue(result is TerminalPaymentResult.Undetermined)
    }

    // MARK: - EL CLIENTE de la venta viaja en el cuerpo del relay
    //
    // 🔴 El defecto: el cajero elegía "Juan Pérez" en el carrito, cobraba $100 con
    // TARJETA sin productos, y la orden `FAST-*` nacía anónima. El EFECTIVO sí lo
    // mandaba (`recordFastCashPayment(..., customerId = attachedCustomerId)`); la
    // tarjeta, no. Se perdían historial de compra, CFDI y atribución — y nadie se
    // entera, porque el ticket sale perfecto.
    //
    // Se prueba sobre el cuerpo REAL que sale por el socket (MockWebServer), no sobre
    // un constructor paralelo: es el único que garantiza lo que ve el server.

    private fun cuerpoDelCobro(): String = server.takeRequest().body.readUtf8()

    @Test
    fun `el relay lleva el cliente congelado de la venta`() = runBlocking {
        enqueue(200, """{"success":true,"status":"success","paymentId":"pay-1"}""")

        service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, customerId = "cmcustomer123")

        val cuerpo = cuerpoDelCobro()
        assertTrue("el cuerpo debe llevar el cliente, y no lo lleva: $cuerpo", cuerpo.contains("\"customerId\":\"cmcustomer123\""))
    }

    @Test
    fun `una venta anonima NO manda la llave customerId`() = runBlocking {
        // Regresión del contrato: la venta anónima es el 99% de los cobros. Mandar
        // `customerId: null` NO es equivalente a no mandarlo — el cuerpo de siempre se
        // conserva byte a byte porque la llave sencillamente no aparece.
        enqueue(200, """{"success":true,"status":"success","paymentId":"pay-1"}""")

        service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25)

        val cuerpo = cuerpoDelCobro()
        assertFalse("una venta anónima no puede mandar la llave: $cuerpo", cuerpo.contains("customerId"))
    }

    @Test
    fun `un cliente en blanco vale como venta anonima`() = runBlocking {
        enqueue(200, """{"success":true,"status":"success","paymentId":"pay-1"}""")

        service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, customerId = "   ")

        val cuerpo = cuerpoDelCobro()
        assertFalse("un id en blanco no es un cliente: $cuerpo", cuerpo.contains("customerId"))
    }

    // MARK: - La sonda de terminales corre SOLA
    //
    // Medido el 2026-08-16 en hardware: un CASHIER cobrando veía, en la pantalla
    // de PROPINA, el modal "No tienes permiso — pídele a un administrador que te
    // active «tpv:read»". Nadie pidió esa consulta: la dispara el arranque del
    // cobro para saber si hay terminales conectadas, y `tpv:read` es el permiso
    // de ADMINISTRAR terminales, que un cajero no tiene. Marcada como de fondo,
    // el interceptor global la deja pasar sin diálogo.

    @Test
    fun `la sonda automatica viaja marcada como tarea de fondo`() = runBlocking {
        enqueue(200, """{"success":true,"terminals":[]}""")

        service.fetchOnlineTerminals(background = true)

        assertEquals("1", server.takeRequest().getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    @Test
    fun `elegir Cobrar con terminal NO va marcada — ese 403 si tiene que verse`() = runBlocking {
        enqueue(200, """{"success":true,"terminals":[]}""")

        service.fetchOnlineTerminals()

        assertNull(server.takeRequest().getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }
    // ══════════════════════════════════════════════════════════════════════════════════════
    // La declaración del cajero, contra un SERVIDOR REAL. Las del ViewModel sustituyen el
    // servicio entero, así que no ven nada de esto — crítica de Codex que resultó exacta: un
    // sabotaje sobre el mapeo del 401 no tumbaba ninguna prueba.
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `una declaracion aceptada libera y suelta la llave`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(200, """{"success":true,"released":true,"status":"FAILED"}""")

        val r = service.declararNoCobrado("req-1")

        assertEquals(ResultadoDeDeclaracion.Liberada, r)
        assertTrue("la entrada se suelta sólo cuando el servidor lo acredita", idsPendientes().isEmpty())
        val pedido = server.takeRequest()
        assertEquals("POST", pedido.method)
        assertTrue(pedido.path!!.endsWith("/terminal-payment/req-1/release"))
        // 🔴 P1 de Codex: la petición prohíbe el reenvío automático tras refrescar la sesión.
        assertEquals("1", pedido.getHeader("X-No-Auto-Retry"))
    }

    @Test
    fun `tras declarar, rearmar con un resultado INDETERMINADO viejo NO repone la llave`() = runBlocking {
        // 🔴 P2 de Codex (20-sep): mi cierre anterior puso la guarda en `armarLlaveSiLibre`, pero la
        // ruta REAL del callback obsoleto era `rearmUnresolvedCharge` (hoy `aplicarDesenlaceTardio`).
        // Flujo A vivo → otro flujo declara A y libera → llega `Undetermined(A)` al flujo viejo → la
        // siguiente venta volvía a mostrar el pendiente que se acababa de liberar.
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(200, """{"released":true}""")
        service.declararNoCobrado("req-1")
        assertTrue(idsPendientes().isEmpty())

        service.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE))

        assertTrue("lo declarado no vuelve a la lista por la ruta obsoleta", idsPendientes().isEmpty())
    }

    @Test
    fun `pero un EXITO tardio SI repone la llave — hay dinero y alguien tiene que enterarse`() = runBlocking {
        // 🔴 El otro lado de la misma regla, y es el que protege el dinero: si el cobro SÍ pasó, la
        // declaración del cajero no puede silenciarlo. Se repone para que la próxima venta lo muestre.
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(200, """{"released":true}""")
        service.declararNoCobrado("req-1")
        assertTrue(idsPendientes().isEmpty())

        service.aplicarDesenlaceTardio("req-1", CardChargeOutcome.Charged("pay-1"), aunSiFueDeclarado = true)

        assertEquals("un éxito tardío manda sobre la declaración", listOf("req-1"), idsPendientes())
        // H2 (26-sep): vuelve MARCADO — ya consta, así que no es «sin resolver», y toda venta lo ve hasta su «Entendido».
        assertTrue(
            "y la venta siguiente lo VE: la declaración no lo esconde",
            service.pendientesDeOtrasVentas(null).single().cobrado,
        )
    }

    @Test
    fun `la declaracion SOBREVIVE a una conexion reciclada que el servidor ya cerro`() = runBlocking {
        // 🔴 MEDIDO EN UNA SUNMI D3 (21-sep), y es el defecto que este test existe para impedir.
        // Ayer puse el cuerpo en «un solo envío» y apagué `retryOnConnectionFailure` para que OkHttp
        // no pudiera reenviar la declaración ante un 408/503. Con eso, en una red NORMAL el POST
        // moría con `unexpected end of stream` y NUNCA llegaba al servidor (0 registros), mientras
        // la pantalla culpaba a la red: «Necesitas conexión». El servidor manda `Keep-Alive:
        // timeout=5`; entre dos toques del cajero pasan más de 5 s, así que reusar una conexión ya
        // cerrada es el caso NORMAL.
        //
        // Reintento de CONEXIÓN ≠ reenvío por RESPUESTA: aquí el servidor no recibió nada, así que
        // repetir es seguro SIEMPRE. Contra el reenvío protege la idempotencia del servidor
        // (`resolutionId` determinista + dedup por `bodyHash`), no romper la reconexión.
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        // La PRIMERA petición deja una conexión en el pool y el servidor la cierra al terminar —
        // igual que su `Keep-Alive: timeout=5` en producción.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"status":"unknown"}""")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END),
        )
        service.resolveOutcome("req-0")
        // Y ahora la declaración: reusa esa conexión ya muerta. Un cliente sano reconecta y llega.
        enqueue(200, """{"released":true}""")

        val r = service.declararNoCobrado("req-1")

        assertEquals("con la conexión rancia, reconecta y la declaración LLEGA", ResultadoDeDeclaracion.Liberada, r)
        assertFalse("y al liberarse suelta SU entrada", "req-1" in idsPendientes())
    }

    @Test
    fun `P1 un ganador NEGATIVO cacheado NO puede tapar un cobro acreditado del POST`() {
        // 🔴 P1 de Codex (21-sep), hermano del de iOS: el ganador cacheado se consultaba ANTES de leer
        // la respuesta. Mientras el POST viajaba, una consulta concurrente podía ver la fila ya
        // declarada y guardar un `Error` — y entonces un POST que vuelve con `success` + `paymentId` se
        // descartaba sin decodificarlo. El dinero se perdía ANTES de llegar a `staleOutcome`.
        //
        // La carrera se reproduce donde ocurre: el ganador se siembra MIENTRAS el POST está en vuelo,
        // desde el propio dispatcher, con el `requestId` que el servicio acaba de generar.
        @Suppress("UNCHECKED_CAST")
        val cache = TerminalPaymentService::class.java.getDeclaredField("recoveredPosts")
            .apply { isAccessible = true }
            .get(service) as MutableMap<String, TerminalPaymentResult>
        val lock = TerminalPaymentService::class.java.getDeclaredField("attemptLock").apply { isAccessible = true }.get(service)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val cuerpo = request.body.readUtf8()
                val rid = Regex("\"requestId\"\\s*:\\s*\"([^\"]+)\"").find(cuerpo)?.groupValues?.get(1) ?: ""
                synchronized(lock) {
                    cache[rid] = TerminalPaymentResult.Error("El cobro se canceló. No se cobró la tarjeta.", requestId = rid)
                }
                return MockResponse().setResponseCode(200)
                    .setBody("""{"status":"success","requestId":"$rid","paymentId":"pay-real","transactionId":"tx-1"}""")
            }
        }

        val r = runBlocking { service.sendPaymentToTerminal("t1", 1000) }

        assertTrue("el cobro CONSTA: no lo tapa una declaración cacheada (fue $r)", r is TerminalPaymentResult.Success)
        assertEquals("pay-real", (r as TerminalPaymentResult.Success).paymentId)
    }

    @Test
    fun `una sesion vencida NO se disfraza de falta de conexion`() = runBlocking {
        // 🔴 P1 de Codex: el transporte refrescaba y REENVIABA el POST solo. Ahora la sesión se
        // renueva pero la declaración no se repite — y el 401 tiene su propio desenlace, porque
        // decir «sin conexión» sería falso: la red funcionó.
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(401, """{"message":"Token expirado"}""")

        val r = service.declararNoCobrado("req-1")

        assertEquals(ResultadoDeDeclaracion.SesionRenovada, r)
        assertEquals("la entrada se conserva: nada se resolvió", listOf("req-1"), idsPendientes())
    }

    @Test
    fun `un rechazo del servidor conserva el pendiente y su mensaje`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(409, """{"success":false,"released":false,"code":"POSITIVE_EVIDENCE_EXISTS","message":"Este cobro sí tiene señales de haber pasado."}""")

        val r = service.declararNoCobrado("req-1")

        assertEquals(
            ResultadoDeDeclaracion.Rechazada("Este cobro sí tiene señales de haber pasado.", "POSITIVE_EVIDENCE_EXISTS"),
            r,
        )
        assertEquals("un rechazo NO suelta la entrada", listOf("req-1"), idsPendientes())
    }

    @Test
    fun `tras declarar, un desenlace indeterminado viejo NO vuelve a armar la llave`() = runBlocking {
        // 🔴 P2 de Codex: el POST original seguía vivo; declarar soltaba la llave y aquel resultado
        // viejo (UNKNOWN) la re-armaba ⇒ la venta siguiente volvía a bloquearse por el cobro que se
        // acababa de liberar.
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(200, """{"released":true}""")
        service.declararNoCobrado("req-1")
        assertTrue(idsPendientes().isEmpty())

        // Llega el resultado viejo e intenta re-armar.
        assertTrue(service.armarLlave("req-1"))

        assertTrue("lo declarado no vuelve a la lista", idsPendientes().isEmpty())
        assertTrue(service.yaDeclaradoSinCobro("req-1"))
    }

    // MARK: - Lote final de arreglos (26-sep): H2, H6, H8, B7b-5

    private fun cobrados() = PendientesDeTarjeta.leer(pendientes).map { it.cobrado }

    @Test
    fun `H8 la terminal que no inicio el cobro dice su motivo en la respuesta inmediata`() = runBlocking {
        // QA-1 (26-sep): la N86 con el lector abierto contestó ESTE 422. El GET de la recuperación trae la evidencia pero
        // NO el mensaje (el servidor sólo expone los que escribe él), así que el motivo sale del cuerpo del POST.
        enqueue(422, """{"success":false,"requestId":"x","status":"failed","errorMessage":"Ya hay un pago en proceso en el terminal","outcomeEvidence":"PRE_AUTHORIZATION"}""")
        enqueue(200, """{"status":"FAILED","inProgress":false,"outcome":"NOT_CHARGED","outcomeEvidence":"PRE_AUTHORIZATION","failureCode":"TPV_CONFIRMED_NO_CHARGE"}""")

        val r = charge()

        assertEquals("Ya hay un pago en proceso en el terminal", (r as TerminalPaymentResult.Error).message)
        assertTrue("consta que no se cobró: la entrada se suelta como siempre", idsPendientes().isEmpty())
    }

    @Test
    fun `H8 la recuperacion de un cobro que la terminal no inicio dice que no lo inicio, nunca rechazado`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(null, "req-1", null, "{}")
        enqueue(200, """{"status":"FAILED","inProgress":false,"outcome":"NOT_CHARGED","outcomeEvidence":"PRE_AUTHORIZATION"}""")

        val r = service.resolveOutcome("req-1")

        assertEquals(CardChargeDecision.NO_INICIADO_RESPALDO, (r as TerminalPaymentResult.Error).message)
    }

    @Test
    fun `H2 una consulta que prueba el cobro lo MARCA en disco, no lo borra`() = runBlocking {
        pendientes = PendientesDeTarjeta.agregar(
            null, "r-otra", "orden-7", """{"requestId":"r-otra","orderId":"orden-7","amountCents":1500,"tipCents":0}""",
        )
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"pay-7"}""")

        assertTrue(service.resolveOutcome("r-otra") is TerminalPaymentResult.Success)

        assertEquals("sigue en disco hasta «Entendido»", listOf("r-otra"), idsPendientes())
        assertEquals(listOf(true), cobrados())
        assertTrue("ya no está SIN resolver", service.unresolvedRequestIds.isEmpty())
        assertTrue(service.yaCobrado("r-otra"))
        val aviso = service.pendientesDeOtrasVentas("orden-8").single()
        assertTrue("toda venta nueva lo sigue recibiendo, marcado", aviso.cobrado)
        assertEquals(1500, aviso.amountCents)
    }

    @Test
    fun `H2 un no se cobro posterior no suelta un cobro que SI paso, y Entendido quita solo ese`() {
        pendientes = PendientesDeTarjeta.agregar(null, "r1", "orden-7", "{}", cobrado = true)
        pendientes = PendientesDeTarjeta.agregar(pendientes, "r2", "orden-8", "{}", cobrado = true)

        service.aplicarDesenlaceTardio("r1", CardChargeOutcome.NotCharged("no se cobró"))
        assertEquals("el dinero probado manda sobre un «no se cobró» posterior", listOf("r1", "r2"), idsPendientes())

        service.reconocerCobro("r1")
        assertEquals("«Entendido» quita SÓLO ése", listOf("r2"), idsPendientes())
    }

    @Test
    fun `N1 el exito directo del POST MARCA la entrada, no la borra, y sobrevive a recrear el servicio`() = runBlocking {
        // Codex r2 (N1): el POST acreditaba el cobro y el servicio BORRABA la entrada antes de devolverle el resultado al
        // flujo. Si el flujo ya no estaba (canceló) o el proceso moría en medio, se perdía el aviso. Ahora la MARCA: la quita
        // la adopción del flujo que lo mandó (`applyCardCharged`) o «Entendido», y si nadie la adopta, dura su ventana.
        enqueue(200, """{"success":true,"status":"success","paymentId":"pay-directo"}""")
        val rid = (service.sendPaymentToTerminal(terminalId = "t1", amountCents = 25, orderId = "orden-8") as TerminalPaymentResult.Success)
            .requestId!!

        assertEquals("sin adopción del consumidor, sigue guardada y marcada", listOf(rid), idsPendientes())
        assertEquals(listOf(true), cobrados())
        val tras = TerminalPaymentService(secureStorage, OkHttpClient()) // reiniciar la app: servicio nuevo, mismo disco
        assertTrue("dentro de su ventana, la venta siguiente lo ve", tras.pendientesDeOtrasVentas("orden-9").single().cobrado)
        assertEquals("y sigue protegiendo SU venta", rid, tras.pendienteDeLaVenta("orden-8"))
    }

    @Test
    fun `H6 la revision de fondo rota - los consultados hace mas tiempo primero`() {
        // El más nuevo primero, como los entrega `pendientesDeOtrasVentas`.
        val todos = listOf("r4", "r3", "r2", "r1").map { com.avoqado.pos.payment.data.ContextoDeCobro(it, "v", "t1", "orden-$it") }

        assertEquals(listOf("r4", "r3", "r2"), service.loteDeRevision(todos).map { it.requestId })
        assertEquals("el que nunca tuvo turno va primero", listOf("r1", "r4", "r3"), service.loteDeRevision(todos).map { it.requestId })
        assertEquals(listOf("r2", "r4", "r3"), service.loteDeRevision(todos).map { it.requestId })
    }

    @Test
    fun `B7b-5 sin red la lista de terminales lo dice, y un 5xx sigue siendo error del servidor`() = runBlocking {
        enqueue(503, "{}")
        assertFalse((service.fetchOnlineTerminals() as com.avoqado.pos.payment.data.TerminalListResult.Error).sinRed)

        service.baseUrl = "http://127.0.0.1:1/api/v1" // nadie escucha: la conexión no sale, como un WiFi sin internet
        assertTrue((service.fetchOnlineTerminals() as com.avoqado.pos.payment.data.TerminalListResult.Error).sinRed)
    }


    @Test
    fun `N4 si el disco no acepta la marca, el SI paso se conserva en memoria y se reintenta en la siguiente lectura`() = runBlocking {
        // Codex r2 (N4): `marcarCobrado` devolvía false y el llamador seguía como si se hubiera guardado. El aviso quedaba
        // confirmado sólo en la pantalla; en la venta siguiente volvía a ser una duda y a los 10 min, sin red, se callaba.
        pendientes = PendientesDeTarjeta.agregar(
            null, "A", "orden-7", """{"requestId":"A","orderId":"orden-7","amountCents":1500,"tipCents":0}""",
        )
        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } returns false
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"pay-a"}""")

        assertTrue(service.resolveOutcome("A") is TerminalPaymentResult.Success)

        assertEquals("el disco sigue con la duda", listOf(false), cobrados())
        assertTrue("pero el aviso ya dice que SÍ pasó (memoria)", service.pendientesDeOtrasVentas("orden-8").single().cobrado)
        assertEquals("y sigue protegiendo su venta", "A", service.pendienteDeLaVenta("orden-7"))
        assertFalse(
            "no se presenta como guardado",
            service.aplicarDesenlaceTardio("A", CardChargeOutcome.Charged("pay-a"), aunSiFueDeclarado = true),
        )

        every { secureStorage.persistCobroQueSiPaso(any(), any(), any(), any()) } answers {
            pendientes = PendientesDeTarjeta.agregar(pendientes, firstArg(), secondArg(), thirdArg(), cobrado = true, cobradoEn = arg(3)); true
        }
        service.pendientesDeOtrasVentas("orden-8") // la siguiente lectura natural reintenta
        assertEquals("se guardó en cuanto el disco lo aceptó", listOf(true), cobrados())
    }

    @Test
    fun `el SI paso dura 10 min como las dudas - dentro se conserva, al vencer se purga del disco`() {
        // Founder, 26-sep: la MISMA ventana que las dudas, desde el cobro (`creadoEn`; si falta, desde que se confirmó). Nunca
        // pegajoso: al vencer sale del disco. Una DUDA nunca se purga (sigue frenando su venta).
        val ahora = System.currentTimeMillis()
        fun ctx(id: String, hace: Long?) = org.json.JSONObject().put("requestId", id).put("orderId", "orden-$id").put("amountCents", 1500)
            .apply { if (hace != null) put("creadoEn", ahora - hace) }.toString()
        val min = 60_000L
        pendientes = PendientesDeTarjeta.agregar(null, "viejo", "orden-viejo", ctx("viejo", 11 * min), cobrado = true, cobradoEn = ahora)
        pendientes = PendientesDeTarjeta.agregar(pendientes, "nuevo", "orden-nuevo", ctx("nuevo", 3 * min), cobrado = true, cobradoEn = ahora)
        pendientes = PendientesDeTarjeta.agregar(pendientes, "sf-viejo", "orden-sf-viejo", ctx("sf-viejo", null), cobrado = true, cobradoEn = ahora - 11 * min)
        pendientes = PendientesDeTarjeta.agregar(pendientes, "sf-nuevo", "orden-sf-nuevo", ctx("sf-nuevo", null), cobrado = true, cobradoEn = ahora - 3 * min)
        pendientes = PendientesDeTarjeta.agregar(pendientes, "duda-vieja", "orden-duda-vieja", ctx("duda-vieja", 60 * min))

        assertEquals(listOf("duda-vieja", "sf-nuevo", "nuevo"), service.pendientesDeOtrasVentas(null).map { it.requestId })
        assertEquals("los vencidos salieron del disco; la duda se queda", listOf("nuevo", "sf-nuevo", "duda-vieja"), idsPendientes())
        assertNull("un SÍ pasó vencido ya no frena su venta", service.pendienteDeLaVenta("orden-viejo"))
    }

    @Test
    fun `N6 un 503 con el cuerpo cortado es error del servidor, no sin red`() = runBlocking {
        // Codex r2 (N6): llegaron las cabeceras (503) y se cortó el cuerpo: el servidor SÍ contestó. Antes salía «sin red».
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("x".repeat(64 * 1024))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val r = service.fetchOnlineTerminals() as com.avoqado.pos.payment.data.TerminalListResult.Error

        assertFalse(r.sinRed)
        assertEquals("se conserva el estado HTTP conocido", "Error al buscar terminales (503)", r.message)
    }

    @Test
    fun `N6 una conexion cortada o un apreton SSL fallido es sin red`() = runBlocking {
        for (falla in listOf(java.net.SocketException("Connection reset"), javax.net.ssl.SSLHandshakeException("handshake"))) {
            val cortado = TerminalPaymentService(secureStorage, OkHttpClient.Builder().addInterceptor { throw falla }.build())
            cortado.baseUrl = server.url("/api/v1").toString().trimEnd('/')
            assertTrue("$falla", (cortado.fetchOnlineTerminals() as com.avoqado.pos.payment.data.TerminalListResult.Error).sinRed)
        }
    }
}
