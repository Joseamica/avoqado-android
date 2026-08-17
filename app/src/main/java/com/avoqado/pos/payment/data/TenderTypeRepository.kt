package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.domain.TenderTypeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catálogo de tipos de pago del negocio para la pantalla "¿cómo pagó el cliente?".
 *
 * 🔴 CACHE-FIRST, y nunca borra una lista buena en un refresh fallido. Es la misma
 * lección que costó un bug real en la impresión offline: al fallar se pisaba la
 * config con una vacía y el local se quedaba sin imprimir. Aquí el equivalente
 * sería que el cajero pierda los tipos del negocio justo cuando se cae el WiFi —
 * y entonces no puede registrar la venta de Uber Eats que acaba de entregar.
 *
 * Una lista ligeramente vieja es infinitamente menos dañina que ninguna: el
 * `revision` que se cachea viaja con el cobro y el server sabe honrarlo.
 */
@Singleton
class TenderTypeRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    /** Última lista buena, por venue. Sobrevive a fallos de red; se pierde al reiniciar. */
    private val cache = mutableMapOf<String, List<TenderTypeOption>>()

    fun cached(venueId: String? = secureStorage.venueId): List<TenderTypeOption> =
        venueId?.let { cache[it] }.orEmpty()

    /**
     * Refresca desde el server. Ante CUALQUIER fallo devuelve lo cacheado —
     * jamás una lista vacía que borre lo que el cajero ya podía usar.
     */
    suspend fun refresh(): List<TenderTypeOption> {
        val venueId = secureStorage.venueId ?: return emptyList()
        val token = secureStorage.accessToken ?: return cached(venueId)

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/tender-types")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            } ?: return cached(venueId)

            val array = JSONObject(body).optJSONArray("tenderTypes") ?: return cached(venueId)
            val parsed = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TenderTypeOption(
                    id = o.getString("id"),
                    revision = o.getInt("revision"),
                    name = o.getString("name"),
                    isSystem = o.optBoolean("isSystem", false),
                    baseMethod = o.optString("baseMethod", "OTHER"),
                    captureTip = o.optBoolean("captureTip", true),
                    posSection = o.optString("posSection", "MORE"),
                    displayOrder = o.optInt("displayOrder", 0),
                )
            }
            cache[venueId] = parsed
            Log.d(TAG, "✅ ${parsed.size} tipos de pago en caché para $venueId")
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ No se pudo refrescar tipos de pago; se conserva la lista en caché", e)
            cached(venueId)
        }
    }

    private companion object {
        const val TAG = "💳 TenderTypes"
    }
}
