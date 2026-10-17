package com.avoqado.pos.pos.data

import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductsResponse
import com.avoqado.pos.pos.data.model.VariantOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Variantes de producto (spec 2026-10-14 §7), bloque 3: el catálogo gana 4 campos de familia y NINGÚN valor raro puede tumbarlo
 * entero (el `catch` de `ProductsRepository.fetchProducts` lo disfrazaría de «sin red»).
 *
 * Mismo `Json` que `ProductsRepository` (`Json { ignoreUnknownKeys = true }`): probar con otro sería probar otra cosa.
 * El payload es la forma que emite el server (`variantPayload.ts`): las 4 llaves SIEMPRE, `null` explícito en el producto suelto.
 */
class ProductoConFamiliaDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val opciones = """[{"name":"Talla","value":"S","namePosition":0,"valuePosition":0},{"name":"Color","value":"Azul","namePosition":1,"valuePosition":0}]"""

    private val payload = """
{"success":true,"data":[
 {"id":"cprod0000000000000000000a1","name":"Playera · S · Azul","price":199,"active":true,"sku":"PL-S-AZ",
  "productGroupId":"cgrp00000000000000000000g1","productGroupName":"Playera","productGroupImageUrl":"https://x/p.jpg",
  "variantOptions":$opciones},
 {"id":"cprod0000000000000000000a2","name":"Agua","price":20,"active":true,
  "productGroupId":null,"productGroupName":null,"productGroupImageUrl":null,"variantOptions":null},
 {"id":"cprod0000000000000000000a3","name":"Gorra","price":150,"active":true}
]}
""".trimIndent()

    private fun decodificar(texto: String): List<Product> = json.decodeFromString<ProductsResponse>(texto).data

    @Test
    fun `la variante trae los 4 campos de familia y sus 2 opciones en orden`() {
        val variante = decodificar(payload)[0]

        assertEquals("cgrp00000000000000000000g1", variante.productGroupId)
        assertEquals("Playera", variante.productGroupName)
        assertEquals("https://x/p.jpg", variante.productGroupImageUrl)
        assertEquals(
            listOf(VariantOption("Talla", "S", 0, 0), VariantOption("Color", "Azul", 1, 0)),
            variante.variantOptions,
        )
    }

    @Test
    fun `el producto suelto con null explicito decodifica con los 4 campos en null`() {
        val suelto = decodificar(payload)[1]

        assertEquals("Agua", suelto.name)
        assertNull(suelto.productGroupId)
        assertNull(suelto.productGroupName)
        assertNull(suelto.productGroupImageUrl)
        assertNull(suelto.variantOptions)
    }

    @Test
    fun `el producto sin las llaves de un server viejo decodifica con los 4 campos en null`() {
        val viejo = decodificar(payload)[2]

        assertEquals("Gorra", viejo.name)
        assertNull(viejo.productGroupId)
        assertNull(viejo.productGroupName)
        assertNull(viejo.productGroupImageUrl)
        assertNull(viejo.variantOptions)
    }

    @Test
    fun `variantOptions roto en UNA variante no tumba el catalogo y esa variante queda suelta`() {
        val roto = payload.replace(opciones, "\"roto\"")

        val catalogo = decodificar(roto)

        assertEquals(3, catalogo.size)
        assertNull(catalogo[0].variantOptions)
        // El resto de la familia de esa variante sigue intacto: sólo el campo raro cae.
        assertEquals("Playera", catalogo[0].productGroupName)
        assertEquals("PL-S-AZ", catalogo[0].sku)
    }

    @Test
    fun `productGroupName con un objeto no tumba el catalogo y ese campo queda null`() {
        val roto = payload.replace("\"productGroupName\":\"Playera\"", "\"productGroupName\":{}")

        val catalogo = decodificar(roto)

        assertEquals(3, catalogo.size)
        assertNull(catalogo[0].productGroupName)
        assertEquals("cgrp00000000000000000000g1", catalogo[0].productGroupId)
        assertEquals(2, catalogo[0].variantOptions?.size)
    }

    @Test
    fun `productGroupId y productGroupImageUrl con un tipo raro tampoco tumban el catalogo`() {
        val roto = payload
            .replace("\"productGroupId\":\"cgrp00000000000000000000g1\"", "\"productGroupId\":[1]")
            .replace("\"productGroupImageUrl\":\"https://x/p.jpg\"", "\"productGroupImageUrl\":{\"a\":1}")

        val catalogo = decodificar(roto)

        assertEquals(3, catalogo.size)
        assertNull(catalogo[0].productGroupId)
        assertNull(catalogo[0].productGroupImageUrl)
        assertEquals("Playera", catalogo[0].productGroupName)
    }

    @Test
    fun `variantOptions con un elemento vacio da null porque VariantOption no tiene defaults`() {
        val roto = payload.replace(opciones, "[{}]")

        val catalogo = decodificar(roto)

        assertEquals(3, catalogo.size)
        assertNull(catalogo[0].variantOptions)
    }

    @Test
    fun `ida y vuelta por la cache conserva los 4 campos`() {
        val lista = decodificar(payload)

        // Es lo que hace `PayloadCache` en `ProductsRepository` al guardar y al hidratar.
        val guardado = json.encodeToString(ListSerializer(Product.serializer()), lista)
        val vuelta = json.decodeFromString(ListSerializer(Product.serializer()), guardado)

        assertEquals(lista, vuelta)
        assertEquals("Playera", vuelta[0].productGroupName)
        assertEquals(2, vuelta[0].variantOptions?.size)
        assertNull(vuelta[1].productGroupId)
    }

    @Test
    fun `cache vieja sin las llaves decodifica con los campos en null`() {
        // Lo que guardó la 2.24.0, antes del feature.
        val cacheVieja = """[{"id":"cprod0000000000000000000a1","name":"Playera","price":199,"sku":"PL-S-AZ"}]"""

        val lista = json.decodeFromString(ListSerializer(Product.serializer()), cacheVieja)

        assertEquals(1, lista.size)
        assertNull(lista[0].productGroupId)
        assertNull(lista[0].productGroupName)
        assertNull(lista[0].productGroupImageUrl)
        assertNull(lista[0].variantOptions)
    }
}
