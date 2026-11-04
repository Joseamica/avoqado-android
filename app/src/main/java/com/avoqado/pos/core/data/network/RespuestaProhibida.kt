package com.avoqado.pos.core.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * ¿Un 403 es de NUESTRA API? Nuestro 403 siempre trae JSON con `error` o `message` (`authorizeRole.middleware.ts`); el de un
 * intermediario (Cloudflare, el portal de un WiFi, un proxy corporativo) no. Una sola regla para el [ForbiddenInterceptor]
 * (que decide si es falta de permiso) y para la recarga automática de D22 (que decide si se detiene).
 */
object RespuestaProhibida {
    private val json = Json { ignoreUnknownKeys = true }

    fun esDenegacionPropia(cuerpo: String): Boolean {
        val objeto = try {
            json.parseToJsonElement(cuerpo).jsonObject
        } catch (_: Exception) {
            return false
        }
        return objeto.tieneValor("error") || objeto.tieneValor("message")
    }

    /** Un texto, como lo lee el interceptor (`ForbiddenResponse.error/message: String?`). */
    private fun JsonObject.tieneValor(campo: String): Boolean = (this[campo] as? JsonPrimitive)?.isString == true
}
