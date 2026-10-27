package com.avoqado.pos.transactions.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.transactions.presentation.exchange.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class ExchangeApiException(val statusCode: Int, val code: String?, override val message: String,
    val details: JsonElement? = null) : Exception(message)

@Serializable
data class ExchangeSettlementInput(val kind: String, val method: String?, val expectedDifferenceCents: Long,
    val cashDrawerSessionId: String? = null, val terminalId: String? = null, val cashFromNonCash: Boolean? = null)

@Serializable
data class ExchangeCreateBody(val idempotencyKey: String, val originalPaymentId: String,
    val returned: List<ExchangeReturnedInput>, val taken: List<ExchangeTakenInput>, val staffId: String,
    val deviceId: String, val createdAtLocal: String, val settlement: ExchangeSettlementInput)

/** Freeze once; EVEN requires an explicit method:null despite the refund encoder's omitted nulls. */
fun freezeExchange(draft: ExchangeDraft, settlement: ExchangeSettlementInput, staffId: String,
    deviceId: String, createdAtLocal: String): String {
    require(settlement.kind == draft.settlement.kind && settlement.expectedDifferenceCents == draft.settlement.expectedDifferenceCents)
    val body = ExchangeCreateBody(UUID.randomUUID().toString().replace("-", ""), draft.originalPaymentId,
        draft.returned, draft.taken, staffId, deviceId, createdAtLocal, settlement)
    val encoded = jsonReembolsos.encodeToJsonElement(body).jsonObject
    val fields = encoded.getValue("settlement").jsonObject + ("method" to (settlement.method?.let(::JsonPrimitive) ?: JsonNull))
    return JsonObject(encoded + ("settlement" to JsonObject(fields))).toString()
}

@Serializable
data class ExchangeResult(val id: String, val status: String, val originalOrderId: String, val originalOrderNumber: String,
    val newOrderId: String, val newOrderNumber: String, val returnedCents: Long, val takenCents: Long,
    val differenceCents: Long, val settlement: ExchangeResultSettlement, val lines: ExchangeResultLines,
    val amountToChargeCents: Long? = null)
@Serializable
data class ExchangeResultSettlement(val kind: String, val method: String?, val amountCents: Long,
    val sources: List<ExchangeResultSource> = emptyList())
@Serializable
data class ExchangeResultSource(val originalPaymentId: String, val refundPaymentId: String, val method: String, val amountCents: Long)
@Serializable
data class ExchangeResultLines(val returned: List<ExchangeReturnedLine>, val taken: List<ExchangeTakenLine>)
@Serializable
data class ExchangeReturnedLine(val orderItemId: String, val productId: String? = null, val productName: String,
    val quantity: Int, val reason: String, val restock: Boolean, val pieceValuesCents: List<Long>, val valueCents: Long)
@Serializable
data class ExchangeTakenLine(val productId: String, val productName: String, val quantity: Int, val listCents: Long,
    val priceCents: Long, val pieces: List<ExchangeTakenPiece>, val newOrderItemIds: List<String>)
@Serializable
data class ExchangeTakenPiece(val priceCents: Long, val inheritedFromOrderItemId: String? = null)
@Serializable
data class ExchangeServerCalculation(val kind: String, val differenceCents: Long, val returnedCents: Long,
    val takenCents: Long, val lines: ExchangeResultLines)

@Singleton
class ExchangeRepository @Inject constructor(private val secureStorage: SecureStorage, private val client: OkHttpClient) {
    internal var baseUrl = ApiConstants.BASE_URL

    suspend fun create(body: String, venueId: String = currentVenue()): ExchangeResult = post("", body, venueId)
    suspend fun complete(id: String, venueId: String = currentVenue()): ExchangeResult = post("/$id/complete", "{}", venueId)
    suspend fun cancel(id: String, reason: String? = null, venueId: String = currentVenue()): ExchangeResult =
        post("/$id/cancel", buildJsonObject { reason?.let { put("reason", it) } }.toString(), venueId)

    private fun currentVenue(): String = requireNotNull(secureStorage.venueId)

    private suspend fun post(path: String, body: String, venueId: String): ExchangeResult = withContext(Dispatchers.IO) {
        check(secureStorage.venueId == venueId) { TextosCambio.SESION_CAMBIO }
        val request = Request.Builder().url("$baseUrl/mobile/venues/$venueId/exchanges$path")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val root = runCatching { jsonReembolsos.parseToJsonElement(raw).jsonObject }.getOrNull()
            // A refusal only counts as the server's when it has the server's envelope ({message, code}); an HTML page or an
            // empty body from a proxy, captive portal or dead tunnel proves nothing, so the pending must survive (rE1).
            if (!response.isSuccessful && root?.get("message") == null && root?.get("code") == null)
                throw java.io.IOException(TextosCambio.ENVIO_SIN_CONFIRMAR)
            if (!response.isSuccessful) throw ExchangeApiException(response.code,
                (root?.get("code") as? JsonPrimitive)?.contentOrNull,
                (root?.get("message") as? JsonPrimitive)?.contentOrNull ?: TextosCambio.ENVIO_SIN_CONFIRMAR,
                root?.get("details"))
            val result = root?.get("data")?.jsonObject?.get("exchange")
                ?: error(TextosCambio.ENVIO_SIN_CONFIRMAR)
            jsonReembolsos.decodeFromJsonElement<ExchangeResult>(result)
        }
    }
}
