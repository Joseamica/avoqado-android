package com.avoqado.pos.auth

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ConexionesCompartidas
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.core.data.network.SesionSinRenovar
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import com.avoqado.pos.payment.data.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Testarudo, 15-oct-2026 16:28: el internet del local parpadeó, la sesión de 10 min venció y el
 * refresco no llegó al servidor. El autenticador devolvía `null` → el 401 «Token has expired» se
 * propagaba tal cual y el cajero veía «Error en el pago · Token has expired» (en inglés) en vez de
 * «Sin conexión», y la venta en efectivo NO se encolaba (un 401 no es un error de red para la cola).
 *
 * El arreglo: si el refresco no se pudo hacer por RED (o por un tropiezo del servidor), la petición
 * falla como lo que es — falta de red — con [SesionSinRenovar] (un `IOException`). La sesión sigue
 * viva igual que antes: nunca se cierra por un problema de transporte.
 */
class SesionSinRenovarTest {

    private lateinit var server: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var authenticator: TokenRefreshAuthenticator
    private val reportes = mutableListOf<SesionSinRenovar>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secureStorage = mockk(relaxed = true)
        every { secureStorage.refreshToken } returns "refresh-viejo"
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns true
        authenticator = TokenRefreshAuthenticator(secureStorage, RefrescoExclusivo())
        authenticator.refreshBaseUrl = server.url("/").toString().removeSuffix("/")
        authenticator.reportarFallo = { reportes.add(it) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun refrescoSinRed() {
        val closedPort = ServerSocket(0).use { it.localPort }
        authenticator.refreshBaseUrl = "http://127.0.0.1:$closedPort"
        authenticator.refreshHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun original401(vararg headers: Pair<String, String>): Response {
        val request = Request.Builder()
            .url(server.url("/mobile/venues/v1/orders"))
            .header("Authorization", "Bearer access-vencido")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    private fun autenticarEsperandoSinRed(response: Response): SesionSinRenovar {
        try {
            authenticator.authenticate(null, response)
        } catch (e: SesionSinRenovar) {
            return e
        }
        fail("se esperaba SesionSinRenovar: un refresco que no salió por red es FALTA DE RED, no un 401 de negocio")
        throw AssertionError()
    }

    @Test
    fun `P1 refresco sin red falla como falta de red y la sesion sigue viva`() {
        refrescoSinRed()

        val e = autenticarEsperandoSinRed(original401())

        assertTrue("debe contar como sin red para la UI", ServerErrorText.isOffline(e))
        assertTrue("debe encolarse como cualquier corte de red", OrderRepository.isQueueableError(e))
        verify(exactly = 0) { secureStorage.clearSession() }
        verify(exactly = 0) { secureStorage.updateTokensSiSigue(any(), any(), any()) }
    }

    @Test
    fun `P1 un 503 del servidor al refrescar tambien falla como falta de red, sin cerrar sesion`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"service unavailable"}"""))

        autenticarEsperandoSinRed(original401())

        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `P1 el que esperaba al lider tambien falla como falta de red si el lider no tuvo red`() {
        // El líder tarda en fallar (503 con retraso) y el seguidor entra mientras tanto.
        server.enqueue(
            MockResponse().setBodyDelay(400, TimeUnit.MILLISECONDS).setResponseCode(503).setBody("{}"),
        )
        val lider = Thread { runCatching { authenticator.authenticate(null, original401()) } }
        lider.start()
        assertTrue("el lider debio mandar su refresco", server.takeRequest(5, TimeUnit.SECONDS) != null)

        autenticarEsperandoSinRed(original401())
        lider.join(5_000)

        assertEquals("un solo refresco: el seguidor reusa el resultado del lider", 1, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `la declaracion que no se reenvia tambien dice sin red cuando el refresco no salio`() {
        refrescoSinRed()

        autenticarEsperandoSinRed(
            original401(TokenRefreshAuthenticator.NO_AUTO_RETRY_HEADER to "1"),
        )
    }

    @Test
    fun `un rechazo REAL del refresco sigue cerrando sesion y devuelve el 401 (no se disfraza de red)`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid refresh token"}"""))

        assertNull(authenticator.authenticate(null, original401()))
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    @Test
    fun `P1 recorrido completo con OkHttp - el 401 con refresco sin red sale como excepcion de red`() {
        // Lo que vio Testarudo, de punta a punta: la API contesta 401 y el refresco no tiene red.
        refrescoSinRed()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","message":"Token has expired"}"""))
        val client = OkHttpClient.Builder().authenticator(authenticator).build()

        try {
            client.newCall(Request.Builder().url(server.url("/mobile/venues/v1/orders")).build()).execute().close()
            fail("el cajero no debe recibir el 401 «Token has expired»")
        } catch (e: SesionSinRenovar) {
            assertTrue(ServerErrorText.isOffline(e))
        }
    }

    @Test
    fun `el fallo se reporta una vez por rafaga, sin tokens`() {
        refrescoSinRed()

        autenticarEsperandoSinRed(original401())
        autenticarEsperandoSinRed(original401())

        assertEquals("una ráfaga de fallos deja UN reporte, no uno por petición", 1, reportes.size)
        val motivo = reportes.single().motivo
        assertTrue("el motivo nombra la clase del fallo: $motivo", motivo.startsWith("red:"))
        assertTrue("nunca viajan los tokens", !motivo.contains("refresh-viejo"))
    }

    @Test
    fun `el refresco usa la misma conexion que el resto de la app`() {
        // Con internet débil, una llamada que ya está abierta aguanta; marcar una nueva falla.
        assertSame(ConexionesCompartidas.pool, TokenRefreshAuthenticator(secureStorage, RefrescoExclusivo()).refreshHttpClient.connectionPool)
        val principal = com.avoqado.pos.core.di.NetworkModule.provideOkHttpClient(
            mockk(relaxed = true), mockk(relaxed = true), authenticator,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        assertSame("el cliente principal y el del refresco comparten conexiones", ConexionesCompartidas.pool, principal.connectionPool)
    }

    // MARK: - Ráfaga (QA en la D3, 16-oct): 17 refrescos en 4 s al reabrir con el servidor caído

    @Test
    fun `P1 tras un refresco fallido, otro 401 dentro de la ventana falla sin red SIN volver a pedir`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

        autenticarEsperandoSinRed(original401())
        autenticarEsperandoSinRed(original401())

        assertEquals("una ráfaga de 401 = UN refresco al servidor", 1, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `pasada la ventana, el siguiente 401 si vuelve a intentar renovar`() {
        var ahora = 1_000_000L
        authenticator.reloj = { ahora }
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        autenticarEsperandoSinRed(original401())
        ahora += TokenRefreshAuthenticator.VENTANA_TRAS_FALLO_MS + 1
        val reintento = authenticator.authenticate(null, original401())

        assertEquals(2, server.requestCount)
        assertEquals("Bearer access-nuevo", reintento?.header("Authorization"))
    }

    // MARK: - Auditoría de Codex (16-oct)

    @Test
    fun `P2 si el reloj se ajusta hacia atras, la ventana no se alarga - vuelve a intentar`() {
        var ahora = 5_000_000L
        authenticator.reloj = { ahora }
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        autenticarEsperandoSinRed(original401())
        ahora -= 3_600_000L // el sistema corrigió su hora una hora hacia atrás
        val reintento = authenticator.authenticate(null, original401())

        assertEquals("un reloj que retrocede no puede trabar la renovación una hora", 2, server.requestCount)
        assertEquals("Bearer access-nuevo", reintento?.header("Authorization"))
    }

    @Test
    fun `P2 el refresco y el cliente principal pueden reusar la MISMA conexion HTTPS`() {
        // OkHttp sólo reusa una conexión si la «dirección» es igual, y eso incluye la fábrica TLS. Dos
        // `OkHttpClient.Builder()` independientes traen cada uno la suya: compartir sólo el pool no basta (Codex).
        val principal = com.avoqado.pos.core.di.NetworkModule.provideOkHttpClient(
            mockk(relaxed = true), mockk(relaxed = true), authenticator,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        val refresco = TokenRefreshAuthenticator(secureStorage, RefrescoExclusivo()).refreshHttpClient

        assertSame(principal.connectionPool, refresco.connectionPool)
        assertSame("misma fábrica TLS", principal.sslSocketFactory, refresco.sslSocketFactory)
        assertSame(principal.x509TrustManager, refresco.x509TrustManager)
        assertSame(principal.hostnameVerifier, refresco.hostnameVerifier)
        assertSame(principal.dns, refresco.dns)
        assertEquals(principal.protocols, refresco.protocols)
        assertEquals(principal.connectionSpecs, refresco.connectionSpecs)
    }
}
