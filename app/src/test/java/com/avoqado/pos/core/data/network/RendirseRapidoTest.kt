package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * D12 (hz:e5ee98bc, ensayo general tanda 2, escenario 3, 11-nov): con el WiFi «conectado pero sin datos» (un puente que acepta la
 * conexión y nunca contesta), abrir una mesa congelaba la hoja ~35 s (el plazo de 30 s del cliente general) y CADA cobro en efectivo
 * de Mostrador volvía a esperar ~13 s aunque la barra ya dijera «Sin conexión».
 *
 * [RendirseRapido] da a las peticiones marcadas (la ruta del dinero y abrir mesa) el plazo corto de la regla offline §2.5, y recuerda
 * que la última se quedó sin respuesta: mientras el servidor siga sin contestar, la siguiente marcada no toca la red y sale al
 * instante como «sin red», que cada camino ya encola. Nada se manda, así que nada se puede duplicar.
 *
 * El puente mudo es `SocketPolicy.NO_RESPONSE`; los plazos se acortan con los parámetros de calibración para que la suite no espere.
 */
class RendirseRapidoTest {

    private lateinit var server: MockWebServer
    private val alcanzable = MutableStateFlow(true)
    private val sinRespuestaDelPing = MutableStateFlow(false)
    private var ahora = 1_000_000L
    private var ultimaRespuesta = 0L

    /**
     * Como el real: cualquier falla de transporte apaga «alcanzable» (lo hace `ConnectivityInterceptor`), y cualquier respuesta —del
     * cliente o del ping, con el código que sea— queda anotada en `ultimaRespuestaMs`.
     */
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isServerReachable } returns alcanzable
        every { it.sinRespuesta } returns sinRespuestaDelPing
        every { it.reportServerError() } answers { alcanzable.value = false }
        every { it.reportServerSuccess() } answers { alcanzable.value = true }
        every { it.ultimaRespuestaMs } answers { ultimaRespuesta }
        every { it.anotarRespuesta() } answers { ultimaRespuesta = ahora }
    }

    private val rendirse = RendirseRapido(monitor, ahoraMs = { ahora }, conectarMs = 300, leerMs = 300)

    /** El orden de producción: la puerta (aquí no hace falta), [RendirseRapido], y la conectividad. El plazo general, 30 s. */
    private val cliente = OkHttpClient.Builder()
        .addInterceptor(rendirse)
        .addInterceptor(ConnectivityInterceptor(monitor))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun mudo() = server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    private fun peticion(marcada: Boolean = true) = Request.Builder()
        .url(server.url("/api/v1/mobile/venues/v-1/orders"))
        .post("{}".toRequestBody("application/json".toMediaType()))
        .apply { if (marcada) header(RendirseRapido.HEADER, "1") }
        .build()

    /** Manda y devuelve cuánto tardó (ms) y la falla, si hubo. */
    private fun mandar(marcada: Boolean = true): Pair<Long, IOException?> {
        val inicio = System.nanoTime()
        val falla = try {
            cliente.newCall(peticion(marcada)).execute().close()
            null
        } catch (e: IOException) {
            e
        }
        return (System.nanoTime() - inicio) / 1_000_000 to falla
    }

    // MARK: - El plazo corto

    @Test fun `P1 una peticion marcada se rinde al plazo corto aunque el cliente tenga 30 s`() {
        mudo()

        val (ms, falla) = mandar()

        assertTrue("debía rendirse por tiempo, no: $falla", falla is SocketTimeoutException)
        assertTrue("tardó $ms ms: el plazo general de 30 s seguía mandando", ms < 5_000)
    }

    @Test fun `la marca no llega al servidor`() {
        server.enqueue(MockResponse().setBody("{}"))

        mandar()

        assertNull(server.takeRequest().getHeader(RendirseRapido.HEADER))
    }

    // MARK: - Recordar que nadie contesta

    @Test fun `P1 tras quedarse sin respuesta, la siguiente marcada no toca la red mientras el servidor siga sin contestar`() {
        mudo()
        mandar()
        assertEquals(false, alcanzable.value)

        val (ms, falla) = mandar()

        assertTrue("debía salir como sin red: $falla", falla is RendirseRapido.SinRespuestaReciente)
        assertTrue("la segunda tardó $ms ms", ms < 1_000)
        assertEquals("la segunda no salió por el cable", 1, server.requestCount)
    }

    /**
     * Lo que sale al instante es un «no se conectó» (`ConnectException`): nada salió por el cable. Así lo lee cada camino: el
     * efectivo lo encola, abrir mesa la abre sin red, la tarjeta dice «Sin conexión» (no un error del servidor) y conserva su llave.
     */
    @Test fun `P1 lo que sale al instante cuenta como sin red y como no enviado`() {
        val falla = RendirseRapido.SinRespuestaReciente()
        assertTrue(falla is java.net.ConnectException)
        assertTrue(OrderRepository.isQueueableError(falla))
        assertTrue(ServerErrorText.isOffline(falla))
    }

    @Test fun `en cuanto el servidor vuelve a contestar, se intenta otra vez`() {
        mudo()
        mandar()
        alcanzable.value = true // el ping de cada 10 s lo encontró
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull(falla)
        assertEquals(2, server.requestCount)
    }

    @Test fun `una respuesta, aunque sea un error del servidor, borra el recuerdo`() {
        mudo()
        mandar()
        alcanzable.value = true
        server.enqueue(MockResponse().setResponseCode(500)) // el 500 vuelve a apagar «alcanzable»: contestó, pero mal
        mandar()
        assertEquals(false, alcanzable.value)
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull("un 5xx dice que alguien contesta: se intenta", falla)
        assertEquals(3, server.requestCount)
    }

    /** Codex r1 #2: el ping de cada 10 s contesta un 500 (el servidor está, aunque mal): «alcanzable» sigue en falso, pero se intenta. */
    @Test fun `P1 si el ping contesta, aunque sea un 500, el recuerdo ya no frena`() {
        mudo()
        mandar()
        ahora += 10_000
        monitor.anotarRespuesta() // el ping: HTTP 500; `sinRespuesta` en falso, `isServerReachable` sigue en falso
        assertEquals(false, alcanzable.value)
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull("el servidor contesta: se intenta, no se inventa «Sin conexión»", falla)
        assertEquals(2, server.requestCount)
    }

    @Test fun `el recuerdo vence al minuto y se vuelve a intentar`() {
        mudo()
        mandar()
        ahora += 61_000
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull(falla)
        assertEquals(2, server.requestCount)
    }

    @Test fun `P1 el ping sin respuesta tambien ahorra la espera`() {
        alcanzable.value = false
        sinRespuestaDelPing.value = true

        val (_, falla) = mandar()

        assertTrue(falla is RendirseRapido.SinRespuestaReciente)
        assertEquals(0, server.requestCount)
    }

    @Test fun `un servidor marcado caido por un 5xx de otra pantalla no frena nada`() {
        alcanzable.value = false // un 5xx de cualquier pantalla; nadie se quedó sin respuesta
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull(falla)
        assertEquals(1, server.requestCount)
    }

    @Test fun `una peticion sin marca no cambia de plazo ni se frena`() {
        mudo()
        mandar() // marcada: deja el recuerdo
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar(marcada = false)

        assertNull(falla)
        assertEquals(2, server.requestCount)
    }

    /** El plazo TOTAL corto de la consulta del saldo (2 s) no prueba que nadie conteste (familia C del D12). */
    @Test fun `un plazo total corto no cuenta como nadie contesta`() {
        mudo()
        val corto = cliente.newBuilder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        try {
            corto.newCall(peticion()).execute().close()
            fail("debía vencer")
        } catch (_: IOException) {
        }
        alcanzable.value = false
        server.enqueue(MockResponse().setBody("{}"))

        val (_, falla) = mandar()

        assertNull(falla)
        assertEquals(2, server.requestCount)
    }

    // MARK: - Quién lleva la marca

    /** Hoy: sólo el cliente del dinero (crear la venta, cobrar efectivo, fast, cancelar, «Pagar después», el saldo) y abrir mesa. */
    @Test fun `P1 la segunda venta en efectivo no espera`() = runBlocking {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v-1"
        every { storage.accessToken } returns "token"
        val repo = OrderRepository(storage, cliente)
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
        val venta = CreateOrderRequest(
            items = listOf(OrderItemRequest(productId = "p-1", name = "Café", quantity = 1, unitPrice = 4_500)),
            subtotal = 4_500, total = 4_500, paymentMethod = "CASH",
        )
        mudo()

        val primera = repo.createOrder(venta, staffId = "s-1", externalId = "llave-1")
        val inicio = System.nanoTime()
        val segunda = repo.createOrder(venta, staffId = "s-1", externalId = "llave-2")
        val ms = (System.nanoTime() - inicio) / 1_000_000

        assertTrue("la primera se rinde por tiempo: ${primera.exceptionOrNull()}", primera.exceptionOrNull() is SocketTimeoutException)
        assertTrue("la segunda va directo a la cola: ${segunda.exceptionOrNull()}", segunda.exceptionOrNull() is RendirseRapido.SinRespuestaReciente)
        assertTrue("la segunda tardó $ms ms", ms < 1_000)
        assertEquals(1, server.requestCount)
    }

    /** Va en el cliente principal, justo después de la puerta de la foto (que sigue siendo la primera): los derivados la heredan. */
    @Test fun `P1 el cliente principal la lleva justo despues de la puerta de la foto`() {
        val principal = com.avoqado.pos.core.di.NetworkModule.provideOkHttpClient(
            authInterceptor = mockk(relaxed = true),
            deviceHeadersInterceptor = mockk(relaxed = true),
            tokenRefreshAuthenticator = mockk(relaxed = true),
            errorNotifier = mockk(relaxed = true),
            connectivityMonitor = monitor,
            managerOverrideCoordinator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            fotosDeMesas = mockk(relaxed = true),
        )

        assertTrue(principal.interceptors[0] is PuertaDeLaFoto)
        assertTrue("el segundo es ${principal.interceptors.getOrNull(1)}", principal.interceptors[1] is RendirseRapido)
    }

    @Test fun `P1 abrir mesa lleva la marca y se rinde al plazo corto`() = runBlocking {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(cliente)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
        mudo()

        val inicio = System.nanoTime()
        val falla = runCatching { api.openTable("v-1", "mesa-8", com.avoqado.pos.tables.data.OpenTableRequest(2)) }.exceptionOrNull()
        val ms = (System.nanoTime() - inicio) / 1_000_000

        assertTrue("debía rendirse por tiempo: $falla", falla is SocketTimeoutException)
        assertTrue("abrir mesa tardó $ms ms", ms < 5_000)
    }
}
