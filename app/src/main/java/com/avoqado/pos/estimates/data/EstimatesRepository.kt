package com.avoqado.pos.estimates.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.estimates.data.model.Estimate
import com.avoqado.pos.estimates.data.model.EstimatesListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "📋 EstimatesRepo"
private val JSON_MEDIA = "application/json".toMediaType()

@Singleton
class EstimatesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - State Flows

    private val _estimates = MutableStateFlow<List<Estimate>>(emptyList())
    val estimates: StateFlow<List<Estimate>> = _estimates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // MARK: - Helpers

    private fun baseUrl(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/mobile/venues/$venueId/estimates"
    }

    private suspend fun executeRequest(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

    // MARK: - Fetch

    suspend fun fetchEstimates() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val wrapper = json.decodeFromString<EstimatesListResponse>(body)
                val list = wrapper.resolved
                _estimates.value = list
                Log.d(TAG, "✅ Loaded ${list.size} estimates")
            } else {
                Log.e(TAG, "❌ fetchEstimates error: HTTP $code")
                _errorMessage.value = "Error al cargar presupuestos"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchEstimates exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar presupuestos"
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Create

    suspend fun createEstimate(payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Estimate created")
                fetchEstimates()
                true
            } else {
                Log.e(TAG, "❌ createEstimate error: HTTP $code")
                _errorMessage.value = "Error al crear presupuesto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createEstimate exception: ${e.message}", e)
            _errorMessage.value = "Error al crear presupuesto"
            false
        }
    }

    // MARK: - Update

    suspend fun updateEstimate(estimateId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/$estimateId")
                .patch(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Estimate $estimateId updated")
                fetchEstimates()
                true
            } else {
                Log.e(TAG, "❌ updateEstimate error: HTTP $code")
                _errorMessage.value = "Error al actualizar presupuesto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateEstimate exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar presupuesto"
            false
        }
    }

    // MARK: - Delete

    suspend fun deleteEstimate(estimateId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/$estimateId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Estimate $estimateId deleted")
                fetchEstimates()
                true
            } else {
                Log.e(TAG, "❌ deleteEstimate error: HTTP $code")
                _errorMessage.value = "Error al eliminar presupuesto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteEstimate exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar presupuesto"
            false
        }
    }
}
