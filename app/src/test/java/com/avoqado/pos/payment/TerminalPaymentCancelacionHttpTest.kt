package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El transporte de la cancelación, contra un MockWebServer real.
 *
 * Cubre las tres piezas del §C.4 que viven en el servicio: el cancel que se manda con el cliente
 * CORTO y cuya respuesta se LEE (antes era un `Thread` que la tiraba), los rechazos de admisión
 * correlacionados —que prueban que este cobro no se creó y por eso sueltan la llave— y el 503 de
 * admisión, que se reintenta con el MISMO `requestId` sin soltar nada.
 *
 * `runBlocking` y no `runTest`: el reloj virtual saltaría los plazos de la llamada real.
 */
class TerminalPaymentCancelacionHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var service: TerminalPaymentService
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private var pendingKey: String? = null
    private var contexto: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        every { secureStorage.pendingCardChargeRequestId } answers { pendingKey }
        every { secureStorage.pendingCardChargeRequestId = any() } answers { pendingKey = firstArg() }
        every { secureStorage.pendingCardChargeContext } answers { contexto }
        every { secureStorage.persistPendingCardCharge(any(), any()) } answers {
            if (pendingKey != null) {
                false
            } else {
                pendingKey = firstArg()
                contexto = secondArg()
                true
            }
        }
        service = TerminalPaymentService(secureStorage, OkHttpClient())
        service.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun cobrar(orderId: String? = "order-1") =
        service.sendPaymentToTerminal(terminalId = "t1", amountCents = 2500, tipCents = 100, orderId = orderId)

    private fun rechazoDeAdmision(code: String, requestId: String) =
        """{"success":false,"message":"rechazado","code":"$code","details":{"requestId":"$requestId"}}"""

    // MARK: - El cancel se manda y su respuesta se LEE

    @Test
    fun `P1 el cancel lee la respuesta del servidor y viaja sin abrir el teclado del PIN`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"message":"Cancelación enviada a la terminal","requestId":"req-1",
                    "cancelIntent":"RECORDED","cancelEmitted":true}""",
            ),
        )

        val respuesta = service.pedirCancelacion("req-1", "t1", "venue-9")

        assertEquals(200, respuesta.http)
        assertEquals(true, respuesta.success)
        assertEquals("RECORDED", respuesta.cancelIntent)
        assertEquals(true, respuesta.cancelEmitted)
        val peticion = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/venue-9/terminal-payment/cancel", peticion.path)
        assertEquals("1", peticion.getHeader(ForbiddenInterceptor.FAIL_FAST_HEADER))
        val cuerpo = JSONObject(peticion.body.readUtf8())
        assertEquals("t1", cuerpo.getString("terminalId"))
        assertEquals("req-1", cuerpo.getString("requestId"))
    }

    @Test
    fun `el cancel aprovecha el estado del cobro cuando el servidor lo manda`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":false,"message":"La solicitud ya terminó","cancelIntent":"ALREADY_FINAL",
                    "payment":{"status":"FAILED","inProgress":false,"outcome":"NOT_CHARGED",
                               "outcomeEvidence":"PROCESSOR_DECLINED","failureCode":"PROCESSOR_DECLINED"}}""",
            ),
        )

        val respuesta = service.pedirCancelacion("req-1", "t1", "venue-1")

        val estado = respuesta.estado as ChargeStatusProbe.Known
        assertEquals("FAILED", estado.status)
        assertEquals("NOT_CHARGED", estado.outcome)
        assertEquals("PROCESSOR_DECLINED", estado.failureCode)
    }

    @Test
    fun `P1 un cancel que no llega se reporta sin respuesta, jamas como aceptado`() = runBlocking {
        server.shutdown()

        val respuesta = service.pedirCancelacion("req-1", "t1", "venue-1")

        assertNull(respuesta.http)
        assertNull(respuesta.success)
    }

    @Test
    fun `P1 la solicitud queda marcada como cancelada ANTES de que el cancel viaje`() = runBlocking {
        val cobroLlego = java.util.concurrent.CountDownLatch(1)
        val soltarCobro = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/cancel")) {
                    soltarCobro.countDown()
                    return MockResponse().setBody("{}")
                }
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                cobroLlego.countDown()
                soltarCobro.await(5, java.util.concurrent.TimeUnit.SECONDS)
                // Un 403 NO está en la lista de códigos que se reconcilian: sólo la marca de
                // «se pidió cancelar» obliga a consultar en vez de concluir.
                return MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")
            }
        }

        val cobrando = async(kotlinx.coroutines.Dispatchers.IO) { cobrar() }
        assertTrue(cobroLlego.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val enVuelo = service.intentoEnVuelo()
        assertNotNull("mientras el POST vive, la solicitud se puede cancelar", enVuelo)
        assertEquals("t1", enVuelo!!.terminalId)
        assertEquals("venue-1", enVuelo.venueId)
        service.pedirCancelacion(enVuelo.requestId, enVuelo.terminalId, enVuelo.venueId)

        val resultado = cobrando.await()
        assertTrue("$resultado", resultado is TerminalPaymentResult.Undetermined)
        assertEquals(enVuelo.requestId, pendingKey)
    }

    // MARK: - Rechazos de admisión correlacionados (H.5)

    @Test
    fun `P1 un rechazo de admision que nombra MI solicitud suelta la llave y dice que no se envio`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                val id = JSONObject(request.body.readUtf8()).getString("requestId")
                return MockResponse().setResponseCode(400).setBody(rechazoDeAdmision("ORDER_CANCELLED_NO_NEW_CHARGE", id))
            }
        }

        val resultado = cobrar()

        val error = resultado as TerminalPaymentResult.Error
        assertEquals(CancelacionDeCobro.RECHAZO_CUENTA_CANCELADA, error.message)
        assertTrue(error.noSeCreo)
        assertNotNull(error.requestId)
        assertNull("consta que no se creó: la llave se suelta", pendingKey)
        assertEquals("no hay nada que consultar", 1, server.requestCount)
    }

    @Test
    fun `P1 la terminal desconectada, la cuenta pagada y la inexistente dicen que el cobro NO se envio`() = runBlocking {
        val casos = listOf(
            "TERMINAL_NOT_CONNECTED" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "TERMINAL_NO_SOCKET" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "TERMINAL_NOT_IN_VENUE" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "ORDER_ALREADY_PAID" to CancelacionDeCobro.RECHAZO_CUENTA_PAGADA,
            "ORDER_NOT_FOUND" to CancelacionDeCobro.RECHAZO_CUENTA_INEXISTENTE,
        )
        for ((code, texto) in casos) {
            pendingKey = null
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setResponseCode(404)
                    val id = JSONObject(request.body.readUtf8()).getString("requestId")
                    return MockResponse().setResponseCode(404).setBody(rechazoDeAdmision(code, id))
                }
            }
            val resultado = cobrar()
            assertEquals(code, texto, (resultado as TerminalPaymentResult.Error).message)
            assertNull(code, pendingKey)
        }
    }

    @Test
    fun `P1 un rechazo de admision que nombra OTRA solicitud se sigue consultando`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(rechazoDeAdmision("ORDER_CANCELLED_NO_NEW_CHARGE", "otra-solicitud")))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }

        val resultado = cobrar()

        assertTrue("$resultado", resultado is TerminalPaymentResult.Undetermined)
        assertEquals((resultado as TerminalPaymentResult.Undetermined).requestId, pendingKey)
    }

    // MARK: - 503 de admisión: se reintenta con el MISMO requestId

    @Test
    fun `P1 un 503 de admision se reintenta con el MISMO requestId`() = runBlocking {
        val ids = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                ids += JSONObject(request.body.readUtf8()).getString("requestId")
                return if (ids.size == 1) {
                    MockResponse().setResponseCode(503)
                        .setBody("""{"success":false,"code":"TERMINAL_PAYMENT_ADMISSION_RETRY","message":"Vuelve a intentarlo"}""")
                } else {
                    MockResponse().setBody("""{"success":true,"status":"success","paymentId":"pay-1"}""")
                }
            }
        }

        val resultado = cobrar()

        assertTrue("$resultado", resultado is TerminalPaymentResult.Success)
        assertEquals(2, ids.size)
        assertEquals("el reintento NO estrena identidad", ids[0], ids[1])
        assertNull(pendingKey)
    }

    @Test
    fun `P1 un 503 de admision que no cede conserva la llave y no concluye nada`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(503)
                    .setBody("""{"success":false,"code":"TERMINAL_PAYMENT_ADMISSION_RETRY","message":"Vuelve a intentarlo"}""")
            }
        }

        val resultado = cobrar()

        assertTrue("$resultado", resultado is TerminalPaymentResult.Undetermined)
        assertEquals((resultado as TerminalPaymentResult.Undetermined).requestId, pendingKey)
    }

    // MARK: - El estado durable trae el desenlace canónico

    @Test
    fun `P1 el estado del cobro trae outcome, evidencia y la orden`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"inProgress":false,"status":"FAILED","requestId":"req-1","terminalId":"t1",
                    "orderId":"order-1","outcome":"NOT_CHARGED","outcomeEvidence":"PROCESSOR_DECLINED",
                    "evidenceClass":"TERMINAL","failureCode":"PROCESSOR_DECLINED","reconciliationRequired":false}""",
            ),
        )

        val probe = service.getPaymentStatus("req-1") as ChargeStatusProbe.Known

        assertEquals("NOT_CHARGED", probe.outcome)
        assertEquals("PROCESSOR_DECLINED", probe.outcomeEvidence)
        assertEquals("TERMINAL", probe.evidenceClass)
        assertEquals("PROCESSOR_DECLINED", probe.failureCode)
        assertEquals(false, probe.reconciliationRequired)
        assertEquals("order-1", probe.orderId)
        assertEquals("t1", probe.terminalId)
    }

    @Test
    fun `la consulta dice si hubo respuesta del servidor o si no se pudo preguntar`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val conRespuesta = service.consultarEstado("req-1", "venue-1")
        assertEquals(ChargeStatusProbe.Unreachable, conRespuesta.probe)
        assertEquals("un 500 SÍ es una respuesta del servidor", false, conRespuesta.sinRespuesta)

        server.shutdown()
        val sinRespuesta = service.consultarEstado("req-1", "venue-1")
        assertEquals(ChargeStatusProbe.Unreachable, sinRespuesta.probe)
        assertTrue(sinRespuesta.sinRespuesta)
    }

    @Test
    fun `la consulta usa el venue que se le pide, no el activo`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"inProgress":false,"status":"CANCELLED"}"""))

        service.consultarEstado("req-1", "venue-original")

        assertEquals("/api/v1/mobile/venues/venue-original/terminal-payment/req-1", server.takeRequest().path)
    }

    // MARK: - El contexto guardado del cobro

    @Test
    fun `P1 el contexto del cobro devuelve terminal, venue y orden para poder cancelarlo despues`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"status":"success","paymentId":"pay-1"}"""))
        cobrar(orderId = "order-77")
        val requestId = JSONObject(server.takeRequest().body.readUtf8()).getString("requestId")

        val ctx = service.contextoDe(requestId)!!

        assertEquals("venue-1", ctx.venueId)
        assertEquals("t1", ctx.terminalId)
        assertEquals("order-77", ctx.orderId)
        assertEquals(2500, ctx.amountCents)
        assertEquals(100, ctx.tipCents)
        assertNull("el contexto de OTRA solicitud no se confunde", service.contextoDe("otra-solicitud"))
    }

    // MARK: - La llave del cobro

    @Test
    fun `P1 soltar la llave solo aplica a la propia solicitud`() {
        pendingKey = "req-1"
        service.soltarLlaveSiEs("otra")
        assertEquals("req-1", pendingKey)
        service.soltarLlaveSiEs("req-1")
        assertNull(pendingKey)
    }

    @Test
    fun `P1 armar la llave no pisa la de otro cobro`() {
        pendingKey = "req-otro"
        assertEquals(false, service.armarLlaveSiLibre("req-1"))
        assertEquals("req-otro", pendingKey)

        pendingKey = null
        assertEquals(true, service.armarLlaveSiLibre("req-1"))
        assertEquals("req-1", pendingKey)
        assertEquals("volver a armar la MISMA es idempotente", true, service.armarLlaveSiLibre("req-1"))
    }
}
