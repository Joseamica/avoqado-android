package com.avoqado.pos.inventory.waste

import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.WasteCatalogDao
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * El transporte de la merma contra un servidor HTTP DE VERDAD (MockWebServer): la URL, la marca
 * de segundo plano y el cuerpo exacto que viajan. El motor sólo le entrega la fila; lo que llega al
 * servidor se decide aquí.
 */
class WasteRepositoryTest {

    private val server = MockWebServer()
    private lateinit var repo: WasteRepository
    private val catalogo = CatalogoFalso()

    /** El token que pondría `AuthInterceptor`: el de la sesión vigente del aparato. */
    private var tokenDeLaSesion = tokenDe("yo")

    /** Como el cliente de la app: una capa que pone la credencial de la sesión en cada petición. */
    private fun clienteConSesion(lectura: Long = 1) = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(lectura, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $tokenDeLaSesion").build())
        }

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
        repo = WasteRepository(clienteConSesion().build(), catalogo)
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

    /**
     * Preguntar por el plan cuesta UN artículo del catálogo, que pasa por el MISMO candado de plan
     * que registrar. En segundo plano: un 403 de permiso no puede abrir el PIN del gerente.
     */
    @Test
    fun `consultar el plan pide un solo articulo, en segundo plano, a la sucursal pedida`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"total":0,"page":1,"pageSize":1}"""))

        assertEquals(true, repo.consultarPlan("venue-centro"))

        val req = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/venue-centro/inventory/waste-items?page=1&pageSize=1", req.path)
        assertEquals("1", req.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    /** El 403 del candado de plan trae `featureCode`: eso, y sólo eso, es «sin plan». */
    @Test
    fun `un 403 con featureCode es falta de plan`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","featureCode":"INVENTORY_TRACKING"}"""),
        )
        assertEquals(false, repo.consultarPlan("venue-centro"))
    }

    /** Un 403 de permiso o la falta de red no dicen nada del plan: no se levanta ni se pone nada. */
    @Test
    fun `un 403 de permiso o sin red no dicen nada del plan`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","required":"inventory:log-waste"}"""),
        )
        assertNull(repo.consultarPlan("venue-centro"))

        server.shutdown()
        assertNull(repo.consultarPlan("venue-centro"))
    }

    /** La anulación va al venue DE LA FILA, con sólo el folio, y de segundo plano: su 403 no admite PIN. */
    @Test
    fun `la anulacion viaja al venue de la fila con solo el folio, en segundo plano`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"outcome":"VOIDED"}"""))

        val respuesta = repo.anular(fila(venueId = "venue-sur"), porStaffId = "yo")

        val pedido = server.takeRequest()
        assertEquals(200, respuesta.code)
        assertEquals("/api/v1/mobile/venues/venue-sur/inventory/waste/void", pedido.path)
        assertEquals("POST", pedido.method)
        assertEquals("1", pedido.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals("""{"idempotencyKey":"3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f"}""", pedido.body.readUtf8())
    }

    // MARK: - La credencial con la que sale (Codex r1, P1)

    /**
     * 🔴 El servidor registra la merma a nombre de quien firma el TOKEN. Si entre la comprobación del
     * motor y el envío terminó un relevo por PIN, la credencial ya es de otra persona: la merma NO
     * sale — ni una sola petición llega al servidor.
     */
    @Test
    fun `con la credencial de otra persona la merma no sale`() = runTest {
        tokenDeLaSesion = tokenDe("persona-B")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"reportId":"r1"}"""))

        val respuesta = repo.enviar(fila())

        assertEquals(0, respuesta.code)
        assertEquals(0, server.requestCount)
    }

    /**
     * 🔴 El segundo camino de Codex: el POST de A recibe 401 DESPUÉS del relevo, el refresco usa la
     * sesión de B y el reenvío saldría firmado por B. La comprobación corre en CADA salida a la red,
     * incluido el reenvío tras refrescar: el primer intento llega, el reenvío no.
     */
    @Test
    fun `si el refresco trae la credencial de otra persona, el reenvio no sale`() = runTest {
        val cliente = clienteConSesion()
            .authenticator { _, respuesta ->
                respuesta.request.newBuilder().header("Authorization", "Bearer ${tokenDe("persona-B")}").build()
            }
            .build()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"reportId":"r1"}"""))

        val respuesta = WasteRepository(cliente, catalogo).enviar(fila())

        assertEquals(0, respuesta.code)
        assertEquals(1, server.requestCount)
    }

    /** Control: si el refresco renueva la credencial de la MISMA persona, el reenvío sí sale. */
    @Test
    fun `si el refresco renueva la credencial de la misma persona, el reenvio sale`() = runTest {
        val cliente = clienteConSesion()
            .authenticator { _, respuesta ->
                respuesta.request.newBuilder().header("Authorization", "Bearer ${tokenDe("yo")}").build()
            }
            .build()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"reportId":"r1"}"""))

        val respuesta = WasteRepository(cliente, catalogo).enviar(fila())

        assertEquals(201, respuesta.code)
        assertEquals(2, server.requestCount)
    }

    /** El `void` también sale con la credencial de quien lo pide: la lápida lleva su nombre. */
    @Test
    fun `la anulacion no sale con la credencial de otra persona`() = runTest {
        tokenDeLaSesion = tokenDe("persona-B")

        val respuesta = repo.anular(fila(), porStaffId = "gerente-1")

        assertEquals(0, respuesta.code)
        assertEquals(0, server.requestCount)
    }

    // MARK: - Lo que no viene de nuestra API (Codex r1)

    /**
     * 🔴 Un 403 o un 408 de un proxy, un portal cautivo o Cloudflare no dicen nada de la merma. Se
     * leen como «servidor no disponible» (503), igual que iOS (`APIClient.isFromIntermediary`) y que
     * los cobros de esta app (`OrderRepository.isTransient4xx`): se reintentan, no van a revisión.
     */
    @Test
    fun `un 4xx de un intermediario se lee como servidor no disponible`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>WAF</html>"))
        // OkHttp reintenta un 408 UNA vez por su cuenta (`RetryAndFollowUpInterceptor`): llegan dos.
        server.enqueue(MockResponse().setResponseCode(408))
        server.enqueue(MockResponse().setResponseCode(408))

        assertEquals(503, repo.enviar(fila()).code)
        assertEquals(503, repo.enviar(fila()).code)
    }

    /** Control: el 403 de NUESTRA API (JSON) sigue siendo un 403 — es falta de permiso de verdad. */
    @Test
    fun `un 403 de nuestra API sigue siendo un 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","required":"inventory:log-waste"}"""))

        assertEquals(403, repo.enviar(fila()).code)
    }

    // MARK: - El plazo corta la petición de verdad (Codex r1)

    /**
     * 🔴 El plazo de 5 s del cierre de sesión cancelaba la ESPERA, pero la petición seguía bloqueada
     * hasta el `readTimeout` de OkHttp (30 s): la persona no podía salir. Un servidor que acepta la
     * conexión y nunca contesta; el plazo tiene que cortar la llamada misma.
     */
    @Test
    fun `al vencer el plazo la peticion se corta, no espera al timeout de lectura`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancelada = AtomicBoolean(false)
        val cliente = clienteConSesion(lectura = 30)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelada.set(true)
            })
            .build()
        val lento = WasteRepository(cliente, catalogo)
        val inicio = System.nanoTime()

        val respuesta = withTimeoutOrNull(300) { lento.enviar(fila()) }

        val ms = (System.nanoTime() - inicio) / 1_000_000
        assertNull(respuesta)
        assertTrue("tardó $ms ms en soltar", ms < 5_000)
        // Y la llamada misma se cortó: no se queda viva en segundo plano hasta el timeout de lectura.
        assertTrue("la llamada siguió viva", cancelada.get())
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
