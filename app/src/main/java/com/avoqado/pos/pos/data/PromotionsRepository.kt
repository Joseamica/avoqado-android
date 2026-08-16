package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.PromotionsPayload
import com.avoqado.pos.pos.data.model.PromotionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * En qué situación está el catálogo de ESTE venue.
 *
 * 🔴 Existe para que la UI pueda distinguir **"todavía no sé"** de **"sé que no
 * hay"**. Sin esta señal, el panel enseña "Aún no hay promociones. Créalas desde
 * el dashboard" durante toda la ventana de carga — mintiéndole al cajero de un
 * local que sí las tiene.
 *
 * Vive en el repositorio, y no como una bandera del ViewModel, porque el cambio
 * de venue entra por aquí (`clearCache()` + `refresh()` desde
 * `AuthRepository.switchVenue`) sin pasar NUNCA por el ViewModel. Una bandera
 * local se quedaría en "ya cargué" con el catálogo del local anterior recién
 * borrado.
 */
enum class EstadoCatalogo {
    /** Nadie ha traído nada todavía, o se acaba de limpiar por cambio de local. */
    SIN_CARGAR,
    CARGANDO,

    /** Ya sabemos qué hay — aunque lo que haya sea nada. */
    CARGADO,
}

/**
 * Catálogo de promociones (combos, paquetes, 2x1) — el panel de cobro.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-2-brief.md
 *
 * Calcado de `UpsellRepository`: cache-first, se baja la tabla, se guarda, y
 * el panel la resuelve LOCALMENTE. Un apagón de WiFi no apaga el panel.
 */
@Singleton
class PromotionsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val payloadCache: PayloadCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _promotions = MutableStateFlow(PromotionsPayload())
    val promotions: StateFlow<PromotionsPayload> = _promotions.asStateFlow()

    private val _estado = MutableStateFlow(EstadoCatalogo.SIN_CARGAR)

    /** Ver [EstadoCatalogo]: deja a la UI distinguir "no sé" de "no hay". */
    val estado: StateFlow<EstadoCatalogo> = _estado.asStateFlow()

    suspend fun refresh(venueId: String) {
        // Sin sesión no va a llegar nada NUNCA: se marca cargado (vacío) en vez
        // de dejar la pantalla girando para siempre. Un "Cargando…" eterno es
        // otra forma de mentir, y encima no dice qué hacer.
        val token = secureStorage.accessToken ?: run {
            _estado.value = EstadoCatalogo.CARGADO
            return
        }

        _estado.value = EstadoCatalogo.CARGANDO
        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/promotions")
                .header("Authorization", "Bearer $token")
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }

            when {
                code in 200..299 -> {
                    val result = json.decodeFromString<PromotionsResponse>(body)
                    _promotions.value = result.data
                    payloadCache.save(TYPE, venueId, body)
                    Log.d(TAG, "✅ ${result.data.active.size} activas, ${result.data.upcoming.size} próximas")
                }

                // 🔴 El ÚNICO caso en que se borra el cache. Ver isPlanLock.
                code == 403 && isPlanLock(body) -> {
                    _promotions.value = PromotionsPayload()
                    payloadCache.clear(TYPE, venueId)
                    Log.w(TAG, "🔒 El local ya no tiene el plan de promociones — catálogo borrado")
                }

                else -> {
                    // Cualquier otro rechazo (permisos del mesero, proxy, 500) NO
                    // apaga la función: se queda lo que ya había.
                    Log.e(TAG, "❌ promotions $code — se conserva lo cacheado")
                    hydrateIfEmpty(venueId)
                }
            }
        } catch (e: Exception) {
            // Sin red NO se toca el cache: es justo cuando más se necesita.
            Log.e(TAG, "❌ promotions sin red: ${e.message}")
            hydrateIfEmpty(venueId)
        } finally {
            // Pase lo que pase se sale del estado de carga: ya sabemos lo que hay
            // (aunque sea el cache del disco, o nada).
            _estado.value = EstadoCatalogo.CARGADO
        }
    }

    /** Arranque en modo avión: levantar el último catálogo bueno del disco. */
    private suspend fun hydrateIfEmpty(venueId: String) {
        if (_promotions.value.active.isNotEmpty() || _promotions.value.upcoming.isNotEmpty()) return
        payloadCache.load(TYPE, venueId)?.let { cached ->
            runCatching { json.decodeFromString<PromotionsResponse>(cached.json) }.getOrNull()?.let {
                _promotions.value = it.data
                Log.w(TAG, "⚠️ Promociones sin red — cache (hace ${cached.ageMinutes} min)")
            }
        }
    }

    /**
     * 🔴 Distinguir el 403 del CANDADO DE PLAN de cualquier otro 403.
     *
     * Sólo el candado de plan trae `featureCode` en el cuerpo (lo pone
     * `checkFeatureAccess` en el server). Un 403 de permisos —el mesero no
     * puede ver algo— o el de un proxy corporativo NO lo trae, y confundirlos
     * apagaría las promociones en un local que sí las paga, sin que nadie
     * entienda por qué.
     */
    private fun isPlanLock(body: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        !obj["featureCode"]?.jsonPrimitive?.content.isNullOrBlank()
    }.getOrDefault(false)

    /**
     * No hay de dónde traer (sesión sin venue activo). Se da por cargado —vacío—
     * para que la UI no se quede girando esperando algo que no va a llegar nunca.
     */
    fun marcarSinVenue() {
        _estado.value = EstadoCatalogo.CARGADO
    }

    /**
     * Al cambiar de venue: borra lo que se ve YA, antes de que llegue el refresh
     * nuevo.
     *
     * 🔴 Y vuelve a `SIN_CARGAR`. Sin esta línea el panel enseñaría "Aún no hay
     * promociones. Créalas desde el dashboard" del local NUEVO usando el "ya
     * cargué" del ANTERIOR — con el catálogo recién vaciado y el fetch todavía
     * en camino.
     */
    fun clearCache() {
        _promotions.value = PromotionsPayload()
        _estado.value = EstadoCatalogo.SIN_CARGAR
    }

    companion object {
        private const val TAG = "🎟️PROMOS"
        const val TYPE = "promotions"
    }
}
