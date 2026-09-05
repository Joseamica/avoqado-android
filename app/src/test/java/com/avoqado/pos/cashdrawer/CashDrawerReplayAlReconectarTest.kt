package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.EstadoDeLosCobros
import com.avoqado.pos.cashdrawer.data.BACKOFF_DE_LA_APERTURA_MS
import com.avoqado.pos.cashdrawer.data.TOPE_DE_LA_BARRERA_MS
import com.avoqado.pos.cashdrawer.data.aperturaEnBackoff
import com.avoqado.pos.cashdrawer.data.esMiPropiaCaja
import com.avoqado.pos.cashdrawer.data.estadoDeLosCobros
import com.avoqado.pos.cashdrawer.data.losCobrosPuedenSalir
import com.avoqado.pos.cashdrawer.data.textoDeCobrosRetenidos
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

    /**
     * Un servidor alcanzable que CUENTA los `POST /open` y contesta 500. Es el escenario que el
     * tope existe para acotar: hay red, el servidor responde, y la apertura no aterriza igual.
     * (`IOException` sería «sin red», que es justo el caso contrario.)
     */
    private fun servidorConRedQueFalla(contador: AtomicInteger): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val call = mockk<Call>()
            if (request.url.encodedPath.endsWith("/open")) contador.incrementAndGet()
            every { call.execute() } answers { respuesta(500, """{"message":"boom"}""", request.url.toString()) }
            call
        }
    }

    /** Reescribe la cola guardada. Es la forma de mover el reloj sin esperar en tiempo real. */
    private fun reescribirCola(st: SecureStorage, f: (PendingDrawerOp) -> PendingDrawerOp) {
        val nueva = cola(st).map(f)
        st.setPendingDrawerOpsJson(
            VENUE_ID,
            Json { encodeDefaults = false }.encodeToString(ListSerializer(PendingDrawerOp.serializer()), nueva),
        )
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

    /**
     * `hayRed` es el parámetro que la ronda 2 volvió imprescindible: el reloj del tope y el
     * backoff sólo cuentan intentos hechos CON red, así que una prueba que no lo diga no ejercita
     * nada. Por default `false` —igual que el resto de las suites del cajón— porque el cliente
     * falso de casi todas lanza `IOException("sin red")`.
     */
    private fun repo(st: SecureStorage, client: OkHttpClient, hayRed: Boolean = false) =
        CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = st,
            client = client,
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(hayRed),
        )

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

    /**
     * 🔴 P1 (P3-4, subido a P2 por el controlador) — `openSession` concurrente con un replay manda
     * UN SOLO `POST /open`.
     *
     * Es F2 por la puerta de al lado: `openSession` encolaba su apertura y luego la volvía a
     * inyectar como `ademas`, y `reproducirPendientesYaConElCandado` la re-añade cuando ya NO está
     * en la cola guardada — que es exactamente lo que pasa si el replay del sincronizador ganó el
     * candado y acaba de confirmarla. El respaldo `ademas` ahora se usa SÓLO si el disco falló.
     */
    @Test
    fun `P1 abrir la caja mientras corre un replay manda UN solo POST`() = runTest {
        val aperturas = AtomicInteger(0)
        val st = almacenConCola(null)
        val client = servidorQueCuentaAperturas(aperturas, retrasoMs = 60) { nth ->
            cuerpoDeCaja("server-1", cajaCreada = nth == 1, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        // El MISMO repositorio: en producción es `@Singleton`, así que el candado se comparte.
        val repo = repo(st, client)

        listOf(
            async(Dispatchers.IO) { repo.openSession(50_000) },
            async(Dispatchers.IO) { repo.reproducirPendientes() },
        ).awaitAll()

        assertEquals("La apertura salió más de una vez", 1, aperturas.get())
        assertTrue("La apertura confirmada no puede quedarse en la cola", cola(st).none { it.kind == "OPEN" })
        assertTrue("Un solo POST no puede producir un aviso de adopción", repo.cajasAdoptadas().isEmpty())
    }

    /**
     * 🔴 P2 (P2-4) — el replay marca CUÁNDO empezó la espera, y sólo la primera vez.
     *
     * Sin esa marca el tope de 30 minutos no se puede medir; si se reescribiera en cada intento,
     * nunca se cumpliría y los cobros esperarían para siempre — justo lo que el tope viene a cerrar.
     */
    @Test
    fun `P2 la apertura que no llega CON RED guarda cuando empezo la espera, una sola vez`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val repo = repo(st, servidorConRedQueFalla(AtomicInteger(0)), hayRed = true)

        repo.reproducirPendientes()
        val primera = cola(st).first { it.kind == "OPEN" }.primerReintentoEn
        assertTrue("El primer intento fallido CON RED tiene que quedar fechado", primera != null && primera > 0)

        // El backoff impide el segundo POST, pero el reloj de arranque tampoco puede moverse.
        repo.reproducirPendientes()
        assertEquals(
            "El reloj arranca UNA vez: reescribirlo dejaría la barrera sin tope",
            primera,
            cola(st).first { it.kind == "OPEN" }.primerReintentoEn,
        )
    }

    // MARK: - N-P2-1 · el tope sólo cuenta intentos CON RED

    /**
     * 🔴 P2 (N-P2-1, el defecto de dinero de la re-revisión) — 35 MINUTOS DE INTENTOS SIN RED NO
     * GASTAN EL TOPE.
     *
     * Antes, un fallo de red devolvía el mismo `Reintentar` que un 5xx y fechaba el reloj en el
     * acto: abrir la caja con el WiFi apagado, cerrar el local y volver al día siguiente dejaba el
     * presupuesto de media hora consumido, así que el PRIMER tropiezo al reconectar soltaba los
     * cobros sin turno. En un ICP con WiFi malo eso es el martes, no una anomalía.
     */
    @Test
    fun `P2 los intentos SIN RED no arrancan el reloj del tope`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val sinRed = mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                mockk<Call>().also { every { it.execute() } throws IOException("sin red") }
            }
        }

        // 35 minutos de la vida real: el cajero abre sin red y la app reintenta una y otra vez.
        val offline = repo(st, sinRed, hayRed = false)
        repeat(3) { offline.reproducirPendientes() }
        assertEquals(
            "Sin red el reloj del tope no puede arrancar: el servidor ni se enteró",
            null,
            cola(st).first { it.kind == "OPEN" }.primerReintentoEn,
        )
        assertEquals(
            "Y por tanto los cobros siguen retenidos por muchas horas que pasen",
            EstadoDeLosCobros.ESPERANDO_LA_APERTURA,
            estadoDeLosCobros(cola(st), ahora = 1_000 + TOPE_DE_LA_BARRERA_MS * 10),
        )

        // Vuelve la red y el servidor contesta mal UNA vez: aquí SÍ arranca el reloj, en cero.
        val conRed = repo(st, servidorConRedQueFalla(AtomicInteger(0)), hayRed = true)
        assertFalse(
            "Un solo fallo con red no puede soltar los cobros: la espera acaba de empezar",
            conRed.sincronizarCajonPrimero(),
        )
        val arranque = cola(st).first { it.kind == "OPEN" }.primerReintentoEn
        assertTrue("El primer fallo CON red sí fecha el arranque", arranque != null)
        assertTrue(
            "El reloj arranca AHORA, no cuando se encoló hace 35 minutos",
            arranque!! > 1_000,
        )
    }

    /**
     * 🔴 P2 (N-P2-1) — media hora de intentos CON RED sí gasta el tope: los cobros salen sin caja.
     *
     * Es la otra mitad, y la que justifica que el tope exista: con red, un 500 que se repite es
     * exactamente el «transitorio para siempre» que dejaría las ventas del día en el aparato.
     *
     * ⚠️ Esta prueba usa un `mockk<OkHttpClient>` SIN el `ConnectivityInterceptor`, así que fija la
     * regla pero no el estado del aparato: la versión con el interceptor REAL montado y un
     * `MockWebServer` que contesta 500 vive en `CashDrawerIntentoLlegoALaRedTest`, y es la que
     * caza R2-P2-1. Se conserva ésta porque cubre además el camino de `sincronizarCajonPrimero`.
     */
    @Test
    fun `P2 media hora de intentos CON RED suelta los cobros`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val repo = repo(st, servidorConRedQueFalla(AtomicInteger(0)), hayRed = true)

        repo.reproducirPendientes()
        assertTrue("El reloj tiene que estar corriendo", cola(st).first { it.kind == "OPEN" }.primerReintentoEn != null)

        // Media hora después (se mueve el reloj guardado, no se espera en tiempo real).
        val hace31 = System.currentTimeMillis() - TOPE_DE_LA_BARRERA_MS - 60_000
        reescribirCola(st) { if (it.kind == "OPEN") it.copy(primerReintentoEn = hace31, ultimoReintentoEn = hace31) else it }

        assertTrue("Pasado el tope, los cobros salen SIN caja", repo.sincronizarCajonPrimero())
        assertEquals(
            EstadoDeLosCobros.ENVIADOS_SIN_CAJA,
            estadoDeLosCobros(cola(st), System.currentTimeMillis()),
        )
    }

    /** 🔴 P2 (N-P2-1) — confirmar la apertura borra el reloj: la caja siguiente no hereda la espera. */
    @Test
    fun `P2 confirmar la apertura resetea el reloj del tope`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        repo(st, servidorConRedQueFalla(AtomicInteger(0)), hayRed = true).reproducirPendientes()
        assertTrue("Precondición: el reloj arrancó", cola(st).first { it.kind == "OPEN" }.primerReintentoEn != null)

        val conRed = servidorQueCuentaAperturas(AtomicInteger(0), retrasoMs = 0) {
            cuerpoDeCaja("server-1", cajaCreada = true, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        // El backoff acaba de fecharse, así que se mueve el reloj guardado para que toque reintentar.
        reescribirCola(st) { it.copy(ultimoReintentoEn = null) }
        assertTrue("Con la apertura confirmada los cobros salen", repo(st, conRed, hayRed = true).sincronizarCajonPrimero())

        assertTrue("La apertura confirmada sale de la cola, y su reloj con ella", cola(st).none { it.kind == "OPEN" })
        assertEquals(EstadoDeLosCobros.LIBRES, estadoDeLosCobros(cola(st), System.currentTimeMillis()))
    }

    // MARK: - N-P3-5 · el tope es por TODAS las aperturas, no por la primera

    /**
     * 🔴 P2 (N-P3-5) — una apertura vieja y vencida NO levanta la barrera para una caja NUEVA.
     *
     * Con `firstOrNull`, el reloj de la caja de ayer decidía por la de hoy: el cajero abría su
     * caja, cobraba, y sus cobros salían sin turno aunque su apertura llevara dos minutos
     * esperando. El tope es un permiso de último recurso para UNA apertura agotada, no un
     * interruptor que se queda encendido.
     */
    @Test
    fun `P2 una apertura vieja vencida no suelta los cobros de una caja nueva`() {
        val ahora = 10_000_000L
        val vieja = PendingDrawerOp(
            "OPEN", "local-vieja", 50_000, null, "ev-1", 1_000,
            primerReintentoEn = ahora - TOPE_DE_LA_BARRERA_MS - 60_000,
        )
        val nueva = PendingDrawerOp(
            "OPEN", "local-nueva", 30_000, null, "ev-2", ahora - 60_000,
            primerReintentoEn = ahora - 60_000,
        )

        assertEquals(
            "La vieja sola sí levanta la barrera",
            EstadoDeLosCobros.ENVIADOS_SIN_CAJA,
            estadoDeLosCobros(listOf(vieja), ahora),
        )
        assertEquals(
            "Con una apertura NUEVA dentro del plazo, los cobros esperan",
            EstadoDeLosCobros.ESPERANDO_LA_APERTURA,
            estadoDeLosCobros(listOf(vieja, nueva), ahora),
        )
        assertFalse(losCobrosPuedenSalir(listOf(vieja, nueva), ahora))
        assertEquals(
            "Y el orden en la cola no puede cambiar la respuesta",
            EstadoDeLosCobros.ESPERANDO_LA_APERTURA,
            estadoDeLosCobros(listOf(nueva, vieja), ahora),
        )
    }

    // MARK: - N-P3-4 · backoff de la apertura atorada

    /**
     * 🔴 P2 (N-P3-4) — con la apertura atorada CON RED, dos replays seguidos mandan UN solo POST.
     *
     * Desde que el outbox de mesas reproduce el cajón antes de drenar, cada intent encolado
     * dispara un replay: sin backoff, agregar artículos a una mesa producía un `POST /open` por
     * artículo contra un servidor que ya está contestando mal.
     */
    @Test
    fun `P2 la apertura atorada con red no se remanda antes de 30 segundos`() = runTest {
        val intentos = AtomicInteger(0)
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val repo = repo(st, servidorConRedQueFalla(intentos), hayRed = true)

        repo.reproducirPendientes()
        repo.reproducirPendientes()
        repo.reproducirPendientes()
        assertEquals("Tres replays seguidos, un solo POST", 1, intentos.get())

        // Pasados los 30 s vuelve a intentar (se mueve el reloj guardado, no se espera de verdad).
        reescribirCola(st) {
            if (it.kind == "OPEN") it.copy(ultimoReintentoEn = System.currentTimeMillis() - BACKOFF_DE_LA_APERTURA_MS - 1_000) else it
        }
        repo.reproducirPendientes()
        assertEquals("Pasado el backoff sí se reintenta", 2, intentos.get())
    }

    /**
     * 🔴 P1 — y el backoff NO puede retrasar el caso que esta tarea existe para cerrar: sin red no
     * hubo POST del que hacer backoff, así que al volver el WiFi la apertura sale de inmediato.
     */
    @Test
    fun `P1 un fallo SIN RED no activa el backoff`() = runTest {
        val st = almacenConCola("[" + apertura("local-1", 50_000, "ev-1", 1_000) + "]")
        val sinRed = mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                mockk<Call>().also { every { it.execute() } throws IOException("sin red") }
            }
        }
        repo(st, sinRed, hayRed = false).reproducirPendientes()
        assertEquals(
            "Sin red no se fecha el backoff: no hubo POST que amortiguar",
            null,
            cola(st).first { it.kind == "OPEN" }.ultimoReintentoEn,
        )

        // Vuelve el WiFi en el mismo segundo: la apertura tiene que salir YA, no dentro de 30 s.
        val aperturas = AtomicInteger(0)
        val conRed = servidorQueCuentaAperturas(aperturas, retrasoMs = 0) {
            cuerpoDeCaja("server-1", cajaCreada = true, fondo = 500.0, aparato = miAparato, staffId = "staff-1")
        }
        assertTrue(repo(st, conRed, hayRed = true).sincronizarCajonPrimero())
        assertEquals("La apertura sale en cuanto vuelve la red", 1, aperturas.get())
    }

    /** La función pura del backoff, en su frontera exacta. */
    @Test
    fun `P2 aperturaEnBackoff mide desde el ultimo intento con red`() {
        val base = PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000)
        assertFalse("Sin intentos con red no hay backoff", aperturaEnBackoff(base, ahora = 99_999))
        val recien = base.copy(ultimoReintentoEn = 100_000)
        assertTrue(aperturaEnBackoff(recien, ahora = 100_000 + BACKOFF_DE_LA_APERTURA_MS - 1))
        assertFalse(
            "Justo en el tope ya toca reintentar",
            aperturaEnBackoff(recien, ahora = 100_000 + BACKOFF_DE_LA_APERTURA_MS),
        )
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
        assertFalse(losCobrosPuedenSalir(cola, ahora = 1_000))
    }

    /** Sin apertura pendiente (o con la cola vacía) los cobros salen como siempre. */
    @Test
    fun `P1 sin apertura pendiente los cobros salen`() {
        assertTrue(losCobrosPuedenSalir(emptyList(), ahora = 1_000))
        assertTrue(
            "Un retiro o un cierre pendientes no frenan los cobros: su caja YA existe en el server",
            losCobrosPuedenSalir(
                listOf(
                    PendingDrawerOp("PAY_OUT", "local-1", 5_000, null, "ev-2", 2_000),
                    PendingDrawerOp("CLOSE", "local-1", 45_000, null, null, 3_000),
                ),
                ahora = 4_000,
            ),
        )
    }

    // MARK: - P2-4 · la barrera tiene TOPE, y lo DICE

    /**
     * 🔴 P1 — una apertura atorada NO congela los cobros para siempre: a la media hora la barrera
     * se levanta y los cobros salen SIN caja.
     *
     * El porqué, que es una comparación de daños y no una preferencia: una venta que nunca llega
     * al servidor no existe en ningún reporte; una venta sin turno se ve como «fuera de turno» y
     * se reatribuye. Y sí hay errores que el diseño trata como transitorios PARA SIEMPRE (el 409
     * con `/current` vacío, decisión I3), así que sin tope «para siempre» es literal.
     */
    @Test
    fun `P1 a los 30 minutos la barrera se levanta y los cobros salen sin caja`() {
        val cola = listOf(
            PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000, primerReintentoEn = 1_000),
        )
        val tope = TOPE_DE_LA_BARRERA_MS

        assertFalse("Un minuto antes del tope los cobros siguen esperando", losCobrosPuedenSalir(cola, ahora = 1_000 + tope - 60_000))
        assertEquals(EstadoDeLosCobros.ESPERANDO_LA_APERTURA, estadoDeLosCobros(cola, 1_000 + tope - 60_000))

        assertTrue("Justo en el tope ya salen", losCobrosPuedenSalir(cola, ahora = 1_000 + tope))
        assertEquals(EstadoDeLosCobros.ENVIADOS_SIN_CAJA, estadoDeLosCobros(cola, 1_000 + tope))
    }

    /**
     * 🔴 P1 — el reloj arranca en el PRIMER REINTENTO, no al encolar. Sin red el aparato ni
     * siquiera lo intentó: contar esa espera levantaría la barrera por un apagón de WiFi.
     */
    @Test
    fun `P1 una apertura que nunca se ha intentado sigue frenando los cobros`() {
        val cola = listOf(PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000))
        assertFalse(
            "Sin primer reintento la espera ni siquiera ha empezado",
            losCobrosPuedenSalir(cola, ahora = 1_000 + TOPE_DE_LA_BARRERA_MS * 10),
        )
    }

    /** 🔴 P2 — la espera se DICE, y no en rojo. Sin nada pendiente no hay banda. */
    @Test
    fun `P2 el estado visible dice que los cobros esperan y despues que salieron sin caja`() {
        assertEquals(null, textoDeCobrosRetenidos(EstadoDeLosCobros.LIBRES))
        assertEquals(
            "La apertura de caja aún no llega al servidor: los cobros se enviarán en cuanto llegue",
            textoDeCobrosRetenidos(EstadoDeLosCobros.ESPERANDO_LA_APERTURA),
        )
        assertEquals(
            "Cobros enviados sin caja: la apertura sigue pendiente",
            textoDeCobrosRetenidos(EstadoDeLosCobros.ENVIADOS_SIN_CAJA),
        )
        assertEquals(
            "Sin apertura pendiente no hay nada que decir",
            EstadoDeLosCobros.LIBRES,
            estadoDeLosCobros(emptyList(), 99_999_999),
        )
    }

    /** Una apertura RECHAZADA no espera ni avisa: de ella habla el aviso ROJO de la pantalla de Caja. */
    @Test
    fun `P2 una apertura rechazada no produce banda de espera`() {
        val cola = listOf(
            PendingDrawerOp("OPEN", "local-1", 50_000, null, "ev-1", 1_000, rechazadaEn = 2_000, motivoDelRechazo = "no"),
        )
        assertEquals(EstadoDeLosCobros.LIBRES, estadoDeLosCobros(cola, 3_000))
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
        assertTrue(losCobrosPuedenSalir(cola, ahora = 10_000))
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
        assertTrue("Sin apertura encolada, esa cola vieja no frena ningún cobro", losCobrosPuedenSalir(leida, ahora = 5_000))
        assertEquals("El campo nuevo llega nulo, no roto", null, leida[0].primerReintentoEn)
    }
}
