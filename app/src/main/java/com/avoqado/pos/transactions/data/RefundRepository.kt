package com.avoqado.pos.transactions.data

// ============================================================================
// TODO [HIGH RISK - DISABLED IN UI]
// ============================================================================
// The unassociated refund flow is currently HIDDEN in the UI because creating
// a refund corrupts backend financial reports. The repository code is kept so
// re-enabling is trivial once the server is fixed.
//
// Server bugs (avoqado-server):
//   1. sales-summary.dashboard.service.ts:150-160 — sums REFUNDED Payment
//      amounts without Math.abs(), inflating gross sales.
//   2. cashCloseout.dashboard.service.ts:66-74 — only counts COMPLETED
//      payments; refunds are skipped, breaking expected-cash math.
//   3. moneygiver-settlement.job.ts:265 — only counts COMPLETED; card
//      refunds are missing from settlement.
//   4. availableBalance.dashboard.service.ts:102-113, 248-261 — only
//      counts COMPLETED cash; available balance is overstated.
//   5. refund.mobile.service.ts:97-117 — creates BOTH a negative Payment AND
//      a CashDrawerEvent PAY_OUT. Today these don't double-count because most
//      queries filter REFUNDED out, but fixing #1-#4 will start double-
//      counting unless one source of truth is chosen.
//
// Also: this repository sends `items` in the request body but the server
// controller never reads them, so inventory is NEVER restored on a refund.
//
// See full audit in TransactionsScreen.kt next to the disabled refund button.
// ============================================================================

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

private const val TAG = "💸 RefundRepo"

class RefundApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

@Serializable
data class RefundResult(
    val refundId: String? = null,
    val message: String? = null,
)

@Serializable
data class RefundItem(
    val productId: String,
    val name: String,
    val quantity: Int,
    val unitPrice: Double,
)

@Serializable
private data class RefundRequest(
    val amount: Double,
    val reason: String,
    val method: String = "CASH",
    val items: List<RefundItem>? = null,
)

@Serializable
private data class RefundResponse(
    val success: Boolean = false,
    val refund: RefundResult? = null,
    val message: String? = null,
)

/** Item-level refund request (new associated-refund endpoint) */
@Serializable
data class AssociatedRefundItem(
    val orderItemId: String,
    val quantity: Int? = null,
)

@Serializable
private data class AssociatedRefundRequest(
    val amount: Int? = null, // cents
    val items: List<AssociatedRefundItem>? = null,
    val restockItemIds: List<String>? = null,
    val reason: String,
    val note: String? = null,
    /**
     * Optional explicit tip portion of the refund, in cents.
     * Null => backend uses proportional split (default for payments with tip).
     * 0    => refund only the sale portion, staff tip stays intact.
     * =amount => refund only the tip (accidental tip charge case).
     */
    val tipRefundCents: Int? = null,
)

@Serializable
private data class AssociatedRefundResponse(
    val success: Boolean = false,
    val data: AssociatedRefundData? = null,
    val message: String? = null,
)

@Serializable
data class AssociatedRefundData(
    val refundId: String,
    val amount: Double,
    val remainingRefundable: Double,
    val status: String,
)

@Singleton
class RefundRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Issue an associated refund against an existing payment.
     * Supports either amount-based (`amountCents`) or item-based (`items`) refunds,
     * with an optional `restockItemIds` list to return inventory.
     */
    suspend fun issueAssociatedRefund(
        paymentId: String,
        reason: String,
        amountCents: Int? = null,
        items: List<AssociatedRefundItem>? = null,
        restockItemIds: List<String>? = null,
        note: String? = null,
        tipRefundCents: Int? = null,
    ): Result<AssociatedRefundData> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue ID"))

        return try {
            val body = json.encodeToString(
                AssociatedRefundRequest.serializer(),
                AssociatedRefundRequest(
                    amount = amountCents,
                    items = items?.takeIf { it.isNotEmpty() },
                    restockItemIds = restockItemIds?.takeIf { it.isNotEmpty() },
                    reason = reason,
                    note = note,
                    tipRefundCents = tipRefundCents,
                ),
            )

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/$paymentId/refund")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val parsed = runCatching {
                    json.decodeFromString<AssociatedRefundResponse>(responseBody)
                }.getOrNull()
                val data = parsed?.data
                if (data != null) {
                    Log.d(TAG, "✅ Associated refund issued: $data")
                    Result.success(data)
                } else {
                    Log.d(TAG, "✅ Refund returned 2xx but no data field; response: $responseBody")
                    Result.failure(Exception("Respuesta inválida del servidor"))
                }
            } else {
                Log.e(TAG, "❌ Associated refund failed: $code - $responseBody")
                val message = runCatching {
                    json.decodeFromString<AssociatedRefundResponse>(responseBody).message
                }.getOrNull() ?: "Error $code"
                Result.failure(RefundApiException(code, message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Associated refund error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Create an unassociated refund (not tied to any existing order).
     * @param amountCents Amount in cents (converted to dollars for the API)
     * @param reason Reason for the refund
     * @param items Optional list of products being returned (for inventory restoration)
     * @return Result wrapping the RefundResult on success
     */
    suspend fun createUnassociatedRefund(
        amountCents: Int,
        reason: String,
        items: List<RefundItem> = emptyList(),
    ): Result<RefundResult> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue ID"))

        return try {
            // Convert cents to dollars for the API
            val amountDollars = amountCents / 100.0

            val requestBody = json.encodeToString(
                RefundRequest.serializer(),
                RefundRequest(
                    amount = amountDollars,
                    reason = reason,
                    method = "CASH",
                    items = items.ifEmpty { null },
                ),
            )

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/refunds")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val result = if (body.isNotEmpty()) {
                    try {
                        json.decodeFromString<RefundResponse>(body)
                    } catch (_: Exception) {
                        RefundResponse(success = true)
                    }
                } else {
                    RefundResponse(success = true)
                }

                Log.d(TAG, "✅ Unassociated refund created: amount=$amountDollars dollars, reason=$reason")
                Result.success(
                    result.refund ?: RefundResult(
                        message = result.message ?: "Reembolso procesado",
                    ),
                )
            } else {
                Log.e(TAG, "❌ Refund failed: $responseCode - $body")
                Result.failure(Exception("Error $responseCode: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Refund error: ${e.message}")
            Result.failure(e)
        }
    }
}
