package com.avoqado.pos.reservations.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.model.AddToWaitlistRequest
import com.avoqado.pos.reservations.data.model.PromoteWaitlistRequest
import com.avoqado.pos.reservations.data.model.WaitlistEntry
import com.avoqado.pos.reservations.data.model.WaitlistStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WaitlistApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "📝Waitlist"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun base(): String? {
        val v = secureStorage.venueId ?: return null
        return "${baseUrlProvider()}/dashboard/venues/$v/reservations/waitlist"
    }

    suspend fun list(status: WaitlistStatus? = null): Result<List<WaitlistEntry>> = call {
        val url = base() ?: error("No venue")
        val full = if (status != null) "$url?status=${status.name}" else url
        Request.Builder().url(full).get().build()
    }.mapCatching { body ->
        val element = json.parseToJsonElement(body)
        when (element) {
            is JsonArray -> json.decodeFromJsonElement(ListSerializer(WaitlistEntry.serializer()), element)
            is JsonObject -> {
                val arr = element["data"] ?: error("Unexpected waitlist response shape")
                json.decodeFromJsonElement(ListSerializer(WaitlistEntry.serializer()), arr)
            }
            else -> error("Unexpected waitlist response: $body")
        }
    }

    suspend fun add(body: AddToWaitlistRequest): Result<WaitlistEntry> = call {
        val payload = json.encodeToString(AddToWaitlistRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url(base() ?: error("No venue")).post(payload).build()
    }.mapCatching { json.decodeFromString(WaitlistEntry.serializer(), it) }

    suspend fun remove(entryId: String): Result<Unit> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$entryId").delete().build()
    }.map { Unit }

    suspend fun promote(entryId: String, body: PromoteWaitlistRequest): Result<WaitlistEntry> = call {
        val payload = json.encodeToString(PromoteWaitlistRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$entryId/promote").post(payload).build()
    }.mapCatching { json.decodeFromString(WaitlistEntry.serializer(), it) }

    private suspend inline fun call(crossinline buildRequest: () -> Request): Result<String> = runCatching {
        val req = buildRequest()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code in 200..299) {
            body
        } else {
            Log.e(tag, "${req.method} ${req.url} -> $code: ${body.take(300)}")
            error("HTTP $code: ${body.take(200)}")
        }
    }
}
