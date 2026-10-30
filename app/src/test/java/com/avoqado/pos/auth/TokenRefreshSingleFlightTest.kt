package com.avoqado.pos.auth

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.core.data.network.SelloDePeticion
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Task 14, Parte A — sesiones revocables: single-flight en el refresh.
 *
 * Ahora que el refresco ROTA el refresh token (Tasks 8 y 9 del server), dos
 * peticiones que fallan a la vez con el access vencido no pueden disparar
 * dos refrescos: el primero consume el grant y el segundo llegaría con uno
 * ya usado — el servidor lo lee como reutilización y revoca la sesión
 * ENTERA, dejando al cajero fuera a media venta. `TokenRefreshAuthenticator`
 * ya tenía la mitad (`synchronized(refreshLock)` + `wait`/`notifyAll`); lo
 * que faltaba era que quien espera REUSE el resultado del líder en vez de
 * arrancar el suyo.
 */
class TokenRefreshSingleFlightTest {

    private lateinit var server: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var authenticator: TokenRefreshAuthenticator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secureStorage = mockk(relaxed = true)
        every { secureStorage.refreshToken } returns "refresh-viejo"
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

    /** Un 401 de una petición que salió con la sesión de [sello] (como la marca `AuthInterceptor`). */
    private fun original401ConSello(sello: Long): Response {
        val request = Request.Builder()
            .url(server.url("/mobile/venues/v1/orders"))
            .header("Authorization", "Bearer access-vencido")
            .tag(SelloDePeticion::class.java, SelloDePeticion(sello))
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    private fun respuesta401De(path: String): Response {
        val request = Request.Builder()
            .url(server.url(path))
            .header("Authorization", "Bearer access-vigente")
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    @Test
    fun `P1 un 401 de cambiar usuario NO refresca ni cierra sesion — es un PIN mal tecleado`() {
        // Medido en la Samsung el 2026-08-29: al teclear el PIN en «Cambiar usuario», la app se
        // fue a la pantalla de LOGIN. El servidor mostró `switch-user 401 → refresh 200 →
        // switch-user 401` y fuera: el autenticador leyó el segundo 401 como "el refresh token ya
        // no sirve" y cerró la sesión. Alguien que se equivoca de PIN perdía su sesión, en el
        // mostrador. El reloj checador ya estaba exento por lo mismo; faltaba esta ruta.
        val resultado = authenticator.authenticate(null, respuesta401De("/mobile/venues/v1/auth/switch-user"))

        assertNull("un 401 de PIN no se reintenta con token nuevo", resultado)
        assertEquals("y NO debe dispararse ningún refresco", 0, server.requestCount)
    }

    @Test
    fun `P1 el reloj checador sigue exento — la regresion que este arreglo no puede romper`() {
        val resultado = authenticator.authenticate(null, respuesta401De("/mobile/venues/v1/time-clock/identify"))

        assertNull(resultado)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dos peticiones con 401 simultaneo disparan UN solo refresco`() {
        // La respuesta se retrasa a propósito: mantiene al líder ocupado en
        // la llamada HTTP el tiempo suficiente para que el seguidor entre a
        // `authenticate()` mientras `isRefreshing` sigue en true.
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        val leaderDone = CountDownLatch(1)
        val leaderResult = AtomicReference<Request?>()
        val leaderThread = Thread {
            leaderResult.set(authenticator.authenticate(null, original401()))
            leaderDone.countDown()
        }
        leaderThread.start()
        // Espera a que el servidor RECIBA la petición del líder — garantiza
        // que `isRefreshing` ya es `true` (se pone ANTES de mandar la
        // petición HTTP, en el mismo hilo) sin adivinar con un sleep, que
        // bajo carga puede no alcanzar y hacer que el seguidor entre creyendo
        // que no hay nadie refrescando.
        assertTrue("el lider debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)

        val followerResult = authenticator.authenticate(null, original401())
        assertTrue("el lider debio terminar", leaderDone.await(5, TimeUnit.SECONDS))

        // Un solo POST /mobile/auth/refresh llegó al servidor — el seguidor
        // NUNCA disparó el suyo.
        assertEquals(1, server.requestCount)
        assertEquals("Bearer access-nuevo", leaderResult.get()?.header("Authorization"))
        assertEquals("Bearer access-nuevo", followerResult?.header("Authorization"))
        verify(exactly = 1) { secureStorage.updateTokensSiSigue("refresh-viejo", "access-nuevo", "refresh-nuevo") }
    }

    @Test
    fun `el que espera usa el token NUEVO, no el vencido`() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        val leaderThread = Thread { authenticator.authenticate(null, original401()) }
        leaderThread.start()
        assertTrue("el lider debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)

        val followerResult = authenticator.authenticate(null, original401())
        leaderThread.join(5_000)

        // Sin el fix, el seguidor reintentaba con `secureStorage.accessToken`
        // leído a ciegas — aquí lo verificamos directo contra lo que el
        // líder de verdad recibió del servidor, no contra un valor que
        // pudiera coincidir por casualidad.
        assertEquals("Bearer access-nuevo", followerResult?.header("Authorization"))
        assertEquals("true", followerResult?.header("X-Retry-After-Refresh"))
    }

    @Test
    fun `si el refresco falla, los que esperan tambien fallan — no se cuelgan`() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(401)
                .setBody("""{"message":"invalid refresh token"}"""),
        )

        val leaderThread = Thread { authenticator.authenticate(null, original401()) }
        leaderThread.start()
        assertTrue("el lider debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)

        val start = System.currentTimeMillis()
        val followerResult = authenticator.authenticate(null, original401())
        val elapsedMs = System.currentTimeMillis() - start
        leaderThread.join(5_000)

        assertNull("el seguidor no reintenta si el lider no consiguio token nuevo", followerResult)
        // El wait(10_000) es un respaldo, no el camino normal: el lider
        // notifica en cuanto termina (aquí, ~300ms después de que el
        // seguidor empezó a esperar), así que el seguidor NO debe quedarse
        // colgado cerca de los 10 segundos completos.
        assertTrue("el seguidor no debe colgarse: tardo ${elapsedMs}ms", elapsedMs < 3_000)
        // Un solo cierre de sesión — el del líder. El seguidor no repite el
        // trabajo del líder ni dispara uno propio.
        verify(exactly = 1) { secureStorage.clearSessionSiSigue("refresh-viejo") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `si otra persona entro mientras se renovaba, no se pisan sus llaves ni se reenvia (Codex D9)`() {
        // A pidió algo con la llave vencida; mientras su renovación viajaba, B entró con su PIN y guardó su sesión.
        // La renovación de A llega tarde: guardarla dejaba a B en pantalla con las llaves de A.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"access-de-A","refreshToken":"refresh-de-A"}"""),
        )
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns false // la sesión ya es otra

        val retry = authenticator.authenticate(null, original401())

        assertNull("con la sesión de otra persona, la petición de A no se reenvía", retry)
        verify(exactly = 1) { secureStorage.updateTokensSiSigue("refresh-viejo", "access-de-A", "refresh-de-A") }
        verify(exactly = 0) { secureStorage.updateTokens(any(), any()) }
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }; verify(exactly = 0) { secureStorage.clearSessionSiSigueElAccess(any()) } // la sesión de B sigue
    }

    @Test
    fun `D4 H1-a el 401 de A que llega despues de que entro B no renueva a B ni reenvia`() {
        // A mandó algo; B entró con su PIN mientras viajaba (la generación ya es otra); llega el 401 de A.
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns false

        val retry = authenticator.authenticate(null, original401ConSello(1L))

        assertNull("lo de A no se reenvía con la sesión de B", retry)
        assertEquals("y la sesión de B no se renueva por un 401 ajeno", 0, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `D4 H1-c si B entra entre guardar la renovacion de A y reenviar, no se reenvia`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns true
        every { secureStorage.accessSiSigue(1L) } returns null // al armar el reenvío, la sesión ya es de B

        val retry = authenticator.authenticate(null, original401ConSello(1L))

        assertNull(retry)
        assertEquals("la renovación de A sí salió (y se guardó con su comparación)", 1, server.requestCount)
        verify(exactly = 1) { secureStorage.updateTokensSiSigue("refresh-viejo", "access-nuevo", "refresh-nuevo") }
    }

    @Test
    fun `D4 con su sesion intacta, el reenvio sale con el access de SU sesion`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns true
        every { secureStorage.accessSiSigue(1L) } returns "access-nuevo"

        val retry = authenticator.authenticate(null, original401ConSello(1L))

        assertEquals("Bearer access-nuevo", retry?.header("Authorization"))
    }

    @Test
    fun `D4 H1-b quien esperaba la renovacion de A no reenvia si mientras tanto entro B`() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returns true
        every { secureStorage.accessSiSigue(1L) } returns "access-nuevo"

        val leaderThread = Thread { authenticator.authenticate(null, original401ConSello(1L)) }
        leaderThread.start()
        assertTrue("el lider debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)
        every { secureStorage.accessSiSigue(1L) } returns null // B entra mientras el seguidor espera

        val followerResult = authenticator.authenticate(null, original401ConSello(1L))
        leaderThread.join(5_000)

        assertNull("el seguidor no reenvía lo de A con la sesión de B", followerResult)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `D4 si entra B mientras el 401 de A espera turno, no se renueva la sesion de B`() {
        // Pasa la primera mirada, pero al tomar el candado la generación ya cambió.
        every { secureStorage.siSigueLaGeneracion(1L, any()) } returnsMany listOf(true, false)

        val retry = authenticator.authenticate(null, original401ConSello(1L))

        assertNull(retry)
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { secureStorage.clearSessionSiSigue(any()) }
    }

    @Test
    fun `los que esperaban esa renovacion descartada tampoco reenvian con la llave de A`() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"accessToken":"access-de-A","refreshToken":"refresh-de-A"}"""),
        )
        every { secureStorage.updateTokensSiSigue(any(), any(), any()) } returns false

        val leaderThread = Thread { authenticator.authenticate(null, original401()) }
        leaderThread.start()
        assertTrue("el lider debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)

        val followerResult = authenticator.authenticate(null, original401())
        leaderThread.join(5_000)

        assertNull("el seguidor no reenvía con una llave que no se guardó", followerResult)
        assertEquals(1, server.requestCount)
    }
}
