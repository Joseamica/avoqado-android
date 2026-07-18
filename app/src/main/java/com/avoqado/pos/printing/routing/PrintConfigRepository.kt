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
) {
    private val _config = MutableStateFlow(PrintConfig())
    val config: StateFlow<PrintConfig> = _config.asStateFlow()

    fun getCurrentConfig(): PrintConfig = _config.value

    suspend fun refresh(venueId: String) {
        try {
            val response = apiService.getPrintConfig(venueId)
            _config.value = response.data
            Log.d(TAG, "✅ Print config loaded: ${response.data.stations.size} station(s)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch print config, falling back to default (no routing): ${e.message}")
            _config.value = PrintConfig()
        }
    }

    companion object {
        private const val TAG = "PrintConfigRepository"
    }
}
