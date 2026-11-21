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

    /** Un cierre de la máquina como lo deja el motor: lo registrado y su cierre físico ([conSuCierreFisico]) dicen lo mismo. */
    private fun final(ref: String, estado: String, credit: Int, change: Int, cancel: String? = null, venue: String = "venue-1") =
        CobroEnMaquinaEntity(
            referenceCC = ref, venueId = venue, llaveDeVenta = "venta-$ref", pedidoJson = pedido, saleCents = 18_000,
            tipCents = 750, dueCents = 18_750, saleShiftId = "turno-1", quoteId = "cot-1", creadoEn = 1_000, estado = estado,
            creditCents = credit, changeDispensedCents = change, cancelPedido = cancel, cierreEn = 1_763_640_000_000,
            cierreConocido = true, resueltoPor = "MAQUINA",
        ).conSuCierreFisico()

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
        insertar(duda(REF_1).copy(creditCents = 5_000, creditoFisicoCents = 5_000, ultimoCreditoEn = 1_763_639_050_000))
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

    /**
     * Codex código POS r7 #3: el SERVIDOR ya tiene el PAGADO de este folio ($200 por $187.50, $0 de cambio: debe $12.50) y la máquina confirma
     * DESPUÉS que sí dio los $12.50. Eso sólo está en esta PC: sube como duda (el SERVIDOR lo anota «TRAS_RESOLVER» para el gerente) y frena
     * salir hasta que llega. Lo registrado no cambia y el papel sigue en «Revisar en el panel».
     */
    @Test
    fun `P1 Codex POS r7 #3 un cierre distinto que llega con el PAGADO ya subido sube como duda y frena salir hasta entregarse`() = runBlocking {
        val subido = final(REF_1, EstadoDelIntento.PAGADO, credit = 20_000, change = 0)
            .copy(subido = true, creditoFisicoCents = 20_000, cierreFisico = "PAGADO", cambioFisicoCents = 0)
        insertar(subido)
        assertEquals(0, dao.pendientes("venue-1"))
        val conCambio = JSONObject().put("comando", "status").put("cobrando", false).put("Status", "Pago Completado").put("referenceCC", REF_1)
            .put("Credit", 200.0).put("Due", 187.5).put("changeDispensed", 12.5).toString()

        assertTrue(dao.anotarMensaje(REF_1, 1, conCambio, 20_000, 18_750))
        assertEquals("sólo está en esta PC: frena salir", 1, dao.pendientes("venue-1"))
        cuando("POST", ".*/cash-machine/doubts", ok(evidencia(subido)))
        assertFalse(subida.subir())

        val q = pedido()
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", q.path)
        assertTrue(cuerpo(q).getString("lastSeen").contains("Pago Completado"))
        val f = dao.fila(REF_1)!!
        assertTrue("entregada", f.dudaSubida)
        assertTrue(f.maquinaSeContradijo)
        assertEquals(listOf<Any>(EstadoDelIntento.PAGADO, 20_000, 0), listOf(f.estado, f.creditCents, f.changeDispensedCents))
        assertEquals(0, dao.pendientes("venue-1"))
        assertTrue("el papel sigue sin deuda", f.porRevisarEnElPanel())
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
        insertar(duda(REF_1).copy(dueCents = 500_000, saleCents = 500_000, tipCents = 0, creditCents = 400_000, creditoFisicoCents = 400_000, mensajesCrudos = diario))
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

    // MARK: - Codex código POS r8: la pasada exhaustiva (F1 y F2)

    /** Un `status` de [ref] con [due] (cierre si trae [estado]). */
    private fun statusDeLaMaquina(ref: String, due: Int, credit: Int, change: Int = 0, estado: String? = null) =
        JSONObject().put("comando", "status").put("cobrando", estado == null).put("Status", estado ?: JSONObject.NULL)
            .put("referenceCC", ref).put("Credit", credit / 100.0).put("Due", due / 100.0).put("changeDispensed", change / 100.0).toString()

    /** El 409 del SERVIDOR cuando el folio es de OTRA PC: guarda la observación y deja su incidencia. */
    private fun deOtraPc(code: String = "CASH_MACHINE_DOUBT_CONFLICT") = MockResponse().setResponseCode(409).setBody(
        JSONObject().put("message", "Este folio de la máquina es de otra PC").put("code", code)
            .put("details", JSONObject().put("motivo", "OTRA_PC").put("deviceId", "la-otra-pc").put("incidentId", "cinc0000000000000000otrapc"))
            .toString(),
    )

    /**
     * Codex código POS r8 #4 (F1): venta de $80. El primer cierre dice $80 recibidos y $0 de cambio; ANTES de subir, la máquina cuenta $100 del
     * mismo folio sin devolver cambio. La PC mandaba a registrar $80 y $0 de deuda (faltaban $20). Una fila que se contradice antes de su
     * primera subida sube como DUDA con lo que contó la máquina; el SERVIDOR nunca recibe la venta limpia del primer cierre.
     */
    @Test
    fun `P1 Codex POS r8 #4 un cierre que la maquina contradice antes de subir sube como duda con lo que conto, nunca como venta del primer cierre`() = runBlocking {
        insertar(
            CobroEnMaquinaEntity(REF_1, "venue-1", "venta-$REF_1", pedido, 8_000, 0, 8_000, "turno-1", null, 1_763_639_000_000, EstadoDelIntento.EN_CURSO),
        )
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 8_000, 8_000, 0, "Pago Completado"), 8_000, 8_000))
        assertEquals(1, dao.cerrar(REF_1, EstadoDelIntento.PAGADO, 8_000, 0, 1_763_640_000_000, aDeber = 0))
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 8_000, 10_000, 0, "Pago Completado"), 10_000, 8_000))
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(dao.fila(REF_1)!!)))
        cuando("POST", ".*/cash-machine/doubts", ok(abierta(1)))

        subida.subir()

        val q = pedido()
        assertEquals("la venta de \$80 nunca sube", "/api/v1/mobile/venues/venue-1/cash-machine/doubts", q.path)
        val b = cuerpo(q)
        assertEquals(10_000, b.getInt("creditCents"))
        assertTrue(b.getString("lastSeen"), b.getString("lastSeen").startsWith("Entró \$100.00"))
        assertEquals(1, servidor.requestCount)
        val f = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.ESPERANDO, f.estado)
        assertTrue("el SERVIDOR ya la tiene", f.dudaSubida)
        assertEquals("una duda propia frena salir hasta que la resuelva el panel", 1, dao.pendientes("venue-1"))
        assertEquals("y la venta sigue frenada", 1, dao.bloqueanLaVenta(f.llaveDeVenta))
    }

    /**
     * Codex código POS r8 #3 (F2): la PC informa una cancelación en $0 y el SERVIDOR contesta que el folio es de OTRA PC (abre su incidencia).
     * Después la QuickPay confirma $80 recibidos de ese folio. La PC los guardaba en disco pero no los subía ni frenaba salir: el gerente seguía
     * viendo $0. Ahora suben como duda (el SERVIDOR los agrega a la incidencia), frenan salir hasta el acuse, y la misma evidencia no vuelve a subir.
     */
    @Test
    fun `P1 Codex POS r8 #3 con el folio de OTRA PC el dinero que llega despues sube como duda y frena salir hasta su acuse`() = runBlocking {
        insertar(final(REF_1, EstadoDelIntento.CANCELADO, credit = 0, change = 0, cancel = "1"))
        cuando("POST", ".*/cash-machine/sales", deOtraPc("CASH_MACHINE_CLOSE_CONFLICT"))
        cuando("POST", ".*/cash-machine/doubts", deOtraPc())
        assertFalse(subida.subir())
        assertEquals("su incidencia está en el SERVIDOR", 0, dao.pendientes("venue-1"))

        val ochenta = statusDeLaMaquina(REF_1, 18_750, 8_000)
        assertTrue(dao.anotarMensaje(REF_1, 1, ochenta, 8_000, 18_750))

        assertEquals("los \$80 sólo están aquí: frenan salir", 1, dao.pendientes("venue-1"))
        assertEquals(listOf(REF_1), dao.paraSubir("venue-1").map { it.referenceCC })
        assertFalse(subida.subir())
        pedido()   // el cierre de $0
        val q = pedido()
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/doubts", q.path)
        assertEquals(8_000, cuerpo(q).getInt("creditCents"))
        assertEquals("con su acuse (la incidencia de la otra PC) ya no frena", 0, dao.pendientes("venue-1"))

        assertFalse("la misma evidencia otra vez no es nueva", dao.anotarMensaje(REF_1, 1, ochenta, 8_000, 18_750))
        assertFalse(subida.subir())
        assertEquals("sin vueltas", 2, servidor.requestCount)
    }

    /**
     * Codex código POS r8 #5 (F1): venta de $80; el gerente la resolvió con $150 recibidos. Después la QuickPay confirma $100 recibidos y $20
     * devueltos. La PC subía `creditCents = 15000` y «Entró $150.00» como observación de la PC: $50 de más atribuidos a la máquina. Lo que
     * sube como lo que vio la PC sale de las columnas físicas; lo que tecleó un gerente nunca.
     */
    @Test
    fun `P1 Codex POS r8 #5 lo que tecleo el gerente nunca sube como lo que conto la maquina`() = runBlocking {
        insertar(
            CobroEnMaquinaEntity(
                REF_1, "venue-1", "venta-$REF_1", pedido, 8_000, 0, 8_000, "turno-1", null, 1_763_639_000_000, EstadoDelIntento.ESPERANDO,
                dudaSubida = true, versionDeLaDuda = 1,
            ),
        )
        val eco = JSONObject().put("success", true).put("ack", "TERMINADO").put("deviceId", DISPOSITIVO).put("status", "RESUELTO")
            .put("referenceCC", REF_1).put("outcome", "PAGADO").put("resolvedBy", "GERENTE").put("dueCents", 8_000).put("saleCents", 8_000)
            .put("tipCents", 0).put("creditCents", 15_000).put("changeDispensedCents", 7_000).put("owedCents", 0).put("excedenteCents", 0)
        assertTrue(dao.resolverConElEco(dao.fila(REF_1)!!, eco, eco.toString()))
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 8_000, 10_000, 2_000, "Pago Completado"), 10_000, 8_000))
        cuando("POST", ".*/cash-machine/doubts", ok(eco))

        assertFalse(subida.subir())

        val b = cuerpo(pedido())
        assertEquals("lo que contó la máquina", 10_000, b.getInt("creditCents"))
        val lastSeen = b.getString("lastSeen")
        assertTrue(lastSeen, lastSeen.startsWith("Entró \$100.00"))
        assertFalse(lastSeen, "\$150.00" in lastSeen)
        assertEquals("entregada", 0, dao.pendientes("venue-1"))
    }

    /**
     * Codex código POS r8 #3 (F2, la familia completa): la tabla estado × «la máquina cuenta más de lo que ya se le reportó al SERVIDOR». En
     * TODOS los estados esa fila vuelve a quedar por subir como duda, frena salir hasta el acuse y sube lo que contó la máquina. El acuse la
     * entrega y la misma evidencia nunca vuelve a subir (sin ciclo).
     */
    @Test
    fun `P1 Codex POS r8 F2 en todos los estados la evidencia nueva sube como duda, frena salir hasta su acuse y no da vueltas`() = runBlocking {
        val otraPc = JSONObject().put("code", "CASH_MACHINE_DOUBT_CONFLICT")
            .put("details", JSONObject().put("motivo", "OTRA_PC").put("deviceId", "la-otra-pc").put("incidentId", "cinc0000000000000000otrapc")).toString()
        val imposible = JSONObject().put("code", CODIGO_IMPOSIBLE).put("details", JSONObject().put("incidentId", "cinc000000000000imposible")).toString()
        fun duda(ref: String, n: Long) = duda(ref).copy(creadoEn = n, mensajesCrudos = "")
        fun cerrado(ref: String, n: Long, estado: String, credit: Int, change: Int, cancel: String? = null) =
            final(ref, estado, credit, change, cancel).copy(creadoEn = n)
        val filas = listOf(
            duda("AQ00000000000A", 1).copy(dudaSubida = true),                                                       // duda abierta en el SERVIDOR
            duda("AQ00000000000B", 2).copy(dudaSubida = true, conflicto = "otra PC", respuestaServidor = otraPc),     // duda de otra PC
            duda("AQ00000000000C", 3).copy(dudaSubida = true, conflicto = "imposible", respuestaServidor = imposible), // sólo incidencia (422)
            duda("AQ00000000000D", 4).copy(conflicto = "El pedido debe ser un objeto"),                                // rechazo sin incidencia
            cerrado("AQ00000000000E", 5, EstadoDelIntento.PAGADO, 20_000, 1_250),                                     // por subir
            cerrado("AQ00000000000F", 6, EstadoDelIntento.CANCELADO, 0, 0, "1")
                .copy(conflicto = "otra PC", respuestaServidor = otraPc),                                               // de otra PC (r8 #3)
            cerrado("AQ00000000000G", 7, EstadoDelIntento.PAGADO, 20_000, 1_250).copy(conflicto = "El pedido debe ser un objeto"),
            cerrado("AQ00000000000H", 8, EstadoDelIntento.PAGADO, 20_000, 1_250).copy(conflicto = TEXTO_RESPUESTA_DISTINTA),
            cerrado("AQ00000000000I", 9, EstadoDelIntento.PAGADO, 20_000, 1_250).copy(subido = true, respuestaServidor = "{}"),
            cerrado("AQ00000000000J", 10, EstadoDelIntento.GUARDADO, 5_000, 0, "2")
                .copy(subido = true, conflicto = "incidencia", respuestaServidor = """{"details":{"incidentId":"cinc00000000000000000000j"}}"""),
            cerrado("AQ00000000000K", 11, EstadoDelIntento.CANCELADO, 5_000, 5_000, "1").copy(subido = true, respuestaServidor = "{}"),
            duda("AQ00000000000L", 12).copy(
                estado = EstadoDelIntento.RESUELTO, dudaSubida = true, desenlaceDelServidor = "CANCELADO",
                respuestaServidor = """{"outcome":"CANCELADO","creditCents":0,"changeDispensedCents":0}""",
            ),
        )
        insertar(*filas.toTypedArray())
        val porFolio = mutableMapOf<String, MutableList<String>>()
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ruta = request.path!!
                if (request.method == "GET") return ok(abierta(1, ruta.substringAfterLast('/')))
                val b = JSONObject(request.body.readUtf8())
                val ref = b.getString("referenceCC")
                synchronized(porFolio) { porFolio.getOrPut(ref) { mutableListOf() } += ruta.substringAfterLast('/') + ":" + b.optInt("creditCents") }
                return when {
                    ruta.endsWith("/sales") -> MockResponse().setResponseCode(500)
                    ref == "AQ00000000000B" || ref == "AQ00000000000F" -> deOtraPc()
                    ref == "AQ00000000000C" -> MockResponse().setResponseCode(422).setBody(imposible)
                    else -> MockResponse().setResponseCode(409).setBody(
                        JSONObject().put("code", "CASH_MACHINE_DOUBT_CONFLICT").put("message", "Quedó registrado para el gerente")
                            .put("details", JSONObject().put("motivo", "OTRA_EVIDENCIA").put("deviceId", DISPOSITIVO).put("incidentId", "cinc00000000000000000otra")).toString(),
                    )
                }
            }
        }

        // La máquina cuenta $50 más de lo que cada fila ya le reportó al SERVIDOR.
        val mensajes = filas.associate { f -> f.referenceCC to statusDeLaMaquina(f.referenceCC, f.dueCents, f.creditCents + 5_000) }
        for (f in filas) assertTrue(f.referenceCC, dao.anotarMensaje(f.referenceCC, 1, mensajes.getValue(f.referenceCC), f.creditCents + 5_000, f.dueCents))

        val porSubir = dao.paraSubir("venue-1").map { it.referenceCC }.toSet()
        for (f in filas) {
            assertTrue("${f.referenceCC} vuelve a quedar por subir", f.referenceCC in porSubir)
            assertTrue("${f.referenceCC} frena salir", dao.fila(f.referenceCC)!!.frenaLaSalida())
        }

        subida.subir()

        for (f in filas) {
            assertEquals("${f.referenceCC} sube UNA vez como duda, con lo que contó la máquina", listOf("doubts:${f.creditCents + 5_000}"), porFolio[f.referenceCC])
        }
        val propias = setOf("AQ00000000000A", "AQ00000000000D", "AQ00000000000E", "AQ00000000000G", "AQ00000000000H")
        for (f in filas) {
            val fila = dao.fila(f.referenceCC)!!
            if (f.referenceCC in propias) assertEquals("${f.referenceCC}: una duda propia abierta", EstadoDelIntento.ESPERANDO, fila.estado)
            else assertFalse("${f.referenceCC}: con su acuse ya no frena", fila.frenaLaSalida())
        }

        // La misma evidencia otra vez: nada nuevo, nada sube (sólo se consultan las dudas propias abiertas).
        for (f in filas) assertFalse(f.referenceCC, dao.anotarMensaje(f.referenceCC, 1, mensajes.getValue(f.referenceCC), f.creditCents + 5_000, f.dueCents))
        subida.subir()
        for (f in filas) assertEquals("${f.referenceCC} sin vueltas", 1, porFolio[f.referenceCC]!!.size)
    }

    // MARK: - Codex código POS r9 (F5: el envío y su respuesta se aplican al conteo que les corresponde)

    /** Una venta de $80 que la máquina cerró con $80 recibidos y $0 de cambio, por subir (como la deja el motor). */
    private suspend fun ventaDe80PorSubir(ref: String, creadoEn: Long): CobroEnMaquinaEntity {
        dao.insertar(CobroEnMaquinaEntity(ref, "venue-1", "venta-$ref", pedido, 8_000, 0, 8_000, "turno-1", null, creadoEn, EstadoDelIntento.EN_CURSO))
        assertTrue(dao.anotarMensaje(ref, 1, statusDeLaMaquina(ref, 8_000, 8_000, 0, "Pago Completado"), 8_000, 8_000))
        assertEquals(1, dao.cerrar(ref, EstadoDelIntento.PAGADO, 8_000, 0, 1_763_640_000_000, aDeber = 0))
        return dao.fila(ref)!!
    }

    /**
     * Codex código POS r9 #1 (F5): al volver internet la PC lee DOS ventas por subir. Mientras espera la respuesta de la primera, la QuickPay
     * corrige la segunda ($80 → $100 recibidos, $0 de cambio) y la PC la pasa a duda. El envío de la segunda usaba la lectura del lote: el
     * SERVIDOR registraba $80 y $0 de adeudo (faltaban $20). Cada folio se relee justo antes de armar su envío.
     */
    @Test
    fun `P1 Codex POS r9 #1 la correccion que llega mientras sube otro folio se manda con lo que conto la maquina, nunca con la lectura del lote`() = runBlocking {
        insertar(pagado)
        val segunda = ventaDe80PorSubir(REF_2, creadoEn = 2_000)
        val envios = mutableListOf<String>()
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ruta = request.path!!.substringAfterLast('/')
                val b = JSONObject(request.body.readUtf8())
                val ref = b.getString("referenceCC")
                synchronized(envios) { envios += "$ruta:$ref:" + (b.optJSONObject("cierre") ?: b).getInt("creditCents") }
                return when {
                    ref == REF_1 -> {
                        // Mientras el SERVIDOR contesta la primera, la máquina corrige la segunda.
                        runBlocking { dao.anotarMensaje(REF_2, 1, statusDeLaMaquina(REF_2, 8_000, 10_000, 0, "Pago Completado"), 10_000, 8_000) }
                        ok(evidencia(pagado))
                    }
                    ruta == "sales" -> ok(evidencia(segunda))
                    else -> ok(abierta(1, REF_2))
                }
            }
        }

        subida.subir()

        assertEquals("la segunda sube como duda con los \$100", listOf("sales:$REF_1:20000", "doubts:$REF_2:10000"), envios)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_2)!!.estado)
        assertTrue(dao.fila(REF_2)!!.dudaSubida)
    }

    /**
     * Codex código POS r9 #2 (F5): la PC manda una duda por $0. Antes de la respuesta la QuickPay informa $50 de ese folio. Llega el 400 (sin
     * incidencia) del envío de $0 y la PC lo aplicaba a la fila que ya tenía $50: quedaba fuera de los reintentos y de las consultas, y el
     * gerente nunca veía esos $50. Una respuesta sólo se aplica a la evidencia que se mandó: los $50 suben en la pasada siguiente.
     */
    @Test
    fun `P1 Codex POS r9 #2 un 400 que llega despues de 50 nuevos no saca al folio de la cola - los 50 suben`() = runBlocking {
        insertar(duda(REF_1))
        val envios = mutableListOf<Int>()
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val credito = JSONObject(request.body.readUtf8()).getInt("creditCents")
                val primera = synchronized(envios) { envios += credito; envios.size == 1 }
                if (!primera) return ok(abierta(1))
                runBlocking { dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 18_750, 5_000), 5_000, 18_750) }
                return error(400, "El pedido debe ser un objeto", "VALIDATION_ERROR")
            }
        }

        subida.subir()

        val f = dao.fila(REF_1)!!
        assertNull("el 400 era del envío de \$0", f.conflicto)
        assertEquals("los \$50 siguen por subir", listOf(REF_1), dao.paraSubir("venue-1").map { it.referenceCC })
        assertEquals(1, dao.pendientes("venue-1"))
        subida.subir()
        assertEquals(listOf(0, 5_000), envios)
        assertTrue("ya están en el SERVIDOR", dao.fila(REF_1)!!.dudaSubida)
    }

    /**
     * Codex código POS r9 (F5, la familia completa): TODO escritor que aplica una respuesta del SERVIDOR a una fila no la toca si su evidencia
     * ya no es la que se mandó (0 filas, la fila igual), y sí la aplica con la evidencia que se mandó.
     */
    @Test
    fun `P1 Codex POS r9 F5 ningun escritor de una respuesta toca una fila cuya evidencia cambio despues del envio`() = runBlocking {
        val cierre = pagado
        val duda = duda(REF_2)
        insertar(cierre, duda)
        val enviadoCierre = dao.fila(REF_1)!!
        val enviadaDuda = dao.fila(REF_2)!!
        // Después del envío, la máquina dice algo nuevo de cada folio (el cierre lo contradice: pasa a duda).
        assertTrue(dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 18_750, 25_000), 25_000, 18_750))
        assertTrue(dao.anotarMensaje(REF_2, 1, statusDeLaMaquina(REF_2, 18_750, 5_000), 5_000, 18_750))
        val antes = dao.todas()
        val eco = """{"outcome":"CANCELADO","creditCents":0,"changeDispensedCents":0}"""

        val escritores: List<Pair<String, suspend () -> Int>> = listOf(
            "marcarSubido" to { dao.marcarSubido(REF_1, null, "{}", enviadoCierre.mensajesCrudos, enviadoCierre.creditCents) },
            "marcarDeOtraPc" to { dao.marcarDeOtraPc(REF_1, "otra PC", "{}", enviadoCierre.mensajesCrudos, enviadoCierre.creditCents) },
            "anotarConflicto (cierre)" to { dao.anotarConflicto(REF_1, "400", enviadoCierre.mensajesCrudos, enviadoCierre.creditCents) },
            "anotarConflicto (duda)" to { dao.anotarConflicto(REF_2, "400", enviadaDuda.mensajesCrudos, enviadaDuda.creditCents) },
            "marcarDudaEnConflicto" to { dao.marcarDudaEnConflicto(REF_2, "409", "{}", enviadaDuda.mensajesCrudos, enviadaDuda.creditCents) },
            "marcarDudaAbierta" to { dao.marcarDudaAbierta(REF_2, 3, enviadaDuda.mensajesCrudos, enviadaDuda.creditCents) },
            "resolver" to { dao.resolver(REF_2, "GERENTE", 0, 0, null, eco, "CANCELADO", enviadaDuda.mensajesCrudos, enviadaDuda.creditCents) },
            "marcarEvidenciaEntregada" to { dao.marcarEvidenciaEntregada(REF_2, enviadaDuda.mensajesCrudos, enviadaDuda.creditCents) },
        )
        for ((nombre, escribir) in escritores) assertEquals("$nombre con la evidencia vieja", 0, escribir())
        assertEquals("ninguna fila cambió", antes, dao.todas())
        assertEquals("y lo nuevo de las dos sigue por subir", listOf(REF_1, REF_2), dao.paraSubir("venue-1").map { it.referenceCC })

        // Con la evidencia que sí se mandó, la respuesta se aplica.
        val ahora = dao.fila(REF_2)!!
        assertEquals(1, dao.marcarDudaAbierta(REF_2, 3, ahora.mensajesCrudos, ahora.creditCents))
        assertTrue(dao.fila(REF_2)!!.dudaSubida)
    }

    /**
     * Codex código POS r9 #1, el hueco que queda (LÍMITE): la máquina corrige el folio DESPUÉS de releerlo y antes de que el SERVIDOR conteste
     * su cierre de $80. Ese cierre ya salió; su acuse no marca la fila (ya es duda) y los $100 suben como duda en la pasada siguiente: el
     * SERVIDOR los anota para el gerente («TRAS_RESOLVER») y la PC queda con lo que él resolvió, sin deuda en papel.
     */
    @Test
    fun `P1 Codex POS r9 #1 la correccion que llega con el cierre en camino sube despues como duda y nunca queda como subida`() = runBlocking {
        val venta = ventaDe80PorSubir(REF_1, creadoEn = 1_000)
        val envios = mutableListOf<String>()
        val ecoDelCierre = evidencia(venta)
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ruta = request.path!!.substringAfterLast('/')
                val b = JSONObject(request.body.readUtf8())
                synchronized(envios) { envios += "$ruta:" + (b.optJSONObject("cierre") ?: b).getInt("creditCents") }
                if (ruta == "sales") {
                    runBlocking { dao.anotarMensaje(REF_1, 1, statusDeLaMaquina(REF_1, 8_000, 10_000, 0, "Pago Completado"), 10_000, 8_000) }
                    return ok(ecoDelCierre)
                }
                // El folio ya tiene su cierre de $80: contesta ése (TERMINADO) y anota los $100 para el gerente.
                return ok(JSONObject(ecoDelCierre.toString()).put("status", "RESUELTO"))
            }
        }

        subida.subir()
        val tras = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.ESPERANDO, tras.estado)
        assertFalse("el acuse del cierre de \$80 no la marca", tras.subido)
        assertEquals("los \$100 sólo están aquí: frenan salir", 1, dao.pendientes("venue-1"))

        subida.subir()
        assertEquals(listOf("sales:8000", "doubts:10000"), envios)
        val f = dao.fila(REF_1)!!
        assertEquals(EstadoDelIntento.RESUELTO, f.estado)
        assertTrue("entregada", f.dudaSubida)
        assertTrue("ni comprobante ni «Te debemos» en papel", f.porRevisarEnElPanel())
    }

    /** Codex código POS r8 (F1): el SQL que vuelve duda un cierre sin cierre físico confiable dice lo mismo que [loQueContoLaMaquina]. */
    @Test
    fun `Codex POS r8 la regla del cierre confiable es la misma en SQL y en Kotlin`() = runBlocking {
        val base = final(REF_1, EstadoDelIntento.PAGADO, credit = 20_000, change = 1_250)
        var n = 0
        val filas = listOf(true, false).flatMap { contradijo ->
            listOf("PAGADO", null).flatMap { cierre ->
                listOf(1_250, null).map { cambio ->
                    base.copy(referenceCC = "AQ" + (++n).toString().padStart(12, '0'), llaveDeVenta = "venta-$n", maquinaSeContradijo = contradijo, cierreFisico = cierre, cambioFisicoCents = cambio)
                }
            }
        }
        insertar(*filas.toTypedArray())

        dao.aDudaSinCierreConfiable(null)

        for (f in filas) {
            val aDuda = dao.fila(f.referenceCC)!!.estado == EstadoDelIntento.ESPERANDO
            assertEquals(f.referenceCC, f.loQueContoLaMaquina().cierre == null, aDuda)
        }
        assertEquals("sólo una de las ocho es confiable", 1, filas.count { it.loQueContoLaMaquina().cierre != null })
    }

    private companion object {
        const val REF_1 = "AQ000000000001"
        const val REF_2 = "AQ000000000002"
        const val DISPOSITIVO = "cterm00000000000000000001"
    }
}
