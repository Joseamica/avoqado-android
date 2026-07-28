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
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pinRegex = Regex("^\\d{4,10}$")

    suspend fun identifyStaff(pin: String): Result<StaffData> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val normalizedPin = pin.trim()

        if (!pinRegex.matches(normalizedPin)) {
            return Result.failure(Exception("Ingresa un PIN valido de 4 a 10 digitos"))
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
            Result.failure(e)
        }
    }

    suspend fun clockIn(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("clock-in", pin, note = note)
    suspend fun clockOut(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("clock-out", pin, note = note)
    suspend fun startBreak(pin: String, breakType: String? = null): Result<Unit> =
        postTimeAction("break/start", pin, breakType)
    suspend fun endBreak(pin: String, note: String? = null): Result<Unit> =
        postTimeAction("break/end", pin, note = note)

    private suspend fun postTimeAction(
        action: String,
        pin: String,
        breakType: String? = null,
        note: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val normalizedPin = pin.trim()

        if (!pinRegex.matches(normalizedPin)) {
            return Result.failure(Exception("Ingresa un PIN valido de 4 a 10 digitos"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("pin", normalizedPin)
                if (breakType != null) put("breakType", breakType)
                if (note != null) put("note", note)
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
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody, "Error en acción: $action")))
            }
        } catch (e: Exception) {
            Log.e("⏰", "Time clock error: ${e.message}")
            Result.failure(e)
        }
    }
}

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
