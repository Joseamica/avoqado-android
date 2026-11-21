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
        JSONObject().put("success", true).put("referenceCC", f.referenceCC).put("outcome", outcome).put("resolvedBy", "MAQUINA")
            .put("owedKind", JSONObject.NULL).put("orderId", "orden-1").put("paymentId", "pago-1").put("dueCents", f.dueCents)
            .put("saleCents", f.saleCents).put("tipCents", f.tipCents).put("appliedCents", f.saleCents).put("excedenteCents", 0)
            .put("creditCents", credit).put("changeDispensedCents", change).put("owedCents", 0).put("shiftStatus", "EN_TURNO")

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
    fun `CANCELADO y GUARDADO suben sin pedido y con el modo de su cancel`() = runBlocking {
        val cancelado = final(REF_1, EstadoDelIntento.CANCELADO, credit = 5_000, change = 5_000, cancel = "1")
        val guardado = final(REF_2, EstadoDelIntento.GUARDADO, credit = 5_000, change = 0, cancel = "2").copy(creadoEn = 2_000)
        insertar(cancelado, guardado)
        cuando("POST", ".*/cash-machine/sales", ok(evidencia(cancelado)), ok(evidencia(guardado)))

        subida.subir()

        val primero = cuerpo(pedido())
        val segundo = cuerpo(pedido())
        assertFalse(primero.has("pedido"))
        assertFalse(segundo.has("pedido"))
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
        val sinRegistro = JSONObject().put("success", true).put("referenceCC", REF_1).put("incident", "NO_SE_PUDO_REGISTRAR")
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
        val abierta = JSONObject().put("success", true).put("status", "ABIERTO").put("referenceCC", REF_1)
        val resuelta = evidencia(duda(REF_1), outcome = "CANCELADO", credit = 5_000, change = 0)
            .put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("POST", ".*/cash-machine/doubts", ok(abierta))
        cuando("GET", ".*/cash-machine/doubts/$REF_1", ok(abierta), ok(resuelta))

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
        assertEquals("""{"comando":"sockets","cobrando":true}""", b.getString("lastSeen"))
        assertEquals("turno-1", b.getString("saleShiftId"))
        assertTrue(dao.fila(REF_1)!!.dudaSubida)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)

        assertTrue("el gerente todavía no decide", subida.subir())
        assertEquals("GET", pedido().method)
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(REF_1)!!.estado)

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

    // MARK: - Disparadores (R3#12, R5#6)

    @Test
    fun `cada 60 s se consulta sola mientras hay una duda, y sin trabajo no hay reloj`() = runBlocking {
        insertar(duda(REF_1))
        val abierta = JSONObject().put("success", true).put("status", "ABIERTO").put("referenceCC", REF_1)
        val resuelta = evidencia(duda(REF_1), outcome = "PAGADO", credit = 20_000, change = 1_250)
            .put("status", "RESUELTO").put("resolvedBy", "GERENTE")
        cuando("POST", ".*/cash-machine/doubts", ok(abierta))
        cuando("GET", ".*/cash-machine/doubts/$REF_1", ok(abierta), ok(resuelta))
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

    private companion object {
        const val REF_1 = "AQ000000000001"
        const val REF_2 = "AQ000000000002"
    }
}
