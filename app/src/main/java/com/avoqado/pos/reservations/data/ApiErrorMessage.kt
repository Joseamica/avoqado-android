package com.avoqado.pos.reservations.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * El server manda los errores de negocio con un `message` humano en español
 * ("Esta reservación requiere al menos 60 minutos de anticipación."). Los
 * diálogos lo muestran VERBATIM — el cuerpo crudo con prefijo "HTTP 422:"
 * leía como pantalla trabada/bug en vez de como regla de negocio.
 */
internal fun apiErrorMessage(code: Int, body: String): String {
    if (code == 404) return "No se encontró el recurso"
    val message = runCatching {
        lenientJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return message?.takeIf { it.isNotBlank() } ?: "HTTP $code: ${body.take(200)}"
}
