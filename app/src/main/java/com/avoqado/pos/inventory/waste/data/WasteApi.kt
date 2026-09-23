package com.avoqado.pos.inventory.waste.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** `code` y `featureCode` de un cuerpo de error, tal cual. Los decide `clasificarRespuestaDeMerma`. */
data class FalloDeMerma(val code: String?, val featureCode: String?)

/**
 * Las rutas `/mobile/venues/:venueId/inventory/waste*` — la forma de las peticiones y de las
 * respuestas, sin tocar la red. Quien envía es el repositorio (con el `OkHttpClient` que ya
 * lleva el token), así que esto se prueba entero en JVM.
 *
 * El contrato se leyó en el CÓDIGO del servidor (fase 1, `inventoryWaste.mobile.controller.ts`),
 * no sólo en su reporte.
 */
object WasteApi {

    /** El tope del servidor: pedir más no trae más, sólo lo recorta. */
    const val TAMANO_DE_PAGINA = 200

    /** @param venueBase `…/api/v1/mobile/venues/{venueId}`, la misma base del resto del inventario. */
    fun urlDeArticulos(venueBase: String, page: Int): String =
        "$venueBase/inventory/waste-items?page=$page&pageSize=$TAMANO_DE_PAGINA"

    /**
     * `{ items: [{ itemType, itemId, name, sku, unit }], total, page, pageSize }` → la página, o
     * `null` si la respuesta no tiene esa forma.
     *
     * 🔴 Un `null` aborta la descarga SIN escribir, así que el catálogo anterior sigue intacto.
     * Por eso sólo se rechaza lo que de verdad rompe el contrato: faltar `itemType`, `itemId`,
     * `name` o `unit` (sin unidad el artículo no se puede declarar: el POST la exige). El `sku`
     * sólo se muestra, y un artículo sin SKU no puede dejar a la sucursal sin catálogo.
     */
    fun parsearPagina(body: String): PaginaDeCatalogo? {
        val raiz = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return runCatching {
            val items = raiz.getValue("items").jsonArray.map { elemento ->
                val o = elemento.jsonObject
                WasteCatalogItem(
                    itemType = o.texto("itemType") ?: return null,
                    itemId = o.texto("itemId") ?: return null,
                    name = o.texto("name") ?: return null,
                    sku = o.texto("sku").orEmpty(),
                    unit = o.texto("unit") ?: return null,
                )
            }
            PaginaDeCatalogo(
                items = items,
                total = raiz.getValue("total").jsonPrimitive.int,
                page = raiz.getValue("page").jsonPrimitive.int,
                pageSize = raiz.getValue("pageSize").jsonPrimitive.int,
            )
        }.getOrNull()
    }

    /** `…/mobile/venues/{venueId}/inventory/waste`, con el venue DE LA FILA (Review Focus 3). */
    fun urlDeMerma(venueBase: String): String = "$venueBase/inventory/waste"

    /**
     * El cuerpo EXACTO del contrato. 🔴 El servidor es estricto: «ningún campo más, ni siquiera en
     * null». La nota ausente no viaja como `null`: la llave no está. La cantidad viaja como TEXTO
     * (ya normalizada), que es lo que el contrato recomienda.
     */
    fun cuerpoDeMerma(fila: PendingWasteEntity): String = buildJsonObject {
        put("itemType", fila.itemType)
        put("itemId", fila.itemId)
        put("quantity", fila.quantity)
        put("unit", fila.unit)
        put("reasonCode", fila.reasonCode)
        fila.note?.let { put("note", it) }
        put("idempotencyKey", fila.idempotencyKey)
        put("clientOccurredAt", fila.clientOccurredAt)
    }.toString()

    /**
     * `code` y `featureCode` de un cuerpo de error, sin interpretarlos. Un 502 de un proxy trae HTML
     * o nada: entonces no hay código, y el HTTP decide solo.
     */
    fun leerFallo(body: String): FalloDeMerma {
        val raiz = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return FalloDeMerma(null, null)
        return FalloDeMerma(raiz.texto("code"), raiz.texto("featureCode"))
    }

    /** Un `null` de JSON y una llave ausente se leen igual: no hay texto. */
    private fun JsonObject.texto(llave: String): String? =
        (this[llave] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
