package com.avoqado.pos.customers

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * «Dany no aparece» (14-oct): la app pedía `?limit=50`, el servidor lee `pageSize` (default 20) y la tablet sólo
 * recibía los 20 más nuevos. Dany era el #41. Ahora la búsqueda viaja al servidor y lo recibido se guarda para sin red.
 */
class CustomersRepositoryHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: CustomersRepository
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        repository = CustomersRepository(secureStorage, OkHttpClient())
        repository.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun lista(vararg ids: String) =
        ids.joinToString(prefix = """{"data":[""", postfix = """],"meta":{"totalCount":${ids.size}}}""") {
            """{"id":"$it","firstName":"N$it"}"""
        }

    @Test
    fun `P1 pide pageSize 50, no limit, y manda la busqueda tal cual la escribio el cajero`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1")))

        repository.fetchCustomers(search = "+52 81 2944 0042")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/mobile/venues/venue-1/customers", url.encodedPath)
        assertEquals("50", url.queryParameter("pageSize"))
        assertNull(url.queryParameter("limit"))
        assertEquals("+52 81 2944 0042", url.queryParameter("search"))
        assertEquals("createdAt", url.queryParameter("sortBy"))
        assertEquals("desc", url.queryParameter("sortOrder"))
    }

    @Test
    fun `sin texto no manda search`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1")))

        repository.fetchCustomers()

        assertNull(server.takeRequest().requestUrl!!.queryParameter("search"))
    }

    @Test
    fun `lo recibido queda guardado para buscar sin red, sin repetir clientes`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1", "c2")))
        server.enqueue(MockResponse().setBody(lista("c2", "c3")))

        repository.fetchCustomers()
        repository.fetchCustomers(search = "n")

        assertEquals(listOf("c1", "c2", "c3"), repository.clientesGuardados().map { it.id })
    }

    @Test
    fun `P1 al cambiar de sucursal los guardados de la otra no aparecen`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1")))
        repository.fetchCustomers()

        every { secureStorage.venueId } returns "venue-2"

        assertEquals(emptyList<String>(), repository.clientesGuardados().map { it.id })
    }

    @Test
    fun `P1 una respuesta tardia de la sucursal anterior no borra ni ensucia los guardados de la nueva`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1")).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(lista("c2")))

        // La búsqueda de la sucursal 1 sale y se queda en vuelo…
        val tardia = launch(Dispatchers.Default) { repository.fetchCustomers() }
        server.takeRequest()
        // …el cajero cambia a la sucursal 2 y ahí sí le contestan.
        every { secureStorage.venueId } returns "venue-2"
        repository.fetchCustomers()
        tardia.join()

        assertEquals(listOf("c2"), repository.clientesGuardados().map { it.id })
    }

    @Test
    fun `P1 una busqueda cancelada no se lee como fallo, se relanza la cancelacion`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1")).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        var resultado: Result<PaginaDeClientes>? = null

        val busqueda = launch(Dispatchers.Default) { resultado = repository.buscarClientes("Dan") }
        server.takeRequest() // ya salió: el cajero siguió tecleando
        busqueda.cancelAndJoin()

        assertTrue(busqueda.isCancelled)
        assertNull("una búsqueda cancelada no devuelve un «fallo» que se pinte como sin red", resultado)
    }

    // Auditoría A #10: el total que dice el servidor ya no se tira.
    @Test
    fun `P1 lee cuantos clientes cumplen la busqueda en todo el negocio y si el servidor topo`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}],"meta":{"totalCount":120,"pageSize":50,"currentPage":1,"totalPages":3,"hasNextPage":true,"hasPrevPage":false}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}],"meta":{"totalCount":6200,"searchCapped":true}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}],"pagination":{"total":7}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}]}"""))

        assertEquals(PaginaDeClientes(listOf(Customer(id = "c1")), total = 120, busquedaTopada = false), repository.buscarClientes("a").getOrThrow())
        assertEquals(PaginaDeClientes(listOf(Customer(id = "c1")), total = 6200, busquedaTopada = true), repository.buscarClientes("a").getOrThrow())
        assertEquals(7, repository.buscarClientes("a").getOrThrow().total)
        assertNull("servidor viejo sin total: no se inventa", repository.buscarClientes("a").getOrThrow().total)
    }

    // Auditoría B (B3): con más de 5000 coincidencias el servidor topa. `meta.totalCount` = las ALCANZABLES (5000, para
    // que el dashboard no ofrezca páginas vacías) y `meta.totalMatches` = el conteo EXACTO. La tablet dice el exacto.
    @Test
    fun `P1 busqueda topada dice el total exacto totalMatches, no las 5000 alcanzables`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}],"meta":{"totalCount":5000,"totalMatches":6200,"searchCapped":true}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1"}],"meta":{"totalCount":5000,"searchCapped":true}}"""))

        assertEquals(PaginaDeClientes(listOf(Customer(id = "c1")), total = 6200, busquedaTopada = true), repository.buscarClientes("a").getOrThrow())
        assertEquals(
            "sin totalMatches (servidor que no lo manda) queda el alcanzable, y sigue topada",
            PaginaDeClientes(listOf(Customer(id = "c1")), total = 5000, busquedaTopada = true),
            repository.buscarClientes("a").getOrThrow(),
        )
    }

    @Test
    fun `fetchCustomers sigue devolviendo la lista para quien solo la necesita (reservas)`() = runBlocking {
        server.enqueue(MockResponse().setBody(lista("c1", "c2")))

        assertEquals(listOf("c1", "c2"), repository.fetchCustomers().getOrThrow().map { it.id })
    }
}
