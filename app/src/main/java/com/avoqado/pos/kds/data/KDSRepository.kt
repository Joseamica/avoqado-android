package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.kds.domain.CanalReparto
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
                orderId = json.optString("orderId", "").takeIf { it.isNotEmpty() && it != "null" },
                needsAcceptance = json.optBoolean("needsAcceptance", false),
                needsPrint = json.optBoolean("needsPrint", false),
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
                productId = json.optString("productId", "").takeIf { it.isNotEmpty() && it != "null" },
                categoryId = json.optString("categoryId", "").takeIf { it.isNotEmpty() && it != "null" },
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

    // MARK: - Responder a un pedido de delivery

    /**
     * "Sí lo preparo." Sólo hace falta en canales configurados en MANUAL, donde el sistema
     * NO acepta solo y el plazo del proveedor (~11.5 min en Uber) está corriendo.
     */
    suspend fun acceptDeliveryOrder(orderId: String): Result<Unit> =
        responderDelivery(orderId, "accept", null)

    /**
     * "No puedo prepararlo." El SERVIDOR decide si eso significa rechazar (antes de aceptar)
     * o cancelar (después): la cocina sólo dice que no puede, no tiene por qué conocer el
     * protocolo del proveedor para avisar que se acabó la carne.
     */
    suspend fun denyDeliveryOrder(orderId: String, reason: String = "OUT_OF_ITEMS"): Result<Unit> =
        responderDelivery(orderId, "deny", reason)

    /**
     * "Me saturé": frena los pedidos de reparto durante `minutos`.
     *
     * 🔴 NO se encola offline, a propósito. Pausar sólo cuenta si el marketplace se entera,
     * y eso necesita red — igual que el cobro con tarjeta. Un intent encolado le diría al
     * cocinero "listo, ya no entran pedidos" mientras siguen entrando: es la clase de
     * mentira que la regla de offline-first prohíbe explícitamente ("jamás pintes un éxito
     * encolado como algo que ya ocurrió"). Sin red, el error se ve tal cual.
     */
    suspend fun snoozeDelivery(linkId: String, minutos: Int): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val cuerpo = JSONObject().apply { put("minutos", minutos) }
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/delivery/channels/$linkId/snooze")
                .header("Authorization", "Bearer $token")
                .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
                .build()
            ejecutarCanal(request, "snooze")
        } catch (e: Exception) {
            Log.e(TAG, "snoozeDelivery falló", e)
            Result.failure(e)
        }
    }

    /** "Ya nos pusimos al día." Sólo cancela una pausa CON reloj; la del dashboard no. */
    suspend fun reanudarDelivery(linkId: String): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/delivery/channels/$linkId/snooze")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            ejecutarCanal(request, "reanudar")
        } catch (e: Exception) {
            Log.e(TAG, "reanudarDelivery falló", e)
            Result.failure(e)
        }
    }

    /** Los canales de reparto del venue, con su estado y hasta cuándo dura la pausa. */
    suspend fun fetchDeliveryChannels(): Result<List<CanalReparto>> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/delivery/channels")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            // Forma EXPLÍCITA (`response ->`), no `it`: la corta rompe la inferencia de
            // tipos de Kotlin dentro de `use { }`. Es la misma trampa que ya documentaba
            // `responderDelivery` unas líneas abajo.
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }

            if (code !in 200..299) {
                // 403 = este puesto no tiene el permiso, o el venue no tiene el plan. No es
                // un error que valga la pena gritarle a la cocina: simplemente no hay control.
                Log.d(TAG, "fetchDeliveryChannels: $code")
                return Result.success(emptyList())
            }

            val arreglo = JSONObject(body).optJSONArray("channels")
            val canales = (0 until (arreglo?.length() ?: 0)).mapNotNull { i ->
                arreglo?.optJSONObject(i)?.let { o ->
                    CanalReparto(
                        id = o.optString("id"),
                        proveedor = o.optString("provider"),
                        pausado = o.optString("status") == "PAUSED",
                        // `null` con pausado=true es la pausa INDEFINIDA del dashboard: se
                        // pinta como pausado pero SIN cuenta regresiva, porque no se va a
                        // reactivar sola y un reloj que no corre es una mentira.
                        pausadoHasta = o.optString("snoozedUntil").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
            Result.success(canales)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDeliveryChannels falló", e)
            Result.failure(e)
        }
    }

    /**
     * "Yo imprimo esta comanda." Devuelve `true` sólo si este aparato ganó la carrera.
     *
     * Perder es el resultado NORMAL para todas las tablets menos una, así que no es un error:
     * el servidor responde 200 con `claimed:false` y aquí se traduce a `false`, no a una
     * excepción que pintaría una falla cada vez que otra tablet fue más rápida.
     */
    /** El venue actual, para que el despachador de comandas baje su configuración de ruteo. */
    fun venueIdActual(): String? = secureStorage.venueId

    suspend fun reclamarImpresion(kdsId: String, deviceId: String): Boolean {
        val venueId = secureStorage.venueId ?: return false
        val token = secureStorage.accessToken ?: return false
        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders/$kdsId/claim-print")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("deviceId", deviceId).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }
            code in 200..299 && runCatching { JSONObject(body).optBoolean("claimed") }.getOrDefault(false)
        } catch (e: Exception) {
            // Sin red no se reclama nada. NO se imprime a ciegas: dos tablets offline
            // sacarían el mismo papel y nadie se enteraría.
            Log.d(TAG, "reclamarImpresion falló: ${e.message}")
            false
        }
    }

    /** "Ya salió el papel" / "no pude". Ambas son best-effort: no pueden tumbar la cocina. */
    suspend fun marcarImpresion(kdsId: String, deviceId: String, accion: String) {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return
        runCatching {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/kds/orders/$kdsId/$accion")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("deviceId", deviceId).toString().toRequestBody("application/json".toMediaType()))
                .build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.close() } }
        }.onFailure { Log.d(TAG, "marcarImpresion($accion) falló: ${it.message}") }
    }

    private suspend fun ejecutarCanal(request: Request, accion: String): Result<Unit> {
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }
        return if (code in 200..299) {
            Log.d(TAG, "Canal $accion OK")
            Result.success(Unit)
        } else {
            // El servidor explica QUÉ pasa —por ejemplo, que esa pausa la puso el dueño y
            // no se puede deshacer desde aquí—. Se propaga tal cual.
            val msg = runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") } }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: "No se pudo $accion el reparto ($code)"
            Log.e(TAG, "Canal $accion falló: $code - $body")
            Result.failure(Exception(msg))
        }
    }

    private suspend fun responderDelivery(orderId: String, accion: String, reason: String?): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken
            ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val cuerpo = JSONObject().apply { reason?.let { put("reason", it) } }
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$orderId/delivery/$accion")
                .header("Authorization", "Bearer $token")
                .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // Mismo patrón que el resto del archivo: se saca el valor DENTRO del
            // `withContext` y se decide fuera. Envolver el `use { }` entero rompe la
            // inferencia de tipos de Kotlin.
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }

            if (code in 200..299) {
                Log.d(TAG, "Delivery $accion OK: $orderId")
                Result.success(Unit)
            } else {
                // El servidor manda un mensaje pensado para leerse EN LA COCINA — por
                // ejemplo, que el plazo ya venció y no sirve reintentar. Se propaga tal cual
                // en vez de inventar uno genérico que invite a picarle otra vez a un pedido
                // que ya no existe.
                val msg = runCatching { JSONObject(body).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "No se pudo avisarle a la app de delivery ($code)"
                Log.e(TAG, "Delivery $accion falló: $code - $body")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "responderDelivery($accion) falló", e)
            Result.failure(e)
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
