package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.ChargeStatusProbe
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
    private var pendingKey: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        // La llave durable de verdad vive en disco; aquí se emula con una variable.
        every { secureStorage.pendingCardChargeRequestId } answers { pendingKey }
        every { secureStorage.pendingCardChargeRequestId = any() } answers { pendingKey = firstArg() }

        every { secureStorage.persistPendingCardCharge(any(), any()) } answers {
            if (pendingKey != null) false else { pendingKey = firstArg(); true }
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
            pendingKey = "pending"
            enqueue(200, """{"status":"$status","inProgress":false,"cancelDisposition":"ACTIVE"}""")
            val result = service.resolveOutcome("pending")
            assertTrue(result is TerminalPaymentResult.Undetermined)
            assertEquals("pending", pendingKey)
            // Drain unused responses if a terminal disposition was resolved immediately.
        }
    }

    @Test
    fun `HTTP success carrying unknown status must not clear protection`() = runBlocking {
        enqueue(200, """{"success":false,"status":"unknown"}""")
        repeat(3) { enqueue(404) }
        assertTrue(charge() is TerminalPaymentResult.Undetermined)
        assertTrue(pendingKey != null)
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
        assertNull(pendingKey)
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
        pendingKey = "pending"
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
        assertTrue(pendingKey != null)
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
        every { secureStorage.persistPendingCardCharge(any(), any()) } returns false
        val result = charge()
        assertTrue(result is TerminalPaymentResult.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recovery after venue switch uses original venue`() = runBlocking {
        pendingKey = "original-request"
        every { secureStorage.pendingCardChargeContext } returns """{"requestId":"original-request","venueId":"original-venue"}"""
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"paid"}""")
        service.resolveOutcome("original-request")
        assertEquals("/api/v1/mobile/venues/original-venue/terminal-payment/original-request", server.takeRequest().path)
    }

    @Test
    fun `accepted cancellation permits a deliberate new attempt`() = runBlocking {
        pendingKey = "cancelled-request"
        enqueue(200, """{"status":"CANCELLED","inProgress":false,"cancelDisposition":"ACCEPTED"}""")
        assertTrue(service.resolveOutcome("cancelled-request") is TerminalPaymentResult.Error)
        assertEquals(null, pendingKey)
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
        assertEquals(null, service.unresolvedRequestId) // desenlace resuelto ⇒ llave liberada
    }

    @Test
    fun `un 503 con el cobro rechazado se propaga como error real`() = runBlocking {
        enqueue(503)
        enqueue(200, """{"success":true,"inProgress":false,"status":"FAILED"}""")

        val result = charge()

        assertTrue(result is TerminalPaymentResult.Error)
        assertEquals(null, service.unresolvedRequestId) // consta que no se cobró
    }

    @Test
    fun `un 503 con el server tambien caido queda INDETERMINADO y conserva la llave`() = runBlocking {
        enqueue(503)
        repeat(3) { enqueue(500) } // la consulta tampoco se puede responder

        val result = charge()

        assertTrue("sin poder saber, jamás fracaso", result is TerminalPaymentResult.Undetermined)
        // 🔴 La llave SOBREVIVE: es lo que bloquea el siguiente cobro a ciegas.
        assertEquals((result as TerminalPaymentResult.Undetermined).requestId, service.unresolvedRequestId)
    }

    @Test
    fun `404 and generic 409 after POST remain unknown`() = runBlocking {
        for (code in listOf(400, 404, 409, 422)) {
            pendingKey = null
            enqueue(code)
            repeat(3) { enqueue(404) }
            val result = charge()
            assertTrue(result is TerminalPaymentResult.Undetermined)
            assertEquals((result as TerminalPaymentResult.Undetermined).requestId, pendingKey)
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
        assertNull(pendingKey)
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
        assertEquals((result as TerminalPaymentResult.Undetermined).requestId, pendingKey)
    }

    @Test
    fun `un 409 TERMINAL_BUSY sin solicitud nombrada (server viejo) sigue siendo incertidumbre`() = runBlocking {
        enqueue(409, """{"success":false,"status":"failed","code":"TERMINAL_BUSY","message":"ocupada"}""")
        repeat(3) { enqueue(404) }
        val result = charge()
        assertTrue("$result", result is TerminalPaymentResult.Undetermined)
        assertEquals((result as TerminalPaymentResult.Undetermined).requestId, pendingKey)
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
        assertNull(pendingKey)
    }

    @Test
    fun `unresolved sale cannot POST again on another terminal after restart`() = runBlocking {
        pendingKey = "old-request"
        enqueue(200, """{"success":true,"status":"success"}""")
        val result = charge()
        assertTrue(result is TerminalPaymentResult.Undetermined)
        assertEquals("old-request", pendingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `resolving an old request does not clear a newer pending charge`() = runBlocking {
        pendingKey = "new-request"
        enqueue(200, """{"status":"COMPLETED","inProgress":false,"paymentId":"old-payment"}""")
        service.resolveOutcome("old-request")
        assertEquals("new-request", pendingKey)
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
        assertEquals(null, service.unresolvedRequestId)
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
}
