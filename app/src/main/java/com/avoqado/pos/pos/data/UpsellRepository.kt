package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.UpsellRule
import com.avoqado.pos.pos.data.model.UpsellRulesPayload
import com.avoqado.pos.pos.data.model.UpsellRulesResponse
import com.avoqado.pos.pos.data.model.UpsellSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Upsell "¿Algo más?" — la tabla de reglas y el registro de momentos.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md (R3, C7)
 *
 * Cache-first como el resto del catálogo: se baja la tabla, se guarda, y al dar
 * Cobrar se resuelve LOCALMENTE. Un apagón de WiFi no apaga la sugerencia.
 */
@Singleton
class UpsellRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val payloadCache: PayloadCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _payload = MutableStateFlow(UpsellRulesPayload())
    val payload: StateFlow<UpsellRulesPayload> = _payload.asStateFlow()

    val rules: List<UpsellRule> get() = _payload.value.rules
    val surfaces: UpsellSurfaces get() = _payload.value.surfaces
    val holdoutPercent: Int get() = _payload.value.holdoutPercent

    suspend fun fetchRules(venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/upsell-rules")
                .header("Authorization", "Bearer $token")
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }

            when {
                code in 200..299 -> {
                    val result = json.decodeFromString<UpsellRulesResponse>(body)
                    _payload.value = result.data
                    payloadCache.save(TYPE, venue, body)
                    Log.d(TAG, "✅ ${result.data.rules.size} reglas de upsell")
                }

                // 🔴 El ÚNICO caso en que se borra el cache. Ver isPlanLock.
                code == 403 && isPlanLock(body) -> {
                    _payload.value = UpsellRulesPayload(rules = emptyList())
                    payloadCache.clear(TYPE, venue)
                    Log.w(TAG, "🔒 El local ya no tiene el plan del upsell — tabla borrada")
                }

                else -> {
                    // Cualquier otro rechazo (permisos del mesero, proxy, 500) NO apaga
                    // la función: se queda lo que ya había.
                    Log.e(TAG, "❌ upsell-rules $code — se conserva lo cacheado")
                    hydrateIfEmpty(venue)
                }
            }
        } catch (e: Exception) {
            // Sin red NO se toca el cache: es justo cuando más se necesita.
            Log.e(TAG, "❌ upsell-rules sin red: ${e.message}")
            hydrateIfEmpty(venue)
        }
    }

    /** Arranque en modo avión: levantar la última tabla buena del disco. */
    suspend fun hydrateIfEmpty(venueId: String? = null) {
        if (_payload.value.rules.isNotEmpty()) return
        val venue = venueId ?: secureStorage.venueId ?: return
        payloadCache.load(TYPE, venue)?.let { cached ->
            runCatching { json.decodeFromString<UpsellRulesResponse>(cached.json) }.getOrNull()?.let {
                _payload.value = it.data
                Log.w(TAG, "⚠️ Upsell sin red — cache (hace ${cached.ageMinutes} min)")
            }
        }
    }

    /**
     * 🔴 Distinguir el 403 del CANDADO DE PLAN de cualquier otro 403.
     *
     * Sólo el candado de plan trae `featureCode` en el cuerpo (lo pone
     * `checkFeatureAccess` en el server). Un 403 de permisos —el mesero no puede
     * ver algo— o el de un proxy corporativo NO lo trae, y confundirlos apagaría
     * el upsell en un local que sí lo paga, sin que nadie entienda por qué.
     */
    private fun isPlanLock(body: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        !obj["featureCode"]?.jsonPrimitive?.content.isNullOrBlank()
    }.getOrDefault(false)

    // ── Momentos de upsell ────────────────────────────────────────────────────

    /**
     * Fuego-y-olvido: el cobro NUNCA espera esta llamada. Si truena, se pierde
     * una métrica; si bloqueara, se pierde una venta.
     */
    suspend fun recordImpression(payloadJson: String, venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return
        runCatching {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/upsell-impressions")
                .header("Authorization", "Bearer $token")
                .post(payloadJson.toRequestBody(JSON_MEDIA))
                .build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
        }.onFailure { Log.w(TAG, "⚠️ impresión no registrada: ${it.message}") }
    }

    /**
     * El momento terminó en venta PAGADA. Sin esto la impresión aporta $0 al
     * reporte: el cliente aceptó pero el cobro nunca cerró.
     */
    suspend fun convertImpression(impressionId: String, orderId: String, venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return
        runCatching {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/upsell-impressions/$impressionId")
                .header("Authorization", "Bearer $token")
                .patch("""{"orderId":"$orderId"}""".toRequestBody(JSON_MEDIA))
                .build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
        }.onFailure { Log.w(TAG, "⚠️ conversión no registrada: ${it.message}") }
    }

    fun clearMemory() {
        _payload.value = UpsellRulesPayload()
    }

    companion object {
        private const val TAG = "🛒UPSELL"
        const val TYPE = "upsell_rules"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
