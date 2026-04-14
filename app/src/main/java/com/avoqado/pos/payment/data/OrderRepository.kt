package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // MARK: - Exception types

    class ServerException(val code: Int, message: String) : Exception(message)

    companion object {
        fun isQueueableError(e: Throwable): Boolean {
            return e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException
        }

        fun isQueueableHttpCode(code: Int): Boolean {
            return code >= 500
        }
    }

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
                val orderResponse = parseCreateOrderResponse(responseBody)
                Log.d("📦", "✅ Order created: ${orderResponse.data?.id}")
                Result.success(orderResponse)
            } else {
                Log.e("📦", "❌ Order creation failed: $responseCode - $responseBody")
                Result.failure(ServerException(responseCode, "Error al crear orden ($responseCode)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Order creation error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseCreateOrderResponse(responseBody: String): CreateOrderResponse {
        // Backend can return either {"data": {...}} or {"order": {...}}.
        val decoded = json.decodeFromString<CreateOrderResponse>(responseBody)
        if (decoded.data != null) return decoded

        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val orderElement = root["order"] ?: root["data"]
            if (orderElement != null) {
                val orderData = json.decodeFromJsonElement(OrderData.serializer(), orderElement)
                decoded.copy(data = orderData)
            } else {
                decoded
            }
        } catch (_: Exception) {
            decoded
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

    // MARK: - Fast Cash Payment (no products)

    suspend fun recordFastCashPayment(
        amount: Int,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
    ): Result<String?> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val staffId = secureStorage.userId ?: return Result.failure(Exception("No staff"))

        return try {
            val bodyJson = buildString {
                append("{")
                append("\"venueId\":\"$venueId\",")
                append("\"amount\":$amount,")
                append("\"tip\":$tip,")
                append("\"status\":\"COMPLETED\",")
                append("\"method\":\"CASH\",")
                append("\"splitType\":\"$splitType\",")
                append("\"staffId\":\"$staffId\",")
                append("\"source\":\"AVOQADO_ANDROID\"")
                append("}")
            }

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/fast")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("💵", "✅ Fast cash payment recorded: $amount cents, body: ${body.take(200)}")
                // Extract paymentId from response
                val paymentId = try {
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val data = jsonObj["data"]?.jsonObject
                    val id = data?.get("paymentId")?.jsonPrimitive?.contentOrNull
                        ?: data?.get("id")?.jsonPrimitive?.contentOrNull
                        ?: jsonObj["payment"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    Log.d("💵", "Extracted paymentId: $id")
                    id
                } catch (e: Exception) {
                    Log.e("💵", "Failed to parse paymentId: ${e.message}")
                    null
                }
                Result.success(paymentId)
            } else {
                Log.e("💵", "❌ Fast cash payment failed ($code): $body")
                Result.failure(ServerException(code, "Error al registrar pago rápido ($code)"))
            }
        } catch (e: Exception) {
            Log.e("💵", "❌ Fast cash payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Record Cash Payment

    suspend fun recordCashPayment(
        orderId: String,
        amount: Int,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
    ): Result<String?> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val staffId = secureStorage.userId ?: return Result.failure(Exception("No staff"))

        return try {
            val bodyJson = buildString {
                append("{")
                append("\"venueId\":\"$venueId\",")
                append("\"amount\":$amount,")
                append("\"tip\":$tip,")
                append("\"status\":\"COMPLETED\",")
                append("\"method\":\"CASH\",")
                append("\"splitType\":\"$splitType\",")
                append("\"staffId\":\"$staffId\",")
                append("\"source\":\"AVOQADO_ANDROID\"")
                append("}")
            }

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$orderId/pay")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("💵", "✅ Cash payment recorded for order: $orderId")
                // Extract paymentId from response if available
                val paymentId = try {
                    val responseJson = json.parseToJsonElement(body)
                    responseJson.jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                        ?: responseJson.jsonObject["data"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }
                Log.d("💵", "   paymentId: $paymentId")
                Result.success(paymentId)
            } else {
                Log.e("💵", "❌ Cash payment failed ($code): $body")
                Result.failure(ServerException(code, "Error al registrar pago ($code)"))
            }
        } catch (e: Exception) {
            Log.e("💵", "❌ Cash payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Send Receipt via Email

    suspend fun sendReceiptEmail(paymentId: String, email: String): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val bodyJson = """{"paymentId":"$paymentId","email":"$email"}"""

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/receipts/send-email")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📧", "✅ Email receipt sent to $email")
                Result.success(Unit)
            } else {
                Log.e("📧", "❌ Email receipt failed ($code): $body")
                Result.failure(ServerException(code, "Error al enviar recibo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📧", "❌ Email receipt error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Send Receipt via WhatsApp

    suspend fun sendReceiptWhatsApp(paymentId: String, phone: String): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val bodyJson = """{"paymentId":"$paymentId","phone":"$phone"}"""

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/receipts/send-whatsapp")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📨", "✅ WhatsApp receipt sent to $phone")
                Result.success(Unit)
            } else {
                Log.e("📨", "❌ WhatsApp receipt failed ($code): $body")
                Result.failure(ServerException(code, "Error al enviar recibo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📨", "❌ WhatsApp receipt error: ${e.message}")
            Result.failure(e)
        }
    }
}
