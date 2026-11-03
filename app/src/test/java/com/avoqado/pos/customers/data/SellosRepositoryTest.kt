package com.avoqado.pos.customers.data

import com.avoqado.pos.core.data.local.SecureStorage
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
import org.junit.Before
import org.junit.Test

class SellosRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: SellosRepository
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val vista = TarjetaDeSellos(3, 8, "Cafe", 0, "card-1", 4, true)

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        every { storage.venueId } returns "venue-1"
        repo = SellosRepository(storage, OkHttpClient())
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `P1 stampsEnabled false con el JSON literal del servidor da null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stampsEarned":0,"stampsRequired":10,"rewardLabel":"","pendingRewards":0,"stampCardId":null,"version":0,"stampsEnabled":false,"rewardsToClaim":[],"otro":1}"""))
        assertNull(repo.obtener("c1").getOrThrow())
        assertEquals("/api/v1/mobile/venues/venue-1/customers/c1/stamp-card", server.takeRequest().path)
    }

    @Test
    fun `P1 403 de plan da null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"plan"}"""))
        assertNull(repo.obtener("c1").getOrThrow())
    }

    @Test
    fun `P1 lectura con sellos devuelve la tarjeta`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stampsEarned":3,"stampsRequired":8,"rewardLabel":"Cafe","pendingRewards":1,"stampCardId":"card-1","version":4,"stampsEnabled":true,"rewardsToClaim":[]}"""))
        assertEquals(vista.copy(pendingRewards = 1), repo.obtener("c1").getOrThrow())
    }

    @Test
    fun `P1 ajustar manda los expected y lee el resultado`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stampCardId":"card-1","from":3,"to":8,"delta":5,"completed":true,"rewardId":"r1","stampsRequired":8}"""))
        val r = repo.ajustar("c1", 8, "  Migracion de sellos ", vista)
        assertEquals(ResultadoDeAjuste.Hecho(8, true), r)
        val req = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/venue-1/customers/c1/stamps/adjust", req.path)
        val b = req.body.readUtf8()
        assertTrue(b.contains("\"expectedStampCardId\":\"card-1\""))
        assertTrue(b.contains("\"expectedStampsEarned\":3"))
        assertTrue(b.contains("\"expectedVersion\":4"))
        // F1: la meta de la foto también viaja (el servidor la compara).
        assertTrue(b, b.contains("\"expectedStampsRequired\":8"))
        assertTrue(b.contains("\"reason\":\"Migracion de sellos\""))
    }

    @Test
    fun `P1 sin cartilla manda expectedStampCardId null y la meta de la foto`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stampCardId":"card-n","from":0,"to":2,"delta":2,"completed":false,"stampsRequired":10}"""))
        repo.ajustar("c1", 2, "Migracion de sellos", TarjetaDeSellos(0, 10, "Cafe", 0, null, 0, true))
        val b = server.takeRequest().body.readUtf8()
        assertTrue(b, b.contains("\"expectedStampCardId\":null"))
        assertTrue(b, b.contains("\"expectedStampsRequired\":10"))
    }

    @Test
    fun `P1 409 STAMPS_CHANGED da Cambio con el numero nuevo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"STAMPS_CHANGED","details":{"stampCardId":"card-1","stampsEarned":6,"stampsRequired":8,"version":5}}"""))
        assertEquals(ResultadoDeAjuste.Cambio(6), repo.ajustar("c1", 8, "Migracion de sellos", vista))
    }

    @Test
    fun `P1 409 STAMPS_DISABLED da Apagado`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"STAMPS_DISABLED"}"""))
        assertEquals(ResultadoDeAjuste.Apagado, repo.ajustar("c1", 8, "Migracion de sellos", vista))
    }

    @Test
    fun `P1 sin red da SinRed`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ResultadoDeAjuste.SinRed, repo.ajustar("c1", 8, "Migracion de sellos", vista))
    }
}
