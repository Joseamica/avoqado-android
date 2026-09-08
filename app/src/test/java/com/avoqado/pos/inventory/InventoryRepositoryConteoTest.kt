package com.avoqado.pos.inventory

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.SIN_VENUE
import com.avoqado.pos.inventory.data.model.StockCountItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Fase 3, Task 2 — el cuerpo del conteo se serializa de verdad y los escritores
 * devuelven el codigo HTTP para que el ViewModel lo clasifique con `ConteoEnCurso`.
 *
 * El cuerpo se armaba concatenando strings: una nota con comillas o salto de linea
 * producia un JSON invalido que el servidor rechazaba con 400, y el cajero perdia
 * su avance sin saber por que.
 */
class InventoryRepositoryConteoTest {
    private val server = MockWebServer()
    private lateinit var repo: InventoryRepository

    @Before
    fun setUp() {
        server.start()
        // ApiConstants.BASE_URL es fija: se apunta el venueBaseUrl a través de un SecureStorage falso NO basta.
        // El repositorio arma la URL con ApiConstants.BASE_URL; para probar el cuerpo y la clasificación
        // se sustituye la base con la propiedad de sistema que el repo lee en tests (ver Step 3).
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        val secure = mockk<SecureStorage>(relaxed = true)
        every { secure.venueId } returns "venue-1"
        val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        repo = InventoryRepository(secure, client, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        System.clearProperty("avoqado.test.baseUrl")
        server.shutdown()
    }

    @Test
    fun `P1 enviarAvance manda solo id y counted por linea, con JSON de verdad`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        val r = repo.enviarAvance("c1", listOf(StockCountItem(id = "l1", productId = "p", productName = "x", counted = 2.5, countedAt = "t")))
        assertEquals(200, r.code)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.endsWith("/mobile/venues/venue-1/inventory/stock-counts/c1"))
        val crudo = req.body.readUtf8()
        val body = JSONObject(crudo)
        assertEquals(1, body.getJSONArray("items").length())
        assertEquals("l1", body.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals(2.5, body.getJSONArray("items").getJSONObject(0).getDouble("counted"), 0.0)
        // `getDouble` coerciona "2.5" a 2.5, asi que por si solo NO distingue numero de cadena:
        // se fija el tipo y el texto crudo. Un `counted` entrecomillado es otro cuerpo para el server.
        assertTrue(
            "counted viaja como numero, no como cadena",
            body.getJSONArray("items").getJSONObject(0).get("counted") is Number,
        )
        assertTrue("en el JSON crudo: $crudo", crudo.contains("\"counted\":2.5"))
        assertTrue("sin nota no viaja la llave note", !body.has("note"))
    }

    @Test
    fun `updateStockCount escapa la nota por serializador, no a mano`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        val ok = repo.updateStockCount("c1", emptyList(), note = "estante \"3\"\nlínea nueva \\ fin")
        assertTrue(ok.isSuccess)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("estante \"3\"\nlínea nueva \\ fin", body.getString("note"))
    }

    @Test
    fun `enviarAvance devuelve el codigo tal cual y 0 sin transporte`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Conteo no encontrado o ya completado"}"""))
        assertEquals(404, repo.enviarAvance("c1", emptyList()).code)
        server.shutdown()
        assertEquals(0, repo.enviarAvance("c1", emptyList()).code)
    }

    @Test
    fun `cancelStockCount hace POST a cancel y devuelve el codigo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Este conteo ya estaba cancelado"}"""))
        val r = repo.cancelStockCount("c1")
        assertEquals(409, r.code)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        // Ruta COMPLETA: `endsWith` de la cola dejaba pasar un `/dashboard/venues/...`, que es
        // justo el invariante en rojo del brief. El prefijo `/api/v1` lo pone el @Before.
        assertEquals("/api/v1/mobile/venues/venue-1/inventory/stock-counts/c1/cancel", req.path)
    }

    /**
     * Los dos escritores nuevos corren SOLOS (autoguardado mientras el cajero teclea, replay al
     * reconectar). Sin la marca de fondo, un 403 abre el modal de permisos y el teclado del PIN de
     * gerente encima de la pantalla en la que este el cajero (`ForbiddenInterceptor:94`).
     */
    @Test
    fun `enviarAvance y cancelStockCount van marcados de fondo, updateStockCount no`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        repo.enviarAvance("c1", emptyList())
        assertEquals("1", server.takeRequest().getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        repo.cancelStockCount("c1")
        assertEquals("1", server.takeRequest().getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))

        // Lo dispara un toque: conserva el modal y el teclado de gerente de siempre.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        repo.updateStockCount("c1", emptyList(), note = "hola")
        assertNull(server.takeRequest().getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    /**
     * Sin venue NO es sin red: con el 0 la pantalla diria "Sin conexion" con la red perfecta
     * (`_sinRedAlEnviar = (code == 0)`), que es un aviso falso con el que el cajero decide.
     */
    @Test
    fun `sin venue devuelve su propio codigo y no toca la red`() = runBlocking {
        val sinVenue = mockk<SecureStorage>(relaxed = true)
        every { sinVenue.venueId } returns null
        val repoSinVenue = InventoryRepository(sinVenue, OkHttpClient(), mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(SIN_VENUE, repoSinVenue.enviarAvance("c1", emptyList()).code)
        assertEquals(SIN_VENUE, repoSinVenue.cancelStockCount("c1").code)
        assertEquals("no puede salir una peticion sin venue", 0, server.requestCount)
    }

    @Test
    fun `P1 transporte explicito usa venue y expectedRevision sin mutar el venue activo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"revision":5}"""))

        val respuesta = repo.enviarAvance(
            venueId = "venue-inactivo",
            countId = "c9",
            items = emptyList(),
            expectedRevision = 4,
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/venue-inactivo/inventory/stock-counts/c9", request.path)
        assertEquals(4, JSONObject(request.body.readUtf8()).getInt("expectedRevision"))
        assertEquals("1", request.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals(5, respuesta.revision)
    }

    @Test
    fun `P1 final manda borrado de nota vacia y confirm manda la revision reconocida`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"revision":5}"""))
        repo.enviarFinal("venue-1", "c1", emptyList(), note = "", expectedRevision = 4)
        val final = server.takeRequest()
        val finalBody = JSONObject(final.body.readUtf8())
        assertEquals("", finalBody.getString("note"))
        assertEquals(4, finalBody.getInt("expectedRevision"))
        assertNull(final.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"revision":6}"""))
        repo.confirmarConteo("venue-1", "c1", expectedRevision = 5)
        val confirm = server.takeRequest()
        assertEquals(5, JSONObject(confirm.body.readUtf8()).getInt("expectedRevision"))
    }

    @Test
    fun `P1 cancel explicito lleva revision y sigue marcado de fondo`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"count":{"revision":8}}"""),
        )

        val respuesta = repo.cancelStockCount("venue-a", "c7", expectedRevision = 7)

        val request = server.takeRequest()
        assertEquals(7, JSONObject(request.body.readUtf8()).getInt("expectedRevision"))
        assertEquals("1", request.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals(8, respuesta.revision)
    }

    @Test
    fun `P1 RespuestaHttp conserva conflicto revision estructurado y codigo applying separado`() {
        val conflicto = RespuestaHttp(
            409,
            """{"message":"cambió","code":"INVENTORY_COUNT_REVISION_CONFLICT","details":{"venueId":"v","countId":"c","expectedRevision":2,"currentRevision":3,"status":"IN_PROGRESS"}}""",
        )
        assertEquals("INVENTORY_COUNT_REVISION_CONFLICT", conflicto.codigo)
        assertNotNull(conflicto.conflictoRevision)
        assertEquals(3, conflicto.conflictoRevision?.currentRevision)

        val applying = RespuestaHttp(
            409,
            """{"code":"STOCK_COUNT_APPLYING","details":{"currentRevision":3,"status":"APPLYING"}}""",
        )
        assertEquals("STOCK_COUNT_APPLYING", applying.codigo)
        assertNull(applying.conflictoRevision)
    }
}
