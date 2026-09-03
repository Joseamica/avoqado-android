package com.avoqado.pos.auth

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import io.mockk.every
import io.mockk.mockk
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

/**
 * El refresco tiene DOS caminos, y hasta ahora sólo uno tenía candado.
 *
 * 🔴 Medido en la Sunmi D3 el 2026-09-02 16:07 (owner@owner.com, sesión
 * cmtkmm7sv0001q0t1z0ogtsgz): el POS despertó del background, seis peticiones con el
 * access vencido salieron juntas y el servidor recibió DOS `POST /mobile/auth/refresh`
 * solapados 915 ms — uno a las .150 y otro a las .521, o sea 4 ms después de su 401, sin
 * esperar a nadie. El segundo se topó con el grant ya rotado, recibió
 * «Tu sesión ya no es válida» y la app cerró sesión sola: el aparato se quedó en la
 * pantalla de bienvenida y así siguió 40 minutos después.
 *
 * `TokenRefreshSingleFlightTest` ya cubría el candado DENTRO del autenticador, y por eso
 * pasaba mientras el defecto seguía vivo: el segundo refresco no entró por ahí, entró por
 * `AuthRepository` (Retrofit), que nunca tocó ese candado.
 */
class RefrescoExclusivoTest {

    private lateinit var server: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var exclusivo: RefrescoExclusivo
    private lateinit var authenticator: TokenRefreshAuthenticator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secureStorage = mockk(relaxed = true)
        every { secureStorage.refreshToken } returns "refresh-viejo"
        exclusivo = RefrescoExclusivo()
        authenticator = TokenRefreshAuthenticator(secureStorage, exclusivo)
        authenticator.refreshBaseUrl = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respuesta401De(path: String): Response {
        val request = Request.Builder()
            .url(server.url(path))
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
    fun `P1 el refresco del repositorio ESPERA al del autenticador, no se solapa`() {
        // El escenario exacto del incidente: mientras el autenticador refresca (camino A),
        // AuthRepository dispara el suyo por Retrofit (camino B). Si los dos salen a la vez,
        // el segundo presenta un refresh token que el servidor acaba de rotar.
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"accessToken":"access-nuevo","refreshToken":"refresh-nuevo"}"""),
        )

        val terminoA = CountDownLatch(1)
        val hiloA = Thread {
            authenticator.authenticate(null, respuesta401De("/mobile/venues/v1/products"))
            terminoA.countDown()
        }
        hiloA.start()
        // Garantiza que A ya está DENTRO del refresco (el candado se toma antes de la
        // petición HTTP), sin adivinar con un sleep.
        assertTrue("el autenticador debio mandar su peticion", server.takeRequest(5, TimeUnit.SECONDS) != null)

        // Camino B: lo que hace AuthRepository.repairCurrentVenueBinding().
        val inicioB = System.currentTimeMillis()
        exclusivo.enExclusiva { /* aquí iría el POST /auth/refresh de Retrofit */ }
        val esperaB = System.currentTimeMillis() - inicioB

        assertTrue("A debio terminar", terminoA.await(5, TimeUnit.SECONDS))
        // B tuvo que esperar a que A soltara: el cuerpo de A tarda 400 ms a propósito. Sin
        // candado compartido, B entra en ~0 ms — que es lo que pasó en la D3.
        assertTrue("los dos refrescos se solaparon: B entro a los ${esperaB}ms", esperaB >= 200)
    }

    @Test
    fun `P1 un 401 del PROPIO refresco no encadena otro refresco`() {
        // `/auth/refresh` viaja sin Authorization (AuthInterceptor lo excluye), así que su
        // 401 nunca significa «el access venció» — significa que el refresh token no sirve.
        // Sin esta exención, el 401 del refresco por Retrofit hace que OkHttp invoque al
        // autenticador, que manda OTRO refresco con el token ya rotado: eso sí es
        // reutilización de verdad, y revoca la sesión entera en el servidor.
        val resultado = authenticator.authenticate(null, respuesta401De("/mobile/auth/refresh"))

        assertNull("un 401 del refresco no se reintenta", resultado)
        assertEquals("y NO debe dispararse un refresco encadenado", 0, server.requestCount)
    }
}
