package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.WasteApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GET /mobile/venues/:venueId/inventory/waste-items` — la ruta y el lector de la respuesta.
 *
 * El contrato se leyó en el CÓDIGO del servidor (fase 1), no en el reporte:
 * `res.json({ items: result.items.map(itemDelCatalogo), total, page, pageSize })`, con
 * `name`, `sku` y `unit` NOT NULL en el esquema y el `unit` de producto rellenado con
 * `COALESCE(p.unit, 'UNIT')`.
 */
class WasteApiTest {

    /** Se pide el tope del servidor (200): menos viajes, y el servidor recorta lo que pase de ahí. */
    @Test
    fun `la ruta pide la pagina con el tope del servidor`() {
        assertEquals(
            "https://api.avoqado.io/api/v1/mobile/venues/v1/inventory/waste-items?page=3&pageSize=200",
            WasteApi.urlDeArticulos("https://api.avoqado.io/api/v1/mobile/venues/v1", page = 3),
        )
    }

    @Test
    fun `lee una pagina valida`() {
        val pagina = WasteApi.parsearPagina(
            """{"items":[{"itemType":"RAW_MATERIAL","itemId":"rm1","name":"Aguacate","sku":"AGU-01","unit":"KILOGRAM"}],
               "total":450,"page":2,"pageSize":200}""",
        )!!
        assertEquals(450, pagina.total)
        assertEquals(2, pagina.page)
        assertEquals(200, pagina.pageSize)
        val item = pagina.items.single()
        assertEquals("RAW_MATERIAL", item.itemType)
        assertEquals("rm1", item.itemId)
        assertEquals("Aguacate", item.name)
        assertEquals("AGU-01", item.sku)
        assertEquals("KILOGRAM", item.unit)
    }

    /**
     * El `sku` sólo se muestra. Si algún día llegara nulo, un artículo sin SKU no puede
     * tumbar la descarga del catálogo entero — sin catálogo, sin red no se busca nada.
     */
    @Test
    fun `un sku nulo o ausente se lee como vacio, no tumba la pagina`() {
        val pagina = WasteApi.parsearPagina(
            """{"items":[{"itemType":"PRODUCT","itemId":"p1","name":"Pan","sku":null,"unit":"UNIT"},
                         {"itemType":"PRODUCT","itemId":"p2","name":"Leche","unit":"UNIT"}],
               "total":2,"page":1,"pageSize":200}""",
        )!!
        assertEquals(listOf("", ""), pagina.items.map { it.sku })
    }

    /**
     * 🔴 Sin `unit` el artículo no se puede declarar: el POST la exige y el servidor la
     * compara. Es una ruptura del contrato, no un dato opcional — la página se rechaza y
     * la descarga aborta SIN escribir, así que el catálogo anterior sigue intacto.
     */
    @Test
    fun `un articulo sin unidad o sin id rechaza la pagina entera`() {
        assertNull(
            WasteApi.parsearPagina(
                """{"items":[{"itemType":"PRODUCT","itemId":"p1","name":"Pan","sku":""}],"total":1,"page":1,"pageSize":200}""",
            ),
        )
        assertNull(
            WasteApi.parsearPagina(
                """{"items":[{"itemType":"PRODUCT","name":"Pan","sku":"","unit":"UNIT"}],"total":1,"page":1,"pageSize":200}""",
            ),
        )
    }

    @Test
    fun `lo que no es la forma del contrato devuelve nulo, no revienta`() {
        assertNull(WasteApi.parsearPagina(""))
        assertNull(WasteApi.parsearPagina("<html>502 Bad Gateway</html>"))
        assertNull(WasteApi.parsearPagina("""{"success":false,"message":"x"}"""))
        assertNull(WasteApi.parsearPagina("""{"items":[],"page":1,"pageSize":200}""")) // sin total
    }

    /** Un campo nuevo del servidor no rompe una app vieja. */
    @Test
    fun `los campos desconocidos se ignoran`() {
        val pagina = WasteApi.parsearPagina(
            """{"items":[{"itemType":"PRODUCT","itemId":"p1","name":"Pan","sku":"","unit":"UNIT","nuevo":1}],
               "total":1,"page":1,"pageSize":200,"otroCampo":true}""",
        )
        assertEquals(1, pagina?.items?.size)
    }
}
