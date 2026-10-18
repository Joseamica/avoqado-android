package com.avoqado.pos.pos.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.pos.data.model.Product
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Se cobra el precio que vio el cliente» (Task 9): el refresco automático del catálogo no es una carga que el usuario
 * pidió. Con la petición COLGADA (WiFi conectado pero sin datos) el estado que ve el cajero no cambia: el botón
 * «Actualizar» del Cobrar no gira solo y no aparece ningún error. La carga que él pide queda como siempre.
 */
class ProductsRepositoryRefrescoDeFondoTest {

    private val cat = "2026-10-17T15:00:00.000Z"

    private val pan = Product(id = "pa", name = "Pan", priceValue = 12.5, catalogAt = cat)
    private val enDisco = Json.encodeToString(ListSerializer(Product.serializer()), listOf(pan))

    /** Un caché con el catálogo del último arranque: la lista ya está pintada antes de que la red conteste. */
    private val conCatalogo = mockk<PayloadCache>(relaxed = true) {
        coEvery { load(PayloadCache.TYPE_PRODUCTS, "venue-a") } returns PayloadCache.Cached(enDisco, 0L)
    }

    private fun almacen(venue: String? = "venue-a", token: String? = "token") = mockk<SecureStorage> {
        every { venueId } returns venue
        every { accessToken } returns token
    }

    /** Una petición que se queda esperando hasta que el test la suelta, y luego falla como sin red. */
    private class PeticionColgada {
        val enVuelo = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val pedidos = CopyOnWriteArrayList<Request>()
        val cliente: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                pedidos += firstArg<Request>()
                llamada
            }
        }
        private val llamada: Call = mockk {
            every { execute() } answers {
                enVuelo.countDown()
                soltar.await(15, TimeUnit.SECONDS)
                throw IOException("sin datos")
            }
        }
    }

    @Test
    fun `un refresco de fondo con la petición colgada no enciende la carga ni el error`() = runBlocking {
        val colgada = PeticionColgada()
        val repo = ProductsRepository(almacen(), colgada.cliente, conCatalogo)

        val trabajo = launch(Dispatchers.Default) { repo.fetchProducts(enSegundoPlano = true) }
        assertTrue("la petición debía quedar en vuelo", colgada.enVuelo.await(15, TimeUnit.SECONDS))

        assertFalse("el botón Actualizar no gira solo", repo.isLoading.value)
        assertNull(repo.error.value)
        assertEquals("el catálogo del caché sigue a la vista", listOf("pa"), repo.products.value.map { it.id })

        colgada.soltar.countDown()
        trabajo.join()
        assertFalse(repo.isLoading.value)
        assertNull("sin red no hay error de pantalla", repo.error.value)
        assertEquals(listOf("pa"), repo.products.value.map { it.id })
        assertEquals("y conserva la hora con que se mostraron sus precios", listOf(cat), repo.products.value.map { it.catalogAt })
    }

    @Test
    fun `la carga que pide el usuario sigue encendiendo la carga mientras espera`() = runBlocking {
        val colgada = PeticionColgada()
        val repo = ProductsRepository(almacen(), colgada.cliente, conCatalogo)

        val trabajo = launch(Dispatchers.Default) { repo.fetchProducts() }
        assertTrue(colgada.enVuelo.await(15, TimeUnit.SECONDS))
        assertTrue("el botón Actualizar gira mientras él espera", repo.isLoading.value)

        colgada.soltar.countDown()
        trabajo.join()
        assertFalse(repo.isLoading.value)
        assertEquals("con la lista ya pintada, sin red tampoco hay error", null, repo.error.value)
    }

    @Test
    fun `sin negocio activo o sin sesion el refresco de fondo no hace nada`() = runBlocking {
        for ((venue, token) in listOf<Pair<String?, String?>>(null to "token", "venue-a" to null)) {
            val cliente = mockk<OkHttpClient>()
            val repo = ProductsRepository(almacen(venue, token), cliente, conCatalogo)

            repo.fetchProducts(enSegundoPlano = true)

            verify(exactly = 0) { cliente.newCall(any()) }
            assertFalse(repo.isLoading.value)
            assertNull(repo.error.value)
        }
    }

    /**
     * `GET /mobile/venues/:id/products` pide `menu:read`: un rol personalizado, o un permiso quitado a media sesión,
     * devuelve 403. Sin esta marca ese 403 abría el modal «No tienes permisos» (o el teclado del PIN de gerente) encima
     * de la venta cada 5 min (`ForbiddenInterceptor`). Misma convención que `InventoryRepository.marcarDeFondo`.
     */
    @Test
    fun `la peticion de fondo lleva la marca de tarea de fondo y la del usuario no`() = runBlocking {
        val colgada = PeticionColgada().also { it.soltar.countDown() }
        val repo = ProductsRepository(almacen(), colgada.cliente, conCatalogo)

        repo.fetchProducts(enSegundoPlano = true)
        repo.fetchProducts()

        assertEquals(2, colgada.pedidos.size)
        assertEquals("1", colgada.pedidos[0].header(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertNull("la carga que pide el usuario conserva el diálogo de permisos", colgada.pedidos[1].header(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals("sigue autenticada", "Bearer token", colgada.pedidos[0].header("Authorization"))
        assertEquals("Bearer token", colgada.pedidos[1].header("Authorization"))
    }

    /**
     * Con la lista VACÍA el refresco de fondo sí es lo único que hay en pantalla: se ve como la carga normal («Cargando
     * productos…») y no como «No hay productos». Con la lista ya pintada sigue invisible (pruebas de arriba).
     */
    @Test
    fun `un refresco de fondo con la lista vacia se ve como carga normal`() = runBlocking {
        val colgada = PeticionColgada()
        val sinCatalogo = mockk<PayloadCache>(relaxed = true) { coEvery { load(any(), any()) } returns null }
        val repo = ProductsRepository(almacen(), colgada.cliente, sinCatalogo)

        val trabajo = launch(Dispatchers.Default) { repo.fetchProducts(enSegundoPlano = true) }
        assertTrue(colgada.enVuelo.await(15, TimeUnit.SECONDS))
        assertTrue("lista vacía: la cuadrícula muestra «Cargando productos…»", repo.isLoading.value)

        colgada.soltar.countDown()
        trabajo.join()
        assertFalse("y se apaga al terminar", repo.isLoading.value)
        assertEquals("sin red y sin lista, el error de siempre", "Error de conexión al cargar productos", repo.error.value)
    }
}
