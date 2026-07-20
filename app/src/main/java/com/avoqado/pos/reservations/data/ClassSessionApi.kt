package com.avoqado.pos.reservations.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.model.AddAttendeeRequest
import com.avoqado.pos.reservations.data.model.BulkCreateResult
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.reservations.data.model.ClassSessionAttendee
import com.avoqado.pos.reservations.data.model.CreateClassSessionBulkRequest
import com.avoqado.pos.reservations.data.model.CreateClassSessionRequest
import com.avoqado.pos.reservations.data.model.UpdateClassSessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ClassSessionApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "🎓Class"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun base(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${baseUrlProvider()}/dashboard/venues/$venueId/class-sessions"
    }

    suspend fun list(
        dateFrom: String,
        dateTo: String,
        productId: String? = null,
        status: String? = null,
    ): Result<List<ClassSession>> = call {
        val params = buildList {
            add("dateFrom=$dateFrom")
            add("dateTo=$dateTo")
            productId?.let { add("productId=$it") }
            status?.let { add("status=$it") }
        }.joinToString("&")
        Request.Builder().url("${base() ?: error("No venue")}?$params").get().build()
    }.mapCatching { json.decodeFromString(ListSerializer(ClassSession.serializer()), it) }

    suspend fun get(sessionId: String): Result<ClassSession> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$sessionId").get().build()
    }.mapCatching { json.decodeFromString(ClassSession.serializer(), it) }

    suspend fun create(body: CreateClassSessionRequest): Result<ClassSession> = call {
        val payload = json.encodeToString(CreateClassSessionRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url(base() ?: error("No venue")).post(payload).build()
    }.mapCatching { json.decodeFromString(ClassSession.serializer(), it) }

    suspend fun createBulk(body: CreateClassSessionBulkRequest): Result<BulkCreateResult> = call {
        val payload = json.encodeToString(CreateClassSessionBulkRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/bulk").post(payload).build()
    }.mapCatching { json.decodeFromString(BulkCreateResult.serializer(), it) }

    suspend fun update(sessionId: String, body: UpdateClassSessionRequest): Result<ClassSession> = call {
        val payload = json.encodeToString(UpdateClassSessionRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$sessionId").patch(payload).build()
    }.mapCatching { json.decodeFromString(ClassSession.serializer(), it) }

    suspend fun cancel(sessionId: String): Result<ClassSession> = call {
        Request.Builder()
            .url("${base() ?: error("No venue")}/$sessionId/cancel")
            .post(ByteArray(0).toRequestBody(jsonMedia))
            .build()
    }.mapCatching { json.decodeFromString(ClassSession.serializer(), it) }

    suspend fun addAttendee(sessionId: String, body: AddAttendeeRequest): Result<ClassSessionAttendee> = call {
        val payload = json.encodeToString(AddAttendeeRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$sessionId/attendees").post(payload).build()
    }.mapCatching { json.decodeFromString(ClassSessionAttendee.serializer(), it) }

    suspend fun removeAttendee(sessionId: String, reservationId: String): Result<Unit> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$sessionId/attendees/$reservationId").delete().build()
    }.map { Unit }

    private suspend inline fun call(crossinline buildRequest: () -> Request): Result<String> = runCatching {
        val req = buildRequest()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code in 200..299) {
            body
        } else {
            Log.e(tag, "${req.method} ${req.url} -> $code: ${body.take(300)}")
            when (code) {
                403 -> error("No tienes permiso para esta acción")
                404 -> error("Sesión no encontrada")
                // 409/422 y demás: el server manda el motivo EXACTO en español
                // ("ya hay una clase a esa hora", "requiere N min de
                // anticipación") — mostrarlo gana sobre un texto genérico.
                else -> error(apiErrorMessage(code, body))
            }
        }
    }
}
