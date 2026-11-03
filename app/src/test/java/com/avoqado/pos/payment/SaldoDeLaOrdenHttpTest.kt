package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.OrderRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D7 (dividir por producto, fase 1, 27-oct): la consulta del saldo tras una parte con tarjeta corre entre «la tarjeta
 * cobró» y «Cobro exitoso». Con OkHttp real: lee saldo y estado de la MISMA respuesta, una respuesta retenida no pasa de
 * ~2 s y un 401 NO dispara la renovación de sesión (Codex fase 1 código r2).
 */
class SaldoDeLaOrdenHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: OrderRepository
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private var renovaciones = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        // Un cliente con renovación de sesión, como el de la app: la consulta del saldo no debe usarla.
        val conRenovacion = OkHttpClient.Builder().authenticator(Authenticator { _, _ -> renovaciones++; null }).build()
        repository = OrderRepository(secureStorage, conRenovacion)
        repository.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `lee el saldo y el estado de la misma respuesta`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"order":{"paymentStatus":"partial","preBillBalance":{"remainingBalanceCents":2000}}}"""))

        val saldo = repository.saldoDeLaOrden("orden-1")

        assertEquals(OrderRepository.SaldoDeLaOrden(remainingCents = 2000, paymentStatus = "PARTIAL"), saldo)
        assertEquals("/api/v1/mobile/venues/venue-1/orders/orden-1", server.takeRequest().path)
    }

    @Test
    fun `un 401 es no se pudo confirmar, sin renovar la sesion`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(repository.saldoDeLaOrden("orden-1"))
        assertEquals(0, renovaciones)
    }

    @Test
    fun `un DNS que no responde no retiene el presupuesto del cobro`() = runBlocking {
        // Codex fase 1 r3: un resolvedor que ignora interrupciones. La consulta se suelta al vencer el presupuesto.
        val dnsAtorado = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val hasta = System.nanoTime() + 15_000_000_000L
                while (System.nanoTime() < hasta) { try { Thread.sleep(500) } catch (_: InterruptedException) {} }
                return emptyList()
            }
        }
        val repo = OrderRepository(secureStorage, OkHttpClient.Builder().dns(dnsAtorado).build())
        repo.baseUrl = "http://saldo.invalido/api/v1"

        val inicio = System.nanoTime()
        val saldo = kotlinx.coroutines.withTimeoutOrNull(3_000) { repo.saldoDeLaOrden("orden-1") }
        val ms = (System.nanoTime() - inicio) / 1_000_000

        assertNull(saldo)
        assertTrue("tardó $ms ms", ms < 3_800)
    }

    @Test
    fun `una respuesta retenida se corta a los 2 segundos`() = runBlocking {
        // Nunca contesta (sin trabar el apagado del servidor falso, que `setHeadersDelay` sí traba en el `tearDown`).
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))

        val inicio = System.nanoTime()
        val saldo = repository.saldoDeLaOrden("orden-1")
        val ms = (System.nanoTime() - inicio) / 1_000_000

        assertNull(saldo)
        assertTrue("tardó $ms ms", ms < 4_000)
    }
}
