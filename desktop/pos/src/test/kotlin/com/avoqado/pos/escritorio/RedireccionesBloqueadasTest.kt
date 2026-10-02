package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import retrofit2.Retrofit

/**
 * C4 con los clientes REALES que salen del inyector de escritorio y dos servidores de verdad: el «servidor de este build»
 * (A) contesta 302/307 hacia otro (B), y B nunca recibe nada. Los tres clientes de la API: el de NetworkModule (todos los
 * repositorios y Retrofit), el propio del refresco de sesión y el propio de la autorización de gerente.
 */
class RedireccionesBloqueadasTest {
    private val aB = AtomicInteger()
    private val b: HttpServer = servidor { intercambio ->
        aB.incrementAndGet()
        intercambio.sendResponseHeaders(200, -1)
    }
    private val a: HttpServer = servidor { intercambio ->
        val ruta = intercambio.requestURI.path
        when {
            ruta.endsWith("/ok") -> intercambio.sendResponseHeaders(200, -1)
            else -> {
                // GET ⇒ 302; POST ⇒ 307 (OkHttp reenvía el CUERPO: el refresh token, el PIN del gerente).
                intercambio.responseHeaders.add("Location", "http://localhost:${b.address.port}/api/v1/robado")
                intercambio.sendResponseHeaders(if (intercambio.requestMethod == "POST") 307 else 302, -1)
            }
        }
    }
    private val servidorDeEsteBuild = "http://127.0.0.1:${a.address.port}/api/v1"

    private val inyector = run {
        val carpeta = Files.createTempDirectory("Avoqado POS redirecciones ñ")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Inyector.crear(actividad, servidor = servidorDeEsteBuild).also { Escritorio.instalar(actividad, it) }
    }

    @AfterTest fun apagar() { a.stop(0); b.stop(0) }

    @Test fun `el cliente de NetworkModule habla con su servidor pero no sigue un 302 a otro`() {
        val cliente = inyector.getInstance(OkHttpClient::class.java)
        cliente.newCall(Request.Builder().url("$servidorDeEsteBuild/ok").build()).execute().use { assertEquals(200, it.code) }
        val e = assertFailsWith<IOException> {
            cliente.newCall(Request.Builder().url("$servidorDeEsteBuild/mobile/venues/v1/orders").build()).execute().close()
        }
        assertTrue(e.message.orEmpty().startsWith("redirección bloqueada hacia localhost"), e.message)
        assertEquals(0, aB.get(), "B recibió el pedido")
        // Retrofit (ApiService) usa ESE mismo cliente.
        assertTrue(inyector.getInstance(Retrofit::class.java).callFactory() === cliente)
    }

    @Test fun `el cliente de NetworkModule no sigue un 307 de un POST con dinero`() {
        val cliente = inyector.getInstance(OkHttpClient::class.java)
        val cuerpo = """{"amount":15000}""".toRequestBody("application/json".toMediaType())
        assertFailsWith<IOException> {
            cliente.newCall(Request.Builder().url("$servidorDeEsteBuild/mobile/venues/v1/fast").post(cuerpo).build()).execute().close()
        }
        assertEquals(0, aB.get(), "el cuerpo del cobro llegó a B")
    }

    @Test fun `el refresco de sesion real no manda el refresh token a otro servidor`() {
        inyector.getInstance(SecureStorage::class.java).updateTokens(accessToken = "access-viejo", refreshToken = "refresh-SECRETO")
        val autenticador = inyector.getInstance(TokenRefreshAuthenticator::class.java)
        autenticador.refreshBaseUrl = servidorDeEsteBuild   // su POST /mobile/auth/refresh va a A, que contesta 307 hacia B
        val pedido = Request.Builder().url("$servidorDeEsteBuild/mobile/venues/v1/orders").build()
        val respuesta401 = Response.Builder().request(pedido).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
        assertNull(autenticador.authenticate(null, respuesta401), "un refresco cortado es «sin red»: ni reintenta ni cierra sesión")
        assertEquals(0, aB.get(), "el refresh token llegó a B")
        assertEquals("refresh-SECRETO", inyector.getInstance(SecureStorage::class.java).refreshToken, "no cerró la sesión")
    }

    @Test fun `el cliente propio de la autorizacion de gerente no sigue un 307`() {
        val repositorio = inyector.getInstance(PermissionOverrideRepository::class.java)
        // 🔴 Si la app renombra o cambia el tipo de `client`, el inyector ya tronó al crear el repositorio (Inyector.kt).
        val campo = PermissionOverrideRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
        val cliente = campo.get(repositorio) as OkHttpClient
        val cuerpo = """{"pin":"1234","permission":"orders:update"}""".toRequestBody("application/json".toMediaType())
        assertFailsWith<IOException> {
            cliente.newCall(Request.Builder().url("$servidorDeEsteBuild/mobile/venues/v1/permission-overrides").post(cuerpo).build()).execute().close()
        }
        assertEquals(0, aB.get(), "el PIN del gerente llegó a B")
    }

    @Test fun `los tres clientes llevan la guarda como interceptor de red`() {
        val principal = inyector.getInstance(OkHttpClient::class.java)
        val refresco = inyector.getInstance(TokenRefreshAuthenticator::class.java).refreshHttpClient
        val gerente = PermissionOverrideRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
            .get(inyector.getInstance(PermissionOverrideRepository::class.java)) as OkHttpClient
        for ((nombre, cliente) in listOf("NetworkModule" to principal, "refresco" to refresco, "gerente" to gerente)) {
            assertEquals(1, cliente.networkInterceptors.count { it is GuardaDeRedirecciones }, nombre)
        }
    }

    private fun servidor(manejar: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { intercambio -> intercambio.use { manejar(it) } }
            start()
        }
}
