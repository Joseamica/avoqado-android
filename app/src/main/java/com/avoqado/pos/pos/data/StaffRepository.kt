package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StaffMember(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val role: String? = null,
    val active: Boolean = true,
) {
    val fullName: String
        get() = listOfNotNull(firstName, lastName)
            .joinToString(" ")
            .trim()
            .ifEmpty { email ?: "Staff" }
}

@Serializable
private data class StaffListResponse(
    val success: Boolean = false,
    val data: List<StaffMember> = emptyList(),
)

@Singleton
class StaffRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun getActiveStaff(): Result<List<StaffMember>> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/staff?active=true")
                .get()
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val decoded = json.decodeFromString<StaffListResponse>(body)
                Result.success(decoded.data.filter { it.active })
            } else {
                Log.e("👥", "Fetch staff failed: $code - $body")
                Result.failure(Exception("Error al cargar staff ($code)"))
            }
        } catch (e: Exception) {
            Log.e("👥", "Fetch staff error: ${e.message}")
            Result.failure(e)
        }
    }
}
