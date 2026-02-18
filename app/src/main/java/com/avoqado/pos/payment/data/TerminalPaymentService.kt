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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalPaymentService @Inject constructor(
    private val secureStorage: SecureStorage,
    baseClient: OkHttpClient,
) {
    // Terminal payments need extended timeout (120s) since PAX processing takes time
    private val client = baseClient.newBuilder()
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun sendPaymentToTerminal(
        orderId: String,
        amountCents: Int,
        terminalId: String? = null,
    ): TerminalPaymentResult {
        val venueId = secureStorage.venueId ?: return TerminalPaymentResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalPaymentResult.Error("Not authenticated")
        val terminal = terminalId ?: secureStorage.terminalId

        Log.d("💳", "Sending payment to terminal: $terminal, amount: $amountCents")

        return try {
            val requestBody = json.encodeToString(
                TerminalPaymentRequest.serializer(),
                TerminalPaymentRequest(
                    orderId = orderId,
                    amount = amountCents,
                    terminalId = terminal,
                ),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminal/payment")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                Log.d("💳", "✅ Terminal payment sent successfully")
                TerminalPaymentResult.Success
            } else {
                Log.e("💳", "❌ Terminal payment failed: $responseCode - $body")
                TerminalPaymentResult.Error("Error al procesar pago ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e("💳", "❌ Terminal payment error: ${e.message}")
            TerminalPaymentResult.Error("Error de conexión con la terminal")
        }
    }
}

sealed class TerminalPaymentResult {
    data object Success : TerminalPaymentResult()
    data class Error(val message: String) : TerminalPaymentResult()
}

@Serializable
private data class TerminalPaymentRequest(
    val orderId: String,
    val amount: Int,
    val terminalId: String? = null,
)
