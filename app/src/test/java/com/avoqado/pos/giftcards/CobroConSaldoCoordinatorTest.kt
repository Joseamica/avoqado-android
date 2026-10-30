package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class CobroConSaldoCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var fake: SolicitudesDeTarjetaTest.Preferencias
    private lateinit var storage: SecureStorage
    private lateinit var store: SolicitudesDeTarjetaStore
    private lateinit var coordinator: CobroConSaldoCoordinator
    private val dao = mockk<PendingPaymentDao>()
    private val foto = FotoDeLaVenta(orderNote = "Venta ñ")
    private val prueba = PruebaDeTarjeta.Codigo("secreto")
    private var vaciados = 0
    private var entregadas = 0
    private var ocupado = false
    private fun fila(nueva: Boolean = false, mostrador: Boolean = false) = SolicitudDeTarjeta(
        tipo = "GIFT_CARD_REDEEM", llave = "k", venueId = "a", creadaEn = 1,
        orderId = if (nueva) null else "o", cuerpo = """{ "idempotencyKey": "k", "amount": 80.00, "iniciadoPor":"Ana" }""",
        nuevaCuenta = nueva, deMostrador = mostrador || nueva,
        foto = if (nueva) Json.encodeToString(FotoDeLaVenta.serializer(), foto) else null, mandado = true)
    @Before fun preparar() {
        server = MockWebServer().also { it.start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        fake = SolicitudesDeTarjetaTest.Preferencias().apply { values["venueId"] = "a"; values["accessToken"] = "fixture" }
        storage = fake.almacen(); store = SolicitudesDeTarjetaStore(storage)
        coEvery { dao.forVenue(any()) } returns emptyList()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer " + storage.userId).build())
        }.build()
        coordinator = CobroConSaldoCoordinator(GiftCardsRepository(client, storage), store, storage, CobrosLocalesDeLaCuenta(dao, storage, store))
        vaciados = 0; entregadas = 0; ocupado = false
    }
    @After fun cerrar() { server.shutdown(); System.clearProperty("avoqado.test.baseUrl") }
    private fun respuesta(status: Int = 200, body: String = exito()) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun exito(restante: Int = 0) = """{"success":true,"data":{"paymentId":"p","orderId":"o","orderNumber":"n","amount":80,"tipAmount":0,"redeemed":[],"orderRemaining":$restante,"orderPaymentStatus":"${if (restante > 0) "PARTIAL" else "PAID"}"}}"""
    private fun rechazo(code: String = "GIFT_CARD_BLOCKED", status: Int = 409) = respuesta(status, """{"success":false,"code":"$code","message":"rechazo","retryAfter":60,"saldoDisponible":50}""")
    private suspend fun nueva(tip: Int = 0): DesenlaceDeCobro = coordinator.cobrarNuevaCuenta("a", buildJsonObject {
        put("items", JsonArray(emptyList())); put("tip", 2000); put("externalId", "no-debe-viajar"); put("tableId", "no-debe-viajar"); put("isOfflineReplay", true)
    }, foto, prueba, 8000, tip, vaciarCarrito = { vaciados++ }, regresarAlCarrito = {
        entregadas++; assertTrue("Suelta confirmado ANTES de entregar", store.delNegocio("a").isEmpty()); !ocupado
    })
    private suspend fun cuenta(mostrador: Boolean = false) = coordinator.cobrar("a", "o", prueba, 8000, 0,
        foto = if (mostrador) foto else null, vaciarCarrito = if (mostrador) ({ vaciados++ }) else null)

    @Test fun guardaLaSolicitudCompletaAntesDelPost() = runBlocking {
        var vioDiario = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val s = store.delNegocio("a").single()
                vioDiario = s.mandado && s.nuevaCuenta && s.deMostrador && s.foto != null && vaciados == 1 && s.cuerpo == request.body.readUtf8()
                return respuesta()
            }
        }
        assertTrue(nueva() is DesenlaceDeCobro.Cobrado)
        assertTrue(vioDiario); assertEquals(1, server.requestCount)
        assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun tipEsPesosYNoViajanCamposProhibidos() = runBlocking {
        for (tip in listOf(0, 2000)) {
            server.enqueue(respuesta()); assertTrue(nueva(tip) is DesenlaceDeCobro.Cobrado)
            val req = server.takeRequest(1, TimeUnit.SECONDS)!!
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(tip / 100.0, body["tip"]!!.jsonPrimitive.double, 0.0)
            assertEquals(80.0, body["amount"]!!.jsonPrimitive.double, 0.0)
            assertFalse(body.keys.any { it in setOf("externalId", "tableId", "isOfflineReplay") })
            assertTrue(req.path!!.endsWith("/gift-cards/pay-new-order"))
        }
    }
    @Test fun sinRespuestaConservaSolicitudYCarritoVacio() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(DesenlaceDeCobro.Incierto, nueva())
        assertEquals(1, vaciados); assertEquals(0, entregadas)
        assertTrue(store.delNegocio("a").single().mandado)
    }
    @Test fun foundFalseReenviaMismosBytesYAutorizacionVigente() = runBlocking {
        val s = fila(); assertTrue(store.guardar(s)); storage.userId = "Beatriz"
        server.enqueue(respuesta(body = """{"success":true,"data":{"found":false}}""")); server.enqueue(respuesta())
        assertTrue(coordinator.resolver("k") is DesenlaceDeCobro.Cobrado)
        val get = server.takeRequest(1, TimeUnit.SECONDS)!!; val post = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", get.method); assertTrue(get.path!!.endsWith("/payments/k"))
        assertEquals(s.cuerpo, post.body.readUtf8()); assertEquals("Bearer Beatriz", post.getHeader("Authorization"))
        assertEquals(0, vaciados)
    }
    @Test fun foundTrueNoReenvia() = runBlocking {
        assertTrue(store.guardar(fila()))
        val pago = Json.parseToJsonElement(exito()).jsonObject["data"]!!
        server.enqueue(respuesta(body = """{"success":true,"data":{"found":true,"payment":$pago}}"""))
        assertTrue(coordinator.resolver("k") is DesenlaceDeCobro.Cobrado)
        assertEquals(1, server.requestCount); assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun conflictoTresVecesEsDefinitivoYT1Regresa() = runBlocking {
        repeat(3) { server.enqueue(rechazo("ORDER_PAYMENT_CONFLICT")) }
        assertTrue(nueva() is DesenlaceDeCobro.Rechazado)
        assertEquals(3, server.requestCount); assertEquals(1, entregadas)
        val cuerpos = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8() }
        assertEquals(1, cuerpos.distinct().size); assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun conflictoSegundoIntentoExitoso() = runBlocking {
        server.enqueue(rechazo("ORDER_PAYMENT_CONFLICT")); server.enqueue(respuesta())
        assertTrue(cuenta() is DesenlaceDeCobro.Cobrado); assertEquals(2, server.requestCount)
    }
    @Test fun terminalEnCursoY429ConservanLaLlave() = runBlocking {
        for ((code, http) in listOf("ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE" to 409, "GIFT_CARD_RATE_LIMITED" to 429)) {
            server.enqueue(rechazo(code, http)); assertTrue(cuenta() is DesenlaceDeCobro.Reintentable)
            assertTrue(coordinator.cuentaTrabada("o")); assertEquals(1, store.delNegocio("a").size)
            store.quitar(store.delNegocio("a").single().llave)
        }
    }
    @Test fun rechazoDefinitivoT1SueltaAntesDeEntregar() = runBlocking {
        server.enqueue(rechazo()); assertTrue(nueva() is DesenlaceDeCobro.Rechazado)
        assertEquals(1, entregadas); assertEquals(1, vaciados); assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun rechazoT2QuedaPorTerminarSinRecuperar() = runBlocking {
        server.enqueue(rechazo()); assertTrue(cuenta(true) is DesenlaceDeCobro.Rechazado)
        val s = store.delNegocio("a").single(); assertTrue(s.porTerminar); assertFalse(s.porRecuperar)
        assertEquals("o", s.orderId); assertEquals("", s.cuerpo); assertNull(s.restanteCents)
        assertFalse(coordinator.cuentaTrabada("o")); assertEquals(1, vaciados); assertEquals(0, entregadas)
    }
    @Test fun rechazoT7QuitaDiario() = runBlocking {
        server.enqueue(rechazo()); assertTrue(cuenta() is DesenlaceDeCobro.Rechazado)
        assertTrue(store.delNegocio("a").isEmpty()); assertEquals(0, vaciados)
    }
    @Test fun quitarFalseNoEntregaLaVenta() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            fake.ok = false; return rechazo()
        } }
        val r = nueva(); assertTrue(r is DesenlaceDeCobro.Rechazado)
        assertEquals(0, entregadas); assertEquals(1, store.delNegocio("a").size)
        assertEquals("No se pudo recuperar la venta. Intenta otra vez.", (r as DesenlaceDeCobro.Rechazado).texto)
    }
    @Test fun restaurarFalseReinsertaPorRecuperar() = runBlocking {
        ocupado = true; server.enqueue(rechazo()); nueva()
        assertEquals(1, store.delNegocio("a").size)
        val s = store.delNegocio("a").single(); assertTrue(s.porRecuperar); assertEquals("", s.cuerpo)
        assertEquals(1, entregadas)
    }
    @Test fun confirmadoConRestoMostradorNoVuelveAlCarrito() = runBlocking {
        for (nueva in listOf(true, false)) {
            server.enqueue(respuesta(body = exito(500)))
            assertTrue((if (nueva) nueva() else cuenta(true)) is DesenlaceDeCobro.Cobrado)
            val s = store.delNegocio("a").single(); assertTrue(s.porTerminar); assertEquals(50000, s.restanteCents)
            assertEquals("", s.cuerpo); assertEquals("o", s.orderId); assertFalse(coordinator.cuentaTrabada("o"))
            store.quitar(s.llave)
        }
        assertEquals(0, entregadas); assertEquals(2, vaciados)
    }
    @Test fun guardarFalseNoVaciaNiEnvia() = runBlocking {
        fake.ok = false; assertTrue(nueva() is DesenlaceDeCobro.NoSalio)
        assertEquals(0, vaciados); assertEquals(0, server.requestCount)
    }
    @Test fun mandadoFalseYPorTerminarNoSeConsultan() = runBlocking {
        assertTrue(store.guardar(fila().copy(mandado = false)))
        coordinator.resolver("k"); assertEquals(0, server.requestCount); assertFalse(coordinator.cuentaTrabada("o"))
        store.quitar("k"); store.guardar(fila().copy(porTerminar = true, cuerpo = ""))
        coordinator.resolver("k"); assertEquals(0, server.requestCount)
    }
    @Test fun recuperarVentaConfirmaSoltarPrimeroYRespetaOcupado() = runBlocking {
        assertTrue(store.guardar(fila(nueva = true).copy(porRecuperar = true, cuerpo = "")))
        assertEquals(Recuperacion.CARRITO_OCUPADO, coordinator.recuperarVenta("k", { false }, { error("No entrega") }))
        fake.ok = false
        assertEquals(Recuperacion.NO_SE_PUDO_SOLTAR, coordinator.recuperarVenta("k", { true }, { error("No entrega") }))
        fake.ok = true
        assertEquals(Recuperacion.RECUPERADA, coordinator.recuperarVenta("k", { true }, { assertTrue(store.delNegocio("a").isEmpty()); true }))
    }
    @Test fun otraSucursalNoSeToca() = runBlocking {
        assertTrue(store.guardar(fila().copy(venueId = "b")))
        coordinator.resolver("k"); assertEquals(0, server.requestCount)
        assertFalse(coordinator.cuentaTrabada("o"))
    }
    @Test fun Q94SoloListaDelContratoEsReintentable() {
        for ((http, code) in listOf(null to null, 503 to "FUTURO", 429 to "FUTURO", 409 to "ORDER_PAYMENT_CONFLICT", 409 to "ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE"))
            assertTrue("$http/$code", CobroConSaldoCoordinator.esReintentable(http, code))
        for (http in listOf(400, 401, 403, 404, 409, 422))
            assertFalse(CobroConSaldoCoordinator.esReintentable(http, "FUTURO_NO_REINTENTABLE"))
    }
    @Test fun formatoAnteriorSinOrigenSeLeeFalse() {
        val raw = """[{"v":1,"tipo":"GIFT_CARD_REDEEM","llave":"vieja","venueId":"a","creadaEn":1,"orderId":"o","impresos":[],"cuerpo":"{}","mandado":true,"futuro":{"valor":"ñ"}}]"""
        val fila = SolicitudesDeTarjeta.leer(raw).single()
        assertFalse(fila.deMostrador)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(SolicitudesDeTarjeta.conMandado(raw,"vieja")))
    }

    @Test fun marcarFalseNoVaciaNiEnviaAunqueYaGuardo() = runBlocking {
        val spied = spyk(store)
        every { spied.marcarMandado(any()) } returns false
        coordinator = CobroConSaldoCoordinator(GiftCardsRepository(OkHttpClient(), storage), spied, storage, CobrosLocalesDeLaCuenta(dao, storage, spied))
        val r = nueva()
        assertTrue(r is DesenlaceDeCobro.NoSalio)
        assertEquals(0, vaciados); assertEquals(0, server.requestCount)
        verify(exactly = 1) { spied.quitar(any()) }
    }
    @Test fun dosToquesEnVueloMandanUnaSolaSolicitud() = runBlocking {
        server.enqueue(respuesta().setBodyDelay(100, TimeUnit.MILLISECONDS))
        val resultados = coroutineScope { awaitAll(async { nueva() }, async { nueva() }) }
        assertEquals(1, server.requestCount)
        assertEquals(1, resultados.count { it is DesenlaceDeCobro.Cobrado })
        assertEquals(1, vaciados)
    }

    @Test fun rechazoTardioTrasCambiarSucursalNoRestauraVentaDeAEnB() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            storage.venueId = "b"
            return rechazo()
        } }
        assertTrue(nueva() is DesenlaceDeCobro.Rechazado)
        assertEquals(0, entregadas)
        assertTrue(store.delNegocio("a").single().porRecuperar)
        assertTrue(store.delNegocio("b").isEmpty())
    }

    @Test fun cambioSucursalMientrasLeeQ83NoGuardaNiVacia() = runBlocking {
        coEvery { dao.forVenue("a") } coAnswers { storage.venueId = "b"; emptyList() }
        val r = cuenta(true)
        assertTrue(r is DesenlaceDeCobro.NoSalio)
        assertEquals(0, vaciados); assertEquals(0, server.requestCount)
        assertTrue(store.delNegocio("a").isEmpty())
    }

    @Test fun idempotencyReusedConsultaLlaveSinCambiarCuerpo() = runBlocking {
        server.enqueue(rechazo("IDEMPOTENCY_KEY_REUSED"))
        val pago = Json.parseToJsonElement(exito()).jsonObject["data"]!!
        server.enqueue(respuesta(body = """{"success":true,"data":{"found":true,"payment":$pago}}"""))
        assertTrue(cuenta() is DesenlaceDeCobro.Cobrado)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertEquals(2, server.requestCount)
    }

    @Test fun restaurarYReinsertarFallidosConservanFotoDurable() = runBlocking {
        var raw: String? = null
        var falla = false
        val persistencia = mockk<SecureStorage>()
        every { persistencia.venueId } returns "a"
        every { persistencia.giftCardRequestsJson } answers { raw }
        every { persistencia.escribirSolicitudesDeTarjeta(any()) } answers {
            val nuevo = firstArg<(String?) -> String?>()(raw)
            if (nuevo == null || falla) false else { raw = nuevo; true }
        }
        val diario = SolicitudesDeTarjetaStore(persistencia)
        val solicitud = fila(nueva = true).copy(porRecuperar = true, cuerpo = "")
        assertTrue(diario.guardar(solicitud))
        val coordinador = CobroConSaldoCoordinator(mockk(), diario, persistencia, mockk())
        val resultado = coordinador.recuperarVenta("k", { true }, {
            assertTrue("El retiro se confirmó antes de restaurar", diario.delNegocio("a").isEmpty())
            falla = true
            false
        })
        assertEquals(Recuperacion.CARRITO_OCUPADO, resultado)
        assertEquals("Sin restauración ni reescritura la foto debe seguir durable", listOf(solicitud), SolicitudesDeTarjetaStore(persistencia).delNegocio("a"))
    }

    private class MemoriaDeEntrega {
        var raw: String? = null
        var escrituras = 0
        var fallaDesde: Int? = null
        val storage = mockk<SecureStorage>().also { s ->
            every { s.venueId } returns "a"
            every { s.giftCardRequestsJson } answers { raw }
            every { s.escribirSolicitudesDeTarjeta(any()) } answers {
                val nuevo = firstArg<(String?) -> String?>()(raw)
                escrituras++
                if (nuevo == null || fallaDesde?.let { escrituras >= it } == true) false
                else { raw = nuevo; true }
            }
        }
        fun nuevo() = SolicitudesDeTarjetaStore(storage)
    }
    @Test fun reservaInvisibleDuranteEntregaSobreviveAlReinicioSinPost() = runBlocking {
        val m = MemoriaDeEntrega(); val diario = m.nuevo()
        val s = fila(nueva = true).copy(porRecuperar = true, cuerpo = "")
        assertTrue(diario.guardar(s))
        assertTrue("Debe reservar la misma plaza antes de entregar", diario.reservarEntrega("k"))
        assertTrue(SolicitudesDeTarjeta.leer(m.raw).isEmpty())
        assertEquals(listOf(s), m.nuevo().delNegocio("a"))
        val repo = mockk<GiftCardsRepository>()
        val c = CobroConSaldoCoordinator(repo, m.nuevo(), m.storage, mockk())
        assertEquals(DesenlaceDeCobro.Incierto, c.resolver("k"))
        coVerify(exactly = 0) { repo.cobrarNuevaCuenta(any(), any()) }
        assertEquals(0, server.requestCount)
    }
    @Test fun confirmacionFallidaNoRestauraDosVeces() = runBlocking {
        val m = MemoriaDeEntrega(); val diario = m.nuevo()
        val s = fila(nueva = true).copy(porRecuperar = true, cuerpo = "")
        assertTrue(diario.guardar(s)); m.fallaDesde = 3
        val c = CobroConSaldoCoordinator(mockk(), diario, m.storage, mockk())
        var restauraciones = 0
        val r = c.recuperarVenta("k", { true }, { restauraciones++; assertTrue(diario.delNegocio("a").isEmpty()); true })
        assertEquals(Recuperacion.PENDIENTE_DE_CONFIRMAR, r)
        assertFalse(c.confirmarEntrega("k"))
        m.fallaDesde = null
        assertTrue(c.confirmarEntrega("k"))
        assertEquals(1, restauraciones)
        assertTrue(m.nuevo().delNegocio("a").isEmpty())
        assertEquals(0, server.requestCount)
    }
    @Test fun reservaConservaTopeUnknownsYSoloT1() {
        val m = MemoriaDeEntrega(); val diario = m.nuevo()
        assertTrue(diario.guardar(fila().copy(porRecuperar = true, cuerpo = "")))
        assertFalse("T2 jamás entrega foto", diario.reservarEntrega("k"))
        diario.quitar("k")
        val s = fila(nueva = true).copy(porRecuperar = true, cuerpo = "")
        assertTrue(diario.guardar(s))
        val unknown = List(49) { buildJsonObject { put("v", 9); put("tipo", "FUTURO"); put("llave", "f$it"); put("dato", it) } }
        val original = Json.parseToJsonElement(m.raw!!).jsonArray
        m.raw = JsonArray(original + unknown).toString()
        assertTrue(diario.reservarEntrega("k"))
        val despues = Json.parseToJsonElement(m.raw!!).jsonArray
        assertEquals(50, despues.size)
        assertEquals(unknown, despues.drop(1))
        assertEquals(listOf(s), m.nuevo().delNegocio("a"))
    }

    @Test fun entregasPendientesDeDosNegociosConservanSuConfirmacionAlVolver() = runBlocking {
        val m=MemoriaDeEntrega();val diario=m.nuevo()
        val c=CobroConSaldoCoordinator(mockk(),diario,m.storage,mockk())
        for (venue in listOf("a","b")) {
            every { m.storage.venueId } returns venue
            m.fallaDesde=null
            assertTrue(diario.guardar(fila(nueva=true).copy(llave="entrega-$venue",venueId=venue,porRecuperar=true,cuerpo="")))
            m.fallaDesde=m.escrituras+2
            assertEquals(Recuperacion.PENDIENTE_DE_CONFIRMAR,c.recuperarVenta("entrega-$venue",{true},{true}))
        }
        every { m.storage.venueId } returns "a"
        assertEquals("Al volver debe poder confirmar la entrega de A, no la de B", "entrega-a", c.entregaPendienteDelNegocio())
        m.fallaDesde=null
        assertTrue(c.confirmarEntrega("entrega-a"))
        assertNull(c.entregaPendienteDelNegocio())
        every { m.storage.venueId } returns "b"
        assertEquals("entrega-b",c.entregaPendienteDelNegocio())
        assertTrue(c.confirmarEntrega("entrega-b"))
        assertTrue(m.nuevo().delNegocio("b").isEmpty())
        assertEquals(0,server.requestCount)
    }

    @Test fun confirmarUnaEntregaCuyoDestinoDesaparecioConservaLaFoto() = runBlocking {
        val m=MemoriaDeEntrega(); val diario=m.nuevo(); var vigente=true
        val s=fila(nueva=true).copy(porRecuperar=true,cuerpo="")
        assertTrue(diario.guardar(s)); m.fallaDesde=3
        val c=CobroConSaldoCoordinator(mockk(),diario,m.storage,mockk())
        assertEquals(Recuperacion.PENDIENTE_DE_CONFIRMAR,c.recuperarVenta("k",{true},destinoVigente={vigente},restaurar={true}))
        vigente=false; m.fallaDesde=null
        assertFalse("Confirmar nunca borra la foto si el destino ya desapareció",c.confirmarEntrega("k"))
        assertEquals(listOf(s),diario.delNegocio("a"))
        assertEquals(listOf(s),m.nuevo().delNegocio("a"))
        assertEquals(0,server.requestCount)
    }

}
