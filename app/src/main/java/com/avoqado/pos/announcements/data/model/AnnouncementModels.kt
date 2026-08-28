package com.avoqado.pos.announcements.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anuncio de plataforma: lo que Avoqado le manda a este negocio.
 *
 * Espejo exacto de `avoqado-ios/Announcements/AnnouncementModels.swift` — mismos nombres
 * de campo y misma semántica, como manda la regla de paridad del repo.
 */
@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val body: String = "",
    val imageUrl: String? = null,
    val priority: String = "NORMAL",
    val actionLabel: String? = null,
    val actionUrl: String? = null,
    val showAsBanner: Boolean = false,
    val showAsModal: Boolean = false,
    val publishedAt: String? = null,
)

/**
 * Un bloque del contenido ampliado — lo que se ve al abrir el anuncio.
 *
 * 🔴 Los bloques se parsean A MANO desde JSON, no con un `sealed` polimórfico de
 * kotlinx.serialization, y es a propósito: un `type` que esta versión de la app no
 * conozca debe IGNORARSE en silencio, nunca tirar la pantalla. Es lo que permite agregar
 * bloques nuevos en el servidor sin desplegar un APK.
 */
sealed interface ContentBlock {
    data class Heading(val text: String) : ContentBlock
    data class Paragraph(val text: String) : ContentBlock
    data class Bullets(val items: List<String>) : ContentBlock
    data class Image(val url: String, val alt: String, val caption: String?) : ContentBlock
    data class Gallery(val images: List<Image>) : ContentBlock
    data class Specs(val rows: List<Pair<String, String>>) : ContentBlock
    data class Callout(val text: String) : ContentBlock
    data class ActionButton(val label: String, val url: String) : ContentBlock
    data object Divider : ContentBlock
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

/**
 * Convierte el JSON de bloques a la lista que la pantalla sabe pintar.
 * Un bloque desconocido, o uno al que le falte lo esencial, simplemente no entra.
 */
fun parseContentBlocks(raw: JsonArray?): List<ContentBlock> {
    if (raw == null) return emptyList()
    return raw.mapNotNull { elemento ->
        val obj = elemento as? JsonObject ?: return@mapNotNull null
        when (obj.str("type")) {
            "heading" -> obj.str("text")?.let { ContentBlock.Heading(it) }
            "paragraph" -> obj.str("text")?.let { ContentBlock.Paragraph(it) }
            "callout" -> obj.str("text")?.let { ContentBlock.Callout(it) }
            "bullets" -> {
                val items = (obj["items"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (items.isEmpty()) null else ContentBlock.Bullets(items)
            }
            "image" -> {
                val url = obj.str("url") ?: return@mapNotNull null
                ContentBlock.Image(url, obj.str("alt").orEmpty(), obj.str("caption"))
            }
            "gallery" -> {
                val imagenes = (obj["images"] as? JsonArray)?.mapNotNull { img ->
                    val o = img as? JsonObject ?: return@mapNotNull null
                    val url = o.str("url") ?: return@mapNotNull null
                    ContentBlock.Image(url, o.str("alt").orEmpty(), o.str("caption"))
                }.orEmpty()
                if (imagenes.isEmpty()) null else ContentBlock.Gallery(imagenes)
            }
            "specs" -> {
                val filas = (obj["rows"] as? JsonArray)?.mapNotNull { fila ->
                    val o = fila as? JsonObject ?: return@mapNotNull null
                    val label = o.str("label") ?: return@mapNotNull null
                    label to o.str("value").orEmpty()
                }.orEmpty()
                if (filas.isEmpty()) null else ContentBlock.Specs(filas)
            }
            "button" -> {
                val label = obj.str("label") ?: return@mapNotNull null
                val url = obj.str("url") ?: return@mapNotNull null
                ContentBlock.ActionButton(label, url)
            }
            "divider" -> ContentBlock.Divider
            // Tipo desconocido: se ignora. NUNCA romper por algo que el servidor agregó
            // después de que este APK salió.
            else -> null
        }
    }
}

/**
 * Respuesta de `GET /mobile/announcements/:id`.
 *
 * 🔴 El anuncio viene ENVUELTO: `data.announcement`, no `data`. Leerlo un nivel arriba
 * reventaba con `MissingFieldException: Fields [id, title] are required` y el detalle
 * se quedaba girando para siempre en la tablet. El servidor sigue la convención de la
 * casa (`{ data: { <entidad> } }`) y el dashboard lee lo mismo: se corrigieron los
 * clientes, no el contrato.
 */
@Serializable
data class AnnouncementDetailResponse(val success: Boolean = false, val data: AnnouncementDetailData? = null)

@Serializable
data class AnnouncementDetailData(val announcement: AnnouncementDetail? = null)

@Serializable
data class AnnouncementDetail(
    val id: String,
    val title: String,
    val body: String = "",
    val imageUrl: String? = null,
    val actionLabel: String? = null,
    val actionUrl: String? = null,
)

/** Extrae los bloques del cuerpo crudo, que viajan como JSON libre. */
fun bloquesDe(rawJson: String, json: kotlinx.serialization.json.Json): List<ContentBlock> = runCatching {
    val root = json.parseToJsonElement(rawJson).jsonObject
    val data = root["data"]?.jsonObject ?: return emptyList()
    val anuncio = data["announcement"]?.jsonObject ?: return emptyList()
    parseContentBlocks(anuncio["contentBlocks"]?.jsonArray)
}.getOrDefault(emptyList())
