package com.avoqado.pos.printing.routing

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRINT_STATIONS — in-memory cache of the venue's [PrintConfig], mirrors
 * [com.avoqado.pos.tpvsettings.data.TpvSettingsRepository]'s shape.
 *
 * Fail-safe by construction: a venue with no stations configured, or a fetch
 * that fails (offline, 404, server error), both resolve to [PrintConfig]'s
 * all-defaults constructor — `stations = []` — which makes every call site
 * fall back to today's "single kitchen ticket to all KITCHEN printers"
 * behavior. Never throws.
 */
@Singleton
class PrintConfigRepository @Inject constructor(
    private val apiService: ApiService,
    private val payloadCache: com.avoqado.pos.core.data.local.PayloadCache,
) {
    private val _config = MutableStateFlow(PrintConfig())
    val config: StateFlow<PrintConfig> = _config.asStateFlow()

    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun getCurrentConfig(): PrintConfig = _config.value

    /**
     * 🔴 OFFLINE-FIRST (bug encontrado en el smoke de impresión, 2026-07-25):
     * antes, un refresh fallido PISABA la config buena con `PrintConfig()` vacío
     * → cero estaciones → la comanda NO se imprimía. Dos consecuencias reales:
     *   1. Sin internet nunca salía comanda, aunque las impresoras estén en la
     *      MISMA LAN y sean perfectamente alcanzables.
     *   2. Peor: un bache de WiFi a media comida borraba la config y el local
     *      dejaba de imprimir hasta reiniciar la app.
     *
     * Ahora: se espeja a disco al cargar bien, y un fallo NUNCA destruye lo que
     * ya se tenía — se conserva lo vigente, y si no hay nada se hidrata del
     * cache. Sólo se queda vacío si este dispositivo jamás vio la config.
     * Mismo patrón cache-first del Corte A (mesas/productos/menús).
     */
    suspend fun refresh(venueId: String) {
        try {
            val response = apiService.getPrintConfig(venueId)
            _config.value = response.data
            Log.d(TAG, "✅ Print config loaded: ${response.data.stations.size} station(s)")
            runCatching {
                payloadCache.save(
                    com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRINT_CONFIG,
                    venueId,
                    cacheJson.encodeToString(PrintConfig.serializer(), response.data),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Print config sin red (${e.message}) — conservo la vigente / hidrato del cache")
            if (_config.value.stations.isNotEmpty()) return // ya tengo una buena: NO la piso
            hydrateFromCache(venueId)
        }
    }

    private suspend fun hydrateFromCache(venueId: String) {
        val cached = payloadCache.load(
            com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRINT_CONFIG,
            venueId,
        ) ?: run {
            Log.w(TAG, "Sin cache de print config: este dispositivo nunca la ha visto (no habrá ruteo)")
            return
        }
        runCatching {
            val blob = cacheJson.decodeFromString(PrintConfig.serializer(), cached.json)
            _config.value = blob
            Log.d(TAG, "🗂️ Print config hidratada del cache: ${blob.stations.size} estación(es) (hace ${cached.ageMinutes} min)")
        }.onFailure { Log.e(TAG, "❌ Cache de print config corrupto: ${it.message}") }
    }

    companion object {
        private const val TAG = "PrintConfigRepository"
    }
}
