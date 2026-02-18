package com.avoqado.pos.timeclock.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.timeclock.data.model.StaffData
import com.avoqado.pos.timeclock.data.model.StaffIdentifyRequest
import com.avoqado.pos.timeclock.data.model.StaffIdentifyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    suspend fun identifyStaff(pin: String): Result<StaffData> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))

        return try {
            val body = json.encodeToString(StaffIdentifyRequest.serializer(), StaffIdentifyRequest(pin))
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
                if (result.data != null) {
                    Result.success(result.data)
                } else {
                    Result.failure(Exception(result.message ?: "PIN no válido"))
                }
            } else {
                Result.failure(Exception("PIN incorrecto"))
            }
        } catch (e: Exception) {
            Log.e("⏰", "Identify staff error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun clockIn(pin: String): Result<Unit> = postTimeAction("clock-in", pin)
    suspend fun clockOut(pin: String): Result<Unit> = postTimeAction("clock-out", pin)
    suspend fun startBreak(pin: String, breakType: String? = null): Result<Unit> =
        postTimeAction("break/start", pin, breakType)
    suspend fun endBreak(pin: String): Result<Unit> = postTimeAction("break/end", pin)

    private suspend fun postTimeAction(
        action: String,
        pin: String,
        breakType: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))

        return try {
            val bodyJson = buildString {
                append("""{"pin":"$pin"""")
                if (breakType != null) append(""","breakType":"$breakType"""")
                append("}")
            }
            val body = bodyJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/time-clock/$action")
                .post(body)
                .build()

            val responseCode = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }
            if (responseCode in 200..299) {
                Log.d("⏰", "✅ Time clock action: $action")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error en acción: $action"))
            }
        } catch (e: Exception) {
            Log.e("⏰", "Time clock error: ${e.message}")
            Result.failure(e)
        }
    }
}
