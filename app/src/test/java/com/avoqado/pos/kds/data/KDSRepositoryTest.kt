package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Las rutas de la pantalla de cocina contra un servidor HTTP DE VERDAD (MockWebServer): qué URL, qué método y qué
 * cuerpo viajan, y cómo se lee lo que vuelve. Contrato: servidor de la fase 3.1 (spec 2026-09-27 §3).
 */
class KDSRepositoryTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private lateinit var repo: KDSRepository

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = KDSRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After
    fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    private fun comanda(sourceKey: String? = null, printStationId: String? = null, extraJson: String = "", itemJson: String = ""): String {
        val extra = buildString {
            sourceKey?.let { append(",\"sourceKey\":\"$it\"") }
            printStationId?.let { append(",\"printStationId\":\"$it\"") }
            append(extraJson)
        }
        return "{\"id\":\"k1\",\"orderNumber\":\"101\",\"orderType\":\"DINE_IN\",\"orderId\":\"o1\",\"status\":\"NEW\"," +
            "\"createdAt\":\"2026-09-27T12:00:00.000Z\"," +
            "\"items\":[{\"id\":\"i1\",\"productName\":\"Taco\",\"quantity\":2,\"modifiers\":[]$itemJson}]$extra}"
    }

    private fun lista(vararg comandas: String) = "{\"success\":true,\"data\":[${comandas.joinToString(",")}]}"


    @Test fun `board page exposes total and next page while requesting priority before the server limit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[${comanda()}],"page":{"total":250,"offset":100,"limit":100,"nextOffset":101}}"""))
        val page = repo.fetchBoardPage("st-barra", 100).getOrThrow()
        assertEquals(250, page.total); assertEquals(101, page.nextOffset); assertEquals(1, page.items.size)
        val url = server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("1", url.queryParameter("urgencyVersion")); assertEquals("100", url.queryParameter("offset"))
    }
    @Test
    fun `el tablero pide SU estacion y lee el folio y la estacion de cada comanda`() = runTest {
        server.enqueue(MockResponse().setBody(lista(comanda("sale:ext-1:st-barra", "st-barra"))))

        val comandas = repo.fetchOrders("st-barra").getOrThrow()

        val pedido = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", pedido.method)
        assertEquals("/api/v1/mobile/venues/v1/kds/orders", pedido.requestUrl!!.encodedPath)
        assertEquals("NEW,PREPARING,READY", pedido.requestUrl!!.queryParameter("status"))
        assertEquals("st-barra", pedido.requestUrl!!.queryParameter("stationId"))
        assertEquals("1", pedido.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals("sale:ext-1:st-barra", comandas.single().sourceKey)
        assertEquals("st-barra", comandas.single().printStationId)
    }

    @Test
    fun `P1 un servidor anterior a la etapa 3 (sin folio ni estacion) se lee sin romper`() = runTest {
        server.enqueue(MockResponse().setBody(lista(comanda())))

        val c = repo.fetchOrders("st-barra").getOrThrow().single()

        assertNull(c.sourceKey)
        assertNull(c.printStationId)
        assertEquals("101", c.orderNumber)
        // Sin mesa: «En tienda», como siempre, y sin tiempos.
        assertEquals("En tienda", c.orderType)
        assertNull(c.items.single().course)
    }

    @Test
    fun `KDS 3_6 la comanda de una mesa dice Mesa 8 y trae el tiempo de cada platillo`() = runTest {
        server.enqueue(MockResponse().setBody(lista(comanda(extraJson = ",\"tableNumber\":\"8\"", itemJson = ",\"course\":\"Aperitivos\""))))

        val c = repo.fetchOrders("st-barra").getOrThrow().single()

        assertEquals("Mesa 8", c.orderType)
        assertEquals("Aperitivos", c.items.single().course)
    }

    @Test
    fun `KDS 3_6 una mesa y un tiempo en null se leen como sin mesa y sin tiempo`() = runTest {
        server.enqueue(MockResponse().setBody(lista(comanda(extraJson = ",\"tableNumber\":null", itemJson = ",\"course\":null"))))

        val c = repo.fetchOrders("st-barra").getOrThrow().single()

        assertEquals("En tienda", c.orderType)
        assertNull(c.items.single().course)
    }

    @Test
    fun `recientes, deshacer y marcar todas usan sus rutas y sus cuerpos`() = runTest {
        server.enqueue(MockResponse().setBody(lista(comanda())))
        server.enqueue(MockResponse().setBody("{\"success\":true,\"data\":{}}"))
        server.enqueue(MockResponse().setBody("{\"success\":true,\"data\":{\"completed\":2}}"))

        assertEquals(1, repo.fetchRecientes("st-barra").getOrThrow().size)
        repo.recall("k1").getOrThrow()
        assertEquals(2, repo.bumpBatch(listOf("k1", "k2")).getOrThrow())

        val recientes = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/kds/orders/recent", recientes.requestUrl!!.encodedPath)
        assertEquals("st-barra", recientes.requestUrl!!.queryParameter("stationId"))
        val deshacer = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", deshacer.method)
        assertEquals("/api/v1/mobile/venues/v1/kds/orders/k1/recall", deshacer.path)
        val lote = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/kds/orders/bump-batch", lote.path)
        val ids = JSONObject(lote.body.readUtf8()).getJSONArray("ids")
        assertEquals(listOf("k1", "k2"), (0 until ids.length()).map { ids.getString(it) })
        assertEquals("1", lote.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    @Test
    fun `P1 el rechazo de la puerta de lanzamiento llega con su codigo, no como error generico`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                "{\"message\":\"La pantalla de cocina todavía no está disponible para clientes.\"," +
                    "\"code\":\"KITCHEN_DISPLAY_NOT_RELEASED\"}",
            ),
        )

        val fallo = repo.setKitchenDisplay("st-barra", enabled = true).exceptionOrNull()

        val pedido = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", pedido.method)
        assertEquals("/api/v1/mobile/venues/v1/print-stations/st-barra/kitchen-display", pedido.path)
        assertEquals(true, JSONObject(pedido.body.readUtf8()).getBoolean("enabled"))
        assertEquals("1", pedido.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
        assertTrue("fue $fallo", fallo is KdsHttpException)
        assertEquals(403, (fallo as KdsHttpException).status)
        assertEquals("KITCHEN_DISPLAY_NOT_RELEASED", fallo.codigo)
    }

    @Test
    fun `sin red LISTO falla como IOException - la pantalla sabe que fue la red`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val fallo = repo.bumpOrder("k1").exceptionOrNull()

        assertTrue("fue $fallo", fallo is IOException)
    }
}
