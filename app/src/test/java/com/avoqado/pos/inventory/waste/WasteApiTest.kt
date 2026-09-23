package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.Anulacion
import com.avoqado.pos.inventory.waste.data.FalloDeMerma
import com.avoqado.pos.inventory.waste.data.WasteApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * Un JWT con la forma del del servidor (`sub` = Staff.id, `jwt.service.ts`): cabecera, carga y una
 * firma cualquiera — el aparato nunca verifica la firma, sólo lee de quién es.
 */
fun tokenDe(staffId: String): String {
    val b64 = Base64.getUrlEncoder().withoutPadding()
    val cabecera = b64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
    val carga = b64.encodeToString("""{"sub":"$staffId","venueId":"venue-centro","role":"CASHIER"}""".toByteArray())
    return "$cabecera.$carga.firma"
}

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

    // MARK: - Leer un error

    /**
     * El desenlace se decide por el `code` del servicio y por el `featureCode` del candado de plan
     * (que NO trae `code`: lo pone un middleware compartido de la plataforma). Se leen tal cual.
     */
    @Test
    fun `un error se lee por su code y su featureCode, sin interpretarlo`() {
        assertEquals(FalloDeMerma("WASTE_VOIDED", null), WasteApi.leerFallo("""{"code":"WASTE_VOIDED","message":"x"}"""))
        assertEquals(
            FalloDeMerma(null, "INVENTORY_TRACKING"),
            WasteApi.leerFallo("""{"error":"Forbidden","featureCode":"INVENTORY_TRACKING"}"""),
        )
    }

    /** Un 502 de un proxy trae HTML, o nada: no revienta, simplemente no hay código. */
    @Test
    fun `un cuerpo que no es JSON no revienta`() {
        assertEquals(FalloDeMerma(null, null), WasteApi.leerFallo("<html>502 Bad Gateway</html>"))
        assertEquals(FalloDeMerma(null, null), WasteApi.leerFallo(""))
    }

    // MARK: - Anular

    /** La ruta `void` lleva SÓLO el folio: el servidor rechaza cualquier campo de más. */
    @Test
    fun `la anulacion lleva solo el folio`() {
        assertEquals("""{"idempotencyKey":"f-1"}""", WasteApi.cuerpoDeAnulacion("f-1"))
        assertEquals("https://x/mobile/venues/v1/inventory/waste/void", WasteApi.urlDeAnulacion("https://x/mobile/venues/v1"))
    }

    /** Los dos desenlaces del contrato, con la autoría canónica; cualquier otra forma no es un desenlace. */
    @Test
    fun `se leen los dos desenlaces de la anulacion y nada mas`() {
        assertEquals(
            Anulacion.Anulada("gerente-1", Instant.parse("2026-09-22T18:00:00.000Z").toEpochMilli()),
            WasteApi.leerAnulacion("""{"outcome":"VOIDED","voidedByStaffId":"gerente-1","voidedAt":"2026-09-22T18:00:00.000Z"}"""),
        )
        assertEquals(Anulacion.YaAplicada, WasteApi.leerAnulacion("""{"outcome":"ALREADY_APPLIED","report":{"declared":"3"}}"""))
        assertNull(WasteApi.leerAnulacion("""{"outcome":"OTRA_COSA"}"""))
        assertNull(WasteApi.leerAnulacion("<html>502</html>"))
    }

    /**
     * 🔴 Codex r1: la lápida se guardaba con la hora del APARATO. La canónica es la del servidor
     * (`voidedAt`); sólo si no viene, o no se entiende, se cae a la del aparato.
     */
    @Test
    fun `una anulacion sin hora o con hora ilegible no inventa una`() {
        assertEquals(Anulacion.Anulada("gerente-1", null), WasteApi.leerAnulacion("""{"outcome":"VOIDED","voidedByStaffId":"gerente-1"}"""))
        assertEquals(
            Anulacion.Anulada("gerente-1", null),
            WasteApi.leerAnulacion("""{"outcome":"VOIDED","voidedByStaffId":"gerente-1","voidedAt":"ayer"}"""),
        )
    }

    // MARK: - Confirmación del registro

    /**
     * 🔴 Codex r1 (P1): un 200 de un portal cautivo o de un proxy borraba la ÚNICA copia de la merma.
     * El servidor contesta SIEMPRE con `reportId` —al crear y al recuperar el folio—; sin él, un 2xx
     * no confirma nada.
     */
    @Test
    fun `solo un cuerpo con reportId confirma el registro`() {
        assertTrue(WasteApi.confirmaRegistro("""{"reportId":"r1","declared":"3","deducted":"3","unrecorded":"0"}"""))
        assertFalse(WasteApi.confirmaRegistro("{}"))
        assertFalse(WasteApi.confirmaRegistro("""{"reportId":""}"""))
        assertFalse(WasteApi.confirmaRegistro("""{"reportId":null}"""))
        assertFalse(WasteApi.confirmaRegistro("<html>Bienvenido al WiFi de la plaza</html>"))
        assertFalse(WasteApi.confirmaRegistro(""))
    }

    // MARK: - De quién es la credencial

    /**
     * 🔴 Codex r1 (P1): la merma sale a nombre de quien firma el TOKEN, no de quien la capturó. Para
     * compararlos hay que saber de quién es la credencial que de verdad viaja: el `sub` del JWT.
     */
    @Test
    fun `el autor de una credencial es el sub de su token`() {
        assertEquals("yo", WasteApi.autorDelToken("Bearer ${tokenDe("yo")}"))
        assertEquals("persona-B", WasteApi.autorDelToken("Bearer ${tokenDe("persona-B")}"))
    }

    @Test
    fun `una credencial ausente o ilegible no tiene autor`() {
        assertNull(WasteApi.autorDelToken(null))
        assertNull(WasteApi.autorDelToken(""))
        assertNull(WasteApi.autorDelToken("Bearer no-es-un-jwt"))
        assertNull(WasteApi.autorDelToken("Bearer a.%%%.c"))
        val sinSub = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"CASHIER"}""".toByteArray())
        assertNull(WasteApi.autorDelToken("Bearer x.$sinSub.y"))
    }
}
