package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TablesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "🪑Tables"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchTables(): Result<List<Table>> = runCatching {
        val venue = secureStorage.venueId ?: error("No venue")
        val url = "${baseUrlProvider()}/dashboard/venues/$venue/tables"
        val req = Request.Builder().url(url).get().build()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code !in 200..299) {
            Log.e(tag, "GET $url -> $code: ${body.take(200)}")
            error("HTTP $code")
        }
        when (val element = json.parseToJsonElement(body)) {
            is JsonArray -> json.decodeFromJsonElement(ListSerializer(Table.serializer()), element)
            is JsonObject -> {
                val arr = element["data"] ?: error("Unexpected tables shape")
                json.decodeFromJsonElement(ListSerializer(Table.serializer()), arr)
            }
            else -> error("Unexpected tables shape")
        }
    }
}
