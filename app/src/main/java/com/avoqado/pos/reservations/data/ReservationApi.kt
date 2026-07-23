package com.avoqado.pos.reservations.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.ProductStaffContract
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationAvailabilitySlot
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.ReservationSettingsContract
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.data.model.UpdateReservationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ReservationApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "📅Res"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun base(): String? {
        val v = secureStorage.venueId ?: return null
        return "${baseUrlProvider()}/dashboard/venues/$v/reservations"
    }

    /**
     * Slots REALMENTE reservables del día (el server aplica horario de
     * operación, intervalo, pacing y aviso mínimo). El picker de Crear cita
     * los consume para no ofrecer horas imposibles.
     */
    suspend fun availability(
        date: String,
        durationMin: Int?,
        productId: String? = null,
        staffId: String? = null,
        includeFull: Boolean = false,
        windowSemantics: String? = null,
    ): Result<List<ReservationAvailabilitySlot>> = call {
        val params = buildList {
            add("date=$date")
            durationMin?.let { add("duration=$it") }
            productId?.let {
                add("productId=$it")
                if (windowSemantics == "base") add("productIds=$it")
            }
            staffId?.let { add("staffId=$it") }
            if (includeFull) add("includeFull=true")
            windowSemantics?.let { add("windowSemantics=$it") }
        }.joinToString("&")
        Request.Builder().url("${base() ?: error("No venue")}/availability?$params").get().build()
    }.mapCatching { body ->
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Unexpected availability shape")
        val arr = obj["slots"] as? JsonArray ?: error("Unexpected availability shape")
        json.decodeFromJsonElement(ListSerializer(ReservationAvailabilitySlot.serializer()), arr)
    }

    suspend fun settings(): Result<ReservationSettingsContract> = call {
        Request.Builder().url("${base() ?: error("No venue")}/settings").get().build()
    }.mapCatching { json.decodeFromString(ReservationSettingsContract.serializer(), it) }

    suspend fun productStaff(productId: String): Result<ProductStaffContract> = call {
        Request.Builder().url("${base() ?: error("No venue")}/products/$productId/staff").get().build()
    }.mapCatching { json.decodeFromString(ProductStaffContract.serializer(), it) }

    suspend fun list(filters: ReservationFilters): Result<ReservationListResponse> = call {
        val url = "${base() ?: error("No venue")}?${filters.toQueryString()}"
        Request.Builder().url(url).get().build()
    }.mapCatching { json.decodeFromString(ReservationListResponse.serializer(), it) }

    suspend fun calendar(dateFrom: String, dateTo: String, groupBy: String? = null): Result<List<Reservation>> = call {
        val params = buildList {
            add("dateFrom=$dateFrom"); add("dateTo=$dateTo")
            groupBy?.let { add("groupBy=$it") }
        }.joinToString("&")
        Request.Builder().url("${base() ?: error("No venue")}/calendar?$params").get().build()
    }.mapCatching { body ->
        val element = json.parseToJsonElement(body)
        when (element) {
            is JsonArray -> json.decodeFromJsonElement(ListSerializer(Reservation.serializer()), element)
            is JsonObject -> {
                val arr = element["reservations"] ?: element["data"]
                    ?: error("Unexpected calendar response shape")
                json.decodeFromJsonElement(ListSerializer(Reservation.serializer()), arr)
            }
            else -> error("Unexpected calendar response shape: $body")
        }
    }

    suspend fun get(id: String): Result<Reservation> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$id").get().build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    suspend fun confirm(id: String) = stateTransition(id, "confirm")
    suspend fun checkIn(id: String) = stateTransition(id, "check-in")
    suspend fun complete(id: String) = stateTransition(id, "complete")
    suspend fun noShow(id: String) = stateTransition(id, "no-show")

    suspend fun reschedule(id: String, body: RescheduleRequest): Result<Reservation> = call {
        val payload = json.encodeToString(RescheduleRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$id/reschedule").post(payload).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    suspend fun cancel(id: String, body: CancelReservationRequest): Result<Unit> = call {
        val payload = json.encodeToString(CancelReservationRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$id").delete(payload).build()
    }.map { Unit }

    suspend fun create(body: CreateReservationRequest): Result<Reservation> = call {
        val payload = json.encodeToString(CreateReservationRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url(base() ?: error("No venue")).post(payload).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    suspend fun update(id: String, body: UpdateReservationRequest): Result<Reservation> = call {
        val payload = json.encodeToString(UpdateReservationRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$id").put(payload).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    private suspend fun stateTransition(id: String, action: String): Result<Reservation> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$id/$action").post(ByteArray(0).toRequestBody(jsonMedia)).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    private suspend inline fun call(crossinline buildRequest: () -> Request): Result<String> = runCatching {
        val req = buildRequest()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code in 200..299) {
            body
        } else {
            Log.e(tag, "${req.method} ${req.url} -> $code: ${body.take(300)}")
            throw reservationApiException(code, body)
        }
    }
}

class ReservationApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
    val preview: String? = null,
) : Exception(message)

private fun reservationApiException(status: Int, body: String): ReservationApiException {
    val payload = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val details = payload?.get("details") as? JsonObject
    val previewElement = details?.get("preview")
    val preview = when (previewElement) {
        is kotlinx.serialization.json.JsonPrimitive -> previewElement.contentOrNull
        is JsonObject -> {
            val occupancy = previewElement["occupancy"]?.jsonPrimitive?.intOrNull
            val limit = previewElement["limit"]?.jsonPrimitive?.intOrNull
            if (occupancy != null && limit != null) "$occupancy de $limit" else null
        }
        else -> null
    }
    return ReservationApiException(
        status = status,
        message = apiErrorMessage(status, body),
        code = payload?.get("code")?.jsonPrimitive?.contentOrNull,
        preview = preview,
    )
}
