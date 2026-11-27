package com.avoqado.pos.timeclock.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.timeclock.data.model.StaffData
import com.avoqado.pos.timeclock.data.model.StaffIdentifyRequest
import com.avoqado.pos.timeclock.data.model.StaffIdentifyResponse
import com.avoqado.pos.timeclock.data.model.toStaffDataOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeEntryRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    client: OkHttpClient,
) {
    // Los cuerpos del checador contienen PIN y no deben registrarse.
    private val client = client.newBuilder()
        .apply { interceptors().removeAll { com.avoqado.pos.core.di.esRegistroDeRed(it) } }
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pinRegex = Regex("^\\d{4,10}$")

    suspend fun identifyStaff(pin: String): Result<StaffData> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val normalizedPin = pin.trim()

        if (!pinRegex.matches(normalizedPin)) {
            return Result.failure(Exception("Ingresa un PIN válido de 4 a 10 dígitos"))
        }

        return try {
            val body = json.encodeToString(StaffIdentifyRequest.serializer(), StaffIdentifyRequest(normalizedPin))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/time-clock/identify")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val result = json.decodeFromString<StaffIdentifyResponse>(responseBody)
                val staffData = result.toStaffDataOrNull()
                if (staffData != null) {
                    Result.success(staffData)
                } else {
                    Result.failure(Exception(result.message ?: "No se pudo identificar al usuario"))
                }
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody, "PIN incorrecto")))
            }
        } catch (e: Exception) {
            Log.e("⏰", "Identify staff error: ${e.message}")
            Result.failure(Exception(humanNetworkMessage(e)))
        }
    }

    suspend fun clockIn(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("clock-in", pin, note = note).map { }

    /**
     * Marca Entrada con la foto de entrada (JPEG) cuando el negocio la pide: viaja en base64 en la misma petición y el
     * servidor la guarda en su almacén privado. Sin red no hay checada (el PIN lo valida el servidor), así que la foto
     * tampoco se guarda en el aparato: se toma otra vez al reintentar.
     *
     * Devuelve si el servidor de verdad GUARDÓ la foto: si su almacén falla, registra la entrada sin ella, y el acuse no
     * puede decir «con tu foto» (visto en el banco del encargo b0c1a1).
     */
    suspend fun clockInConSelfie(pin: String, note: String?, selfieJpeg: ByteArray?): Result<Boolean> =
        postTimeAction("clock-in", pin, note = note, selfieJpeg = selfieJpeg).map { body ->
            selfieJpeg != null && fotoGuardada(body)
        }
    suspend fun clockOut(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("clock-out", pin, note = note).map { }
    suspend fun startBreak(pin: String, breakType: String? = null): Result<Unit> =
        postTimeAction("break/start", pin, breakType).map { }
    suspend fun endBreak(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("break/end", pin, note = note).map { }

    private suspend fun postTimeAction(
        action: String,
        pin: String,
        breakType: String? = null,
        note: String? = null,
        selfieJpeg: ByteArray? = null,
    ): Result<String> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val normalizedPin = pin.trim()

        if (!pinRegex.matches(normalizedPin)) {
            return Result.failure(Exception("Ingresa un PIN válido de 4 a 10 dígitos"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("pin", normalizedPin)
                if (breakType != null) put("breakType", breakType)
                if (note != null) put("note", note)
                if (selfieJpeg != null) put("checkInPhoto", java.util.Base64.getEncoder().encodeToString(selfieJpeg))
            }.toString()
            val body = bodyJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/time-clock/$action")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299) {
                Log.d("⏰", "✅ Time clock action: $action")
                Result.success(responseBody)
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody, "Error en acción: $action")))
            }
        } catch (e: Exception) {
            Log.e("⏰", "Time clock error: ${e.message}")
            Result.failure(Exception(humanNetworkMessage(e)))
        }
    }
}

/**
 * Traduce el fallo de red al idioma del empleado.
 *
 * Antes se le enseñaba la excepción cruda —"Failed to connect to
 * /10.55.2.158:3000"— a quien sólo quiere marcar su entrada: no entiende qué
 * pasó, no sabe si quedó registrado, y no tiene idea de qué hacer. El reloj es
 * de los pocos flujos que EXIGEN internet (el turno se lleva en el server, no
 * en este equipo), así que decirlo claro es lo único útil.
 */
private fun humanNetworkMessage(e: Exception): String = when (e) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException,
    is java.io.IOException,
    ->
        "Sin conexión. El reloj necesita internet para registrar tu turno; " +
            "revisa la red e inténtalo de nuevo."
    else -> e.message?.takeIf { it.isNotBlank() && !it.contains("Failed to connect", ignoreCase = true) }
        ?: "No se pudo completar la acción. Inténtalo de nuevo."
}

/** `{"timeEntry":{"checkInPhotoUrl":"private/…"}}` → true. Cualquier otra forma (o null) → false. */
internal fun fotoGuardada(body: String): Boolean = runCatching {
    val entrada = timeClockErrorJson.parseToJsonElement(body).jsonObject["timeEntry"]?.jsonObject
    val foto = entrada?.get("checkInPhotoUrl")
    foto is kotlinx.serialization.json.JsonPrimitive && foto.isString && foto.content.isNotBlank()
}.getOrDefault(false)

@Serializable
private data class TimeClockErrorResponse(
    val message: String? = null,
)

private val timeClockErrorJson = Json { ignoreUnknownKeys = true }

private fun parseErrorMessage(responseBody: String, fallback: String): String {
    if (responseBody.isBlank()) return fallback

    return runCatching {
        timeClockErrorJson
            .decodeFromString<TimeClockErrorResponse>(responseBody)
            .message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallback
    }.getOrDefault(fallback)
}
