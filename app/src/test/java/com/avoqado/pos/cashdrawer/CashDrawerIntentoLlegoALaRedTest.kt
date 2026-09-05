package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.DesenlaceDelIntento
import com.avoqado.pos.cashdrawer.data.EstadoDeLosCobros
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.TOPE_DE_LA_BARRERA_MS
import com.avoqado.pos.cashdrawer.data.estadoDeLosCobros
import com.avoqado.pos.cashdrawer.data.intentoLlegoALaRed
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ConnectivityInterceptor
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task 8b, ronda de arreglo 3 — **R2-P2-1**: «con red» se decide por el DESENLACE del intento, no
 * preguntándole al monitor.
 *
 * El defecto que cierra, medido leyendo el código vivo del aparato: el reloj del tope de la
 * barrera preguntaba `ConnectivityMonitor.isFullyConnected`, que incluye `isServerReachable`; y
 * esa bandera la escribe el [ConnectivityInterceptor] montado en el ÚNICO `OkHttpClient` de la app
 * (`core/di/NetworkModule.kt`), que ante un **5xx** o un `IOException` llama a `reportServerError()`
 * **dentro** del `execute()`. Secuencia real: `POST /open` → 500 → el interceptor apaga la bandera
 * → el repositorio la consulta → «no había red» → el reloj NO arranca → los cobros del día se
 * quedan en la tablet **para siempre**, con la banda ámbar diciendo «se enviarán en cuanto llegue».
 *
 * Y la prueba que lo cubría pasaba porque su `mockk<OkHttpClient>` no lleva el interceptor: la
 * combinación «respuesta 500 + `isFullyConnected == true`» **no existe en el aparato**. Por eso
 * aquí el cliente es REAL —`OkHttpClient` con el `ConnectivityInterceptor` de producción— contra
 * un `MockWebServer` que contesta 500 de verdad.
 */
class CashDrawerIntentoLlegoALaRedTest {

    // MARK: - La función PURA, fila por fila

    /** Cualquier código HTTP demuestra que el servidor contestó — el 500 es el caso del defecto. */
    @Test
    fun `P2 una respuesta HTTP llego a la red aunque sea un 500`() {
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Respondio(500)))
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Respondio(503)))
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Respondio(409)))
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Respondio(201)))
    }

    /** Un timeout de socket significa que hubo ruta y algo al otro lado nos hizo esperar. */
    @Test
    fun `P2 un timeout de socket llego a la red`() {
        assertTrue(
            intentoLlegoALaRed(DesenlaceDelIntento.Fallo(SocketTimeoutException("timeout"))),
        )
        // ⚠️ El timeout de CONEXIÓN también es SocketTimeoutException en OkHttp y cuenta igual:
        // está declarado en el KDoc de intentoLlegoALaRed. Treinta segundos de espera no es la
        // firma de un WiFi apagado — ésa falla al instante, por DNS.
        assertTrue(
            intentoLlegoALaRed(
                DesenlaceDelIntento.Fallo(
                    SocketTimeoutException("failed to connect to /10.0.0.1 (port 3000) after 30000ms"),
                ),
            ),
        )
    }

    /** DNS que no resuelve: el paquete nunca salió. Es la firma real del WiFi apagado en Android. */
    @Test
    fun `P2 un UnknownHost no llego a la red`() {
        assertFalse(
            intentoLlegoALaRed(
                DesenlaceDelIntento.Fallo(UnknownHostException("Unable to resolve host \"api.avoqado.io\"")),
            ),
        )
    }

    /** El sistema supo al instante que no había a dónde ir. */
    @Test
    fun `P2 un ConnectException no llego a la red`() {
        assertFalse(
            intentoLlegoALaRed(DesenlaceDelIntento.Fallo(ConnectException("Failed to connect to /10.0.0.1:3000"))),
        )
    }

    /** «Network is unreachable» viaja a veces dentro de un SocketException pelón: se mira el mensaje. */
    @Test
    fun `P2 un unreachable no llego a la red aunque el tipo sea generico`() {
        assertFalse(intentoLlegoALaRed(DesenlaceDelIntento.Fallo(SocketException("Network is unreachable"))))
        assertFalse(intentoLlegoALaRed(DesenlaceDelIntento.Fallo(IOException("No route to host"))))
    }

    /** La causa anidada cuenta igual: OkHttp envuelve seguido. */
    @Test
    fun `P2 la causa anidada tambien decide`() {
        val envuelta = IOException("unexpected", UnknownHostException("Unable to resolve host \"x\""))
        assertFalse("El UnknownHost de la causa manda", intentoLlegoALaRed(DesenlaceDelIntento.Fallo(envuelta)))
    }

    /**
     * Lo desconocido cuenta como «llegó», y es una decisión declarada: clasificar de más suelta los
     * cobros a la media hora y eso se VE; clasificar de menos deja el dinero del día dentro de la
     * tablet en silencio. El caso «no hay red» ya lo cubren el pre-chequeo y los tipos de arriba.
     */
    @Test
    fun `P3 una excepcion desconocida cuenta como que llego`() {
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Fallo(IOException("unexpected end of stream"))))
    }

    /** Una causa que se apunta a sí misma no puede colgar el recorrido. */
    @Test
    fun `P3 una cadena de causas ciclica no cuelga`() {
        val a = IOException("a")
        val b = IOException("b", a)
        assertTrue(intentoLlegoALaRed(DesenlaceDelIntento.Fallo(b)))
    }

    // MARK: - El aparato de verdad: OkHttpClient REAL con el ConnectivityInterceptor de producción

    private lateinit var servidor: MockWebServer

    @Before
    fun levantarServidor() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun bajarServidor() {
        // Una prueba lo apaga a propósito (el caso «sin ruta»); apagar dos veces no puede tumbar
        // la suite entera.
        runCatching { servidor.shutdown() }
    }

    /**
     * 🔴 P2 (R2-P2-1, el defecto de dinero de esta ronda) — CON EL INTERCEPTOR REAL PUESTO, un
     * `/open` que contesta 500 SÍ arranca el reloj del tope.
     *
     * Antes no: el interceptor marcaba el servidor caído durante ese mismo POST y el repositorio
     * leía esa bandera como veredicto. Aquí se comprueba en el mismo test que el mecanismo del
     * defecto está VIVO —`isFullyConnected` queda en `false` después del replay— y que aun así el
     * reloj arrancó, que es exactamente la combinación que el aparato produce.
     */
    @Test
    fun `P2 con el interceptor real un 500 arranca el reloj del tope`() = runTest {
        servidor.dispatcher = quinientosSiempre()
        val servidorAlcanzable = AtomicBoolean(true)
        val monitor = monitorComoElDeProduccion(servidorAlcanzable)
        val st = almacenConColaDeApertura()

        val repo = repoConClienteReal(st, monitor)
        repo.reproducirPendientes()

        assertTrue("Precondición: el POST salió de verdad", servidor.requestCount >= 1)
        assertFalse(
            "Precondición del defecto: el interceptor REAL apagó la bandera durante el propio POST",
            servidorAlcanzable.get(),
        )
        val abierta = cola(st).first { it.kind == "OPEN" }
        assertTrue(
            "Con el interceptor puesto, un 500 tiene que arrancar el reloj del tope",
            abierta.primerReintentoEn != null,
        )
        assertTrue(
            "Y fechar el backoff, que sin esto tampoco enganchaba",
            abierta.ultimoReintentoEn != null,
        )
    }

    /**
     * 🔴 P2 (R2-P2-1) — y media hora de 500 suelta los cobros. Es la consecuencia de dinero: sin
     * este arreglo el estado se quedaba en ESPERANDO_LA_APERTURA para siempre.
     */
    @Test
    fun `P2 con el interceptor real media hora de 500 suelta los cobros`() = runTest {
        servidor.dispatcher = quinientosSiempre()
        val monitor = monitorComoElDeProduccion(AtomicBoolean(true))
        val st = almacenConColaDeApertura()
        val repo = repoConClienteReal(st, monitor)

        repo.reproducirPendientes()
        val arranque = cola(st).first { it.kind == "OPEN" }.primerReintentoEn
        assertTrue("Precondición: el reloj arrancó", arranque != null)

        assertEquals(
            "Media hora después de ese primer 500, los cobros salen SIN caja en vez de quedarse",
            EstadoDeLosCobros.ENVIADOS_SIN_CAJA,
            estadoDeLosCobros(cola(st), arranque!! + TOPE_DE_LA_BARRERA_MS + 1),
        )
    }

    /**
     * 🔴 P2 — el otro lado de la regla, también con cliente real: sin ruta el reloj NO arranca.
     *
     * El servidor se apaga antes de intentar, así que OkHttp falla con `ConnectException` de
     * verdad. Con la regla vieja este caso pasaba por el mismo `Reintentar` que el 500; con la
     * nueva se distinguen, que es todo el punto.
     */
    @Test
    fun `P2 con cliente real un fallo sin ruta no arranca el reloj`() = runTest {
        val monitor = monitorComoElDeProduccion(AtomicBoolean(true))
        val st = almacenConColaDeApertura()
        val repo = repoConClienteReal(st, monitor)
        servidor.shutdown() // nadie escuchando: ConnectException real, no simulada

        repo.reproducirPendientes()

        val abierta = cola(st).first { it.kind == "OPEN" }
        assertEquals("Sin ruta el servidor no se enteró: el tope no puede arrancar", null, abierta.primerReintentoEn)
        assertEquals("Ni el backoff, o la apertura se retrasaría al volver el WiFi", null, abierta.ultimoReintentoEn)
        assertEquals(
            "Y los cobros siguen retenidos por muchas horas que pasen",
            EstadoDeLosCobros.ESPERANDO_LA_APERTURA,
            estadoDeLosCobros(cola(st), ahora = 1_000 + TOPE_DE_LA_BARRERA_MS * 10),
        )
    }

    /**
     * 🔴 P2 — el pre-chequeo sigue vivo: si el APARATO no tenía red al empezar, el intento no
     * cuenta aunque su desenlace dijera que sí. Es el AND que conserva el arreglo de la ronda 2.
     */
    @Test
    fun `P2 sin red del aparato el 500 tampoco arranca el reloj`() = runTest {
        servidor.dispatcher = quinientosSiempre()
        val monitor = monitorComoElDeProduccion(AtomicBoolean(true), hayRedDelAparato = false)
        val st = almacenConColaDeApertura()

        repoConClienteReal(st, monitor).reproducirPendientes()

        assertEquals(
            "El aparato dijo que no tenía red: el intento no cuenta pase lo que pase",
            null,
            cola(st).first { it.kind == "OPEN" }.primerReintentoEn,
        )
    }

    // MARK: - Andamio

    private fun quinientosSiempre() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setResponseCode(500).setBody("""{"message":"boom"}""")
    }

    /**
     * Un doble de [ConnectivityMonitor] con la MISMA semántica que el real para las dos banderas
     * que el interceptor toca: `reportServerError()` apaga `isServerReachable`, y
     * `isFullyConnected` es la conjunción. `isConnected` —la red del aparato— no la toca nadie,
     * que es justo por qué el arreglo la usa a ella como pre-chequeo.
     */
    private fun monitorComoElDeProduccion(
        servidorAlcanzable: AtomicBoolean,
        hayRedDelAparato: Boolean = true,
    ): ConnectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true).also { m ->
        every { m.isConnected } returns MutableStateFlow(hayRedDelAparato)
        every { m.isFullyConnected } answers { hayRedDelAparato && servidorAlcanzable.get() }
        every { m.reportServerError() } answers { servidorAlcanzable.set(false) }
        every { m.reportServerSuccess() } answers { servidorAlcanzable.set(true) }
    }

    /**
     * El `OkHttpClient` REAL de la app en lo que importa: el [ConnectivityInterceptor] de
     * producción, sin doblar. Delante va un reescritor de host, porque el repositorio arma su URL
     * con `BuildConfig.BASE_URL` y no se puede apuntar desde fuera; al ir ANTES, el interceptor
     * bajo prueba ve la respuesta REAL del `MockWebServer`.
     */
    private fun repoConClienteReal(st: SecureStorage, monitor: ConnectivityMonitor): CashDrawerRepository {
        val destino = servidor.url("/")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.newBuilder()
                    .scheme(destino.scheme).host(destino.host).port(destino.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
            .addInterceptor(ConnectivityInterceptor(monitor))
            .build()
        return CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = st,
            client = client,
            pendingCashSales = sinCobrosEnCola(),
            conectividad = monitor,
        )
    }

    /** Mismo andamio que el resto de las suites del cajón: la cola vive por venue. */
    private fun almacenConColaDeApertura(): SecureStorage = mockk<SecureStorage>(relaxed = true).also { st ->
        var guardada: String? =
            """[{"kind":"OPEN","sessionId":"local-1","amountCents":50000,"localId":"ev-1","at":1000}]"""
        every { st.venueId } returns VENUE_ID
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { guardada }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { guardada = secondArg() }
    }

    private fun cola(st: SecureStorage): List<PendingDrawerOp> =
        Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(PendingDrawerOp.serializer()), st.pendingDrawerOpsJson(VENUE_ID) ?: "[]")
}
