package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.printing.data.model.ReceiptData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalPaymentService @Inject constructor(
    private val secureStorage: SecureStorage,
    baseClient: OkHttpClient,
) {
    // Terminal payments need extended timeout since the server waits through TPV retries.
    private val client = baseClient.newBuilder()
        .readTimeout(310, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // Track current request for cancellation
    private var currentRequestId: String? = null
    private var currentTerminalId: String? = null

    /**
     * GET /mobile/venues/{venueId}/terminals/online
     * Returns terminals currently connected via Socket.IO.
     */
    suspend fun fetchOnlineTerminals(): TerminalListResult {
        val venueId = secureStorage.venueId ?: return TerminalListResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalListResult.Error("Not authenticated")

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminals/online")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val response = json.decodeFromString(OnlineTerminalsResponse.serializer(), body)
                Log.d("💳", "Found ${response.terminals.size} online terminals")
                TerminalListResult.Success(response.terminals)
            } else {
                Log.e("💳", "Failed to fetch terminals: $responseCode - $body")
                TerminalListResult.Error("Error al buscar terminales ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e("💳", "Error fetching terminals: ${e.message}")
            TerminalListResult.Error("Error de conexión")
        }
    }

    /**
     * POST /mobile/venues/{venueId}/terminal-payment
     * Sends payment to a specific terminal. Server long-polls until terminal succeeds, is cancelled, or times out.
     */
    suspend fun sendPaymentToTerminal(
        terminalId: String,
        amountCents: Int,
        tipCents: Int = 0,
        rating: Int? = null,
        orderId: String? = null,
        processedByStaffId: String? = null,
    ): TerminalPaymentResult {
        val venueId = secureStorage.venueId ?: return TerminalPaymentResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalPaymentResult.Error("Not authenticated")

        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        currentTerminalId = terminalId

        Log.d("💳", "Sending payment to terminal: $terminalId, amount: $amountCents, tip: $tipCents, requestId: $requestId")

        return try {
            val requestBody = json.encodeToString(
                TerminalPaymentRequest.serializer(),
                TerminalPaymentRequest(
                    terminalId = terminalId,
                    amountCents = amountCents,
                    tipCents = tipCents,
                    rating = rating,
                    skipReview = true, // Android already collected tip/rating
                    orderId = orderId,
                    processedByStaffId = processedByStaffId,
                    requestId = requestId,
                ),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminal-payment")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            currentRequestId = null
            currentTerminalId = null

            when (responseCode) {
                in 200..299 -> {
                    val response = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                    Log.d("💳", "✅ Terminal payment success: ${response.status}")
                    TerminalPaymentResult.Success(
                        transactionId = response.transactionId,
                        cardLastFour = response.cardDetails?.lastFour,
                        cardBrand = response.cardDetails?.brand,
                        paymentId = response.paymentId ?: response.transactionId,
                        receiptAccessKey = response.receipt?.receiptAccessKey,
                    )
                }
                404 -> {
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    Log.e("💳", "❌ Terminal not connected (404): $body")
                    TerminalPaymentResult.Error(errorMsg ?: "La terminal no está conectada")
                }
                422 -> {
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    Log.e("💳", "❌ Terminal payment rejected (422): $body")
                    TerminalPaymentResult.Error(errorMsg ?: "La terminal no tiene conexión activa")
                }
                504 -> {
                    // Server-side timeout: the charge may still have completed. Verify the
                    // durable status before surfacing a failure (never blind-fail money).
                    Log.e("💳", "⏳ Terminal payment server timeout (504): recovering via durable status")
                    resolveViaStatus(requestId)
                }
                else -> {
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    Log.e("💳", "❌ Terminal payment failed: $responseCode - $body")
                    TerminalPaymentResult.Error(errorMsg ?: "Error al procesar pago ($responseCode)")
                }
            }
        } catch (e: Exception) {
            currentRequestId = null
            currentTerminalId = null
            // Network drop / client timeout / 5xx = UNKNOWN outcome. Ask the server what
            // actually happened before giving up — prevents a false "failed" (and the double
            // charge a blind retry would cause). A definitive 409 busy is an HTTP response,
            // not an exception, so it never reaches here — it stays in the `else` arm above.
            Log.e("💳", "⚠️ Terminal payment connection error: recovering via durable status: ${e.message}")
            resolveViaStatus(requestId)
        }
    }

    // MARK: - Status Recovery

    /**
     * GET /mobile/venues/{venueId}/terminal-payment/{requestId}
     * Durable status of a charge request — used to recover the real outcome after a dropped
     * long-poll / server timeout / network error (never blind-fail money). Returns null on
     * 404 NOT_FOUND (never persisted → safe to retry) or on any other error (best-effort).
     */
    suspend fun getPaymentStatus(requestId: String): TerminalPaymentStatusDto? {
        val venueId = secureStorage.venueId ?: return null
        val token = secureStorage.accessToken ?: return null

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminal-payment/$requestId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val dto = json.decodeFromString(TerminalPaymentStatusDto.serializer(), body)
                Log.d("💳", "Payment status $requestId → ${dto.status} (inProgress=${dto.inProgress})")
                dto
            } else {
                // 404 NOT_FOUND → never persisted (safe to retry); any other code → best-effort null.
                Log.d("💳", "Payment status $requestId → $responseCode (no durable status)")
                null
            }
        } catch (e: Exception) {
            Log.e("💳", "Error fetching payment status: ${e.message}")
            null
        }
    }

    /**
     * Resolve an unknown outcome (dropped long-poll / server timeout / network error) by polling
     * the durable status up to 3 times. Terminal COMPLETED → success; FAILED/CANCELLED → surface;
     * TIMED_OUT/UNKNOWN, still-in-progress, or NOT_FOUND → "verify on the terminal" (never a silent
     * success — that's how false failures and double charges happen).
     */
    private suspend fun resolveViaStatus(requestId: String): TerminalPaymentResult {
        val unresolved = TerminalPaymentResult.Error(
            "El terminal no respondió a tiempo. Verifica el estado en la terminal.",
        )
        repeat(3) { attempt ->
            // Back off between polls (500ms → 2s) — give the terminal a beat to settle.
            if (attempt > 0) delay(if (attempt == 1) 500L else 2000L)

            val status = getPaymentStatus(requestId)
                ?: return unresolved // 404 NOT_FOUND → never processed → safe

            if (!status.inProgress) {
                return when (status.status) {
                    "COMPLETED" -> TerminalPaymentResult.Success(
                        transactionId = null,
                        cardLastFour = null,
                        cardBrand = null,
                        paymentId = status.paymentId,
                        receiptAccessKey = null,
                    )
                    "FAILED" -> TerminalPaymentResult.Error("El cobro falló")
                    "CANCELLED" -> TerminalPaymentResult.Error("Cobro cancelado")
                    else -> unresolved // TIMED_OUT / UNKNOWN — outcome not confirmed
                }
            }
            // still in progress → poll again
        }
        return unresolved // stayed in progress after 3 polls
    }

    /**
     * POST /mobile/venues/{venueId}/terminal-payment/cancel
     * Cancel a pending terminal payment.
     */
    fun cancelCurrentPayment() {
        val terminalId = currentTerminalId ?: return
        val requestId = currentRequestId
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        // Fire-and-forget cancel
        Thread {
            try {
                val cancelBody = json.encodeToString(
                    CancelPaymentRequest.serializer(),
                    CancelPaymentRequest(
                        terminalId = terminalId,
                        requestId = requestId,
                    ),
                ).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminal-payment/cancel")
                    .header("Authorization", "Bearer $token")
                    .post(cancelBody)
                    .build()

                val response = client.newCall(request).execute()
                Log.d("💳", "Cancel payment response: ${response.code}")
            } catch (e: Exception) {
                Log.e("💳", "Cancel payment error: ${e.message}")
            }
        }.start()

        currentRequestId = null
        currentTerminalId = null
    }

    suspend fun printReceiptOnTerminal(
        terminalId: String,
        receipt: ReceiptData,
        paymentId: String? = null,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val requestId = UUID.randomUUID().toString()

        return try {
            val receiptJson = JSONObject().apply {
                put("orderNumber", receipt.orderNumber)
                put("orderType", receipt.orderType)
                put("items", JSONArray().apply {
                    receipt.items.forEach { item ->
                        put(JSONObject().apply {
                            put("name", item.name)
                            put("quantity", item.quantity)
                            put("unitPrice", item.unitPrice)
                            put("totalPrice", item.totalPrice)
                            item.modifiers?.let { put("modifiers", JSONArray(it)) }
                            item.note?.let { put("note", it) }
                        })
                    }
                })
                put("subtotal", receipt.subtotal)
                put("taxAmount", receipt.taxAmount)
                receipt.tipAmount?.let { put("tipAmount", it) }
                receipt.discountAmount?.let { put("discountAmount", it) }
                put("total", receipt.total)
                receipt.paymentMethod?.let { put("paymentMethod", it) }
                receipt.cardLastFour?.let { put("cardLastFour", it) }
                put("venueName", receipt.venueName)
                receipt.venueAddress?.let { put("venueAddress", it) }
                receipt.venuePhone?.let { put("venuePhone", it) }
                receipt.cashierName?.let { put("cashierName", it) }
                receipt.customerName?.let { put("customerName", it) }
                receipt.transactionId?.let { put("transactionId", it) }
                receipt.cashTendered?.let { put("cashTendered", it) }
                receipt.changeAmount?.let { put("changeAmount", it) }
                paymentId?.takeIf { it.isNotBlank() }?.let { put("paymentId", it) }
                receiptAccessKey?.takeIf { it.isNotBlank() }?.let { put("receiptAccessKey", it) }
            }

            val bodyJson = JSONObject()
                .put("requestId", requestId)
                .put("receipt", receiptJson)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminals/$terminalId/print-receipt")
                .header("Authorization", "Bearer $token")
                .post(bodyJson)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("🖨️", "✅ Receipt printed on TPV $terminalId")
                Result.success(Unit)
            } else {
                Log.e("🖨️", "❌ TPV receipt print failed ($code): $body")
                val message = runCatching {
                    JSONObject(body).optString("errorMessage")
                        .ifBlank { JSONObject(body).optString("message") }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                Result.failure(Exception(message ?: "Error al imprimir en TPV ($code)"))
            }
        } catch (e: Exception) {
            Log.e("🖨️", "❌ TPV receipt print error: ${e.message}")
            Result.failure(e)
        }
    }
}

// MARK: - Results

sealed class TerminalPaymentResult {
    data class Success(
        val transactionId: String? = null,
        val cardLastFour: String? = null,
        val cardBrand: String? = null,
        val paymentId: String? = null,
        val receiptAccessKey: String? = null,
    ) : TerminalPaymentResult()
    data class Error(val message: String) : TerminalPaymentResult()
}

sealed class TerminalListResult {
    data class Success(val terminals: List<OnlineTerminal>) : TerminalListResult()
    data class Error(val message: String) : TerminalListResult()
}

// MARK: - Request/Response models

@Serializable
private data class TerminalPaymentRequest(
    val terminalId: String,
    val amountCents: Int,
    val tipCents: Int = 0,
    val rating: Int? = null,
    val skipReview: Boolean = true,
    val orderId: String? = null,
    val processedByStaffId: String? = null,
    val requestId: String,
)

@Serializable
private data class CancelPaymentRequest(
    val terminalId: String,
    val requestId: String? = null,
)

@Serializable
data class TerminalPaymentResponse(
    val success: Boolean = false,
    val status: String? = null,
    val requestId: String? = null,
    val transactionId: String? = null,
    val paymentId: String? = null,
    val cardDetails: CardDetails? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val receipt: ReceiptInfo? = null,
)

@Serializable
data class CardDetails(
    val lastFour: String? = null,
    val brand: String? = null,
    val entryMode: String? = null,
)

@Serializable
data class ReceiptInfo(
    val receiptUrl: String? = null,
    val receiptAccessKey: String? = null,
)

/**
 * Response of GET /mobile/venues/:venueId/terminal-payment/:requestId — the durable status of a
 * charge request, used to recover the real outcome after a dropped long-poll / timeout / network
 * error. `inProgress` is true for PENDING/SENT/CANCEL_REQUESTED; terminal (final) otherwise.
 */
@Serializable
data class TerminalPaymentStatusDto(
    val status: String = "",
    val inProgress: Boolean = false,
    val paymentId: String? = null,
)

@Serializable
data class OnlineTerminalsResponse(
    val success: Boolean = true,
    val terminals: List<OnlineTerminal> = emptyList(),
)

@Serializable
data class OnlineTerminal(
    val terminalId: String,
    val name: String = "",
    val isOnline: Boolean = true,
    val hasSocket: Boolean = false,
)
