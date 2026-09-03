package com.avoqado.pos.auth

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
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
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Task 13, Parte A — sesiones revocables: un access vencido SIN RED no es
 * logout.
 *
 * `offline-first-y-hub-lan.md` §2.3: "un fallo de RED se convierte en
 * intent; un rechazo de NEGOCIO se propaga tal cual. Confundirlos es el
 * bug clásico". Con el access de 10 minutos (Task 12), un local con mal
 * internet deja el token vencido seguido — si el cliente trata eso como
 * logout, la caja queda inservible cada 10 minutos sin señal.
 */
class AccessVencidoSinRedTest {

    private lateinit var server: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var authenticator: TokenRefreshAuthenticator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secureStorage = mockk(relaxed = true)
        every { secureStorage.refreshToken } returns "refresh-viejo"
        authenticator = TokenRefreshAuthenticator(secureStorage, RefrescoExclusivo())
        authenticator.refreshBaseUrl = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun original401(): Response {
        val request = Request.Builder()
            .url(server.url("/mobile/venues/v1/orders"))
            .header("Authorization", "Bearer access-vencido")
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    @Test
    fun `access vencido sin red no cierra sesion ni vacia el outbox`() {
        // El refresco falla por RED de verdad: apunta a un puerto local
        // cerrado (nada escuchando) en vez de al MockWebServer, así que
        // OkHttp lanza IOException (connection refused) — no una respuesta
        // HTTP con código de error.
        val closedPort = ServerSocket(0).use { it.localPort }
        authenticator.refreshBaseUrl = "http://127.0.0.1:$closedPort"
        authenticator.refreshHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val retry = authenticator.authenticate(null, original401())

        assertNull("un fallo de red no produce reintento (el 401 original se propaga)", retry)
        // La sesión local sigue viva: nunca se limpia el storage por un
        // tropiezo de transporte. El outbox no es responsabilidad de esta
        // clase, pero vive exactamente de esto — de que la sesión no muera.
        verify(exactly = 0) { secureStorage.clearSession() }
        verify(exactly = 0) { secureStorage.updateTokens(any(), any()) }
    }

    @Test
    fun `un 401 real del servidor al refrescar SI cierra la sesion`() {
        // Aquí SÍ hay respuesta HTTP — el servidor contestó y dijo que el
        // refresh token ya no sirve (vencido de verdad, o reutilización
        // detectada). Es la contraparte del test de arriba: el rechazo de
        // NEGOCIO se propaga tal cual, no se traga.
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"message":"invalid refresh token"}"""),
        )

        val retry = authenticator.authenticate(null, original401())

        assertNull(retry)
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    @Test
    fun `un 503 del servidor al refrescar NO cierra la sesion (error transitorio)`() {
        // CRÍTICO encontrado en revisión: un 502/503 durante un deploy del
        // backend (que en este proyecto tarda minutos) NO es el servidor
        // afirmando que el refresh token murió — es el servidor tropezando.
        // Antes, `refreshTokens()` metía CUALQUIER respuesta no-2xx en
        // `Rejected`, y `applyOutcome` desloguéaba ante cualquier `Rejected`
        // sin mirar el código: un despliegue normal dejaba cualquier POS con
        // el access vencido en ese instante con la caja inservible.
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("""{"message":"service unavailable"}"""),
        )

        val retry = authenticator.authenticate(null, original401())

        assertNull("un 503 no produce reintento", retry)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `un 500 del servidor al refrescar NO cierra la sesion (error transitorio)`() {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"message":"internal error"}"""),
        )

        val retry = authenticator.authenticate(null, original401())

        assertNull(retry)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `un 429 del servidor al refrescar NO cierra la sesion (limite de peticiones)`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"message":"too many requests"}"""),
        )

        val retry = authenticator.authenticate(null, original401())

        assertNull(retry)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `un refresco exitoso reintenta la peticion original con el token nuevo`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        val retry = authenticator.authenticate(null, original401())

        assertEquals("Bearer access-nuevo", retry?.header("Authorization"))
        assertEquals("true", retry?.header("X-Retry-After-Refresh"))
        verify(exactly = 1) { secureStorage.updateTokens("access-nuevo", "refresh-nuevo") }
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `sin refresh token guardado no hay nada que reintentar, y se cierra sesion`() {
        every { secureStorage.refreshToken } returns null

        val retry = authenticator.authenticate(null, original401())

        assertNull(retry)
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    @Test
    fun `una peticion ya reintentada que vuelve a fallar SI cierra sesion (rechazo real)`() {
        // Sólo se llega aquí tras un refresco EXITOSO (ver buildRetry): un
        // fallo de red nunca produce el header X-Retry-After-Refresh, así
        // que esta rama es siempre un rechazo de negocio de verdad.
        val alreadyRetried = Request.Builder()
            .url(server.url("/mobile/venues/v1/orders"))
            .header("X-Retry-After-Refresh", "true")
            .build()
        val response = Response.Builder()
            .request(alreadyRetried)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val retry = authenticator.authenticate(null, response)

        assertNull(retry)
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    @Test
    fun `PIN del checador no dispara refresco ni toca la sesion`() {
        val request = Request.Builder()
            .url(server.url("/mobile/venues/v1/time-clock/clock-in"))
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val retry = authenticator.authenticate(null, response)

        assertNull(retry)
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSession() }
    }
}
