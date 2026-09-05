package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CODIGO_CAJA_YA_ABIERTA
import com.avoqado.pos.cashdrawer.data.CODIGO_CIERRE_EN_PROCESO
import com.avoqado.pos.cashdrawer.data.DestinoDeLaOperacion
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.clasificarRespuestaDelServer
import com.avoqado.pos.cashdrawer.data.codigoDeNegocio
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 🔴 LA APERTURA DE LA CAJA ES DURABLE — Task 8b (4-sep-2026).
 *
 * El defecto medido: abrir la caja disparaba UN POST y nada más. Sin red, o si el proceso moría
 * entre el toque y la respuesta, el aparato se quedaba con su caja local y el SERVIDOR sin
 * ninguna — así que los cobros de ese día nacían con `shiftId = null` y quedaban fuera de todo
 * turno. Es justo lo que este proyecto existe para arreglar.
 *
 * Cuatro reglas, y cada una tiene su prueba aquí:
 *  1. la intención de abrir se guarda ANTES de tocar la red (encolar en el `catch` es tarde: el
 *     `catch` no corre si el proceso ya murió);
 *  2. al reproducir, la APERTURA sale antes que cualquier movimiento o cierre de esa caja, y una
 *     apertura que no llegó es BARRERA — nada posterior de esa caja se manda;
 *  3. un 409 `CASH_SHIFT_ALREADY_OPEN` no es un rechazo: se pregunta por `/current` y se ADOPTA
 *     la caja del servidor;
 *  4. `SHIFT_CLOSE_IN_PROGRESS` es transitorio: se reintenta y la intención NO desaparece.
 *
 * ⚠️ La prueba que ya se llamaba «apertura encolada sin red» (en la suite de la promoción) NO
 * cubre esto: empieza cuando el POST ya llegó al servicio.
 */
class CashDrawerAperturaDurableTest {

    // MARK: - Andamio

    /** Un `SecureStorage` que de verdad recuerda la cola entre llamadas. */
    private fun almacenConCola(colaInicial: String? = null) = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = colaInicial
        every { st.venueId } returns VENUE_ID
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
    }

    private fun cola(st: SecureStorage): List<PendingDrawerOp> =
        st.pendingDrawerOpsJson(VENUE_ID)
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?: emptyList()

    private fun respuesta(code: Int, body: String, url: String) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    /**
     * Cliente que sabe contestar con CÓDIGOS, no sólo 200. Es lo único que permite ejercitar el
     * 409 y su `code` de negocio, que es donde está toda la decisión nueva.
     */
    private fun clienteConCodigos(
        vararg rutas: Pair<String, Pair<Int, String>>,
        capturadas: MutableList<LlamadaCapturada>? = null,
        alLlamar: (Request) -> Unit = {},
    ): OkHttpClient {
        val porRuta = rutas.toMap()
        return mockk {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val path = request.url.encodedPath
                capturadas?.add(
                    LlamadaCapturada(
                        path = path,
                        body = request.body?.let { c -> okio.Buffer().also { c.writeTo(it) }.readUtf8() } ?: "",
                    ),
                )
                alLlamar(request)
                val configurada = porRuta.entries.firstOrNull { path.endsWith(it.key) }?.value
                val call = mockk<Call>()
                if (configurada == null) {
                    every { call.execute() } throws IOException("sin red: $path")
                } else {
                    every { call.execute() } returns respuesta(configurada.first, configurada.second, request.url.toString())
                }
                call
            }
        }
    }

    private fun repo(st: SecureStorage, client: OkHttpClient, dao: FakeCashDrawerDao = FakeCashDrawerDao()) =
        CashDrawerRepository(dao = dao, secureStorage = st, client = client, pendingCashSales = sinCobrosEnCola())

    private fun errorJson(code: String, message: String) = """{"message":"$message","code":"$code"}"""

    // MARK: - 1. La intención se guarda ANTES de tocar la red

    /**
     * 🔴 EL PROCESO MUERE JUSTO ANTES DEL POST y la apertura sobrevive.
     *
     * Se simula con un `Error` (no una `Exception`): ningún `catch (e: Exception)` del repositorio
     * lo atrapa, así que es indistinguible de que el proceso se hubiera ido en esa línea. Si la
     * cola se escribiera en el `catch`, aquí quedaría vacía — que es exactamente el defecto.
     */
    @Test
    fun `la apertura queda en la cola aunque el proceso muera antes del POST`() = runTest {
        val st = almacenConCola()
        val repo = repo(st, clienteConCodigos(alLlamar = { throw ProcesoMuerto() }))

        runCatching { repo.openSession(200_000) } // $2,000 de fondo

        val pendientes = cola(st)
        assertEquals("la apertura no sobrevivió a la muerte del proceso: $pendientes", 1, pendientes.size)
        assertEquals("OPEN", pendientes[0].kind)
        assertEquals(200_000, pendientes[0].amountCents)
    }

    /** Sin red, la caja se abre igual EN LOCAL y su apertura queda encolada. */
    @Test
    fun `abrir sin red deja la caja abierta en local y su apertura en la cola`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        val repo = repo(st, clienteConCodigos(), dao)

        val sesion = repo.openSession(200_000)

        assertEquals("OPEN", sesion.status)
        assertEquals(1, dao.sessions.size)
        val pendientes = cola(st)
        assertEquals(1, pendientes.size)
        assertEquals("OPEN", pendientes[0].kind)
        assertEquals("la apertura tiene que nombrar a la caja local", sesion.id, pendientes[0].sessionId)
    }

    /** Y con red la apertura NO se queda en la cola: el servidor ya la tiene. */
    @Test
    fun `abrir con red no deja apertura pendiente`() = runTest {
        val st = almacenConCola()
        val repo = repo(st, clienteConCodigos("/cash-drawer/open" to (201 to sesionJson("srv-1"))))

        repo.openSession(200_000)

        assertTrue("la apertura confirmada siguió en la cola: ${cola(st)}", cola(st).none { it.kind == "OPEN" })
    }

    // MARK: - 2. El orden del replay, y la apertura como barrera

    /**
     * 🔴 La apertura SALE PRIMERO. Un retiro mandado antes recibiría 404 («no conozco esa caja»)
     * y volvería a la cola; un cierre mandado antes firmaría el arqueo de una caja que allá no
     * existe.
     */
    @Test
    fun `al reproducir, la apertura va antes que el retiro y que el cierre`() = runTest {
        val st = almacenConCola(
            colaCon(
                cierre("prov-1", 25_000, at = 3),
                retiro("prov-1", 5_000, "loc-a", at = 2),
                apertura("prov-1", 200_000, "loc-open", at = 1),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("prov-1")),
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
                "/cash-drawer/close" to (200 to sesionJson("prov-1")),
                capturadas = llamadas,
            ),
        )

        repo.reproducirPendientes()

        val orden = llamadas.map { it.path.substringAfterLast('/') }.filter { it in setOf("open", "pay-out", "close") }
        assertEquals("el orden del replay no es apertura → movimiento → cierre: $orden", listOf("open", "pay-out", "close"), orden)
    }

    /**
     * 🔴 UNA APERTURA QUE NO LLEGÓ ES BARRERA. Sin ella el servidor no conoce la caja: mandar el
     * retiro o el cierre encima no puede producir más que un número que miente.
     */
    @Test
    fun `una apertura sin confirmar bloquea el retiro y el cierre de esa caja`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-1", 200_000, "loc-open", at = 1),
                retiro("prov-1", 5_000, "loc-a", at = 2),
                cierre("prov-1", 25_000, at = 3),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        // Nada configurado ⇒ el POST de apertura revienta como "sin red".
        val repo = repo(st, clienteConCodigos(capturadas = llamadas))

        repo.reproducirPendientes()

        assertTrue("se intentó la apertura, como debe ser", llamadas.any { it.path.endsWith("/open") })
        assertFalse("el retiro se mandó sin que el server conociera la caja: $llamadas", llamadas.any { it.path.endsWith("/pay-out") })
        assertFalse("el cierre se mandó sin que el server conociera la caja: $llamadas", llamadas.any { it.path.endsWith("/close") })
        assertEquals("nada se perdió de la cola", 3, cola(st).size)
    }

    /** El bloqueo es POR CAJA: la apertura pendiente de una no puede frenar el cierre de otra. */
    @Test
    fun `una apertura pendiente de otra caja no bloquea este cierre`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-OTRA", 200_000, "loc-open", at = 1),
                cierre("srv-1", 25_000, at = 2),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(st, clienteConCodigos("/cash-drawer/close" to (200 to sesionJson("srv-1")), capturadas = llamadas))

        repo.reproducirPendientes()

        assertTrue("el cierre de srv-1 debía intentarse: $llamadas", llamadas.any { it.path.endsWith("/close") })
    }

    /**
     * Cuando la apertura SÍ llega, la caja adopta el id del servidor y **la cola se muda con
     * ella**: el retiro que sigue viaja con el id que el servidor conoce, no con el provisional.
     */
    @Test
    fun `al confirmar la apertura, lo que sigue en la cola viaja con el id del servidor`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-1", 200_000, "loc-open", at = 1),
                retiro("prov-1", 5_000, "loc-a", at = 2),
            ),
        )
        val dao = FakeCashDrawerDao()
        // La caja provisional existe en Room, como después de abrir sin red.
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("srv-1")),
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
                capturadas = llamadas,
            ),
            dao,
        )

        repo.reproducirPendientes()

        val retiro = llamadas.first { it.path.endsWith("/pay-out") }
        assertTrue(
            "el retiro viajó con el id provisional: el server contestaría 404 para siempre — ${retiro.body}",
            retiro.body.contains("\"sessionId\":\"srv-1\""),
        )
        assertTrue("la cola debió quedar vacía: ${cola(st)}", cola(st).isEmpty())
    }

    // MARK: - 3. El 409 «ya hay un turno abierto» ADOPTA, no rechaza

    @Test
    fun `un 409 CASH_SHIFT_ALREADY_OPEN adopta la caja del servidor`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to errorJson(CODIGO_CAJA_YA_ABIERTA, "Ya hay un turno de caja abierto en este negocio.")),
                "/cash-drawer/current" to (200 to sesionJson("srv-1")),
                capturadas = llamadas,
            ),
            dao,
        )

        repo.reproducirPendientes()

        assertTrue("no se preguntó por la caja del servidor: $llamadas", llamadas.any { it.path.endsWith("/current") })
        assertEquals("Room debió quedar con UNA caja, la del servidor: ${dao.sessions.keys}", 1, dao.sessions.size)
        assertEquals("srv-1", dao.sessions.keys.first())
        assertTrue("la apertura debió salir de la cola: ${cola(st)}", cola(st).none { it.kind == "OPEN" })
        assertTrue("un 409 que se adopta NO es un rechazo que avisar", repo.operacionesRechazadas().isEmpty())
    }

    /**
     * 🔴 Y el caso contradictorio: el servidor dice que ya hay un turno abierto y su `/current` no
     * trae ninguno. Ahí sí se marca — se muestra y bloquea el cierre — pero NUNCA se borra.
     */
    @Test
    fun `un 409 con un current vacio se marca para avisar y no se borra`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to errorJson(CODIGO_CAJA_YA_ABIERTA, "Ya hay un turno de caja abierto en este negocio.")),
                "/cash-drawer/current" to (200 to """{"success":true,"data":null}"""),
            ),
        )

        repo.reproducirPendientes()

        val avisos = repo.operacionesRechazadas()
        assertEquals("el cajero tiene que enterarse: $avisos", 1, avisos.size)
        assertEquals("OPEN", avisos[0].kind)
        assertEquals("la apertura no puede desaparecer de la cola", 1, cola(st).size)
    }

    /** Si no se pudo preguntar (`/current` sin red), se REINTENTA: no se inventa un rechazo. */
    @Test
    fun `un 409 con el current sin red se reintenta`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to errorJson(CODIGO_CAJA_YA_ABIERTA, "Ya hay un turno de caja abierto en este negocio.")),
            ),
        )

        repo.reproducirPendientes()

        assertTrue("no se pudo saber nada: marcarlo como rechazo sería mentir", repo.operacionesRechazadas().isEmpty())
        assertEquals("la apertura sigue viva", 1, cola(st).size)
    }

    // MARK: - 4. `SHIFT_CLOSE_IN_PROGRESS` es transitorio

    /**
     * 🔴 El servidor está cerrando el turno anterior — dura milisegundos. Con la lista de rechazos
     * por código HTTP a secas (409 ∈ RECHAZOS_DEFINITIVOS) esto se marcaba como rechazo y la
     * intención de abrir desaparecía: el segundo defecto que esta tarea arregla.
     */
    @Test
    fun `SHIFT_CLOSE_IN_PROGRESS se reintenta y la apertura sigue en la cola`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to errorJson(CODIGO_CIERRE_EN_PROCESO, "El cierre de turno ya está en proceso.")),
            ),
        )

        repo.reproducirPendientes()

        assertTrue("un transitorio NO es un rechazo: ${repo.operacionesRechazadas()}", repo.operacionesRechazadas().isEmpty())
        val pendientes = cola(st)
        assertEquals("la intención de abrir se perdió", 1, pendientes.size)
        assertEquals("OPEN", pendientes[0].kind)
    }

    // MARK: - La función pura

    @Test
    fun `el clasificador distingue los dos 409 del servidor`() {
        assertEquals(
            DestinoDeLaOperacion.ADOPTAR_LA_DEL_SERVIDOR,
            clasificarRespuestaDelServer("OPEN", 409, CODIGO_CAJA_YA_ABIERTA),
        )
        assertEquals(
            DestinoDeLaOperacion.REINTENTAR,
            clasificarRespuestaDelServer("OPEN", 409, CODIGO_CIERRE_EN_PROCESO),
        )
        assertEquals(
            "un cierre en proceso es transitorio para cualquier operación",
            DestinoDeLaOperacion.REINTENTAR,
            clasificarRespuestaDelServer("PAY_OUT", 409, CODIGO_CIERRE_EN_PROCESO),
        )
        assertEquals(
            "un 409 con otro código sigue siendo un rechazo",
            DestinoDeLaOperacion.RECHAZADA,
            clasificarRespuestaDelServer("OPEN", 409, "OTRA_COSA"),
        )
        assertEquals(
            "y un 409 sin código también",
            DestinoDeLaOperacion.RECHAZADA,
            clasificarRespuestaDelServer("OPEN", 409, null),
        )
    }

    /** «Ya hay un turno abierto» sólo puede ADOPTARSE sobre una apertura. */
    @Test
    fun `CASH_SHIFT_ALREADY_OPEN sobre un retiro no adopta nada`() {
        assertEquals(
            DestinoDeLaOperacion.RECHAZADA,
            clasificarRespuestaDelServer("PAY_OUT", 409, CODIGO_CAJA_YA_ABIERTA),
        )
    }

    /** Lo de siempre no cambia: la firma nueva tiene default y los demás códigos deciden igual. */
    @Test
    fun `el resto de la clasificacion no cambia`() {
        assertEquals(DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("OPEN", 201))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("OPEN", 0))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("OPEN", 503))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("OPEN", 429))
        assertEquals("una apertura que el server no conoce se reintenta", DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("OPEN", 404))
        assertEquals(DestinoDeLaOperacion.RECHAZADA, clasificarRespuestaDelServer("OPEN", 400))
    }

    /** El código sale de la LLAVE `code`, nunca del texto del mensaje. */
    @Test
    fun `el codigo de negocio se lee de la llave, no del mensaje`() {
        assertEquals(CODIGO_CAJA_YA_ABIERTA, codigoDeNegocio(errorJson(CODIGO_CAJA_YA_ABIERTA, "Ya hay un turno")))
        assertEquals(null, codigoDeNegocio("""{"message":"habla de CASH_SHIFT_ALREADY_OPEN en prosa"}"""))
        assertEquals(null, codigoDeNegocio(""))
        assertEquals(null, codigoDeNegocio("<html>502 Bad Gateway</html>"))
        assertEquals(null, codigoDeNegocio("""{"message":"x","code":""}"""))
    }

    // MARK: - 5. Compatibilidad con una cola ya guardada en un aparato de la calle

    /**
     * 🔴 Una cola escrita por la versión ANTERIOR sólo trae CLOSE/PAY_IN/PAY_OUT. Tiene que leerse
     * igual: `OPEN` es un valor más de `kind`, no un campo nuevo. Si esto reventara, un aparato en
     * la calle perdería su cierre encolado al actualizar.
     */
    @Test
    fun `una cola vieja sin aperturas se lee sin reventar`() = runTest {
        val st = almacenConCola(colaCon(retiro("srv-1", 5_000, "loc-a", at = 1), cierre("srv-1", 25_000, at = 2)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
                "/cash-drawer/close" to (200 to sesionJson("srv-1")),
                capturadas = llamadas,
            ),
        )

        repo.reproducirPendientes()

        assertEquals(
            "una cola sin aperturas se reproduce como siempre: retiro y luego cierre",
            listOf("pay-out", "close"),
            llamadas.map { it.path.substringAfterLast('/') },
        )
        assertTrue("todo se confirmó: ${cola(st)}", cola(st).isEmpty())
    }

    /** Y un `kind` desconocido de una versión FUTURA tampoco puede tumbar la cola. */
    @Test
    fun `una cola con un kind desconocido no revienta`() = runTest {
        val st = almacenConCola(
            colaCon(
                """{"kind":"ALGO_NUEVO","sessionId":"srv-1","amountCents":100,"at":1}""",
                cierre("srv-1", 25_000, at = 2),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(st, clienteConCodigos("/cash-drawer/close" to (200 to sesionJson("srv-1")), capturadas = llamadas))

        repo.reproducirPendientes()

        assertTrue("el cierre tenía que seguir mandándose", llamadas.any { it.path.endsWith("/close") })
    }

    // MARK: - Helpers de la cola

    private fun colaCon(vararg filas: String) = "[" + filas.joinToString(",") + "]"

    private fun apertura(sessionId: String, cents: Int, localId: String, at: Long) =
        """{"kind":"OPEN","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":$at}"""

    private fun retiro(sessionId: String, cents: Int, localId: String, at: Long) =
        """{"kind":"PAY_OUT","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":$at}"""

    private fun cierre(sessionId: String, cents: Int, at: Long) =
        """{"kind":"CLOSE","sessionId":"$sessionId","amountCents":$cents,"at":$at}"""

    private fun sesionLocal(id: String) = com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity(
        id = id,
        venueId = VENUE_ID,
        deviceName = "Sunmi D3",
        openedByStaffId = "staff-1",
        openedByName = "Ana Ruiz",
        openedAt = haceMinutos(90),
        startingAmountCents = 200_000,
        status = "OPEN",
    )

    /** No es una `Exception`: ningún `catch` del repositorio la atrapa. Es "el proceso se fue". */
    private class ProcesoMuerto : Error("el proceso murió antes del POST")
}
