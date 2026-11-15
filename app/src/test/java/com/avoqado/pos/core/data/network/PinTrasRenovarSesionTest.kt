package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * D16 (full-testing del D4, W3, 12-nov): a los ~10 min de sesión, el PIN del gerente dio 401 porque el access de la
 * cajera venció; la app renovó la sesión 2 s después, pero el teclado se quedó en «Tu sesión expiró» y había que
 * teclear el PIN otra vez. La petición del PIN salía por un cliente propio, sin el autenticador que renueva y reintenta.
 *
 * Se arma el repositorio con su constructor de producción (el que usa Hilt) y sólo se le redirige el servidor.
 */
class PinTrasRenovarSesionTest {

    private lateinit var server: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var authenticator: TokenRefreshAuthenticator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secureStorage = mockk(relaxed = true)
        every { secureStorage.accessToken } returns "access-vencido"
        every { secureStorage.selloYAccess() } returns (1L to "access-vencido")
        every { secureStorage.refreshToken } returns "refresh-viejo"
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns true
        every { secureStorage.accessSiSigue(1L) } returns "access-nuevo"
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns true
        every { secureStorage.clearSessionSiSigue(any()) } returns true
        every { secureStorage.clearSessionSiSigueElAccess(any()) } returns true
        authenticator = TokenRefreshAuthenticator(secureStorage, RefrescoExclusivo())
        authenticator.refreshBaseUrl = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** El repositorio de producción, con su cliente apuntado a [server] (misma reflexión que usa escritorio). */
    private fun repositorio(): PermissionOverrideRepository {
        val repo = PermissionOverrideRepository(secureStorage, authenticator)
        val campo = PermissionOverrideRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = campo.get(repo) as OkHttpClient
        campo.set(
            repo,
            original.newBuilder().addInterceptor { cadena ->
                val url = cadena.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                cadena.proceed(cadena.request().newBuilder().url(url).build())
            }.build(),
        )
        return repo
    }

    private fun tokenVencido() = MockResponse().setResponseCode(401)
        .setBody("""{"error":"Unauthorized","message":"Token has expired"}""")

    private fun renovado() = MockResponse().setResponseCode(200)
        .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}""")

    private fun pinMalo() = MockResponse().setResponseCode(401)
        .setBody("""{"success":false,"code":"OVERRIDE_INVALID_PIN","message":"PIN incorrecto"}""")

    private fun concedido() = MockResponse().setResponseCode(201)
        .setBody("""{"success":true,"data":{"token":"tok-1","authorizedBy":{"id":"sv-9","name":"Daniel"}}}""")

    private fun pedir() = runBlocking { repositorio().requestToken("v1", "1234", "orders:discount-approve") }

    @Test
    fun `token vencido y renovacion buena, el PIN se reintenta solo y se concede`() {
        server.enqueue(tokenVencido())
        server.enqueue(renovado())
        server.enqueue(concedido())

        assertEquals(OverrideResult.Granted("tok-1", "Daniel"), pedir())

        val primero = server.takeRequest()
        val renovacion = server.takeRequest()
        val reintento = server.takeRequest()
        assertEquals("/mobile/auth/refresh", renovacion.path)
        assertEquals("Bearer access-vencido", primero.getHeader("Authorization"))
        assertEquals("Bearer access-nuevo", reintento.getHeader("Authorization"))
        assertEquals("el reintento lleva el mismo PIN", primero.body.readUtf8(), reintento.body.readUtf8())
        assertEquals("un solo refresco y un solo reintento", 3, server.requestCount)
    }

    @Test
    fun `si la renovacion la rechaza el servidor, sigue el mensaje de sesion expirada`() {
        server.enqueue(tokenVencido())
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        assertEquals(OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar."), pedir())
        assertEquals("no se reintenta el PIN", 2, server.requestCount)
    }

    @Test
    fun `si la renovacion no llega por red, dice que falta conexion y no que la sesion expiro`() {
        server.enqueue(tokenVencido())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(OverrideResult.Failed("Necesitas conexión para pedir autorización"), pedir())
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }
        verify(exactly = 0) { secureStorage.clearSessionSiSigueElAccess(any()) }
    }

    @Test
    fun `un PIN equivocado no renueva ni cierra la sesion`() {
        server.enqueue(pinMalo())

        assertEquals(OverrideResult.WrongPin, pedir())
        assertEquals("ni renovación ni reintento", 1, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }
        verify(exactly = 0) { secureStorage.clearSessionSiSigueElAccess(any()) }
    }

    @Test
    fun `token vencido y luego PIN equivocado, es PIN equivocado y la sesion sigue`() {
        server.enqueue(tokenVencido())
        server.enqueue(renovado())
        server.enqueue(pinMalo())

        assertEquals(OverrideResult.WrongPin, pedir())
        assertEquals("un solo reintento", 3, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }
        verify(exactly = 0) { secureStorage.clearSessionSiSigueElAccess(any()) }
    }

    @Test
    fun `si mientras viajaba entro otra persona, no se reintenta con su sesion`() {
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns false
        server.enqueue(tokenVencido())

        assertEquals(OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar."), pedir())
        assertEquals("ni renovación ni reintento", 1, server.requestCount)
    }
}
