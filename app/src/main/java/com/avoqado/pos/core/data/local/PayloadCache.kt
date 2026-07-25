package com.avoqado.pos.core.data.local

import android.util.Log
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first (Corte A): fachada string-based sobre `cached_payloads`.
 * Los repositorios serializan/deserializan con SU propio kotlinx Json (mismas
 * opciones que usan para la red), así el cache nunca diverge del parser real.
 *
 * Regla de uso (patrón cache-first):
 * 1. Al arrancar/antes del fetch: si el estado está vacío, hidratar del cache.
 * 2. En cada fetch exitoso: `save()` el payload crudo.
 * 3. En fetch fallido: el estado hidratado del cache se queda — nunca se borra
 *    por un error de red.
 */
@Singleton
class PayloadCache @Inject constructor(
    private val dao: CachedPayloadDao,
) {
    data class Cached(val json: String, val updatedAt: Long) {
        val ageMinutes: Long get() = (System.currentTimeMillis() - updatedAt) / 60_000L
    }

    suspend fun load(type: String, venueId: String): Cached? = runCatching {
        dao.get(key(type, venueId))?.let { Cached(it.json, it.updatedAt) }
    }.onFailure { Log.e(TAG, "❌ load $type falló: ${it.message}") }.getOrNull()

    suspend fun save(type: String, venueId: String, json: String) {
        runCatching {
            dao.upsert(
                CachedPayloadEntity(
                    cacheKey = key(type, venueId),
                    venueId = venueId,
                    json = json,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "❌ save $type falló: ${it.message}") }
    }

    /** Al cambiar de venue no arrastramos catálogo ajeno. */
    suspend fun pruneOtherVenues(venueId: String) {
        runCatching { dao.deleteOtherVenues(venueId) }
    }

    private fun key(type: String, venueId: String) = "$type:$venueId"

    companion object {
        private const val TAG = "PayloadCache"

        // Tipos conocidos (Corte A)
        const val TYPE_PRODUCTS = "products"
        const val TYPE_TABLES = "tables"
        const val TYPE_MENUS = "menus"
        const val TYPE_PRINT_CONFIG = "print_config"
    }
}
