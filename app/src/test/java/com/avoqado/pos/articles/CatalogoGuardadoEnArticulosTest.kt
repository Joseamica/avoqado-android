package com.avoqado.pos.articles

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
 * IVA por producto, plan 7 (D2, spec §10; r1 #10): abrir Artículos SIN red después de matar la app enseña el catálogo guardado
 * —el mismo que usa el cobro— con el IVA de cada producto. El repositorio es NUEVO (como tras reiniciar el proceso) y el DAO es
 * el almacenamiento durable (Room en producción); encima va el `PayloadCache` real.
 */
class CatalogoGuardadoEnArticulosTest {

    /** Lo que Room tendría en disco, con el mismo contrato que `CachedPayloadDao` para las llaves que usa `PayloadCache`. */
    private fun disco(): CachedPayloadDao {
        val filas = mutableMapOf<String, CachedPayloadEntity>()
        val dao = mockk<CachedPayloadDao>()
        coEvery { dao.get(any()) } answers { filas[firstArg()] }
        coEvery { dao.upsert(any()) } answers { firstArg<CachedPayloadEntity>().let { filas[it.cacheKey] = it } }
        coEvery { dao.delete(any()) } answers { filas.remove(firstArg<String>()); Unit }
        coEvery { dao.deleteOtherVenues(any()) } answers { filas.values.removeAll { it.venueId != firstArg<String>() }; Unit }
        return dao
    }

    private val secureStorage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "venue-1" }
    private val sinRed = mockk<OkHttpClient> {
        every { newCall(any()) } returns mockk<Call> { every { execute() } throws IOException("sin red") }
    }

    private suspend fun guardarCatalogo(disco: CachedPayloadDao, vararg productos: Product) {
        // Lo que guardó `ProductsRepository` la última vez que hubo red (mismo serializador).
        PayloadCache(disco).save(
            PayloadCache.TYPE_PRODUCTS, "venue-1",
            Json.encodeToString(ListSerializer(Product.serializer()), productos.toList()),
        )
    }

    @Test
    fun `P1 sin red tras reiniciar - Articulos muestra el catalogo guardado con su IVA y lo dice`() = runTest {
        val disco = disco()
        guardarCatalogo(
            disco,
            Product(id = "p1", name = "GRN TURISMO 2 KG", priceValue = 520.0, ivaTratamiento = "IVA_0", category = ProductCategory(id = "c1", name = "Café")),
        )

        val repo = ArticlesRepository(secureStorage, sinRed, PayloadCache(disco))
        repo.fetchProducts()

        assertEquals("sin red, Artículos enseña el catálogo guardado", 1, repo.products.value.size)
        val producto = repo.products.value.single()
        assertEquals("GRN TURISMO 2 KG", producto.name)
        assertEquals(520.0, producto.price)
        assertEquals("IVA_0", producto.ivaTratamiento)
        assertTrue("la ficha que se abra de aquí es de sólo lectura", producto.desdeCatalogoGuardado)
        assertTrue(repo.vistaSinConexion.value)
        assertNull("no es un error: es el catálogo guardado", repo.errorMessage.value)

        // Las categorías tampoco bajan sin red: la banda ámbar ya lo dice, sin un aviso rojo encima (como iOS).
        repo.fetchCategories()
        assertNull(repo.errorMessage.value)
        assertEquals("con su categoría aunque las categorías no bajen", "Café", repo.products.value.single().category?.name)
    }

    @Test
    fun `sin red y sin catalogo guardado - el error de siempre`() = runTest {
        val repo = ArticlesRepository(secureStorage, sinRed, PayloadCache(disco()))
        repo.fetchProducts()
        assertTrue(repo.products.value.isEmpty())
        assertFalse(repo.vistaSinConexion.value)
        assertEquals("Error al cargar productos", repo.errorMessage.value)
    }

    @Test
    fun `al volver la red el aviso se quita y la lista nueva ya no es de lectura`() = runTest {
        val disco = disco()
        guardarCatalogo(disco, Product(id = "p1", name = "GRN TURISMO 2 KG", priceValue = 520.0, ivaTratamiento = "IVA_0"))
        var conRed = false
        val cliente = mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                mockk<Call> {
                    if (conRed) {
                        every { execute() } returns Response.Builder()
                            .request(Request.Builder().url("https://api.avoqado.io/test").build())
                            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("""{"success":true,"data":[{"id":"p1","name":"GRN TURISMO 2 KG","price":480.0,"ivaTratamiento":"IVA_0"}]}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        every { execute() } throws IOException("sin red")
                    }
                }
            }
        }
        val repo = ArticlesRepository(secureStorage, cliente, PayloadCache(disco))
        repo.fetchProducts()
        assertTrue(repo.vistaSinConexion.value)

        conRed = true
        repo.fetchProducts()

        assertFalse(repo.vistaSinConexion.value)
        assertEquals(480.0, repo.products.value.single().price)
        assertFalse(repo.products.value.single().desdeCatalogoGuardado)
    }

    @Test
    fun `el catalogo guardado de una version anterior (sin ivaTratamiento) abre igual y dice consultalo`() {
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(Product.serializer()), """[{"id":"p1","name":"Café","price":50.0}]""")
        assertNull(viejo.single().ivaTratamiento)
        assertEquals("IVA: consúltalo en el dashboard", com.avoqado.pos.articles.data.model.IvaEnLectura.titulo(viejo.single().ivaTratamiento))
    }
}
