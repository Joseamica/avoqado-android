package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `un 404 del cobro es rechazo de negocio — error directo, sin consultar`() = runBlocking {
        enqueue(404, """{"errorMessage":"La terminal no está conectada"}""")

        val result = charge()

        assertTrue(result is TerminalPaymentResult.Error)
        assertEquals("La terminal no está conectada", (result as TerminalPaymentResult.Error).message)
        assertEquals("un 4xx no debe disparar consultas", 1, server.requestCount)
        assertEquals(null, service.unresolvedRequestId)
    }

    @Test
    fun `un 409 de terminal ocupada tampoco va a consultar`() = runBlocking {
        enqueue(409, """{"errorMessage":"La terminal está ocupada"}""")

        val result = charge()

        assertTrue(result is TerminalPaymentResult.Error)
        assertEquals(1, server.requestCount)
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
}
