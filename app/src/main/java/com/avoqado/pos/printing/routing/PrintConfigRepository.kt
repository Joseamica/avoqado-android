package com.avoqado.pos.printing.routing

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Donde sigue la descarga que [refreshConTope] dejó de esperar. */
    private val fondo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getCurrentConfig(): PrintConfig = _config.value

    /**
     * La sucursal del ÚLTIMO refresh pedido. Etapa 3 del KDS (3.5, paridad con la ronda 1 de la Task 7 de iOS): el refresh
     * no se cancela al vencer el tope ([refreshConTope]), así que el de la sucursal ANTERIOR puede contestar después del de
     * la vigente; esa respuesta tardía no pisa la config. Bajo [candado]: los refresh corren en hilos de IO.
     */
    private var ultimoVenuePedido: String? = null
    private val candado = Any()

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
        synchronized(candado) { ultimoVenuePedido = venueId }
        try {
            val response = apiService.getPrintConfig(venueId)
            // La copia en disco es POR SUCURSAL: se guarda aunque la respuesta llegue tarde.
            runCatching {
                payloadCache.save(
                    com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRINT_CONFIG,
                    venueId,
                    cacheJson.encodeToString(PrintConfig.serializer(), response.data),
                )
            }
            val aplicada = synchronized(candado) {
                (ultimoVenuePedido == venueId).also { if (it) _config.value = response.data }
            }
            if (!aplicada) {
                Log.i(TAG, "⏭️ Print config de $venueId llegó tarde: ya se pidió la de otra sucursal — no se aplica")
                return
            }
            Log.d(TAG, "✅ Print config loaded: ${response.data.stations.size} station(s)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Print config sin red (${e.message}) — conservo la vigente / hidrato del cache")
            if (_config.value.stations.isNotEmpty()) return // ya tengo una buena: NO la piso
            if (synchronized(candado) { ultimoVenuePedido != venueId }) return // otra sucursal ya pidió la suya
            hydrateFromCache(venueId)
        }
    }

    /**
     * Etapa 3 del KDS (3.4, H2 de la spec): espera la config fresca a lo más [topeMs]. Si la red tarda más —API muerta
     * con el WiFi vivo, el caso del ICP— regresa y quien llama decide e imprime con la guardada. La descarga NO se
     * cancela: sigue en segundo plano y actualiza [config] cuando llegue (cancelarla dejaría a una red lenta sin config
     * fresca nunca, porque este es el único refresco del mostrador).
     *
     * 🔴 «La guardada» tiene que EXISTIR en memoria: en un arranque en frío [config] está vacía y [refresh] sólo hidrata
     * del disco cuando la red FALLA (con la API colgada, ~30 s). Por eso se hidrata PRIMERO; si no, se decidiría con una
     * config vacía (ticket legado / «SIN ESTACIÓN»). `topeMs = 0` = no espera a la red (precarga de `AppState`).
     */
    suspend fun refreshConTope(venueId: String, topeMs: Long = TOPE_REFRESCO_MS) {
        if (_config.value.stations.isEmpty()) hydrateFromCache(venueId)
        val enCurso = fondo.launch { refresh(venueId) }
        withTimeoutOrNull(topeMs) { enCurso.join() }
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
        const val TOPE_REFRESCO_MS = 1_500L
    }
}
