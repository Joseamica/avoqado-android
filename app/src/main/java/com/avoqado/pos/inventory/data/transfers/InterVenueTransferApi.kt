package com.avoqado.pos.inventory.data.transfers

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.apiErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * API de traslados entre sucursales (CEDIS). Espejo del patrón de
 * `reservations/data/ReservationApi.kt` (OkHttp + SecureStorage + apiBaseUrl) y
 * del contrato del dashboard web (`interVenueTransfer.service.ts`):
 *
 * - Envelope SIEMPRE `{ success, data }` — aquí se desenvuelve `data`.
 * - `dispatch` y `receive` llevan header `Idempotency-Key` (UUID), igual que el web.
 * - El server es la autoridad de permisos/feature (INVENTORY_TRACKING +
 *   inventory-transfers:*): un 403/409 se traduce a mensaje en español vía
 *   `apiErrorMessage`.
 */
@Singleton
class InterVenueTransferApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "🚚Traslados"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun base(venueId: String? = secureStorage.venueId): String? {
        val v = venueId ?: return null
        return "${baseUrlProvider()}/dashboard/venues/$v/inventory/inter-venue-transfers"
    }

    suspend fun list(): Result<TransferListPage> = call {
        Request.Builder().url("${base() ?: error("No venue")}?pageSize=100").get().build()
    }.mapCatching { body -> json.decodeFromString(TransferListPage.serializer(), unwrap(body)) }

    suspend fun get(id: String): Result<InterVenueTransferDetail> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$id").get().build()
    }.mapCatching { body -> json.decodeFromString(InterVenueTransferDetail.serializer(), unwrap(body)) }

    suspend fun create(input: CreateTransferInput): Result<InterVenueTransferDetail> = call {
        val payload = json.encodeToString(CreateTransferInput.serializer(), input).toRequestBody(jsonMedia)
        Request.Builder().url(base() ?: error("No venue")).post(payload).build()
    }.mapCatching { body -> json.decodeFromString(InterVenueTransferDetail.serializer(), unwrap(body)) }

    suspend fun approve(id: String): Result<InterVenueTransferDetail> = action(id, "approve", "{}")

    suspend fun reject(id: String, reason: String): Result<InterVenueTransferDetail> =
        action(id, "reject", json.encodeToString(TransferReasonBody.serializer(), TransferReasonBody(reason)))

    suspend fun cancel(id: String, reason: String): Result<InterVenueTransferDetail> =
        action(id, "cancel", json.encodeToString(TransferReasonBody.serializer(), TransferReasonBody(reason)))

    suspend fun dispatch(id: String, body: DispatchTransferBody): Result<InterVenueTransferDetail> =
        action(id, "dispatch", json.encodeToString(DispatchTransferBody.serializer(), body), idempotent = true)

    suspend fun receive(id: String, body: ReceiveTransferBody): Result<InterVenueTransferDetail> =
        action(id, "receive", json.encodeToString(ReceiveTransferBody.serializer(), body), idempotent = true)

    /**
     * Insumos activos del venue ORIGEN para el picker de crear (requiere membresía
     * en ese venue — mismo límite que el dashboard web hoy).
     */
    suspend fun rawMaterials(venueId: String): Result<List<TransferPickerRawMaterial>> = call {
        Request.Builder()
            .url("${baseUrlProvider()}/dashboard/venues/$venueId/inventory/raw-materials?active=true")
            .get()
            .build()
    }.mapCatching { body ->
        json.decodeFromString(ListSerializer(TransferPickerRawMaterial.serializer()), unwrap(body))
    }

    private suspend fun action(
        id: String,
        path: String,
        payload: String,
        idempotent: Boolean = false,
    ): Result<InterVenueTransferDetail> = call {
        val builder = Request.Builder()
            .url("${base() ?: error("No venue")}/$id/$path")
            .post(payload.toRequestBody(jsonMedia))
        if (idempotent) builder.header("Idempotency-Key", UUID.randomUUID().toString())
        builder.build()
    }.mapCatching { body -> json.decodeFromString(InterVenueTransferDetail.serializer(), unwrap(body)) }

    /**
     * Desenvuelve `{ success, data }`; si la respuesta viene plana (por si algún
     * endpoint difiere), la regresa tal cual — mismo criterio tolerante que
     * `ReservationApi.calendar`.
     */
    private fun unwrap(body: String): String {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return body
        val data = (element as? JsonObject)?.get("data") ?: return body
        return data.toString()
    }

    private suspend inline fun call(crossinline buildRequest: () -> Request): Result<String> = runCatching {
        val req = buildRequest()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code in 200..299) {
            body
        } else {
            Log.e(tag, "${req.method} ${req.url} -> $code: ${body.take(300)}")
            error(apiErrorMessage(code, body))
        }
    }
}
