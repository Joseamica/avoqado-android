package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.*
import com.avoqado.pos.giftcards.data.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GiftCardsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: SecureStorage
    private val cache = mutableMapOf<String, String?>()
    @Before fun preparar() {
        server = MockWebServer().also { it.start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        storage = mockk(relaxed = true)
        every { storage.venueId } returns "v"
        every { storage.leerAjustesDeTarjetas(any()) } answers { cache[firstArg()] }
        every { storage.guardarAjustesDeTarjetas(any(), any()) } answers { cache[firstArg()] = secondArg<String?>() ?: "null" }
    }
    @After fun cerrar() { server.shutdown(); System.clearProperty("avoqado.test.baseUrl") }
    private fun responde(status: Int, json: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json))
    private fun ok(json: String) = responde(200, "{\"success\":true,\"data\":$json}")

    @Test fun `union servidor 1a oculta entradas y borra cache previa de 1b`() = runBlocking {
        ok(FijasDeTarjetas.ajustes)
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        assertTrue(repo.refrescarAjustes("v") is EstadoDeAjustes.Disponible)
        ok("""{"enabled":true,"suggestedAmounts":[500,1000],"minAmount":100,"maxAmount":10000,"llavesConfiguradas":true,"sale":{"sellable":true}}""")
        val estado = repo.refrescarAjustes("v")
        assertEquals(EstadoDeAjustes.NoDisponible, estado)
        assertEquals("null", cache["v"])
        verify { storage.guardarAjustesDeTarjetas("v", null) }
        assertEquals(EstadoDeAjustes.NoDisponible, GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value)
        assertFalse(com.avoqado.pos.giftcards.domain.estadoDeLaHoja(estado, true, true, true, true, "Pro").mosaicoVisible)
        val fila = com.avoqado.pos.giftcards.domain.estadoDeLaVenta(estado, true, true, false, false, false,
            true, false, false, false, false, false, false, false, false)
        assertEquals(com.avoqado.pos.giftcards.domain.FilaDeLaCaja.T12, fila.fila)
        assertFalse(fila.visible)
    }

    @Test fun `union primer arranque sin cache no muestra mosaico`() {
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        assertEquals(EstadoDeAjustes.Desconocido, repo.estadoDeAjustes.value)
        for (red in listOf(true, false)) {
            val hoja = com.avoqado.pos.giftcards.domain.estadoDeLaHoja(repo.estadoDeAjustes.value, red, true, true, true, "Pro")
            assertFalse(hoja.mosaicoVisible)
            assertFalse(hoja.vender.visible)
            assertFalse(hoja.consultar.visible)
        }
    }
    @Test fun `ajustes persisten 200 y 404 por negocio sin cruzarlos`() = runBlocking {
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        ok(FijasDeTarjetas.ajustes)
        assertTrue(repo.refrescarAjustes("v") is EstadoDeAjustes.Disponible)
        assertTrue(GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value is EstadoDeAjustes.Disponible)
        every { storage.venueId } returns "otro"
        assertEquals(EstadoDeAjustes.Desconocido, GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value)
        responde(404, "{\"message\":\"Not found\"}")
        assertEquals(EstadoDeAjustes.NoDisponible, repo.refrescarAjustes("otro"))
        assertEquals(EstadoDeAjustes.NoDisponible, GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value)
        responde(403, "{\"error\":\"Forbidden\"}")
        assertEquals(EstadoDeAjustes.SinPermiso, repo.refrescarAjustes("otro"))
    }
    @Test fun `P1 caja y gerente conservan aparato y quitan ambos logging interceptors`() = runBlocking {
        val logs = mutableListOf<String>()
        val logging = HttpLoggingInterceptor { logs.add(it) }.setLevel(HttpLoggingInterceptor.Level.BODY)
        val base = OkHttpClient.Builder().addInterceptor(logging).addNetworkInterceptor(logging)
            .addInterceptor { it.proceed(it.request().newBuilder().header("x-device-id", "d1").build()) }.build()
        val repo = GiftCardsRepository(base, storage)
        ok("{\"usableBalance\":0,\"cards\":[]}")
        repo.consultar("v", PruebaDeTarjeta.Codigo("secretoCodigo"))
        val q = server.takeRequest()
        assertEquals("d1", q.getHeader("x-device-id"))
        assertEquals("1", q.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
        assertEquals("{\"code\":\"secretoCodigo\"}", q.body.readUtf8())
        assertFalse(q.path!!.contains("secretoCodigo"))
        assertTrue(logs.isEmpty())
        val result = "{\"giftCardId\":\"g\",\"deliveryStatus\":\"PENDING\",\"repuesta\":false,\"ultimos4\":\"JKMN\"}"
        ok(result); repo.cambiarDestino("v", "g", "operation", "EMAIL", null, "a@example.test")
        ok("{\"codigo\":\"secretoCodigo\",\"pageToken\":\"secretoPagina\",\"ultimos4\":\"JKMN\",\"monto\":500,\"saldo\":500}"); repo.revelar("v", "g", "operation", "SHOW_QR", true)
        ok("{\"giftCardIds\":[\"g\"]}"); repo.cancelarVenta("v", "o", "operation")
        ok("{\"released\":true}"); repo.liberarCancelacion("v", "o", "operation")
        for (suffix in listOf("change-destination", "reveal-manager", "cancel-sale", "release-cancel")) {
            val r = server.takeRequest()
            assertTrue(r.path!!.endsWith(suffix))
            assertNull(r.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
            assertNull(r.getHeader(ForbiddenInterceptor.FAIL_FAST_HEADER))
            assertEquals("d1", r.getHeader("x-device-id"))
        }
        assertEquals(0, repo.clienteDeGerente.callTimeoutMillis)
        assertTrue(logs.isEmpty())
    }
    @Test fun `P1 429 propio por transporte conserva extras reenvio y red es incierta`() = runBlocking {
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        for ((code, extras, seconds) in listOf(Triple("GIFT_CARD_LOOKUP_RATE_LIMITED", "\"retryAfter\":60", 60), Triple("GIFT_CARD_PHONE_RATE_LIMITED", "\"details\":{\"retryAfter\":600}", 600), Triple("GIFT_CARD_RESEND_RATE_LIMITED", "\"retryAfter\":3600", 3600))) {
            responde(429, "{\"code\":\"$code\",\"message\":\"Espera\",$extras}")
            val r = when (code) {
                "GIFT_CARD_PHONE_RATE_LIMITED" -> repo.pedirCodigo("v", "+525512345678")
                "GIFT_CARD_RESEND_RATE_LIMITED" -> repo.reenviar("v", "g", "operation")
                else -> repo.consultar("v", PruebaDeTarjeta.Codigo("secretoCodigo"))
            } as Respuesta.Rechazo
            assertEquals(code, r.fallo.code); assertEquals("Espera", r.fallo.message); assertEquals(seconds, r.fallo.retryAfterSeg)
        }
        responde(429, "{}")
        assertEquals(Respuesta.SinRespuesta, repo.pedirCodigo("v", "+525512345678"))
        responde(503, "{}")
        assertEquals(Respuesta.SinRespuesta, repo.cobrar("v", "o", "{ \"idempotencyKey\": \"operation\" }"))
        repeat(4) { server.takeRequest() }
        assertEquals("{ \"idempotencyKey\": \"operation\" }", server.takeRequest().body.readUtf8())
    }
    @Test fun `P1 PIN solo en gerente y cuerpo crudo sobrevive reintento`() = runBlocking {
        val coordinator = object : ManagerOverrideCoordinator(mockk(relaxed = true)) {
            var calls = 0
            override fun awaitToken(permission: String): String { calls++; return "tok" }
        }
        val repo = GiftCardsRepository(OkHttpClient.Builder().addInterceptor(ForbiddenInterceptor(ErrorNotifier(), coordinator)).build(), storage)
        val forbidden = """{"error":"Forbidden","required":"giftCards:manage","overridable":true}"""
        responde(403, forbidden); ok("{\"giftCardIds\":[\"g\"]}")
        assertTrue(repo.cancelarVenta("v", "o", "operation") is Respuesta.Ok)
        assertEquals(1, coordinator.calls)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals("tok", second.getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
        responde(403, forbidden)
        assertTrue(repo.reenviar("v", "g", "operation") is Respuesta.Rechazo)
        assertEquals(1, coordinator.calls)
    }
    @Test fun `plazo vencido es incierto y no intenta cobrar otra vez`() = runBlocking {
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(Respuesta.SinRespuesta, repo.cobrar("v", "o", "{}"))
        assertEquals(1, server.requestCount)
    }
    @Test fun `todas las acciones usan rutas cuerpos y unidades del contrato`() = runBlocking {
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        ok("{\"orderId\":\"o\",\"orderNumber\":\"n\",\"total\":500,\"sobres\":[]}")
        val body = "{ \"idempotencyKey\": \"operation\", \"sobres\": [] }"
        repo.vender("v", body)
        assertEquals(body, server.takeRequest().body.readUtf8())
        ok(FijasDeTarjetas.cobro); repo.cobrarNuevaCuenta("v", "{}")
        assertEquals("/api/v1/mobile/venues/v/gift-cards/pay-new-order", server.takeRequest().path)
        ok("{\"found\":true,\"payment\":${FijasDeTarjetas.cobro}}")
        assertEquals(80.0, (repo.recuperarCobro("v", "operation") as Respuesta.Ok).valor!!.amount, 0.0)
        assertEquals("GET", server.takeRequest().method)
        ok("{\"cancelled\":true}"); repo.regresarSaldoYCancelar("v", "o", "operation")
        val cancel = server.takeRequest(); assertEquals("/api/v1/mobile/venues/v/orders/o/gift-balance-return-and-cancel", cancel.path)
        assertEquals("{\"operationKey\":\"operation\"}", cancel.body.readUtf8())
        ok("{\"tarjetas\":[{\"id\":\"g\",\"codigo\":\"secretoCodigo\",\"pageToken\":\"secretoPagina\",\"ultimos4\":\"JKMN\",\"monto\":500,\"saldo\":500}]}")
        val reveals = (repo.revelarCuenta("v", "o", "operation") as Respuesta.Ok).valor
        assertEquals("g", reveals.single().giftCardId)
        assertEquals("/api/v1/mobile/venues/v/gift-cards/orders/o/reveal", server.takeRequest().path)
        ok(FijasDeTarjetas.telefono); repo.verificarCodigo("v", "+525512345678", "123456")
        val phone = server.takeRequest()
        assertEquals("{\"phone\":\"+525512345678\",\"code\":\"123456\"}", phone.body.readUtf8())
    }

    @Test fun `P1 mismo negocio conserva respuesta nueva en memoria cache y recreacion`() = runBlocking {
        respuestasInvertidas(200, 200, volverA = false)
    }

    @Test fun `P1 A B A no deja que A vieja sobrescriba A nueva ni cache de B`() = runBlocking {
        respuestasInvertidas(200, 200, volverA = true)
    }

    @Test fun `P1 200 antigua no resucita la 404 reciente en cache ni memoria`() = runBlocking {
        respuestasInvertidas(200, 404, volverA = false)
    }

    @Test fun `P1 404 antigua no borra la 200 reciente de cache ni memoria`() = runBlocking {
        respuestasInvertidas(404, 200, volverA = false)
    }

    @Test fun `P1 200 antigua no desplaza 403 reciente ni toca su cache conservada`() = runBlocking {
        respuestasInvertidas(200, 403, volverA = false)
    }

    private fun respuestaDeAjustes(http: Int, ajustes: AjustesDeTarjetas): MockResponse {
        val body = when (http) {
            200 -> "{\"success\":true,\"data\":${GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(), ajustes)}}"
            404 -> "{\"message\":\"Not found\"}"
            else -> "{\"error\":\"Forbidden\"}"
        }
        return MockResponse().setResponseCode(http).setHeader("Content-Type", "application/json").setBody(body)
    }

    private suspend fun respuestasInvertidas(viejoHttp: Int, nuevoHttp: Int, volverA: Boolean) = coroutineScope {
        val vieja = GiftCardsApi.parsearAjustes(FijasDeTarjetas.ajustes)
        val nueva = vieja.copy(enabled = false, sale = VentaPermitida(false, message = "Venta apagada recientemente"))
        val previa = nueva.copy(minAmount = 200.0)
        cache["v"] = GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(), previa)
        val bytesPrevios = cache["v"]
        val recibida = CountDownLatch(1)
        val liberar = CountDownLatch(1)
        val peticionesA = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/venues/v/") && peticionesA.incrementAndGet() == 1) {
                    recibida.countDown()
                    if (!liberar.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return respuestaDeAjustes(viejoHttp, vieja)
                }
                return respuestaDeAjustes(if (request.path!!.contains("/venues/b/")) 200 else nuevoHttp, nueva)
            }
        }
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        val antigua = async(Dispatchers.IO) { repo.refrescarAjustes("v") }
        try {
            assertTrue("La primera solicitud debe llegar al servidor falso", recibida.await(5, TimeUnit.SECONDS))
            var bytesB: String? = null
            if (volverA) {
                every { storage.venueId } returns "b"
                assertEquals(EstadoDeAjustes.Disponible(nueva), repo.refrescarAjustes("b"))
                bytesB = cache["b"]
                every { storage.venueId } returns "v"
            }
            val esperado = when (nuevoHttp) {
                200 -> EstadoDeAjustes.Disponible(nueva)
                404 -> EstadoDeAjustes.NoDisponible
                else -> EstadoDeAjustes.SinPermiso
            }
            assertEquals(esperado, repo.refrescarAjustes("v"))
            assertEquals(2, peticionesA.get())
            val bytesNuevos = cache["v"]
            when (nuevoHttp) {
                200 -> assertEquals(nueva, GiftCardsApi.parsearAjustes(bytesNuevos!!))
                404 -> assertEquals("null", bytesNuevos)
                else -> assertEquals(bytesPrevios, bytesNuevos)
            }
            liberar.countDown()
            antigua.await()
            assertEquals(esperado, repo.estadoDeAjustes.value)
            assertEquals("No reescribir los bytes recientes", bytesNuevos, cache["v"])
            if (volverA) assertEquals(bytesB, cache["b"])
            val alRecrear = if (nuevoHttp == 403) EstadoDeAjustes.Disponible(previa) else esperado
            assertEquals(alRecrear, GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value)
        } finally {
            liberar.countDown()
            antigua.cancelAndJoin()
        }
    }

    @Test fun `respuesta tardia de A actualiza solo su cache mientras B sigue visible`() = runBlocking {
        val a = GiftCardsApi.parsearAjustes(FijasDeTarjetas.ajustes)
        val b = a.copy(enabled = false)
        val recibida = CountDownLatch(1)
        val liberar = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/venues/v/")) {
                    recibida.countDown()
                    if (!liberar.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return respuestaDeAjustes(200, a)
                }
                return respuestaDeAjustes(200, b)
            }
        }
        val repo = GiftCardsRepository(OkHttpClient(), storage)
        val antigua = async(Dispatchers.IO) { repo.refrescarAjustes("v") }
        try {
            assertTrue(recibida.await(5, TimeUnit.SECONDS))
            every { storage.venueId } returns "b"
            assertEquals(EstadoDeAjustes.Disponible(b), repo.refrescarAjustes("b"))
            val bytesB = cache["b"]
            liberar.countDown()
            antigua.await()
            assertEquals(EstadoDeAjustes.Disponible(b), repo.estadoDeAjustes.value)
            assertEquals(bytesB, cache["b"])
            assertEquals(a, GiftCardsApi.parsearAjustes(cache["v"]!!))
            every { storage.venueId } returns "v"
            assertEquals(EstadoDeAjustes.Disponible(a), GiftCardsRepository(OkHttpClient(), storage).estadoDeAjustes.value)
        } finally {
            liberar.countDown()
            antigua.cancelAndJoin()
        }
    }
    @Test fun `union E retirada tardia de A avisa aunque B ya tuviera el mismo estado global`() = runBlocking {
        cache["v"]=FijasDeTarjetas.ajustes
        val llego=CountDownLatch(1);val seguir=CountDownLatch(1)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.path!!.contains("/venues/v/")) { llego.countDown();check(seguir.await(10,TimeUnit.SECONDS)) }
                return MockResponse().setHeader("Content-Type","application/json").setBody(
                    """{"success":true,"data":{"enabled":true,"suggestedAmounts":[],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true}}}""")
            }
        }
        val repo=GiftCardsRepository(OkHttpClient(),storage)
        val a=async(Dispatchers.IO) { repo.refrescarAjustes("v") }
        try {
            assertTrue(llego.await(5,TimeUnit.SECONDS))
            assertEquals(EstadoDeAjustes.NoDisponible,repo.refrescarAjustes("b"))
            assertFalse(repo.cambiosDeAjustes.value.containsKey("v"))
            seguir.countDown();a.await()
            assertEquals("null",cache["v"])
            assertEquals(EstadoDeAjustes.NoDisponible,repo.estadoDeAjustes.value)
            assertEquals("La vista de A debe recibir SU retiro aunque el global no cambie",EstadoDeAjustes.NoDisponible,repo.cambiosDeAjustes.value["v"])
            assertEquals(EstadoDeAjustes.NoDisponible,repo.cambiosDeAjustes.value["b"])
        } finally { seguir.countDown();a.cancelAndJoin() }
    }

    @Test fun `union E A desconocida no recibe Disponible de B si su refresco falla`() = runBlocking {
        cache["b"]=FijasDeTarjetas.ajustes
        val llego=CountDownLatch(1);val seguir=CountDownLatch(1)
        val disponible=GiftCardsApi.parsearAjustes(FijasDeTarjetas.ajustes)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.path!!.contains("/venues/v/")) {
                    llego.countDown();check(seguir.await(10,TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
                return respuestaDeAjustes(200,disponible)
            }
        }
        val repo=GiftCardsRepository(OkHttpClient(),storage)
        val a=async(Dispatchers.IO) { repo.refrescarAjustes("v") }
        try {
            assertTrue(llego.await(5,TimeUnit.SECONDS))
            assertEquals(EstadoDeAjustes.Disponible(disponible),repo.refrescarAjustes("b"))
            seguir.countDown()
            assertEquals("El retorno debe pertenecer a A aunque el flow global sea B",EstadoDeAjustes.Desconocido,a.await())
            assertNull(cache["v"])
            assertEquals(EstadoDeAjustes.Disponible(disponible),repo.estadoDeAjustes.value)
        } finally { seguir.countDown();a.cancelAndJoin() }
    }

}
