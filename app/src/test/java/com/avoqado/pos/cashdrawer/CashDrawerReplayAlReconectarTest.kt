package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.esMiPropiaCaja
import com.avoqado.pos.cashdrawer.data.losCobrosPuedenSalir
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 8b, ronda de arreglo 3 — los DOS defectos medidos en una Samsung SM-X133 el 5-sep-2026
 * (`/full-testing`, receta: abrir la caja sin red, matar la app, relanzar sin red, volver a Cobrar
 * y encender el WiFi sin tocar la pantalla).
 *
 * F1 — la cola del cajón NO se reproducía sola al volver la red: el `POST /open` salió una vez
 * mientras el WiFi apenas subía, falló, y nadie volvió a intentarlo en 8 minutos. Mientras tanto
 * los cobros SÍ se reintentaban, así que llegaban al servidor antes que la apertura y nacían con
 * `shiftId = null`. Aquí se prueba la mitad que vive en el repositorio: la barrera
 * ([losCobrosPuedenSalir]) y el punto de entrada del sincronizador. El disparador al reconectar y
 * el orden entre las dos colas se prueban en `PaymentSyncCajonPrimeroTest`.
 *
 * F2 — el replay corría DOS veces a la vez: dos `POST /open` con 48 ms de diferencia, y el segundo
 * volvió `cajaCreada:false` sobre la caja que el primero acababa de crear. La pantalla lo pintó
 * como «Esta caja ya estaba abierta · Se adoptó la caja abierta por Main Owner» sobre la caja del
 * propio cajero. Dos arreglos: el candado de un solo vuelo y la regla de la caja propia.
 */
class CashDrawerReplayAlReconectarTest {

    // MARK: - Andamio (copiado de CashDrawerAperturaDurableTest: mismo contrato)

    /**
     * El `deviceName` que el repositorio compone en un test JVM. Sale de
     * `"${'$'}{Build.MANUFACTURER} ${'$'}{Build.MODEL}"` y en estas pruebas los stubs del SDK devuelven
     * null (`unitTests.isReturnDefaultValues = true`), así que el aparato "propio" es literalmente
     * «null null». No es un valor bonito, pero es EL que el código produce aquí: escribir otro
     * haría pasar la prueba sin ejercitar la comparación.
     */
    private val miAparato = "null null"


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
     * Un servidor falso que CUENTA los `POST /open` y tarda en contestar. La tardanza es la mitad
     * del andamio: sin ella dos corrutinas no llegan a solaparse y la prueba pasaría con el
     * candado quitado.
     */
    private fun servidorQueCuentaAperturas(
        contador: AtomicInteger,
        retrasoMs: Long = 60,
        cuerpo: (Int) -> String,
    ): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val path = request.url.encodedPath
            val call = mockk<Call>()
            if (path.endsWith("/open")) {
                val nth = contador.incrementAndGet()
                every { call.execute() } answers {
                    // `runBlocking` porque `execute()` es bloqueante de verdad, igual que OkHttp.
                    runBlocking { delay(retrasoMs) }
                    respuesta(201, cuerpo(nth), request.url.toString())
                }
            } else {
                every { call.execute() } throws IOException("sin red: $path")
            }
            call
        }
    }

    private fun repo(st: SecureStorage, client: OkHttpClient) =
        CashDrawerRepository(dao = FakeCashDrawerDao(), secureStorage = st, client = client, pendingCashSales = sinCobrosEnCola())

    private fun apertura(sessionId: String, cents: Int, localId: String, at: Long) =
        """{"kind":"OPEN","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":$at}"""

    /**
     * La forma REAL de la respuesta del `POST /open`: los campos de la sesión van planos dentro de
     * `data`, con `cajaCreada` de hermano. Es la misma que `sesionJson` del andamio compartido; aquí
     * se escribe aparte sólo porque estas pruebas necesitan mandar el APARATO y la PERSONA.
     */
    private fun cuerpoDeCaja(id: String, cajaCreada: Boolean, fondo: Double, aparato: String, staffId: String) =
        """{"success":true,"data":{"id":"$id","venueId":"$VENUE_ID","deviceName":"$aparato","status":"OPEN",""" +
            """"openedByStaffId":"$staffId","openedByName":"Ana Ruiz",""" +
            """"openedAt":"2026-09-05T16:22:00.000Z","startingAmount":$fondo,""" +
            """"closedByStaffId":null,"closedByName":null,"closedAt":null,""" +
            """"actualAmount":null,"overShort":null,"closingNote":null,""" +
            """"cajaCreada":$cajaCreada,"events":[]}}"""

    // MARK: - F2 · un solo vuelo

    /**
     * 🔴 P1 — DOS replays concurrentes con una apertura en cola mandan UN SOLO `POST /open`.
     *
     * Es el defecto medido tal cual: `syncAndLoad()` y `loadCurrentSession()` del ViewModel se
     * disparan casi a la vez al entrar a Caja. Sin candado, los dos leen la misma cola y los dos
     * mandan la apertura; el segundo recibe `cajaCreada:false` y el cajero ve un aviso falso.
     */
    @Test
    fun `P1 dos replays concurrentes con una apertura en cola mandan UN solo POST`() = runTest {
        val aperturas = AtomicInteger(0)
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val client = servidorQueCuentaAperturas(aperturas) { nth ->
            // El segundo POST (si existiera) contestaría LIGADA, que es lo que se vio en la Samsung.
            cuerpoDeCaja("server-1", cajaCreada = nth == 1, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        val repo = repo(st, client)

        // Dos corrutinas de VERDAD (Dispatchers.IO): `runTest` sin dispatcher real las serializaría
        // y la prueba pasaría con el candado quitado.
        listOf(
            async(Dispatchers.IO) { repo.reproducirPendientes() },
            async(Dispatchers.IO) { repo.reproducirPendientes() },
        ).awaitAll()

        assertEquals("La apertura salió más de una vez", 1, aperturas.get())
        assertTrue("La apertura confirmada debe salir de la cola", cola(st).none { it.kind == "OPEN" })
        // Con un solo POST el servidor contesta `cajaCreada:true` y no hay nada que adoptar. Es el
        // aviso falso que el cajero vio en la Samsung, y con el candado deja de existir.
        assertTrue("Un solo vuelo no puede producir un aviso de adopción", repo.cajasAdoptadas().isEmpty())
    }

    // MARK: - F2 · la caja propia no se "adopta"

    /**
     * 🔴 P2 — `ligada = true` sobre MI PROPIA caja (mismo aparato, misma persona, mismo fondo) NO
     * anota adopción. Es lo que pasa cuando el `OPEN` sí aterrizó y su respuesta se perdió.
     */
    @Test
    fun `P2 una caja LIGADA propia no anota adopcion`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val client = servidorQueCuentaAperturas(AtomicInteger(0), retrasoMs = 0) {
            cuerpoDeCaja("server-1", cajaCreada = false, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        val repo = repo(st, client)

        repo.reproducirPendientes()

        assertTrue("La caja del propio aparato no es una adopción", repo.cajasAdoptadas().isEmpty())
    }

    /** 🔴 P2 — la caja de OTRO sí avisa: es la regresión del caso real de dos aparatos. */
    @Test
    fun `P2 una caja LIGADA ajena SI anota adopcion`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val client = servidorQueCuentaAperturas(AtomicInteger(0), retrasoMs = 0) {
            cuerpoDeCaja("server-9", cajaCreada = false, fondo = 200.0, aparato = "otro aparato", staffId = "staff-otro")
        }
        val repo = repo(st, client)

        repo.reproducirPendientes()

        val avisos = repo.cajasAdoptadas()
        assertEquals("La caja de otro aparato SÍ es una adopción", 1, avisos.size)
        assertEquals("server-9", avisos.first().sessionId)
    }

    /**
     * 🔴 P2 — mismo aparato y misma persona pero FONDO DISTINTO: el aviso SALE.
     *
     * Es la condición que vuelve segura la regla: dos tablets idénticas del mismo local comparten
     * `deviceName` y pueden compartir la sesión del dueño. Mirar sólo aparato y persona callaría
     * una adopción real; con el fondo dentro, sólo se calla cuando no había nada que avisar.
     */
    @Test
    fun `P2 mismo aparato y misma persona con fondo distinto SI avisa`() {
        val op = PendingDrawerOp("OPEN", "local-1", 200_000, null, "ev-1", 1_000)
        val server = CashDrawerSessionEntity(
            id = "server-1",
            venueId = VENUE_ID,
            deviceName = "samsung SM-X133",
            openedByStaffId = "staff-1",
            openedByName = "Ana Ruiz",
            openedAt = 2_000,
            startingAmountCents = 50_000,
            status = "OPEN",
        )
        assertFalse(
            "Un fondo distinto es dinero que el cajero tiene que saber",
            esMiPropiaCaja(server, op, "samsung SM-X133", "staff-1"),
        )
        assertTrue(
            "Con el MISMO fondo sí es la caja propia",
            esMiPropiaCaja(server.copy(startingAmountCents = 200_000), op, "samsung SM-X133", "staff-1"),
        )
        assertFalse(
            "Otro aparato nunca es la caja propia",
            esMiPropiaCaja(server.copy(startingAmountCents = 200_000), op, "sunmi D3", "staff-1"),
        )
        assertFalse(
            "Otra persona nunca es la caja propia",
            esMiPropiaCaja(server.copy(startingAmountCents = 200_000), op, "samsung SM-X133", "staff-9"),
        )
    }

    // MARK: - F1 · la barrera de los cobros

    /** 🔴 P1 — con una apertura VIVA en la cola, los cobros NO pueden salir. */
    @Test
    fun `P1 una apertura viva en la cola frena los cobros`() {
        val cola = listOf(PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000))
        assertFalse(losCobrosPuedenSalir(cola))
    }

    /** Sin apertura pendiente (o con la cola vacía) los cobros salen como siempre. */
    @Test
    fun `P1 sin apertura pendiente los cobros salen`() {
        assertTrue(losCobrosPuedenSalir(emptyList()))
        assertTrue(
            "Un retiro o un cierre pendientes no frenan los cobros: su caja YA existe en el server",
            losCobrosPuedenSalir(
                listOf(
                    PendingDrawerOp("PAY_OUT", "local-1", 5_000, null, "ev-2", 2_000),
                    PendingDrawerOp("CLOSE", "local-1", 45_000, null, null, 3_000),
                ),
            ),
        )
    }

    /**
     * 🔴 P1 — una apertura RECHAZADA no es barrera, y es una decisión, no un descuido: el servidor
     * nunca la va a aceptar sola, así que bloquear con ella congelaría la cola de cobros PARA
     * SIEMPRE. Esa apertura ya sale en rojo en Caja con su propio «Reintentar».
     */
    @Test
    fun `P1 una apertura RECHAZADA no congela los cobros para siempre`() {
        val cola = listOf(
            PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000, rechazadaEn = 9_999, motivoDelRechazo = "no"),
        )
        assertTrue(losCobrosPuedenSalir(cola))
    }

    /** 🔴 P1 — el punto de entrada del sincronizador reproduce Y contesta si los cobros salen. */
    @Test
    fun `P1 sincronizarCajonPrimero manda la apertura y solo entonces deja pasar los cobros`() = runTest {
        val aperturas = AtomicInteger(0)
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val sinRed = mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                mockk<Call>().also { every { it.execute() } throws IOException("sin red") }
            }
        }

        // Sin red: la apertura no llega, así que los cobros esperan.
        assertFalse("Sin la apertura confirmada los cobros no pueden salir", repo(st, sinRed).sincronizarCajonPrimero())
        assertEquals("La apertura sigue en la cola", 1, cola(st).count { it.kind == "OPEN" })

        // Vuelve la red: la apertura aterriza y los cobros quedan libres.
        val conRed = servidorQueCuentaAperturas(aperturas, retrasoMs = 0) {
            cuerpoDeCaja("server-1", cajaCreada = true, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        assertTrue("Con la apertura confirmada los cobros salen", repo(st, conRed).sincronizarCajonPrimero())
        assertEquals(1, aperturas.get())
        assertTrue(cola(st).none { it.kind == "OPEN" })
    }

    // MARK: - Compatibilidad

    /**
     * P2 — una cola guardada por la versión ANTERIOR se lee igual. No se agregó ningún campo:
     * el candado y la barrera son comportamiento, no formato.
     */
    @Test
    fun `P2 una cola de la version anterior se lee igual`() = runTest {
        val vieja = """[{"kind":"PAY_OUT","sessionId":"local-1","amountCents":5000,"at":1000},""" +
            """{"kind":"CLOSE","sessionId":"local-1","amountCents":45000,"at":2000}]"""
        val st = almacenConCola(vieja)
        val leida = cola(st)

        assertEquals(2, leida.size)
        assertEquals("PAY_OUT", leida[0].kind)
        assertEquals(5_000, leida[0].amountCents)
        assertTrue("Sin apertura encolada, esa cola vieja no frena ningún cobro", losCobrosPuedenSalir(leida))
    }
}
