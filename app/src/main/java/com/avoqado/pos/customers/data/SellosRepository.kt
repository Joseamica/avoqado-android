package com.avoqado.pos.customers.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Tarjeta de sellos VIGENTE de un cliente. `stampsRequired` es el de la TARJETA, no el de la configuración. */
@Serializable
data class TarjetaDeSellos(
    val stampsEarned: Int,
    val stampsRequired: Int,
    val rewardLabel: String,
    val pendingRewards: Int,
    val stampCardId: String? = null,
    val version: Int = 0,
    val stampsEnabled: Boolean = false,
)

sealed interface ResultadoDeAjuste {
    data class Hecho(val to: Int, val completed: Boolean) : ResultadoDeAjuste
    data class Cambio(val stampsEarned: Int) : ResultadoDeAjuste
    data object Apagado : ResultadoDeAjuste
    data object SinRed : ResultadoDeAjuste
    data class Error(val mensaje: String) : ResultadoDeAjuste
}

/**
 * Sellos a mano. ONLINE-ONLY a propósito: necesita el CAS del servidor, así que nada se encola.
 */
@Singleton
class SellosRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Seam de pruebas: apuntar a un MockWebServer. */
    internal var baseUrl: String = ApiConstants.BASE_URL

    private fun url(customerId: String, tail: String): String? {
        val venueId = secureStorage.venueId ?: return null
        return "$baseUrl/mobile/venues/$venueId/customers/$customerId/$tail"
    }

    private suspend fun ejecutar(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
    }

    /** null = no se pinta la sección (sin plan, o programa de sellos apagado). Falla con [IOException] si no hay red. */
    suspend fun obtener(customerId: String): Result<TarjetaDeSellos?> {
        val u = url(customerId, "stamp-card") ?: return Result.failure(IllegalStateException("Sin venue activo"))
        return try {
            // Consulta que corre sola al abrir la ficha: su 403 de plan no debe sacar el modal de permisos.
            val req = Request.Builder().url(u).header(ForbiddenInterceptor.BACKGROUND_HEADER, "1").get().build()
            val (code, body) = ejecutar(req)
            when {
                code == 403 -> Result.success(null)
                code in 200..299 -> {
                    val t = json.decodeFromString(TarjetaDeSellos.serializer(), body)
                    Result.success(if (t.stampsEnabled) t else null)
                }
                else -> Result.failure(IllegalStateException("HTTP $code"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ajustar(customerId: String, total: Int, motivo: String, vista: TarjetaDeSellos): ResultadoDeAjuste {
        val u = url(customerId, "stamps/adjust") ?: return ResultadoDeAjuste.Error("Sin negocio activo")
        return try {
            val payload = buildJsonObject {
                put("stampsEarned", total)
                put("expectedStampCardId", vista.stampCardId)
                put("expectedStampsEarned", vista.stampsEarned)
                put("expectedVersion", vista.version)
                // F1: la meta que vio el usuario; el servidor da 409 si cambió (sin cartilla, la de la configuración).
                put("expectedStampsRequired", vista.stampsRequired)
                put("reason", motivo.trim())
            }.toString()
            val req = Request.Builder().url(u).post(payload.toRequestBody("application/json".toMediaType())).build()
            val (code, body) = ejecutar(req)
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (code in 200..299 && obj != null) {
                ResultadoDeAjuste.Hecho(
                    to = obj["to"]?.jsonPrimitive?.intOrNull ?: total,
                    completed = obj["completed"]?.jsonPrimitive?.contentOrNull == "true",
                )
            } else {
                errorDe(code, obj)
            }
        } catch (e: IOException) {
            ResultadoDeAjuste.SinRed
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            ResultadoDeAjuste.Error("No se pudieron ajustar los sellos")
        }
    }

    private fun errorDe(code: Int, obj: JsonObject?): ResultadoDeAjuste {
        val c = obj?.get("code")?.jsonPrimitive?.contentOrNull
        return when {
            code == 409 && c == "STAMPS_CHANGED" -> {
                val n = (obj["details"] as? JsonObject)?.get("stampsEarned")?.jsonPrimitive?.intOrNull
                if (n != null) ResultadoDeAjuste.Cambio(n) else ResultadoDeAjuste.Error("Los sellos cambiaron. Revisa y vuelve a intentar")
            }
            code == 409 && c == "STAMPS_DISABLED" -> ResultadoDeAjuste.Apagado
            c == "NO_CHANGE" -> ResultadoDeAjuste.Error("Ese ya es el número de sellos")
            c == "ABOVE_GOAL" -> ResultadoDeAjuste.Error("No puede pasar de la meta de la tarjeta")
            c == "INVALID_REASON" -> ResultadoDeAjuste.Error("El motivo debe tener entre 5 y 500 caracteres")
            else -> ResultadoDeAjuste.Error("No se pudieron ajustar los sellos")
        }
    }
}
