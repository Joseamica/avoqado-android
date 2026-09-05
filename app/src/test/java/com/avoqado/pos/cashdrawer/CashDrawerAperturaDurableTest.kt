package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CODIGO_CAJA_YA_ABIERTA
import com.avoqado.pos.cashdrawer.data.CODIGO_CIERRE_EN_PROCESO
import com.avoqado.pos.cashdrawer.data.DesenlaceDeLaApertura
import com.avoqado.pos.cashdrawer.data.DestinoDeLaOperacion
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.clasificarApertura
import com.avoqado.pos.cashdrawer.data.clasificarRespuestaDelServer
import com.avoqado.pos.cashdrawer.data.codigoDeNegocio
import com.avoqado.pos.cashdrawer.data.leerCajaCreada
import com.avoqado.pos.cashdrawer.data.textoDeAdopcion
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
 * 🔴 LA APERTURA DE LA CAJA ES DURABLE — Task 8b (4-sep-2026), ronda de arreglo 1.
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
        var avisos: String? = null
        every { st.drawerAdoptionNoticesJson(any()) } answers { avisos }
        every { st.setDrawerAdoptionNoticesJson(any(), any()) } answers { avisos = secondArg() }
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

    /**
     * 🔴 Y una cola ILEGIBLE no puede impedir que la apertura se intente. El disparo pasa por el
     * replay para respetar el orden (C1), pero la intención del cajero no puede depender de que
     * una lectura de disco funcione: el almacén de este test no recuerda nada.
     */
    @Test
    fun `con la cola ilegible la apertura se intenta igual`() = runTest {
        val st = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns VENUE_ID
            every { it.userId } returns "staff-1"
            every { it.pendingDrawerOpsJson(any()) } returns null // nunca recuerda nada
        }
        val dao = FakeCashDrawerDao()
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(st, clienteConCodigos("/cash-drawer/open" to (201 to sesionJson("srv-1")), capturadas = llamadas), dao)

        val sesion = repo.openSession(200_000)

        assertTrue("la apertura ni siquiera se intentó: $llamadas", llamadas.any { it.path.endsWith("/open") })
        assertEquals("srv-1", sesion.id)
    }

    // MARK: - 2. El orden del replay, y la apertura como barrera

    /**
     * Dentro de UNA caja el orden es el de siempre — apertura → movimiento → cierre — porque ése
     * es el orden CRONOLÓGICO en que ocurrieron: la apertura se encola al crear la caja.
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

    /**
     * 🔴 EL DEFECTO C1, EN SU FORMA EXACTA: el cierre de la caja A no aterriza y la apertura de la
     * caja B NO puede adelantarse.
     *
     * Con el orden anterior —por TIPO y global— el `OPEN(B)` salía SIEMPRE primero. El servidor,
     * que sólo admite un turno abierto por negocio, LIGA la apertura a la caja A que sigue abierta
     * y contesta 2xx con ELLA; el cliente lo leía como «mi apertura se confirmó», borraba `OPEN(B)`
     * de la cola y mandaba los movimientos de B contra A. Resultado: el arqueo de A firmado con
     * dinero de B, y la caja B sin existir jamás en el servidor — sus cobros nacen con
     * `shiftId = null`, que es el defecto que este proyecto existe para cerrar.
     */
    @Test
    fun `un cierre que no aterriza detiene la apertura de la caja siguiente`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("srv-A", 100_000, "loc-open-a", at = 1),
                retiro("srv-A", 5_000, "loc-a", at = 2),
                cierre("srv-A", 95_000, at = 3),
                apertura("prov-B", 200_000, "loc-open-b", at = 4),
                ingreso("prov-B", 3_000, "loc-b", at = 5),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("srv-A")),
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
                // El cierre NO se configura: revienta como "sin red".
                "/cash-drawer/pay-in" to (201 to eventoJson("srv-e2", "PAY_IN", "30.00", localId = "loc-b")),
                capturadas = llamadas,
            ),
        )

        repo.reproducirPendientes()

        val rutas = llamadas.map { it.path.substringAfterLast('/') }
        assertEquals(
            "la corrida tenía que detenerse en el cierre que no aterrizó: $rutas",
            listOf("open", "pay-out", "close"),
            rutas,
        )
        assertTrue(
            "la apertura de la caja B se mandó ANTES del cierre de la A: es C1 — $llamadas",
            llamadas.none { it.body.contains("\"startingAmount\":2000") },
        )
        assertTrue(
            "la apertura de B tiene que seguir viva en la cola: ${cola(st)}",
            cola(st).any { it.kind == "OPEN" && it.sessionId == "prov-B" },
        )
    }

    /** Con todo confirmándose, el orden es el CRONOLÓGICO exacto, sin importar el tipo. */
    @Test
    fun `con todo confirmado el orden es el cronologico exacto`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("srv-A", 100_000, "loc-open-a", at = 1),
                retiro("srv-A", 5_000, "loc-a", at = 2),
                cierre("srv-A", 95_000, at = 3),
                apertura("prov-B", 200_000, "loc-open-b", at = 4),
                ingreso("prov-B", 3_000, "loc-b", at = 5),
            ),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("srv-A")),
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
                "/cash-drawer/close" to (200 to sesionJson("srv-A")),
                "/cash-drawer/pay-in" to (201 to eventoJson("srv-e2", "PAY_IN", "30.00", localId = "loc-b")),
                capturadas = llamadas,
            ),
        )

        repo.reproducirPendientes()

        assertEquals(
            "el orden del replay no es el cronológico: ${llamadas.map { it.path }}",
            listOf("open", "pay-out", "close", "open", "pay-in"),
            llamadas.map { it.path.substringAfterLast('/') },
        )
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

    // MARK: - 3. El contrato REAL de `POST /open` — la tabla, fila por fila

    /**
     * 🔴 EL CAMINO QUE DE VERDAD VA A CORRER CONTRA `develop`, y que la versión anterior no
     * probaba: **201 con `cajaCreada: false`**. El servidor no rebota cuando ya hay un turno
     * abierto: LIGA y devuelve la caja que ya estaba. Leerlo como «abriste» era el hallazgo C1.
     */
    @Test
    fun `un 201 con cajaCreada false ADOPTA la caja del servidor y lo dice`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (
                    201 to sesionJson("srv-1", cajaCreada = false, startingAmount = 500.00, openedByName = "Hector Díaz")
                    ),
            ),
            dao,
        )

        repo.reproducirPendientes()

        assertEquals("Room debió quedar con UNA caja, la del servidor: ${dao.sessions.keys}", 1, dao.sessions.size)
        assertEquals("srv-1", dao.sessions.keys.first())
        assertTrue("la apertura ya está en el servidor: sale de la cola", cola(st).none { it.kind == "OPEN" })
        val avisos = repo.cajasAdoptadas()
        assertEquals("adoptar la caja de otro NUNCA es silencioso: $avisos", 1, avisos.size)
        assertEquals("Hector Díaz", avisos[0].openedByName)
        assertEquals("el fondo del servidor", 50_000, avisos[0].fondoServidorCents)
        assertEquals("el fondo que tecleó el cajero", 200_000, avisos[0].fondoLocalCents)
        assertTrue("un ligado NO es un rechazo que avisar en rojo", repo.operacionesRechazadas().isEmpty())
    }

    /** Y lo que sigue en la cola viaja con el id ADOPTADO, no con el provisional. */
    @Test
    fun `tras adoptar por cajaCreada false, el ingreso viaja con el id del servidor`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-1", 200_000, "loc-open", at = 1),
                ingreso("prov-1", 3_000, "loc-b", at = 2),
            ),
        )
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("srv-1", cajaCreada = false)),
                "/cash-drawer/pay-in" to (201 to eventoJson("srv-e2", "PAY_IN", "30.00", localId = "loc-b")),
                capturadas = llamadas,
            ),
            dao,
        )

        repo.reproducirPendientes()

        val ingreso = llamadas.first { it.path.endsWith("/pay-in") }
        assertTrue("el ingreso viajó con el id provisional: ${ingreso.body}", ingreso.body.contains("\"sessionId\":\"srv-1\""))
        assertTrue("la cola debió quedar vacía: ${cola(st)}", cola(st).isEmpty())
    }

    /**
     * 🔴 Un 201 con `cajaCreada: true` —o SIN el campo, que es lo que contesta producción hoy— NO
     * saca aviso: es una apertura normal. Confundir «ausente» con `false` le sacaría al cajero un
     * aviso de «adopté la caja de otro» cada mañana contra `main`.
     */
    @Test
    fun `un 201 con cajaCreada true o sin el campo no saca aviso`() = runTest {
        listOf(true, null).forEach { bandera ->
            val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
            val repo = repo(st, clienteConCodigos("/cash-drawer/open" to (201 to sesionJson("srv-1", cajaCreada = bandera))))

            repo.reproducirPendientes()

            assertTrue("cajaCreada=$bandera no puede sacar aviso: ${repo.cajasAdoptadas()}", repo.cajasAdoptadas().isEmpty())
            assertTrue("la apertura debió confirmarse: ${cola(st)}", cola(st).none { it.kind == "OPEN" })
        }
    }

    /** El 409 CON código de `develop` (la carrera de dos aperturas): se pregunta y se adopta. */
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
        assertEquals("adoptar por /current tampoco es silencioso", 1, repo.cajasAdoptadas().size)
    }

    /**
     * 🔴 EL 409 DE PRODUCCIÓN, QUE NO TRAE `code` (hallazgo C2). `main` contesta
     * `ConflictError('Ya existe una caja abierta…')` sin código, y el manejador sólo incluye `code`
     * si existe: la versión anterior lo clasificaba RECHAZADA y bloqueaba la caja de por vida.
     */
    @Test
    fun `un 409 SIN codigo tambien pregunta y adopta, nunca rechaza`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to """{"message":"Ya existe una caja abierta. Cierra la caja actual antes de abrir una nueva."}"""),
                "/cash-drawer/current" to (200 to sesionJson("srv-1")),
                capturadas = llamadas,
            ),
            dao,
        )

        repo.reproducirPendientes()

        assertTrue("un 409 sin código tiene que preguntar por /current: $llamadas", llamadas.any { it.path.endsWith("/current") })
        assertTrue("y NUNCA marcarse como rechazo: ${repo.operacionesRechazadas()}", repo.operacionesRechazadas().isEmpty())
        assertEquals("srv-1", dao.sessions.keys.first())
    }

    /**
     * 🔴 REINTENTAR, no rechazo (hallazgo I3). «Ya hay un turno abierto» seguido de un `/current`
     * vacío es casi siempre una carrera benigna —otro aparato cerró entre mi 409 y mi GET— y
     * marcarla congelaba la caja para siempre, con el dinero ya dentro del cajón.
     */
    @Test
    fun `un 409 con un current vacio se REINTENTA, no se marca`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (409 to errorJson(CODIGO_CAJA_YA_ABIERTA, "Ya hay un turno de caja abierto en este negocio.")),
                "/cash-drawer/current" to (200 to """{"success":true,"data":null}"""),
            ),
        )

        repo.reproducirPendientes()

        assertTrue("una carrera benigna no puede congelar la caja: ${repo.operacionesRechazadas()}", repo.operacionesRechazadas().isEmpty())
        assertEquals("la apertura no puede desaparecer de la cola", 1, cola(st).size)
        assertEquals("OPEN", cola(st)[0].kind)
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

    /**
     * 🔴 UN RECHAZO DEFINITIVO (403) NO BORRA LA APERTURA (hallazgo I4): la caja existe en el
     * aparato, su cierre queda bloqueado, y la única salida es reintentarla. «Ya lo vi» —que sobre
     * un movimiento cierra el asunto— aquí la borraba y dejaba la caja en un limbo sin salida.
     */
    @Test
    fun `una apertura rechazada se marca, sobrevive a Ya lo vi y se puede reintentar`() = runTest {
        val st = almacenConCola(colaCon(apertura("prov-1", 200_000, "loc-open", at = 1)))
        val repo = repo(
            st,
            clienteConCodigos("/cash-drawer/open" to (403 to """{"message":"No tienes permiso para abrir la caja."}""")),
        )

        repo.reproducirPendientes()

        val avisos = repo.operacionesRechazadas()
        assertEquals("el cajero tiene que enterarse: $avisos", 1, avisos.size)
        assertEquals("OPEN", avisos[0].kind)
        assertEquals("el motivo sale del servidor", "No tienes permiso para abrir la caja.", avisos[0].motivo)

        repo.descartarRechazada(avisos[0].localKey)
        assertEquals("«Ya lo vi» NO puede borrar una apertura: dejaría la caja sin salida", 1, cola(st).size)

        repo.reintentarApertura(avisos[0].localKey)
        assertTrue("reintentar tiene que quitarle la marca", repo.operacionesRechazadas().isEmpty())
        assertEquals("y dejarla viva en la cola", 1, cola(st).size)
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

    /**
     * 🔴 LA TABLA DEL CONTRATO REAL, FILA POR FILA. Medida en las dos ramas del servidor el
     * 4-sep-2026: `develop` liga y manda `cajaCreada`; `main` (producción hoy) rebota con un 409
     * SIN código. La versión anterior de esta tarea se construyó sobre lo contrario.
     */
    @Test
    fun `el clasificador de la apertura sigue el contrato real del servidor`() {
        // develop, caja nueva
        assertEquals(DesenlaceDeLaApertura.CREADA, clasificarApertura(201, null, true))
        // main (producción hoy): el campo no viene, y ese 201 sólo puede ser una caja nueva
        assertEquals("campo ausente NO es false", DesenlaceDeLaApertura.CREADA, clasificarApertura(201, null, null))
        // develop, ya había una caja: LIGA y devuelve la que estaba
        assertEquals(DesenlaceDeLaApertura.LIGADA, clasificarApertura(201, null, false))
        // develop, carrera de dos aperturas
        assertEquals(DesenlaceDeLaApertura.PREGUNTAR_POR_LA_SUYA, clasificarApertura(409, CODIGO_CAJA_YA_ABIERTA, null))
        // 🔴 main: 409 SIN código. Esto es lo que rompía C2.
        assertEquals(DesenlaceDeLaApertura.PREGUNTAR_POR_LA_SUYA, clasificarApertura(409, null, null))
        // y un 409 con un código que no conocemos tampoco es un rechazo
        assertEquals(DesenlaceDeLaApertura.PREGUNTAR_POR_LA_SUYA, clasificarApertura(409, "OTRA_COSA", null))
        // el cierre de turno en curso dura milisegundos
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(409, CODIGO_CIERRE_EN_PROCESO, null))
        // rechazos definitivos
        assertEquals(DesenlaceDeLaApertura.RECHAZADA, clasificarApertura(400))
        assertEquals(DesenlaceDeLaApertura.RECHAZADA, clasificarApertura(403))
        assertEquals(DesenlaceDeLaApertura.RECHAZADA, clasificarApertura(422))
        // transitorios
        assertEquals("sin red", DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(0))
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(401))
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(404))
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(408))
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(429))
        assertEquals(DesenlaceDeLaApertura.REINTENTAR, clasificarApertura(503))
    }

    /** `cajaCreada` se lee de la LLAVE del cuerpo, y ausente ≠ `false`. */
    @Test
    fun `cajaCreada se lee de la llave y ausente no es false`() {
        assertEquals(true, leerCajaCreada("""{"success":true,"data":{"id":"s1","cajaCreada":true}}"""))
        assertEquals(false, leerCajaCreada("""{"success":true,"data":{"id":"s1","cajaCreada":false}}"""))
        assertEquals(null, leerCajaCreada("""{"success":true,"data":{"id":"s1"}}"""))
        assertEquals("el texto no es una llave", null, leerCajaCreada("""{"message":"cajaCreada false en prosa"}"""))
        assertEquals(null, leerCajaCreada(""))
        assertEquals(null, leerCajaCreada("<html>502 Bad Gateway</html>"))
    }

    /** El aviso de adopción DICE los dos fondos cuando no coinciden, y se acorta cuando sí. */
    @Test
    fun `el texto de la adopcion distingue fondos distintos de fondos iguales`() {
        val distinto = textoDeAdopcion("Hector Díaz", "07:38", fondoServidorCents = 50_000, fondoLocalCents = 200_000)
        assertTrue("tiene que nombrar a quien la abrió: $distinto", distinto.contains("Hector Díaz"))
        assertTrue("y la hora: $distinto", distinto.contains("07:38"))
        assertTrue("el fondo del servidor: $distinto", distinto.contains("$500.00"))
        assertTrue("el fondo que tecleó el cajero: $distinto", distinto.contains("$2,000.00"))
        assertTrue("y que NO se registró: $distinto", distinto.contains("no se registró"))

        val igual = textoDeAdopcion("Hector Díaz", "07:38", fondoServidorCents = 200_000, fondoLocalCents = 200_000)
        assertEquals("Se adoptó la caja abierta por Hector Díaz.", igual)
    }

    /** Los movimientos NO cambian: para ellos un 409 sigue siendo un rechazo definitivo. */
    @Test
    fun `la clasificacion de los movimientos no cambia`() {
        assertEquals(DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("PAY_OUT", 201))
        assertEquals(
            "un cierre en proceso es transitorio para cualquier operación",
            DestinoDeLaOperacion.REINTENTAR,
            clasificarRespuestaDelServer("PAY_OUT", 409, CODIGO_CIERRE_EN_PROCESO),
        )
        assertEquals(
            "«ya hay un turno abierto» sobre un retiro no adopta nada",
            DestinoDeLaOperacion.RECHAZADA,
            clasificarRespuestaDelServer("PAY_OUT", 409, CODIGO_CAJA_YA_ABIERTA),
        )
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 0))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 503))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 429))
        assertEquals(DestinoDeLaOperacion.REINTENTAR, clasificarRespuestaDelServer("PAY_OUT", 404))
        assertEquals("un cierre 404 ya estaba cerrado", DestinoDeLaOperacion.CONFIRMADA, clasificarRespuestaDelServer("CLOSE", 404))
        assertEquals(DestinoDeLaOperacion.RECHAZADA, clasificarRespuestaDelServer("PAY_OUT", 400))
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

        assertTrue("un tipo desconocido no puede tumbar la corrida", llamadas.isNotEmpty() || cola(st).isNotEmpty())
    }

    /**
     * 🔴 UN `kind` DESCONOCIDO **CON `localId`** NUNCA SE MANDA COMO RETIRO (hallazgo M3).
     *
     * El `else` del enrutador caía en `pay-out`: una versión futura que introdujera un tipo nuevo
     * y luego se degradara publicaría esa operación —el fondo de una apertura, por ejemplo— como
     * dinero SALIENDO del cajón. La prueba anterior no lo veía porque su fila iba sin `localId`,
     * así que salía por un corte anterior y nunca llegaba a la red.
     */
    @Test
    fun `un kind desconocido con localId no se manda como retiro`() = runTest {
        val st = almacenConCola(
            colaCon("""{"kind":"ALGO_NUEVO","sessionId":"srv-1","amountCents":200000,"localId":"loc-x","at":1}"""),
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "2000.00", localId = "loc-x")),
                "/cash-drawer/pay-in" to (201 to eventoJson("srv-e1", "PAY_IN", "2000.00", localId = "loc-x")),
                capturadas = llamadas,
            ),
        )

        repo.reproducirPendientes()

        assertTrue("se publicó un tipo desconocido como movimiento de dinero: $llamadas", llamadas.isEmpty())
        val avisos = repo.operacionesRechazadas()
        assertEquals("y tiene que VERSE, no desaparecer: $avisos", 1, avisos.size)
        assertTrue("el motivo tiene que nombrar el tipo: ${avisos[0].motivo}", avisos[0].motivo.contains("ALGO_NUEVO"))
    }

    /**
     * 🔴 M1: la entrada se localiza por su `localId`, no por `(kind, sessionId)`.
     *
     * Adoptar la caja del servidor le CAMBIA el `sessionId` a la fila guardada. Con la identidad
     * vieja, una confirmación posterior no casaba con la fila y el movimiento se quedaba en la cola
     * para siempre; y un rechazo nunca llegaba a registrarse.
     */
    @Test
    fun `tras adoptar, la confirmacion localiza la entrada por su localId`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-1", 200_000, "loc-open", at = 1),
                retiro("prov-1", 5_000, "loc-a", at = 2),
            ),
        )
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1")
        val repo = repo(
            st,
            clienteConCodigos(
                "/cash-drawer/open" to (201 to sesionJson("srv-1")),
                "/cash-drawer/pay-out" to (201 to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-a")),
            ),
            dao,
        )

        repo.reproducirPendientes()

        assertTrue("nada pudo quedarse en la cola: ${cola(st)}", cola(st).isEmpty())
    }

    /**
     * 🔴 I2: ADOPTAR UNA CAJA QUE ESTE APARATO YA CERRÓ NO PUEDE CREAR UNA SEGUNDA CAJA ABIERTA.
     *
     * Abrir sin red por la mañana y cerrar sin red por la noche —el escenario del ICP— dejaba la
     * caja del servidor entrando a Room OPEN y vacía junto a la local cerrada: la caja FANTASMA.
     * Y el conteo del cajero se perdía de la pantalla.
     */
    @Test
    fun `adoptar sobre una caja ya cerrada en local no deja una caja fantasma`() = runTest {
        val st = almacenConCola(
            colaCon(
                apertura("prov-1", 200_000, "loc-open", at = 1),
                cierre("prov-1", 195_000, at = 2),
            ),
        )
        val dao = FakeCashDrawerDao()
        dao.sessions["prov-1"] = sesionLocal("prov-1").copy(
            status = "CLOSED",
            closedAt = System.currentTimeMillis(),
            actualAmountCents = 195_000,
        )
        val repo = repo(
            st,
            // El servidor devuelve la caja ABIERTA (todavía no sabe del cierre).
            clienteConCodigos("/cash-drawer/open" to (201 to sesionJson("srv-1", cajaCreada = false))),
            dao,
        )

        repo.reproducirPendientes()

        val abiertas = dao.sessions.values.filter { it.status == "OPEN" }
        assertTrue("quedó una caja fantasma abierta: ${dao.sessions.values.map { it.id to it.status }}", abiertas.isEmpty())
        assertEquals("y el conteo del cajero tiene que sobrevivir", 195_000, dao.sessions.values.first { it.id == "srv-1" }.actualAmountCents)
        assertTrue(
            "el cierre encolado tiene que quedar nombrando a la caja del servidor: ${cola(st)}",
            cola(st).any { it.kind == "CLOSE" && it.sessionId == "srv-1" },
        )
    }

    // MARK: - Helpers de la cola

    private fun colaCon(vararg filas: String) = "[" + filas.joinToString(",") + "]"

    private fun apertura(sessionId: String, cents: Int, localId: String, at: Long) =
        """{"kind":"OPEN","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":$at}"""

    private fun ingreso(sessionId: String, cents: Int, localId: String, at: Long) =
        """{"kind":"PAY_IN","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":$at}"""

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
