package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** «Por pagar» / «Por entregar» (tienda híbrida, etapa 1) contra `GET online-orders/to-pay`, con MockWebServer. */
class PorPagarTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val guardado = mutableMapOf<String, String>()
    private val cache = mockk<PayloadCache>().also { c ->
        coEvery { c.save(any(), any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"] = thirdArg() }
        coEvery { c.load(any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"]?.let { PayloadCache.Cached(it, 0L) } }
    }
    private lateinit var repo: OnlineOrdersRepository

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), cache)
    }

    @After
    fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    private fun folio(id: String = "o1", number: String = "W7KQ2", status: String = "PENDING_ACCEPTANCE", total: String = "186.10",
                      remaining: String = "66.10", pago: String = "PARTIAL") =
        """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"$number","status":"$status","paymentMode":"PAY_AT_PICKUP",
        "customerName":"María","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z",
        "acceptDeadlineAt":"2026-11-06T18:30:00.000Z","estimatedReadyAt":null,"total":$total,"remaining":$remaining,"paymentStatus":"$pago",
        "cardStatus":null,"card":null,"rejectReason":null,"requiresAcceptance":false,"channel":"QR","needsPrint":false,
        "items":[{"productId":"p1","categoryId":"c1","name":"Flautas","quantity":2,"note":null,"modifiers":[{"name":"Salsa verde","quantity":1}]}]}"""

    private fun cuerpo(porPagar: String = folio(), porEntregar: String = "", cursorPagar: String? = null, cursorEntregar: String? = null) =
        """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,
        "pollSeconds":30,"counts":{"toPay":2,"toDeliver":1},"toPay":[$porPagar],"toDeliver":[$porEntregar],
        "nextCursors":{"toPay":${cursorPagar?.let { "\"$it\"" } ?: "null"},"toDeliver":${cursorEntregar?.let { "\"$it\"" } ?: "null"}},
        "store":{"status":"OPEN","reason":null,"pausedUntil":null,"manualPause":false}}}"""

    @Test
    fun `porPagar parsea folios con saldo en centavos exactos`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(porEntregar = folio("o2", "WAB23", "ACCEPTED", "50", "0", "PAID"))))
        val lista = repo.porPagar(conLatido = false).getOrThrow()
        val f = lista.porPagar.single()
        assertEquals("W7KQ2", f.orderNumber); assertEquals("o1", f.orderId); assertEquals("PARTIAL", f.paymentStatus)
        assertEquals(18610L, f.totalCents); assertEquals(6610L, f.remainingCents)
        assertEquals(false, f.requiresAcceptance); assertEquals("QR", f.channel)
        assertEquals("María", f.customerName); assertNotNull(f.acceptDeadlineAt); assertEquals("Flautas", f.items.single().name)
        val e = lista.porEntregar.single()
        assertEquals(5000L, e.totalCents); assertEquals(0L, e.remainingCents)
        assertEquals(2, lista.counts.toPay); assertEquals(1, lista.counts.toDeliver)
        assertTrue(lista.disponible); assertTrue(lista.showTile); assertEquals(30, lista.pollSeconds)
        assertNull(lista.nextCursors.toPay); assertNull(lista.nextCursors.toDeliver)
        assertNull(lista.guardadoHaceMin)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/to-pay", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `porPagar no pierde un centavo con cantidades de punto flotante dificiles`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(folio(total = "1.15", remaining = "0.29"))))
        val f = repo.porPagar(conLatido = false).getOrThrow().porPagar.single()
        assertEquals(115L, f.totalCents); assertEquals(29L, f.remainingCents)
    }

    @Test
    fun `403 deja la seccion no disponible sin error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"Forbidden"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}"""))
        for (i in 0..1) {
            val lista = repo.porPagar(conLatido = true).getOrThrow()
            assertFalse(lista.disponible); assertFalse(lista.showTile)
            assertTrue(lista.porPagar.isEmpty()); assertTrue(lista.porEntregar.isEmpty())
        }
    }

    @Test
    fun `con conLatido manda x-online-orders-to-pay 1 y sin el no`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo()))
        server.enqueue(MockResponse().setBody(cuerpo()))
        repo.porPagar(conLatido = true).getOrThrow()
        repo.porPagar(conLatido = false).getOrThrow()
        assertEquals("1", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-to-pay"))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-to-pay"))
    }

    @Test
    fun `porPagar manda seccion y cursor y no pisa lo guardado con otra pagina`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(folio("o1"), cursorPagar = "c1")))
        server.enqueue(MockResponse().setBody(cuerpo(folio("o7"))))
        val primera = repo.porPagar(conLatido = false).getOrThrow()
        assertEquals("c1", primera.nextCursors.toPay)
        repo.porPagar(conLatido = false, seccion = "toPay", cursor = primera.nextCursors.toPay).getOrThrow()
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/to-pay?section=toPay&cursor=c1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertEquals(listOf("o1"), repo.porPagarGuardado()!!.porPagar.map { it.orderId })
    }

    @Test
    fun `porPagar guarda la primera pagina y sin red se lee guardada con su edad`() = runTest {
        assertNull(repo.porPagarGuardado())
        server.enqueue(MockResponse().setBody(cuerpo()))
        repo.porPagar(conLatido = false).getOrThrow()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(repo.porPagar(conLatido = false).isFailure)
        val viejo = repo.porPagarGuardado()!!
        assertEquals(6610L, viejo.porPagar.single().remainingCents)
        assertNotNull(viejo.guardadoHaceMin)
    }

    @Test
    fun `un folio dañado se salta y el resto sobrevive`() = runTest {
        val roto = folio("o8").replace("\"orderId\":\"o8\",", "")
        server.enqueue(MockResponse().setBody(cuerpo(porPagar = folio("o1") + "," + roto)))
        assertEquals(listOf("o1"), repo.porPagar(conLatido = false).getOrThrow().porPagar.map { it.orderId })
    }
}
