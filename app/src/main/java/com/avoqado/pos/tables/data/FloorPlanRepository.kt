package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "FloorPlanRepository"

/**
 * Plano en el POS (spec docs/superpowers/specs/2026-10-09-plano-de-mesas-pos-design.md §4.1): áreas + elementos de
 * `GET /mobile/venues/{venueId}/floor-plan`, guardados en el aparato (`PayloadCache.TYPE_FLOOR_PLAN`).
 * Reglas (las de `PayloadCache`): se guarda en cada éxito ANTES de pintar; un fallo de red nunca borra lo guardado; se
 * hidrata al abrir. Espejo: avoqado-ios `Tables/FloorPlanRepository.swift`.
 */
@Singleton
class FloorPlanRepository @Inject constructor(
    private val apiService: ApiService,
    private val payloadCache: PayloadCache,
) {
    private val cacheJson = Json { ignoreUnknownKeys = true }

    private val _plan = MutableStateFlow<FloorPlanPayload?>(null)
    val plan: StateFlow<FloorPlanPayload?> = _plan.asStateFlow()

    /** La sucursal cuyo plano se pinta: el plano de otra (en memoria o en una respuesta tardía) nunca se pinta. */
    @Volatile
    private var venueEnPantalla: String? = null

    val floorPlanVersion: String?
        get() = _plan.value?.floorPlanVersion?.takeIf { it.isNotEmpty() }

    private fun mirarA(venueId: String) {
        if (venueEnPantalla != venueId) {
            venueEnPantalla = venueId
            _plan.value = null
        }
    }

    /** Pinta lo guardado (sólo si no hay plano de esta sucursal en memoria). */
    suspend fun hydrate(venueId: String) {
        mirarA(venueId)
        if (_plan.value != null) return
        val cached = payloadCache.load(PayloadCache.TYPE_FLOOR_PLAN, venueId) ?: return
        val payload = try {
            cacheJson.decodeFromString(FloorPlanPayload.serializer(), cached.json)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Plano guardado ilegible: ${e.message}")
            return
        }
        if (venueEnPantalla == venueId && _plan.value == null) {
            _plan.value = payload
            Log.d(TAG, "🗂️ Plano hidratado del aparato (hace ${cached.ageMinutes} min)")
        }
    }

    suspend fun refresh(venueId: String): Result<FloorPlanPayload> {
        mirarA(venueId)
        return try {
            val payload = apiService.getFloorPlan(venueId).data ?: throw IllegalStateException("Respuesta sin plano")
            // ANTES de pintar (spec §4.1).
            payloadCache.save(PayloadCache.TYPE_FLOOR_PLAN, venueId, cacheJson.encodeToString(FloorPlanPayload.serializer(), payload))
            if (venueEnPantalla == venueId) _plan.value = payload
            Log.d(TAG, "✅ Plano ${payload.floorPlanVersion}: ${payload.areas.size} áreas, ${payload.elements.size} elementos")
            Result.success(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin red (o servidor viejo): lo guardado se queda; nunca se borra por un error.
            Log.w(TAG, "⚠️ /floor-plan falló: ${e.message} — se queda lo guardado")
            // Sólo si el mesero sigue en esa sucursal: un fallo tardío de otra no la vuelve a pintar.
            if (venueEnPantalla == venueId) hydrate(venueId)
            Result.failure(e)
        }
    }
}
