package com.avoqado.pos.pos.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.CreateProductRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de productos es la del negocio ACTUAL. Si el cajero cambia de sucursal mientras una
 * petición del negocio anterior va en camino, esa respuesta llega tarde y NO debe publicarse: si no,
 * Cobrar (y el aviso de merma) muestran productos de otro negocio. `switchVenue` guarda el negocio
 * nuevo ANTES de limpiar y volver a pedir, así que «el negocio actual» es la verdad al llegar.
 * Gemela de iOS: `ProductsRepositoryCambioDeNegocioTests`.
 */
class ProductsRepositoryCambioDeNegocioTest {

    private var negocioActual = "venue-a"
    private val almacen = mockk<SecureStorage> {
        every { venueId } answers { negocioActual }
        every { accessToken } returns "token"
    }
    private val cache = mockk<PayloadCache>(relaxed = true) {
        coEvery { load(any(), any()) } returns null
    }

    private val productosDeA = """{"data":[{"id":"pa","name":"Pan de A","category":{"id":"cat-a","name":"Panes de A"}}]}"""
    private val productoCreadoEnA = """{"data":{"id":"nuevo","name":"Nuevo en A"}}"""

    /** Contesta `body`; si `cambiarA` no es nulo, el negocio cambia MIENTRAS la petición va en camino. */
    private fun cliente(body: String, cambiarA: String? = null): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } answers {
            cambiarA?.let { negocioActual = it }
            Response.Builder()
                .request(Request.Builder().url("https://api.avoqado.io/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return mockk { every { newCall(any()) } returns call }
    }

    @Test
    fun `control - sin cambio de negocio la respuesta se publica`() = runTest {
        val repo = ProductsRepository(almacen, cliente(productosDeA), cache)
        repo.fetchProducts()
        assertEquals(listOf("pa"), repo.products.value.map { it.id })
        assertEquals(listOf("cat-a"), repo.categories.value.map { it.id })
        assertFalse(repo.isLoading.value)
    }

    @Test
    fun `P1 respuesta del negocio anterior que llega despues del cambio no se publica ni se guarda`() = runTest {
        val repo = ProductsRepository(almacen, cliente(productosDeA, cambiarA = "venue-b"), cache)
        repo.fetchProducts()
        assertEquals(emptyList<String>(), repo.products.value.map { it.id })
        assertEquals(emptyList<String>(), repo.categories.value.map { it.id })
        assertNull(repo.error.value)
        coVerify(exactly = 0) { cache.save(any(), any(), any()) }
        // La petición del negocio nuevo (que la app lanza al cambiar) es la dueña del indicador: ésta no lo apaga.
        assertTrue(repo.isLoading.value)
    }

    @Test
    fun `limpiar la lista reinicia el indicador de carga y el error`() = runTest {
        val repo = ProductsRepository(almacen, cliente(productosDeA, cambiarA = "venue-b"), cache)
        repo.fetchProducts()
        repo.clearCache()
        assertFalse(repo.isLoading.value)
        assertNull(repo.error.value)
    }

    @Test
    fun `P1 el cache en disco del negocio anterior tampoco se publica si el cambio llega mientras se lee`() = runTest {
        coEvery { cache.load(PayloadCache.TYPE_PRODUCTS, "venue-a") } answers {
            negocioActual = "venue-b"
            PayloadCache.Cached("""[{"id":"pa","name":"Pan de A"}]""", 0L)
        }
        // La red también contesta con A; lo que se mira es que NADA de A llegue a la lista.
        val repo = ProductsRepository(almacen, cliente(productosDeA), cache)
        repo.fetchProducts()
        assertEquals(emptyList<String>(), repo.products.value.map { it.id })
    }

    @Test
    fun `P2 un producto creado en el negocio anterior no se agrega a la lista del nuevo`() = runTest {
        val repo = ProductsRepository(almacen, cliente(productoCreadoEnA, cambiarA = "venue-b"), cache)
        val creado = repo.createProduct(CreateProductRequest(name = "Nuevo en A", price = 10.0, categoryId = "c1"))
        // Sí se creó (en A): sólo no se agrega a la lista de B.
        assertEquals("nuevo", creado.getOrNull()?.id)
        assertEquals(emptyList<String>(), repo.products.value.map { it.id })
    }
}
