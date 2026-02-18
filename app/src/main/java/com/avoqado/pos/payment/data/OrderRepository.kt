package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
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
class OrderRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun createOrder(request: CreateOrderRequest): Result<CreateOrderResponse> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        Log.d("📦", "Creating order for venue: $venueId, total: ${request.total}")

        return try {
            val body = json.encodeToString(CreateOrderRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(httpRequest).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val orderResponse = json.decodeFromString<CreateOrderResponse>(responseBody)
                Log.d("📦", "✅ Order created: ${orderResponse.data?.id}")
                Result.success(orderResponse)
            } else {
                Log.e("📦", "❌ Order creation failed: $responseCode - $responseBody")
                Result.failure(Exception("Error al crear orden ($responseCode)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Order creation error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun cancelOrder(orderId: String): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$orderId")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()

            val responseCode = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }
            if (responseCode in 200..299) {
                Log.d("📦", "✅ Order cancelled: $orderId")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al cancelar orden"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
