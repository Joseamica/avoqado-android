package com.avoqado.pos.transactions.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.SesionSinRenovar
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * D18 (3-nov, Sunmi OrderPAD 3 con el WiFi apagado): Ventas decía «Sin transacciones» sin red. La carga ya fallaba, pero
 * nadie decía POR QUÉ. Aquí se separa «nadie contestó» (sin red) de «el servidor contestó mal», y la lista que ya había
 * no se borra.
 */
class TransactionRepositoryFalloDeCargaTest {
    private val server = MockWebServer()
    private val storage = mockk<SecureStorage>(relaxed = true) {
        every { venueId } returns "venue"
        every { accessToken } returns "token"
    }
    private val unaVenta = """{"success":true,"data":[{"id":"pago-1","amount":120.5}],"meta":{"pageCount":1}}"""

    /** [falla] = lo que lanza la red en lugar de contestar (null = deja pasar al servidor de prueba). */
    private var falla: Exception? = null

    private fun repo(client: OkHttpClient = clienteQueFalla()) =
        TransactionRepository(storage, client).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }

    private fun clienteQueFalla() = OkHttpClient.Builder()
        .addInterceptor { chain -> falla?.let { throw it } ?: chain.proceed(chain.request()) }
        .build()

    @After fun apagar() = server.shutdown()

    @Test fun `nadie contesta - es SIN_RED y la lista que ya habia se queda`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setBody(unaVenta))
        assertTrue(repo.fetchTransactions().isSuccess)

        falla = UnknownHostException("sin red de prueba")
        assertTrue(repo.fetchTransactions().isFailure)

        assertEquals(FalloDeLectura.SIN_RED, repo.falloDeCarga.value)
        assertEquals(listOf("pago-1"), repo.transactions.value.map { it.id })
    }

    @Test fun `tiempo agotado - es SIN_RED`() = runBlocking {
        val lento = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        val repo = repo(lento)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertTrue(repo.fetchTransactions().isFailure)

        assertEquals(FalloDeLectura.SIN_RED, repo.falloDeCarga.value)
    }

    @Test fun `conexion rechazada - es SIN_RED`() = runBlocking {
        val apagado = MockWebServer().apply { start() }
        val ruta = apagado.url("/api/v1").toString().trimEnd('/')
        apagado.shutdown()
        val repo = TransactionRepository(storage, OkHttpClient()).apply { baseUrl = ruta }

        assertTrue(repo.fetchTransactions().isFailure)

        assertEquals(FalloDeLectura.SIN_RED, repo.falloDeCarga.value)
    }

    @Test fun `el servidor contesta 500 - es SERVIDOR`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        assertTrue(repo.fetchTransactions().isFailure)

        assertEquals(FalloDeLectura.SERVIDOR, repo.falloDeCarga.value)
    }

    @Test fun `200 con un cuerpo que no se puede leer o vacio - es SERVIDOR`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SERVIDOR, repo.falloDeCarga.value)

        server.enqueue(MockResponse().setBody(""))
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SERVIDOR, repo.falloDeCarga.value)
    }

    @Test fun `sesion sin renovar - si tropezo el servidor es SERVIDOR, si no hubo red es SIN_RED`() = runBlocking {
        val repo = repo()
        falla = SesionSinRenovar("servidor:503")
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SERVIDOR, repo.falloDeCarga.value)

        falla = SesionSinRenovar("red:UnknownHostException")
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SIN_RED, repo.falloDeCarga.value)
    }

    @Test fun `una carga buena borra el fallo`() = runBlocking {
        val repo = repo()
        falla = UnknownHostException("sin red de prueba")
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SIN_RED, repo.falloDeCarga.value)

        falla = null
        server.enqueue(MockResponse().setBody(unaVenta))
        assertTrue(repo.fetchTransactions().isSuccess)

        assertNull(repo.falloDeCarga.value)
    }

    @Test fun `cambiar de sucursal borra el fallo con la lista`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repo.fetchTransactions().isFailure)
        assertEquals(FalloDeLectura.SERVIDOR, repo.falloDeCarga.value)

        repo.clearCache()

        assertNull(repo.falloDeCarga.value)
    }
}
