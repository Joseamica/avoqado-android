package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * C4 (R4-1, R5#6, R5#7, R3#8, R3#12): la tabla `cobros_en_maquina` ES la cola de la máquina. La subida contra un
 * MockWebServer con el contrato FINAL del SERVIDOR y el DAO sobre SQLite de verdad ([CobroEnMaquinaSobreSqlite]).
 */
class SubidaDeLaMaquinaTest {

    private val servidor = MockWebServer()
    /** Sin reintento silencioso de OkHttp: un corte de red tiene que llegar como corte, no gastar la respuesta siguiente. */
    private val okHttp = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val dao = CobroEnMaquinaSobreSqlite()
    private val red = MutableStateFlow(true)
    private val conectividad = mockk<ConnectivityMonitor> {
        every { isConnected } returns red
        every { isServerReachable } returns MutableStateFlow(true)
    }
    private val sesion = mockk<SecureStorage> { every { venueId } returns "venue-1" }
    private val boveda = object : BovedaDeLaMaquina {
        var credencial: String? = "cred-1"
        override fun guardar(credencial: String) { this.credencial = credencial }
        override fun leer(): String? = credencial
        override fun borrar() { credencial = null }
    }
    private val contexto = AjustesDeLaMaquinaTest.contextoConPreferencias()
    private lateinit var api: CierresDeLaMaquinaApi
    private lateinit var subida: SubidaDeLaMaquina

    /** Las respuestas por ruta (el orden entre rutas no importa); sin respuesta preparada, 503 (se reintenta). */
    private val guion = mutableListOf<Pair<(RecordedRequest) -> Boolean, ArrayDeque<MockResponse>>>()

    private fun cuando(metodo: String, ruta: String, vararg respuestas: MockResponse) = synchronized(guion) {
        guion += { q: RecordedRequest -> q.method == metodo && q.path!!.matches(Regex(ruta)) } to ArrayDeque(respuestas.toList())
    }

    @Before
    fun preparar() {
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(guion) {
                guion.firstOrNull { it.first(request) && it.second.isNotEmpty() }?.second?.removeFirst()
            } ?: MockResponse().setResponseCode(503).setBody("""{"message":"sin respuesta preparada"}""")
        }
        servidor.start()
        api = CierresDeLaMaquinaApi(okHttp).apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') }
        subida = SubidaDeLaMaquina(
            dao, api, boveda, sesion, TurnoDelNegocio(contexto, api, sesion), MaquinaDeEfectivoPrefs(contexto), conectividad,
        )
    }

    @After
    fun apagar() {
        subida.stop()
        runCatching { servidor.shutdown() }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
        dao.close()
    }

    // MARK: - Filas y respuestas

    private val pedido = """{"items":[{"productId":"p1","quantity":1}],"externalId":"venta-1"}"""

    private fun final(ref: String, estado: String, credit: Int, change: Int, cancel: String? = null, venue: String = "venue-1") =
        CobroEnMaquinaEntity(
            referenceCC = ref, venueId = venue, llaveDeVenta = "venta-$ref", pedidoJson = pedido, saleCents = 18_000,
            tipCents = 750, dueCents = 18_750, saleShiftId = "turno-1", quoteId = "cot-1", creadoEn = 1_000, estado = estado,
            creditCents = credit, changeDispensedCents = change, cancelPedido = cancel, cierreEn = 1_763_640_000_000,
            cierreConocido = true, resueltoPor = "MAQUINA",
        )

    private fun duda(ref: String) = CobroEnMaquinaEntity(
        referenceCC = ref, venueId = "venue-1", llaveDeVenta = "venta-$ref", pedidoJson = pedido, saleCents = 18_000,
        tipCents = 750, dueCents = 18_750, saleShiftId = "turno-1", quoteId = null, creadoEn = 1_763_639_000_000,
        estado = EstadoDelIntento.ESPERANDO, mensajesCrudos = """{"comando":"sockets","cobrando":true}""",
    )

    private val pagado = final(REF_1, EstadoDelIntento.PAGADO, credit = 20_000, change = 1_250)

    private fun evidencia(f: CobroEnMaquinaEntity, outcome: String = f.estado, credit: Int = f.creditCents, change: Int = f.changeDispensedCents) =
        JSONObject().put("success", true).put("ack", "TERMINADO").put("deviceId", DISPOSITIVO)
            .put("referenceCC", f.referenceCC).put("outcome", outcome).put("resolvedBy", "MAQUINA")
            .put("owedKind", JSONObject.NULL).put("orderId", "orden-1").put("paymentId", "pago-1").put("dueCents", f.dueCents)
            .put("saleCents", f.saleCents).put("tipCents", f.tipCents).put("appliedCents", f.saleCents).put("excedenteCents", 0)
            .put("creditCents", credit).put("changeDispensedCents", change).put("owedCents", 0).put("shiftStatus", "EN_TURNO")

    /** Rediseño del SERVIDOR: la duda ABIERTA es un acuse RECIBIDO de ESTA PC, con la `version` de su evidencia. */
    private fun abierta(version: Int, ref: String = REF_1) = JSONObject().put("success", true).put("status", "ABIERTO").put("ack", "RECIBIDO")
        .put("referenceCC", ref).put("deviceId", DISPOSITIVO).put("incidentId", "cinc0000000000000000duda1").put("version", version)

    private fun ok(cuerpo: JSONObject, codigo: Int = 200) = MockResponse().setResponseCode(codigo).setBody(cuerpo.toString())

    private fun error(codigo: Int, mensaje: String, code: String) =
        MockResponse().setResponseCode(codigo).setBody(JSONObject().put("message", mensaje).put("code", code).toString())

    private fun insertar(vararg filas: CobroEnMaquinaEntity) = runBlocking { filas.forEach { dao.insertar(it) } }

    private fun cuerpo(q: RecordedRequest) = JSONObject(q.body.readUtf8())

    /** Con plazo: si la petición nunca sale, la prueba falla en vez de quedarse colgada. */
    private fun pedido(): RecordedRequest = servidor.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("no llegó la petición")

    // MARK: - Cierres finales

    @Test
    fun `PAGADO sube con su pedido, sus centavos y la credencial, y se marca subido solo con el eco`() = runBlocking {
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado)))

        assertFalse("ya no queda trabajo", subida.subir())

        val q = pedido()
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/sales", q.path)
        assertEquals("cred-1", q.getHeader(CierresDeLaMaquinaApi.CREDENCIAL))
        assertEquals("1", q.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
        val b = cuerpo(q)
        assertEquals(REF_1, b.getString("referenceCC"))
        assertEquals(JSONObject(pedido).toString(), b.getJSONObject("pedido").toString())
        val c = b.getJSONObject("cierre")
        assertEquals("PAGADO", c.getString("outcome"))
        assertEquals(listOf(18_750, 18_000, 750, 20_000, 1_250), listOf("dueCents", "saleCents", "tipCents", "creditCents", "changeDispensedCents").map(c::getInt))
        assertTrue(c.getBoolean("occurredAtKnown"))
        assertEquals("2025-11-20T12:00:00Z", c.getString("occurredAt"))
        assertEquals("turno-1", c.getString("saleShiftId"))
        assertFalse("PAGADO no lleva modo de cancelación", c.has("cancelMode"))

        val fila = dao.fila(REF_1)!!
        assertTrue(fila.subido)
        assertNull(fila.conflicto)
        assertEquals(0, dao.pendientes("venue-1"))
        assertEquals("el eco completo queda en la fila (C7 lee el excedente)", 0, JSONObject(fila.respuestaServidor!!).getInt("excedenteCents"))
        assertEquals("PAGADO", JSONObject(fila.respuestaServidor!!).getString("outcome"))
    }

    @Test
    fun `P1 Codex r1 #15 un cierre sin la hora de la maquina sube sin hora, nunca con la de la PC como conocida`() = runBlocking {
        insertar(pagado.copy(cierreConocido = false))
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado)))

        subida.subir()

        val c = cuerpo(pedido()).getJSONObject("cierre")
        assertFalse(c.getBoolean("occurredAtKnown"))
        assertTrue(c.isNull("occurredAt"))
    }

    @Test
    fun `Codex r1 #13 lo que el servidor dice que se debe queda en la fila (cambio fisico mas diferencia de precio)`() = runBlocking {
        insertar(pagado.copy(aDeberCents = 0))
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado).put("excedenteCents", 300).put("owedCents", 300)))

        subida.subir()

        assertEquals(300, dao.fila(REF_1)!!.aDeberCents)
    }

    @Test
    fun `P1 Codex r2 H15 CANCELADO y GUARDADO suben CON su pedido (el primer mensaje lo congela) y con el modo de su cancel`() = runBlocking {
        val cancelado = final(REF_1, EstadoDelIntento.CANCELADO, credit = 5_000, change = 5_000, cancel = "1")
        val guardado = final(REF_2, EstadoDelIntento.GUARDADO, credit = 5_000, change = 0, cancel = "2").copy(creadoEn = 2_000)
        insertar(cancelado, guardado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(cancelado)), ok(evidencia(guardado)))

        subida.subir()

        val primero = cuerpo(pedido())
        val segundo = cuerpo(pedido())
        assertEquals(JSONObject(pedido).toString(), primero.getJSONObject("pedido").toString())
        assertEquals(JSONObject(pedido).toString(), segundo.getJSONObject("pedido").toString())
        assertEquals("CANCELADO" to "1", primero.getJSONObject("cierre").let { it.getString("outcome") to it.getString("cancelMode") })
        assertEquals("GUARDADO" to "2", segundo.getJSONObject("cierre").let { it.getString("outcome") to it.getString("cancelMode") })
        assertTrue(dao.fila(REF_1)!!.subido && dao.fila(REF_2)!!.subido)
    }

    @Test
    fun `si la respuesta no repite el folio y los importes no se marca, se ve y no se reintenta`() = runBlocking {
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado, credit = 25_000)))

        assertFalse(subida.subir())

        val fila = dao.fila(REF_1)!!
        assertFalse(fila.subido)
        assertEquals(TEXTO_RESPUESTA_DISTINTA, fila.conflicto)
        assertEquals("el freno de cerrar sesión la sigue contando", 1, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals("no se vuelve a mandar", 1, servidor.requestCount)
    }

    @Test
    fun `202 con su incidencia se marca subido y deja visible que el dinero quedo como incidencia`() = runBlocking {
        insertar(pagado)
        val sinRegistro = JSONObject().put("success", true).put("ack", "TERMINADO").put("deviceId", DISPOSITIVO)
            .put("referenceCC", REF_1).put("incident", "NO_SE_PUDO_REGISTRAR")
            .put("motivo", "VENTA_RECHAZADA").put("outcome", "PAGADO").put("resolvedBy", "MAQUINA").put("dueCents", 18_750)
            .put("saleCents", 18_000).put("tipCents", 750).put("creditCents", 20_000).put("changeDispensedCents", 1_250)
            .put("incidentId", "cinc000000000000000000009")
        cuando("POST", ".*/cash-machine/sales", ok(JSONObject(sinRegistro.toString()).put("incidentId", JSONObject.NULL), 202), ok(sinRegistro, 202))

        assertTrue("un 202 sin su incidencia durable no libera la fila: se repite el folio", subida.subir())
        assertFalse(dao.fila(REF_1)!!.subido)
        assertNull(dao.fila(REF_1)!!.conflicto)

        assertFalse(subida.subir())

        val fila = dao.fila(REF_1)!!
        assertTrue(fila.subido)
        assertEquals("Registrado como incidencia: la venta ya no se pudo armar", fila.conflicto)
        assertEquals(0, dao.pendientes("venue-1"))
    }

    private fun errorConIncidencia(codigo: Int, mensaje: String, code: String, incidentId: String) =
        MockResponse().setResponseCode(codigo).setBody(
            JSONObject().put("message", mensaje).put("code", code)
                .put("details", JSONObject().put("motivo", "OTRA_EVIDENCIA").put("incidentId", incidentId)).toString(),
        )

    @Test
    fun `409 y 422 con su incidencia en el servidor se marcan subidos, quedan visibles y ya no frenan cerrar sesion`() = runBlocking {
        val otro = final(REF_2, EstadoDelIntento.PAGADO, credit = 18_750, change = 0).copy(creadoEn = 2_000)
        insertar(pagado, otro)
        cuando(
            "POST", ".*/cash-machine/sales",
            errorConIncidencia(409, "Este folio de la máquina ya se registró con otros importes", "CASH_MACHINE_CLOSE_CONFLICT", "cinc000000000000000000001"),
            errorConIncidencia(422, "Los importes de este cierre de la máquina no son posibles", "CASH_MACHINE_CLOSE_IMPOSSIBLE", "cinc000000000000000000002"),
        )

        assertFalse(subida.subir())

        val conflicto = dao.fila(REF_1)!!
        val imposible = dao.fila(REF_2)!!
        assertEquals("Este folio de la máquina ya se registró con otros importes", conflicto.conflicto)
        assertEquals("Los importes de este cierre de la máquina no son posibles", imposible.conflicto)
        assertTrue("la evidencia ya es durable en el servidor", conflicto.subido && imposible.subido)
        assertEquals("cinc000000000000000000001", JSONObject(conflicto.respuestaServidor!!).getJSONObject("details").getString("incidentId"))
        assertEquals("ya no frena cerrar sesión", 0, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals("no se reintenta", 2, servidor.requestCount)
    }

    @Test
    fun `un 400 sin incidencia queda visible, sin acuse y sin reintento`() = runBlocking {
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", error(400, "El pedido debe ser un objeto", "VALIDATION_ERROR"))

        assertFalse(subida.subir())

        val fila = dao.fila(REF_1)!!
        assertEquals("El pedido debe ser un objeto", fila.conflicto)
        assertFalse("el dinero sólo está en esta PC", fila.subido)
        assertNull(fila.respuestaServidor)
        assertEquals("sigue frenando cerrar sesión", 1, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals(1, servidor.requestCount)
    }

    @Test
    fun `el 409 de reintentar el mismo folio se reintenta y no queda como conflicto`() = runBlocking {
        insertar(pagado)
        cuando(
            "POST", ".*/cash-machine/sales",
            error(409, "Otro registro de este folio estaba en curso. Intenta otra vez.", "CASH_MACHINE_CLOSE_RETRY"),
            ok(evidencia(pagado)),
        )

        assertTrue("queda trabajo", subida.subir())
        assertNull(dao.fila(REF_1)!!.conflicto)
        assertFalse(dao.fila(REF_1)!!.subido)

        assertFalse(subida.subir())
        assertTrue(dao.fila(REF_1)!!.subido)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `403 sin emparejar se ve, la fila no cambia y sube despues de emparejar`() = runBlocking {
        insertar(pagado)
        cuando(
            "POST", ".*/cash-machine/sales",
            error(403, "Esta PC no está emparejada con la máquina de efectivo", "CASH_MACHINE_NOT_PAIRED"),
            ok(evidencia(pagado)),
        )

        assertTrue("queda trabajo: se reintenta", subida.subir())
        assertEquals(TEXTO_PC_SIN_EMPAREJAR, subida.aviso.value)
        assertFalse(dao.fila(REF_1)!!.subido)
        assertNull(dao.fila(REF_1)!!.conflicto)

        assertFalse(subida.subir())
        assertTrue(dao.fila(REF_1)!!.subido)
        assertNull(subida.aviso.value)
    }

    @Test
    fun `sin credencial no se manda nada y se dice que falta emparejar`() = runBlocking {
        insertar(pagado)
        boveda.credencial = null

        assertTrue(subida.subir())

        assertEquals(0, servidor.requestCount)
        assertEquals(TEXTO_PC_SIN_EMPAREJAR, subida.aviso.value)
        assertFalse(dao.fila(REF_1)!!.subido)
    }

    @Test
    fun `sin red no se marca nada y la siguiente pasada la sube`() = runBlocking {
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START), ok(evidencia(pagado)))

        assertTrue(subida.subir())
        assertFalse(dao.fila(REF_1)!!.subido)
        assertNull(dao.fila(REF_1)!!.conflicto)

        assertFalse(subida.subir())
        assertTrue(dao.fila(REF_1)!!.subido)
    }

    @Test
    fun `solo sube lo de la sucursal de la sesion`() = runBlocking {
        insertar(final(REF_1, EstadoDelIntento.PAGADO, credit = 18_750, change = 0, venue = "venue-2"))

        assertFalse("lo de otra sucursal no es trabajo de esta sesión", subida.subir())

        assertEquals(0, servidor.requestCount)
        assertEquals("pero sigue frenando en su sucursal", 1, dao.pendientes("venue-2"))
    }

    // MARK: - Dudas (R5#6)

    @Test
    fun `la duda sube con su carrito y se consulta hasta que el gerente la resuelve`() = runBlocking {
        insertar(duda(REF_1))
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 5_000, change = 0)
            .put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1)), ok(abierta(2)), ok(resuelta))

        assertTrue("la duda espera al gerente", subida.subir())
        assertEquals("la primera pasada sólo la sube", 1, servidor.requestCount)
        val q = pedido()
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", q.path)
        assertEquals("cred-1", q.getHeader(CierresDeLaMaquinaApi.CREDENCIAL))
        val b = cuerpo(q)
        assertEquals(REF_1, b.getString("referenceCC"))
        assertEquals(listOf(18_750, 18_000, 750), listOf("dueCents", "saleCents", "tipCents").map(b::getInt))
        assertEquals(JSONObject(pedido).toString(), b.getJSONObject("pedido").toString())
        assertEquals("2025-11-20T11:43:20Z", b.getString("startedAt"))
        assertEquals("Entró \$0.00\n" + """{"comando":"sockets","cobrando":true}""", b.getString("lastSeen"))
        assertEquals("turno-1", b.getString("saleShiftId"))
        assertTrue(dao.fila(REF_1)!!.dudaSubida)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)
        assertEquals("r6-4: la versión que mandará el gerente", 1, dao.fila(REF_1)!!.versionDeLaDuda)
        assertEquals("aprendió qué aparato es esta PC para el SERVIDOR", DISPOSITIVO, MaquinaDeEfectivoPrefs(contexto).dispositivo)

        assertTrue("el gerente todavía no decide", subida.subir())
        val reenvio = pedido()
        assertEquals("r6-1: la duda se REENVÍA (reanuda un desenlace pendiente y agrega lo nuevo)", "POST", reenvio.method)
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", reenvio.path)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)
        assertEquals(2, dao.fila(REF_1)!!.versionDeLaDuda)

        assertFalse("ya no queda nada", subida.subir())
        val fila = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.RESUELTO, fila.estado)
        assertEquals("GERENTE", fila.resueltoPor)
        assertEquals(5_000 to 0, fila.creditCents to fila.changeDispensedCents)
        assertEquals("para el comprobante (C7)", Desenlace.CANCELADO, fila.desenlace)
        assertNull("cancelPedido sigue siendo sólo lo que pidió la PC (nada)", fila.cancelPedido)
        assertEquals("CANCELADO", JSONObject(fila.respuestaServidor!!).getString("outcome"))
        assertNull(fila.conflicto)
        assertEquals("la duda resuelta ya no frena", 0, dao.pendientes("venue-1"))
    }

    @Test
    fun `una duda con otros importes en el servidor queda visible y no se reintenta`() = runBlocking {
        insertar(duda(REF_1))
        cuando("POST", ".*/cash-machine/doubts", error(409, "Esta duda de la máquina ya se subió con otros importes", "CASH_MACHINE_DOUBT_CONFLICT"))

        assertFalse(subida.subir())

        assertEquals("Esta duda de la máquina ya se subió con otros importes", dao.fila(REF_1)!!.conflicto)
        assertFalse(dao.fila(REF_1)!!.dudaSubida)
        subida.subir()
        assertEquals(1, servidor.requestCount)
    }

    @Test
    fun `P1 Codex r1 #6 la duda sube lo ultimo que quedo en disco, con lo que entro`() = runBlocking {
        insertar(duda(REF_1).copy(creditCents = 5_000, ultimoCreditoEn = 1_763_639_050_000))
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1)))

        subida.subir()

        val b = cuerpo(pedido())
        val lastSeen = b.getString("lastSeen")
        assertEquals("Codex r2 H13: sin la hora de la PC; sólo lo que entró y el diario", "Entró \$50.00\n" + """{"comando":"sockets","cobrando":true}""", lastSeen)
        assertEquals("Codex r3 #9: y lo que entró, en número", 5_000, b.getInt("creditCents"))
    }

    @Test
    fun `P1 Codex r2 H2 una duda con un cierre en su diario sube como duda, nunca como cierre`() = runBlocking {
        insertar(duda(REF_1).copy(mensajesCrudos = "g1 " + JSONObject().put("comando", "status").put("Status", "Pago Completado").toString()))
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1)))

        subida.subir()

        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", pedido().path)
        assertEquals(1, servidor.requestCount)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)
    }

    @Test
    fun `P1 Codex r1 #9 una duda en conflicto con su incidencia ya no frena y se sigue consultando por su folio`() = runBlocking {
        insertar(duda(REF_1))
        cuando(
            "POST", ".*/cash-machine/doubts",
            MockResponse().setResponseCode(409).setBody(
                JSONObject().put("message", "Esta duda de la máquina ya se subió con otros importes o desde otra PC")
                    .put("code", "CASH_MACHINE_DOUBT_CONFLICT")
                    .put("details", JSONObject().put("motivo", "OTRA_EVIDENCIA").put("deviceId", DISPOSITIVO)
                        .put("incidentId", "cinc000000000000000000004")).toString(),
            ),
        )
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 5_000, change = 5_000)
            .put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("GET", ".*/cash-machine/doubts/$REF_1", ok(resuelta))

        assertTrue("se sigue consultando", subida.subir())
        val fila = dao.fila(REF_1)!!
        assertEquals("Esta duda de la máquina ya se subió con otros importes o desde otra PC", fila.conflicto)
        assertTrue(fila.dudaSubida)
        // Codex código r3 #2: pero sigue ABIERTA y es de esta PC: frena salir hasta que el gerente la resuelva.
        assertEquals("una duda propia abierta frena cerrar sesión", 1, dao.pendientes("venue-1"))

        assertFalse(subida.subir())

        val resueltaAqui = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.RESUELTO, resueltaAqui.estado)
        assertEquals("CANCELADO", resueltaAqui.desenlaceDelServidor)
        assertEquals("GET", servidor.takeRequest().let { servidor.takeRequest() }.method)
    }

    @Test
    fun `P1 Codex r2 H8 una duda cuyo folio es de OTRA PC deja de consultarse y su propio cierre posterior SI sube`() = runBlocking {
        insertar(duda(REF_1))
        val deOtraPc = MockResponse().setResponseCode(409).setBody(
            JSONObject().put("message", "Este folio de la máquina ya tiene otro desenlace o es de otra PC").put("code", "CASH_MACHINE_CLOSE_CONFLICT")
                .put("details", JSONObject().put("motivo", "OTRA_PC").put("deviceId", "la-otra-pc").put("incidentId", "cinc000000000000000000009"))
                .toString(),
        )
        cuando("POST", ".*/cash-machine/doubts", deOtraPc, deOtraPc)

        assertFalse("no se consulta el folio de la otra PC: no queda trabajo", subida.subir())
        assertTrue(dao.fila(REF_1)!!.dudaSubida)
        assertFalse(subida.subir())

        // Después llega a esta PC su propio cierre físico: va al diario y vuelve a subir, para que el SERVIDOR lo tenga.
        assertTrue(dao.anotarMensaje(REF_1, 1, """{"comando":"status","Status":"Pago Completado","referenceCC":"$REF_1"}""", 20_000, 18_750))
        // Codex código POS r4 #4: esa evidencia NUEVA sólo está aquí: frena salir hasta entregarla.
        assertEquals(1, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals("entregada (otra vez su incidencia): ya no frena", 0, dao.pendientes("venue-1"))

        val pedidos = (1..servidor.requestCount).map { pedido() }
        assertEquals(listOf("POST", "POST"), pedidos.map { it.method })
        assertTrue(cuerpo(pedidos.last()).getString("lastSeen").contains("Pago Completado"))
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)
    }

    @Test
    fun `P1 Codex r2 H8 un cierre cuyo folio el servidor atribuye a OTRA PC nunca queda como subido, se ve y no se reintenta`() = runBlocking {
        insertar(pagado)
        cuando(
            "POST", ".*/cash-machine/sales",
            MockResponse().setResponseCode(409).setBody(
                JSONObject().put("message", "Este folio de la máquina ya tiene otro desenlace o es de otra PC").put("code", "CASH_MACHINE_CLOSE_CONFLICT")
                    .put("details", JSONObject().put("motivo", "OTRA_PC").put("deviceId", "la-otra-pc").put("incidentId", "cinc000000000000000000010"))
                    .toString(),
            ),
        )

        assertFalse(subida.subir())

        val fila = dao.fila(REF_1)!!
        assertFalse("la otra PC es la dueña del folio", fila.subido)
        assertEquals("Este folio de la máquina ya tiene otro desenlace o es de otra PC", fila.conflicto)
        assertEquals("su incidencia ya está en el SERVIDOR: no frena la sesión", 0, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals("no se reintenta", 1, servidor.requestCount)
    }

    @Test
    fun `P1 Codex r2 H1 un acuse de la evidencia anterior no marca como subida la observacion que llego mientras tanto`() = runBlocking {
        insertar(duda(REF_1))
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Mientras el SERVIDOR contesta la duda, la PC anota una observación nueva en el diario.
                runBlocking { dao.anotarMensaje(REF_1, 1, """{"comando":"status","Credit":50,"referenceCC":"$REF_1"}""", 5_000, 18_750) }
                return ok(abierta(1))
            }
        }

        assertTrue("queda la observación nueva por subir", subida.subir())

        assertFalse(dao.fila(REF_1)!!.dudaSubida)
        assertNull("la versión de un acuse viejo tampoco se guarda", dao.fila(REF_1)!!.versionDeLaDuda)
    }

    // MARK: - Disparadores (R3#12, R5#6)

    @Test
    fun `cada 60 s se consulta sola mientras hay una duda, y sin trabajo no hay reloj`() = runBlocking {
        insertar(duda(REF_1))
        val resuelta = evidencia(duda(REF_1), outcome = "PAGADO", credit = 20_000, change = 1_250)
            .put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1)), ok(abierta(1)), ok(resuelta))
        subida.cadaMs = 100

        subida.start()

        esperarA { dao.fila(REF_1)!!.estado == EstadoDelIntento.RESUELTO }
        assertEquals(Desenlace.PAGADO, dao.fila(REF_1)!!.desenlace)
        delay(600)
        assertEquals("resuelta, ya no se pregunta", 3, servidor.requestCount)
    }

    @Test
    fun `al volver la red sube sin reiniciar la app y sin otra venta`() = runBlocking {
        insertar(pagado)
        red.value = false
        cuando("POST", ".*/cash-machine/sales", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START), ok(evidencia(pagado)))
        subida.esperaDeReconexionMs = 0

        subida.start()
        esperarA { servidor.requestCount == 1 }
        delay(200)
        assertFalse("el reloj de 60 s no ha corrido", dao.fila(REF_1)!!.subido)

        red.value = true

        esperarA { dao.fila(REF_1)!!.subido }
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `disparar despues de un cierre sube aunque no hubiera trabajo`() = runBlocking {
        subida.start()
        delay(200)
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado)))

        subida.disparar()

        esperarA { dao.fila(REF_1)!!.subido }
    }

    // MARK: - Turno del negocio (R3#8)

    @Test
    fun `el turno del negocio se guarda con red, es de su sucursal y sin respuesta se conserva`() = runBlocking {
        val turno = TurnoDelNegocio(contexto, api, sesion)
        cuando(
            "GET", ".*/shifts/business-current",
            MockResponse().setBody("""{"shiftId":"turno-9","startTime":"2026-11-20T14:00:00.000Z"}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            MockResponse().setBody("null"),
        )

        assertEquals("turno-9", turno.refrescar())
        assertEquals("/api/v1/mobile/venues/venue-1/shifts/business-current", pedido().path)
        assertEquals("otra instancia lo lee del disco", "turno-9", TurnoDelNegocio(contexto, api, sesion).turnoActual())

        assertEquals("sin red se queda el guardado", "turno-9", turno.refrescar())

        every { sesion.venueId } returns "venue-2"
        assertNull("es de otra sucursal", turno.turnoActual())
        every { sesion.venueId } returns "venue-1"

        assertNull("no hay turno abierto", turno.refrescar())
        assertNull(turno.turnoActual())
    }

    private suspend fun esperarA(condicion: () -> Boolean) {
        repeat(100) {
            if (condicion()) return
            delay(50)
        }
        throw AssertionError("no pasó en 5 s")
    }

    // MARK: - Adaptación al rediseño del SERVIDOR (r3 #7, r6-1, r6-6)

    @Test
    fun `P1 un 202 RECIBIDO se conserva y se reenvia el MISMO mensaje hasta el TERMINADO`() = runBlocking {
        insertar(pagado)
        val recibido = JSONObject().put("success", true).put("ack", "RECIBIDO").put("referenceCC", REF_1).put("deviceId", DISPOSITIVO)
            .put("outcome", "PAGADO")
        cuando("POST", ".*/cash-machine/sales", ok(recibido, 202), ok(evidencia(pagado)))

        assertTrue("queda trabajo", subida.subir())
        assertFalse(dao.fila(REF_1)!!.subido)
        assertNull(dao.fila(REF_1)!!.conflicto)
        assertEquals("sigue frenando cerrar sesión", 1, dao.pendientes("venue-1"))

        assertFalse(subida.subir())
        assertTrue(dao.fila(REF_1)!!.subido)
        assertEquals("el mismo mensaje", cuerpo(pedido()).toString(), cuerpo(pedido()).toString())
    }

    @Test
    fun `P1 r6-6 el cierre y la duda llevan el emparejamiento con que ESTA PC empezo el cobro`() = runBlocking {
        insertar(pagado.copy(bindingId = "cbind0000000000000000000a"), duda(REF_2).copy(bindingId = "cbind0000000000000000000b"))
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado)))
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1, REF_2)))

        subida.subir()

        val cuerpos = listOf(pedido(), pedido()).associate { it.path!!.substringAfterLast('/') to cuerpo(it) }
        assertEquals("cbind0000000000000000000a", cuerpos.getValue("sales").getString("bindingId"))
        assertEquals("cbind0000000000000000000b", cuerpos.getValue("doubts").getString("bindingId"))
    }

    @Test
    fun `P1 r3 #7 la consulta de una duda en conflicto solo la retira si el folio es de ESTA PC`() = runBlocking {
        val conflicto = duda(REF_1).copy(dudaSubida = true, conflicto = "en conflicto", respuestaServidor = "{}")
        insertar(conflicto)
        MaquinaDeEfectivoPrefs(contexto).recordarDispositivo(DISPOSITIVO)
        val deOtra = evidencia(conflicto, outcome = "CANCELADO", credit = 5_000, change = 0).put("status", "RESUELTO").put("deviceId", "otra-pc")
        val mia = JSONObject(deOtra.toString()).put("deviceId", DISPOSITIVO)
        cuando("GET", ".*/cash-machine/doubts/$REF_1", ok(deOtra), ok(mia))

        assertTrue(subida.subir())
        assertEquals("el folio es de otra PC: no se retira", EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)

        assertFalse(subida.subir())
        assertEquals(EstadoDelIntento.RESUELTO, dao.fila(REF_1)!!.estado)
    }

    @Test
    fun `un TERMINADO sin el aparato duenio no retira el pendiente`() = runBlocking {
        insertar(pagado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado).put("deviceId", JSONObject.NULL)))

        assertTrue(subida.subir())

        assertFalse(dao.fila(REF_1)!!.subido)
    }

    // MARK: - Codex código r3

    private fun statusDe(ref: String, credit: Int) =
        JSONObject().put("comando", "status").put("cobrando", true).put("referenceCC", ref).put("Credit", credit / 100.0)
            .put("Due", 187.5).toString()

    @Test
    fun `P1 Codex r3 #1 una resolucion anterior no borra el billete que llego despues - la venta sigue frenada y la evidencia sube`() = runBlocking {
        // El SERVIDOR ya tiene la versión 1 de la duda: $0.
        insertar(duda(REF_1).copy(dudaSubida = true, versionDeLaDuda = 1))
        // Llega el aviso de la QuickPay: $100 de este folio. Todavía no sube.
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDe(REF_1, 10_000), 10_000, 18_750))
        // Y llega la resolución que el gerente firmó con la versión 1: «Cancelado, $0».
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 0, change = 0).put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        assertTrue(dao.resolverConElEco(dao.fila(REF_1)!!, resuelta, resuelta.toString()))

        val resueltaAqui = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.RESUELTO, resueltaAqui.estado)
        assertEquals("lo que entró no baja", 10_000, resueltaAqui.creditCents)
        assertEquals("la venta sigue frenada", 1, dao.bloqueanLaVenta(resueltaAqui.llaveDeVenta))
        assertEquals("la evidencia sigue por subir", listOf(REF_1), dao.paraSubir("venue-1").map { it.referenceCC })
        // Un mensaje físico que llega DESPUÉS de la resolución tampoco se tira.
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDe(REF_1, 12_000), 12_000, 18_750))
        assertEquals(12_000, dao.fila(REF_1)!!.creditCents)

        // Sube como duda (el SERVIDOR abre la revisión TRAS_RESOLVER) y contesta el eco del folio ya resuelto.
        cuando("POST", ".*/cash-machine/doubts", ok(resuelta))
        assertFalse(subida.subir())

        val q = pedido()
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", q.path)
        val b = cuerpo(q)
        assertEquals("Codex r3 #9: lo que entró, en número", 12_000, b.getInt("creditCents"))
        assertTrue(b.getString("lastSeen").startsWith("Entró \$120.00"))
        val entregada = dao.fila(REF_1)!!
        assertTrue(entregada.dudaSubida)
        assertEquals(EstadoDelIntento.RESUELTO, entregada.estado)
        assertEquals("CANCELADO", entregada.desenlaceDelServidor)
        // Codex código POS r4 #1: el eco de la resolución ANTERIOR («Cancelado, $0») no cubre los $120 que contó la máquina: la venta
        // nunca vuelve a admitir tarjeta por eso. La evidencia ya está allá: salir sí.
        assertEquals("la máquina contó más de lo que dice la resolución", 1, dao.bloqueanLaVenta(entregada.llaveDeVenta))
        assertEquals("lo entregado no frena salir", 0, dao.pendientes("venue-1"))
    }

    @Test
    fun `P1 Codex r3 #1 el TERMINADO de un POST que llevo la evidencia nueva la deja entregada`() = runBlocking {
        insertar(duda(REF_1).copy(creditCents = 10_000))   // la evidencia de $100 no ha subido
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 0, change = 0).put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("POST", ".*/cash-machine/doubts", ok(resuelta))

        assertFalse(subida.subir())

        val fila = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.RESUELTO, fila.estado)
        assertTrue("este POST la llevó: ya está allá", fila.dudaSubida)
        assertEquals(10_000, fila.creditCents)
        assertEquals(1, servidor.requestCount)
        assertEquals("Codex código POS r4 #1: «Cancelado, \$0» no cubre los \$100 que vio la PC", 1, dao.bloqueanLaVenta(fila.llaveDeVenta))
    }

    /** Codex código POS r4 #1/#3: un folio que un gerente resolvió «Cancelado, $0» y, DESPUÉS, la máquina dice que entraron $100. */
    private suspend fun resueltaConEvidenciaNueva(): CobroEnMaquinaEntity {
        insertar(duda(REF_1).copy(dudaSubida = true, versionDeLaDuda = 1))
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 0, change = 0).put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        assertTrue(dao.resolverConElEco(dao.fila(REF_1)!!, resuelta, resuelta.toString()))
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDe(REF_1, 10_000), 10_000, 18_750))
        return dao.fila(REF_1)!!
    }

    @Test
    fun `P1 Codex r4 #3 un rechazo sin incidencia, un RECIBIDO o un TERMINADO sin dueno no entregan la evidencia - sigue frenando y vuelve a subir`() = runBlocking {
        val antes = resueltaConEvidenciaNueva()
        val eco = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 0, change = 0).put("status", "RESUELTO")
        cuando(
            "POST", ".*/cash-machine/doubts",
            // Codex código POS r5 #4: un 413 ya no es «rechazo» (es «más chico»); el rechazo sin incidencia es un 400.
            error(400, "El pedido debe ser un objeto", "VALIDATION_ERROR"),
            ok(abierta(2), 202),
            ok(JSONObject(eco.toString()).put("deviceId", JSONObject.NULL)),   // un TERMINADO que no dice de quién es el folio
            ok(eco),
        )

        repeat(3) { i ->
            assertTrue("vuelta $i: queda trabajo", subida.subir())
            val f = dao.fila(REF_1)!!
            assertFalse("vuelta $i: sin constancia del SERVIDOR sigue pendiente", f.dudaSubida)
            assertEquals("vuelta $i: frena salir", 1, dao.pendientes("venue-1"))
            assertEquals("vuelta $i: y la venta", 1, dao.bloqueanLaVenta(antes.llaveDeVenta))
        }
        assertFalse(subida.subir())

        assertTrue("el TERMINADO de ESTA PC sí la entrega", dao.fila(REF_1)!!.dudaSubida)
        assertEquals(0, dao.pendientes("venue-1"))
        assertEquals("pero la resolución no cubre lo que entró (#1)", 1, dao.bloqueanLaVenta(antes.llaveDeVenta))
        assertEquals(4, servidor.requestCount)
    }

    @Test
    fun `P1 Codex r4 #3 una incidencia durable del servidor si entrega la evidencia tras resolver`() = runBlocking {
        resueltaConEvidenciaNueva()
        cuando(
            "POST", ".*/cash-machine/doubts",
            MockResponse().setResponseCode(409).setBody(
                JSONObject().put("message", "Esta duda ya se subió con otros importes").put("code", "CASH_MACHINE_DOUBT_CONFLICT")
                    .put("details", JSONObject().put("motivo", "OTRA_EVIDENCIA").put("deviceId", DISPOSITIVO).put("incidentId", "cinc000000000000000000013"))
                    .toString(),
            ),
        )

        assertFalse(subida.subir())

        assertTrue(dao.fila(REF_1)!!.dudaSubida)
        assertEquals(0, dao.pendientes("venue-1"))
    }

    @Test
    fun `P1 Codex r4 #5 una duda que el servidor contesta 422 con su incidencia queda entregada - sin vueltas POST y GET y sin frenar la salida`() = runBlocking {
        insertar(duda(REF_1))
        val imposible = MockResponse().setResponseCode(422).setBody(
            JSONObject().put("message", "El adeudo de esta duda no lo puede cobrar la máquina").put("code", "CASH_MACHINE_CLOSE_IMPOSSIBLE")
                .put("details", JSONObject().put("incidentId", "cinc000000000000000000012")).toString(),
        )
        cuando("POST", ".*/cash-machine/doubts", imposible, imposible)
        cuando("GET", ".*/cash-machine/doubts/$REF_1", error(404, "Ese folio no tiene una duda", "CASH_MACHINE_DOUBT_NOT_FOUND"))

        assertFalse("el SERVIDOR ya la tiene como incidencia: no queda trabajo", subida.subir())
        assertFalse(subida.subir())

        assertEquals("ni se consulta ni se vuelve a subir", 1, servidor.requestCount)
        val f = dao.fila(REF_1)!!
        assertTrue(f.dudaSubida)
        assertEquals("no frena salir", 0, dao.pendientes("venue-1"))
        assertEquals("pero su venta sigue frenada", 1, dao.bloqueanLaVenta(f.llaveDeVenta))

        // Lo nuevo que diga la máquina sí vuelve a subir (y frena salir mientras sólo está aquí).
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDe(REF_1, 5_000), 5_000, 18_750))
        assertEquals(1, dao.pendientes("venue-1"))
        assertFalse(subida.subir())
        assertEquals(2, servidor.requestCount)
        assertEquals(0, dao.pendientes("venue-1"))
    }

    @Test
    fun `P1 Codex r3 #14 un carrito ilegible no frena el dinero - el cierre y la duda suben SIN carrito`() = runBlocking {
        insertar(pagado.copy(pedidoJson = "{roto"), duda(REF_2).copy(pedidoJson = "{roto"))
        val incidencia = evidencia(pagado).put("incident", "NO_SE_PUDO_REGISTRAR").put("incidentId", "cinc000000000000000000011")
            .put("motivo", "SIN_CARRITO")
        cuando("POST", ".*/cash-machine/sales", ok(incidencia, 202))
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1, REF_2)))

        subida.subir()

        val pedidos = (1..servidor.requestCount).map { pedido() }
        assertEquals(listOf("/api/v1/mobile/venues/venue-1/cash-machine/sales", "/api/v1/mobile/venues/venue-1/cash-machine/doubts"), pedidos.map { it.path })
        pedidos.forEach { assertFalse("sin carrito", cuerpo(it).has("pedido")) }
        val cierre = dao.fila(REF_1)!!
        assertTrue("el SERVIDOR guardó primero el dinero", cierre.subido)
        assertEquals("Registrado como incidencia: faltaba el carrito de la venta", cierre.conflicto)
        assertTrue(dao.fila(REF_2)!!.dudaSubida)
    }

    // MARK: - Codex código POS r5 #4: un folio demasiado grande nunca se queda fuera de la cola ni del panel

    /** Lo que el SERVIDOR de antes hacía con un cuerpo de más de 1 MiB: 413 sin `incidentId`, sin guardar nada. */
    private fun conLimiteDe1MiB(respuesta: () -> MockResponse) = synchronized(guion) {
        guion.add(0, { q: RecordedRequest -> q.bodySize > 1_048_576 } to ArrayDeque(List(10) { MockResponse().setResponseCode(413).setBody("""{"message":"La solicitud excede el tamaño máximo permitido.","code":"PAYLOAD_TOO_LARGE"}""") }))
        guion.add(1, { q: RecordedRequest -> q.method == "POST" } to ArrayDeque(List(10) { respuesta() }))
    }

    /** Un `status` como los de APS, con hora y barra de cambio. */
    private fun statusCompleto(ref: String, credit: Int, due: Int) =
        JSONObject().put("comando", "status").put("cobrando", true).put("Status", JSONObject.NULL).put("referenceCC", ref)
            .put("Credit", credit / 100.0).put("Due", due / 100.0).put("changeDispensed", 0).put("changeBar", "022220222000")
            .put("fecha", "2026-11-21 18:00:00").toString()

    /**
     * Codex código POS r5 #4 (regresión de r4): venta de $5,000; sin internet entran $4,000 en monedas de $0.50 y el intento acaba en duda.
     * El diario completo pasa de 1 MiB. Antes la PRIMERA duda viajaba entera, el SERVIDOR contestaba 413 y la fila quedaba fuera de los
     * reintentos y de las consultas: el panel nunca veía ese dinero. Ahora sube lo esencial con el diario recortado (lo más nuevo primero).
     */
    @Test
    fun `P1 Codex POS r5 #4 la primera duda con un diario de mas de 1 MiB llega al panel - lo nuevo primero y creditCents como numero`() = runBlocking {
        val diario = (1..8_000).joinToString("\n") { i -> "g1 " + statusCompleto(REF_1, i * 50, 500_000) }
        assertTrue("el caso de Codex: ${diario.length} bytes", diario.toByteArray().size > 1_048_576)
        insertar(duda(REF_1).copy(dueCents = 500_000, saleCents = 500_000, tipCents = 0, creditCents = 400_000, mensajesCrudos = diario))
        conLimiteDe1MiB { ok(abierta(1), 202) }

        assertTrue("queda la consulta al gerente", subida.subir())

        val q = pedido()
        assertTrue("cabe: ${q.bodySize}", q.bodySize <= 1_048_576)
        val b = cuerpo(q)
        assertEquals(400_000, b.getInt("creditCents"))
        assertEquals(500_000, b.getInt("dueCents"))
        assertTrue("con su carrito", b.has("pedido"))
        val lastSeen = b.getString("lastSeen")
        assertTrue(lastSeen.toByteArray().size <= TOPE_DEL_DIARIO_QUE_SUBE)
        assertTrue(lastSeen.startsWith("Entró \$4,000.00\ng1 " + statusCompleto(REF_1, 400_000, 500_000)))
        assertTrue(lastSeen.endsWith("mensajes más viejos, sólo en esta PC"))
        val f = dao.fila(REF_1)!!
        assertTrue("el SERVIDOR ya la tiene: se consulta hasta que el panel la resuelva", f.dudaSubida)
        assertNull(f.conflicto)
        assertEquals("el diario completo se queda en la PC", diario, f.mensajesCrudos)
        assertEquals(1, servidor.requestCount)
    }

    @Test
    fun `P1 Codex POS r5 #4 un 413 se repite al momento sin el carrito, y si sigue el folio se queda en la cola - nunca excluido`() = runBlocking {
        insertar(duda(REF_1), pagado.copy(referenceCC = REF_2))
        val incidencia = evidencia(pagado.copy(referenceCC = REF_2)).put("incidentId", "cinc000000000000sincarrito").put("incident", "SIN_CARRITO")
            .put("motivo", "SIN_CARRITO")
        val grande = MockResponse().setResponseCode(413).setBody("""{"message":"La solicitud excede el tamaño máximo permitido.","code":"PAYLOAD_TOO_LARGE"}""")
        cuando("POST", ".*/cash-machine/doubts", grande, ok(abierta(1), 202))
        cuando("POST", ".*/cash-machine/sales", grande, grande, grande, ok(incidencia, 202))

        assertTrue(subida.subir())

        // El cuerpo de una petición grabada se lee UNA vez.
        val primera = (1..4).map { pedido() }.groupBy({ it.path!!.substringAfterLast('/') }, ::cuerpo)
        assertEquals(setOf("sales", "doubts"), primera.keys)
        for ((ruta, cuerpos) in primera) {
            assertTrue("$ruta: completo primero", cuerpos[0].has("pedido"))
            assertFalse("$ruta: el 413 se repite SIN carrito", cuerpos[1].has("pedido"))
            val entro = cuerpos.map { (it.optJSONObject("cierre") ?: it).getInt("creditCents") }
            assertEquals("$ruta: con lo que entró", entro[0], entro[1])
        }
        assertTrue("la duda llegó al panel", dao.fila(REF_1)!!.dudaSubida)
        assertNull("el cierre que siguió en 413 NO queda excluido", dao.fila(REF_2)!!.conflicto)
        assertEquals(listOf(REF_2), dao.paraSubir("venue-1").map { it.referenceCC })
        assertTrue("y sigue frenando salir", dao.fila(REF_2)!!.frenaLaSalida())

        assertTrue(subida.subir())   // la pasada siguiente lo vuelve a mandar

        assertTrue("el cierre ya tiene su incidencia", dao.fila(REF_2)!!.subido)
        assertFalse(dao.fila(REF_2)!!.frenaLaSalida())
    }

    @Test
    fun `P1 Codex POS r5 #4 un cuerpo que no cabe ni se manda entero - sale sin el carrito al primer intento`() = runBlocking {
        val enorme = JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("productId", "p1").put("quantity", 1).put("notes", "x".repeat(1_100_000))))
        insertar(pagado.copy(pedidoJson = enorme.toString()))
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(pagado).put("incidentId", "cinc000000000000sincarrito").put("incident", "SIN_CARRITO"), 202))

        assertFalse(subida.subir())

        val q = pedido()
        assertTrue(q.bodySize < 10_000)
        assertFalse(cuerpo(q).has("pedido"))
        assertEquals(1, servidor.requestCount)
        assertTrue(dao.fila(REF_1)!!.subido)
    }

    private companion object {
        const val REF_1 = "AQ000000000001"
        const val REF_2 = "AQ000000000002"
        const val DISPOSITIVO = "cterm00000000000000000001"
    }
}
