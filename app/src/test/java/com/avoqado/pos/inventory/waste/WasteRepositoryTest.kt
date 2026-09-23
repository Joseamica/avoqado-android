package com.avoqado.pos.inventory.waste

import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.WasteCatalogDao
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * El transporte de la merma contra un servidor HTTP DE VERDAD (MockWebServer): la URL, la marca
 * de segundo plano y el cuerpo exacto que viajan. El motor sólo le entrega la fila; lo que llega al
 * servidor se decide aquí.
 */
class WasteRepositoryTest {

    private val server = MockWebServer()
    private lateinit var repo: WasteRepository
    private val catalogo = CatalogoFalso()

    /** Sólo lo que usa el refresco del catálogo: el reemplazo atómico por venue. */
    private class CatalogoFalso : WasteCatalogDao {
        val porVenue = mutableMapOf<String, List<WasteCatalogEntity>>()
        override suspend fun insertar(items: List<WasteCatalogEntity>) {
            items.groupBy { it.venueId }.forEach { (v, l) -> porVenue[v] = porVenue[v].orEmpty() + l }
        }
        override suspend fun borrarDelVenue(venueId: String) { porVenue.remove(venueId) }
        override suspend fun catalogoDelVenue(venueId: String) = porVenue[venueId].orEmpty()
        override suspend fun actualizadoEn(venueId: String) = porVenue[venueId]?.maxOfOrNull { it.actualizadoEn }
    }

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        repo = WasteRepository(client, catalogo)
    }

    @After
    fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    private fun fila(venueId: String = "venue-centro", note: String? = null) = PendingWasteEntity(
        idempotencyKey = "3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f", venueId = venueId, staffId = "yo",
        itemType = "RAW_MATERIAL", itemId = "rm-1", itemName = "Aguacate", unit = "kg", quantity = "3.5",
        reasonCode = "SPOILED", note = note, clientOccurredAt = "2026-09-22T12:00:00-06:00",
        estado = EstadoMerma.SENDING, creadaEn = 0L,
    )

    /**
     * 🔴 Review Focus 3 — la URL se arma con el venue DE LA FILA. Con el venue activo, una merma
     * capturada en el Centro se registraría en la Sucursal Sur al cambiar de sucursal, y nada lo
     * delataría: el servidor respondería 201.
     *
     * 🔴 Y viaja marcada de segundo plano: un 403 «overridable» del drenado, que corre solo, no puede
     * abrir el teclado del PIN del gerente encima de otra pantalla (`ForbiddenInterceptor`).
     */
    @Test
    fun `la merma viaja al venue de la fila, marcada de segundo plano`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"reportId":"r1"}"""))

        val respuesta = repo.enviar(fila(venueId = "venue-centro"))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/mobile/venues/venue-centro/inventory/waste", req.path)
        assertEquals("1", req.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals(201, respuesta.code)
    }

    /**
     * 🔴 El cuerpo es ESTRICTO en el servidor: «ningún campo más, ni siquiera en null». Una nota
     * ausente no viaja como `"note": null`; la llave simplemente no está.
     */
    @Test
    fun `el cuerpo lleva exactamente los campos del contrato, sin nulos`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        repo.enviar(fila(note = null))

        val cuerpo = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(
            setOf("itemType", "itemId", "quantity", "unit", "reasonCode", "idempotencyKey", "clientOccurredAt"),
            cuerpo.keys().asSequence().toSet(),
        )
        assertEquals("3.5", cuerpo.getString("quantity"))
        assertEquals("3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f", cuerpo.getString("idempotencyKey"))
    }

    @Test
    fun `con nota, la nota viaja`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        repo.enviar(fila(note = "se derramó al trasvasar"))

        assertEquals("se derramó al trasvasar", JSONObject(server.takeRequest().body.readUtf8()).getString("note"))
    }

    /** Sin red no revienta: devuelve `0`, que el motor lee como «reintentar». */
    @Test
    fun `sin red devuelve 0, no revienta`() = runTest {
        server.shutdown()
        assertEquals(0, repo.enviar(fila()).code)
    }

    /** El refresco baja todas las páginas y reemplaza el catálogo de ESA sucursal de una vez. */
    @Test
    fun `refrescar el catalogo baja todas las paginas y lo reemplaza`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"itemType":"RAW_MATERIAL","itemId":"rm1","name":"Aguacate","sku":"","unit":"kg"}],"total":2,"page":1,"pageSize":1}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"itemType":"PRODUCT","itemId":"p1","name":"Café","sku":"CAF","unit":"pieza"}],"total":2,"page":2,"pageSize":1}""",
            ),
        )

        assertTrue(repo.refrescarCatalogo("venue-centro", ahora = 1_000L))

        assertEquals(setOf("rm1", "p1"), catalogo.catalogoDelVenue("venue-centro").map { it.itemId }.toSet())
        assertEquals(
            "/api/v1/mobile/venues/venue-centro/inventory/waste-items?page=1&pageSize=200",
            server.takeRequest().path,
        )
    }

    /** Si una página falla, el catálogo anterior sigue intacto. */
    @Test
    fun `si una pagina falla, el catalogo anterior sigue intacto`() = runTest {
        catalogo.porVenue["venue-centro"] = listOf(
            WasteCatalogEntity("venue-centro", "RAW_MATERIAL", "viejo", "Viejo", "", "kg", 1L),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        assertFalse(repo.refrescarCatalogo("venue-centro", ahora = 2_000L))

        assertEquals(listOf("viejo"), catalogo.catalogoDelVenue("venue-centro").map { it.itemId })
    }
}
