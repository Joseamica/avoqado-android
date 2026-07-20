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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        private val idExtractorJson = Json { ignoreUnknownKeys = true }
        private val errorParserJson = Json { ignoreUnknownKeys = true }

        fun isQueueableError(e: Throwable): Boolean {
            return e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException
        }

        fun isQueueableHttpCode(code: Int): Boolean {
            return code >= 500
        }

        /**
         * Extracts paymentId from known mobile payment response shapes.
         *
         * Supported examples:
         * - { "data": { "id": "cuid..." } }                 // /mobile/.../fast
         * - { "payment": { "paymentId": "cuid..." } }       // /mobile/.../orders/:id/pay
         * - { "data": { "paymentId": "cuid..." } }          // backward-compatible
         */
        fun extractPaymentIdFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val id = root["data"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("payment")?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["payment"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["payment"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["paymentId"]?.jsonPrimitive?.contentOrNull
                id?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        fun hasProductItems(request: CreateOrderRequest): Boolean {
            return request.items.any { !it.productId.isNullOrBlank() }
        }

        internal fun buildCreateOrderPayload(
            request: CreateOrderRequest,
            staffId: String,
            customerId: String? = null,
            source: String = "AVOQADO_ANDROID",
            orderType: String = "TAKEOUT",
        ): String {
            return buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        request.items.forEach { item ->
                            add(
                                buildJsonObject {
                                    val productId = item.productId?.takeIf { it.isNotBlank() }
                                    if (productId != null) {
                                        put("productId", productId)
                                        // Venta por peso: quantity SIEMPRE 1 y el peso (kg) viaja en
                                        // weightQuantity; el server recalcula el total desde el
                                        // precio/kg. Se omite weightQuantity en líneas normales.
                                        val weightQuantity = item.weightQuantity
                                        if (weightQuantity != null) {
                                            put("quantity", 1)
                                            put("weightQuantity", weightQuantity)
                                        } else {
                                            put("quantity", item.quantity)
                                        }

                                        val modifierIds = item.modifiers
                                            .map { it.modifierId }
                                            .filter { it.isNotBlank() }
                                        if (modifierIds.isNotEmpty()) {
                                            put(
                                                "modifierIds",
                                                buildJsonArray {
                                                    modifierIds.forEach { add(JsonPrimitive(it)) }
                                                },
                                            )
                                        }
                                    } else {
                                        // Custom amount line item (e.g. "Otro importe")
                                        put("name", item.name)
                                        put("quantity", item.quantity)
                                        put("unitPrice", item.unitPrice)
                                    }

                                    item.note
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { put("notes", it) }
                                },
                            )
                        }
                    },
                )
                put("staffId", staffId)
                put("orderType", orderType)
                put("source", source)
                put("subtotal", request.subtotal)
                put("tip", request.tip)
                put("total", request.total)
                if (request.discount > 0) put("discount", request.discount)
                customerId?.takeIf { it.isNotBlank() }?.let { put("customerId", it) }
                request.splitType?.let { put("splitType", it) }
                request.note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
                request.reservationId?.takeIf { it.isNotBlank() }?.let { put("reservationId", it) }
            }.toString()
        }

        private fun extractErrorMessage(responseBody: String): String? {
            return try {
                val root = errorParserJson.parseToJsonElement(responseBody).jsonObject
                root["message"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun createOrder(
        request: CreateOrderRequest,
        staffId: String,
        customerId: String? = null,
        orderType: String = "TAKEOUT",
    ): Result<CreateOrderResponse> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

        if (!hasProductItems(request)) {
            return Result.failure(Exception("No hay productos válidos para crear la orden"))
        }

        Log.d("📦", "Creating order for venue: $venueId, total: ${request.total}")

        return try {
            val payload = buildCreateOrderPayload(
                request = request,
                staffId = staffId,
                customerId = customerId,
                orderType = orderType,
            )
            val body = payload.toRequestBody("application/json".toMediaType())

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
                val message = extractErrorMessage(responseBody) ?: "Error al crear orden ($responseCode)"
                Result.failure(ServerException(responseCode, message))
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
        staffId: String,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    ): Result<String?> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

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
                append("\"source\":\"AVOQADO_ANDROID\",")
                append("\"idempotencyKey\":\"$idempotencyKey\"")
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
                val paymentId = extractPaymentIdFromResponse(body)
                Log.d("💵", "Extracted paymentId: $paymentId")
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
        staffId: String,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    ): Result<String?> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

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
                append("\"source\":\"AVOQADO_ANDROID\",")
                append("\"idempotencyKey\":\"$idempotencyKey\"")
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
                val paymentId = extractPaymentIdFromResponse(body)
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

    // MARK: - Attach customer to completed payment

    suspend fun attachCustomerToPayment(
        paymentId: String?,
        customerId: String,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No payment selected"))
        val normalizedCustomerId = customerId.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No customer selected"))

        return try {
            val bodyJson = buildJsonObject {
                put("customerId", normalizedCustomerId)
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/$normalizedPaymentId/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to payment: $normalizedPaymentId")
                Result.success(Unit)
            } else {
                Log.e("👤", "❌ Attach customer failed ($code): $body")
                val message = extractErrorMessage(body) ?: "Error al agregar cliente ($code)"
                Result.failure(ServerException(code, message))
            }
        } catch (e: Exception) {
            Log.e("👤", "❌ Attach customer error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun attachCustomerToLatestPayment(
        customerId: String,
        amountCents: Int,
        tipCents: Int,
        staffId: String,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedCustomerId = customerId.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No customer selected"))
        if (amountCents <= 0) return Result.failure(Exception("No payment amount"))

        return try {
            val bodyJson = buildJsonObject {
                put("customerId", normalizedCustomerId)
                put("amountCents", amountCents)
                put("tipCents", tipCents)
                if (staffId.isNotBlank()) put("staffId", staffId)
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to latest payment")
                Result.success(Unit)
            } else {
                Log.e("👤", "❌ Attach customer to latest payment failed ($code): $body")
                val message = extractErrorMessage(body) ?: "Error al agregar cliente ($code)"
                Result.failure(ServerException(code, message))
            }
        } catch (e: Exception) {
            Log.e("👤", "❌ Attach customer to latest payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Send Receipt via Email

    suspend fun sendReceiptEmail(
        paymentId: String?,
        email: String,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
        val normalizedReceiptAccessKey = receiptAccessKey?.takeIf { it.isNotBlank() }

        if (normalizedPaymentId == null && normalizedReceiptAccessKey == null) {
            return Result.failure(Exception("No receipt identifier"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("email", email)
                normalizedPaymentId?.let { put("paymentId", it) }
                normalizedReceiptAccessKey?.let { put("receiptAccessKey", it) }
            }.toString()

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

    suspend fun sendReceiptWhatsApp(
        paymentId: String?,
        phone: String,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
        val normalizedReceiptAccessKey = receiptAccessKey?.takeIf { it.isNotBlank() }

        if (normalizedPaymentId == null && normalizedReceiptAccessKey == null) {
            return Result.failure(Exception("No receipt identifier"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("phone", phone)
                normalizedPaymentId?.let { put("paymentId", it) }
                normalizedReceiptAccessKey?.let { put("receiptAccessKey", it) }
            }.toString()

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
