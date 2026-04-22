package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalPaymentService @Inject constructor(
    private val secureStorage: SecureStorage,
    baseClient: OkHttpClient,
) {
    // Terminal payments need extended timeout (120s) since the server long-polls until terminal responds
    private val client = baseClient.newBuilder()
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
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
     * Sends payment to a specific terminal. Server long-polls until terminal responds (60s).
     */
    suspend fun sendPaymentToTerminal(
        terminalId: String,
        amountCents: Int,
        tipCents: Int = 0,
        rating: Int? = null,
        orderId: String? = null,
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
                    Log.e("💳", "❌ Terminal payment timeout: $body")
                    TerminalPaymentResult.Error("El terminal no respondió a tiempo (60s)")
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
            Log.e("💳", "❌ Terminal payment error: ${e.message}")
            TerminalPaymentResult.Error("Error de conexión con la terminal")
        }
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
