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
import java.time.Instant
import java.util.Base64

/** `code` y `featureCode` de un cuerpo de error, tal cual. Los decide `clasificarRespuestaDeMerma`. */
data class FalloDeMerma(val code: String?, val featureCode: String?)

/** Lo que el servidor contestó a un `void` (spec §4.3). */
sealed interface Anulacion {
    /**
     * El folio quedó anulado. `porStaffId` y `cuando` son la autoría CANÓNICA del servidor (quién creó la
     * lápida y a qué hora, en milisegundos); `null` si no vino o no se entendió.
     */
    data class Anulada(val porStaffId: String?, val cuando: Long?) : Anulacion

    /** El folio SÍ se había registrado: no se anuló nada. */
    data object YaAplicada : Anulacion
}

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
    fun urlDeArticulos(venueBase: String, page: Int, pageSize: Int = TAMANO_DE_PAGINA): String =
        "$venueBase/inventory/waste-items?page=$page&pageSize=$pageSize"

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

    /** Páginas cortas: el historial se lee de arriba hacia abajo y se pide «Cargar más». */
    const val TAMANO_DE_PAGINA_DEL_HISTORIAL = 30

    /** La primera página sin cursor; las siguientes con el `nextCursor` que mandó el servidor, tal cual. */
    fun urlDeHistorial(venueBase: String, cursor: String?): String =
        "$venueBase/inventory/waste-reports?pageSize=$TAMANO_DE_PAGINA_DEL_HISTORIAL" +
            (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")

    /**
     * `{ scope, items: [...], total, page, pageSize }` → la página, o `null` si no tiene esa forma. El
     * alcance que no se entiende se lee como «sólo lo mío»: nunca se presume la vista del gerente.
     */
    fun parsearHistorial(body: String): PaginaDeHistorial? {
        val raiz = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return runCatching {
            val folios = raiz.getValue("items").jsonArray.map { elemento ->
                val o = elemento.jsonObject
                FolioDeHistorial(
                    id = o.texto("id") ?: return null,
                    name = o.texto("name").orEmpty(),
                    unit = o.texto("unit").orEmpty(),
                    reasonCode = o.texto("reasonCode").orEmpty(),
                    reasonLabel = o.texto("reasonLabel"),
                    declared = o.texto("declaredQuantity") ?: o.texto("deductedQuantity") ?: return null,
                    unrecorded = o.texto("unrecordedQuantity") ?: "0",
                    note = o.texto("note"),
                    createdAt = o.texto("createdAt").orEmpty(),
                    reportedByName = o.texto("reportedByName"),
                )
            }
            PaginaDeHistorial(
                todos = raiz.texto("scope") == "ALL",
                folios = folios,
                total = raiz.getValue("total").jsonPrimitive.int,
                nextCursor = raiz.texto("nextCursor"),
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

    /** `…/mobile/venues/{venueId}/inventory/waste/void`, con el venue DE LA FILA. */
    fun urlDeAnulacion(venueBase: String): String = "$venueBase/inventory/waste/void"

    /** Sólo el folio: la ruta no acepta nada más. */
    fun cuerpoDeAnulacion(folio: String): String = buildJsonObject { put("idempotencyKey", folio) }.toString()

    /**
     * `{ outcome: "VOIDED", voidedByStaffId, voidedAt }` o `{ outcome: "ALREADY_APPLIED", report? }`.
     * `null` si el cuerpo no tiene esa forma: el motor lo trata como un fallo y la fila se queda.
     */
    fun leerAnulacion(body: String): Anulacion? {
        val raiz = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return when (raiz.texto("outcome")) {
            "VOIDED" -> Anulacion.Anulada(
                raiz.texto("voidedByStaffId"),
                raiz.texto("voidedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
            "ALREADY_APPLIED" -> Anulacion.YaAplicada
            else -> null
        }
    }

    /**
     * ¿Este cuerpo confirma que la merma quedó registrada?
     *
     * 🔴 Codex r1 (P1): un 200 de un portal cautivo o de un proxy borraba la ÚNICA copia de la merma. El
     * servidor contesta SIEMPRE con `reportId` —al crear y al recuperar un folio ya aplicado—; sin él, un
     * 2xx no confirma nada y la fila se queda para reintentarse con el mismo folio.
     */
    fun confirmaRegistro(body: String): Boolean =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()?.texto("reportId").isNullOrEmpty().not()

    /**
     * De quién es la credencial que viaja: el `sub` del JWT (= Staff.id, `jwt.service.ts`). Sólo se LEE —
     * el aparato no verifica firmas—; `null` si no hay credencial o no se entiende.
     *
     * 🔴 Codex r1 (P1): el servidor registra la merma a nombre de quien firma el TOKEN, no de quien la
     * capturó. Compararlos exige saber de quién es el token que sale de verdad.
     */
    fun autorDelToken(autorizacion: String?): String? {
        val partes = autorizacion?.trim()?.removePrefix("Bearer ")?.trim()?.split('.') ?: return null
        if (partes.size != 3) return null
        val carga = runCatching { String(Base64.getUrlDecoder().decode(partes[1]), Charsets.UTF_8) }.getOrNull()
            ?: return null
        return runCatching { Json.parseToJsonElement(carga).jsonObject }.getOrNull()?.texto("sub")?.takeIf { it.isNotEmpty() }
    }

    /** Un `null` de JSON y una llave ausente se leen igual: no hay texto. */
    private fun JsonObject.texto(llave: String): String? =
        (this[llave] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
