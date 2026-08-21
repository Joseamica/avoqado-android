package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🍳 KDS-Repo"

@Singleton
class KDSRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {

    // MARK: - Fetch active KDS orders

    suspend fun fetchOrders(): Result<List<KDSOrder>> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken
            ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders?status=NEW,PREPARING,READY")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                val orders = (0 until data.length()).mapNotNull { parseOrder(data.getJSONObject(it)) }
                Log.d(TAG, "Fetched ${orders.size} orders from API")
                Result.success(orders)
            } else {
                Log.e(TAG, "Fetch failed: $code - $body")
                Result.failure(Exception("Error al obtener ordenes KDS ($code)"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Create KDS order

    suspend fun createOrder(
        orderNumber: String,
        orderType: String,
        orderId: String?,
        items: List<KDSOrderItemRequest>,
    ): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken
            ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val jsonBody = JSONObject().apply {
                put("orderNumber", orderNumber)
                put("orderType", orderType)
                if (orderId != null) put("orderId", orderId)
                put("items", JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().apply {
                            put("productName", item.productName)
                            put("quantity", item.quantity)
                            if (item.modifiers.isNotEmpty()) {
                                put("modifiers", JSONArray(item.modifiers))
                            }
                            if (!item.notes.isNullOrBlank()) {
                                put("notes", item.notes)
                            }
                        })
                    }
                })
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val code = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }

            if (code in 200..299) {
                Log.d(TAG, "KDS order created: #$orderNumber")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Create failed: $code")
                Result.failure(Exception("Error al crear orden KDS ($code)"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Update status

    suspend fun updateStatus(orderId: String, status: String): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken
            ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val jsonBody = JSONObject().apply { put("status", status) }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders/$orderId/status")
                .header("Authorization", "Bearer $token")
                .put(requestBody)
                .build()

            val code = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }

            if (code in 200..299) {
                Log.d(TAG, "Status updated: $orderId -> $status")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al actualizar estado ($code)"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update status error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Bump order

    suspend fun bumpOrder(orderId: String): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken
            ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders/$orderId/bump")
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            val code = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }

            if (code in 200..299) {
                Log.d(TAG, "Order bumped: $orderId")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al completar orden ($code)"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bump error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Parse helpers

    private fun parseOrder(json: JSONObject): KDSOrder? {
        return try {
            val id = json.getString("id")
            val orderNumber = json.getString("orderNumber")
            val orderType = json.optString("orderType", "DINE_IN")
            val statusStr = json.getString("status")
            val status = try { KDSOrderStatus.valueOf(statusStr) } catch (_: Exception) { KDSOrderStatus.NEW }

            val itemsArray = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArray.length()).mapNotNull { parseItem(itemsArray.getJSONObject(it)) }

            val createdAt = parseIsoDate(json.getString("createdAt"))
            val startedAt = json.optString("startedAt", "").takeIf { it.isNotEmpty() && it != "null" }?.let { parseIsoDate(it) }
            val completedAt = json.optString("completedAt", "").takeIf { it.isNotEmpty() && it != "null" }?.let { parseIsoDate(it) }

            // Map orderType to display name
            val displayType = when (orderType) {
                "DINE_IN" -> "En tienda"
                "TAKEOUT" -> "Para llevar"
                "DELIVERY" -> "Delivery"
                else -> orderType
            }

            KDSOrder(
                id = id,
                orderNumber = orderNumber,
                orderType = displayType,
                items = items,
                createdAt = createdAt,
                status = status,
                startedAt = startedAt,
                completedAt = completedAt,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse order error: ${e.message}")
            null
        }
    }

    private fun parseItem(json: JSONObject): KDSOrderItem? {
        return try {
            KDSOrderItem(
                id = json.getString("id"),
                productName = json.getString("productName"),
                quantity = json.getInt("quantity"),
                modifiers = parseKdsModifiers(json.optJSONArray("modifiers")),
                notes = json.optString("notes", "").takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse item error: ${e.message}")
            null
        }
    }

    private fun parseIsoDate(iso: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}

/**
 * 🔴 Los modificadores llegan como lista de TEXTOS. Esta función además tolera la forma de
 * objeto (`{"name":…,"quantity":…}`) porque las comandas de marketplace se guardaron así
 * hasta el 2026-08-20, y `getString()` sobre un objeto de JSON devuelve su JSON CRUDO — que
 * es literalmente lo que la cocina vio en pantalla en una Sunmi D3 con un pedido de Uber.
 *
 * El server ya normaliza al escribir (`toKdsModifierLabels`), así que esto es la red de
 * abajo: un aparato sigue leyendo filas viejas, y de todos modos el cliente nunca debe
 * pintar JSON crudo a un cocinero. Espejo exacto de iOS `parseModifiers`.
 */
internal fun parseKdsModifiers(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        when (val raw = array.opt(index)) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val name = raw.optString("name").trim()
                if (name.isEmpty()) {
                    null
                } else {
                    val quantity = raw.optInt("quantity", 1)
                    if (quantity > 1) "${quantity}x $name" else name
                }
            }
            else -> raw.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

// MARK: - Request model

data class KDSOrderItemRequest(
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
)
