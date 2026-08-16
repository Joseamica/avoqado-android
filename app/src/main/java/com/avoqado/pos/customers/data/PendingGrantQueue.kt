package com.avoqado.pos.customers.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted retry queue for membresía (credit-pack) grants after a successful
 * charge. A failed grant used to be silently dropped — the customer PAID and
 * never received credits, with no record anywhere. Mirrors iOS's
 * PendingGrantQueue.swift. Drained on app start and on demand.
 */
@Singleton
class PendingGrantQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val articlesRepository: ArticlesRepository,
    private val secureStorage: SecureStorage,
) {
    @Serializable
    data class PendingGrant(
        val id: String,
        val venueId: String,
        val packId: String,
        val customerId: String,
        val createdAt: Long,
        val attempts: Int = 0,
    )

    private val prefs = context.getSharedPreferences("pending_pack_grants", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isDraining = false

    init {
        // Retry anything left over from a previous session.
        drainAsync()
    }

    fun enqueue(packId: String, customerId: String) {
        val venueId = secureStorage.venueId ?: return
        val grants = load().toMutableList()
        grants.add(
            PendingGrant(
                id = java.util.UUID.randomUUID().toString(),
                venueId = venueId,
                packId = packId,
                customerId = customerId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        persist(grants)
        Log.d("🎟️", "PendingGrantQueue: enqueued $packId → $customerId (queue: ${grants.size})")
    }

    fun drainAsync() {
        scope.launch { drain() }
    }

    /** Deliver every queued grant for the CURRENT venue; failures stay queued. */
    suspend fun drain() {
        if (isDraining) return
        val venueId = secureStorage.venueId ?: return
        val grants = load()
        if (grants.isEmpty()) return
        isDraining = true
        try {
            val remaining = mutableListOf<PendingGrant>()
            for (grant in grants) {
                if (grant.venueId != venueId) {
                    remaining.add(grant) // belongs to another venue — keep
                    continue
                }
                // Reintento automático (arranque de la app / vuelta de la red): su 403
                // no puede saltar como modal encima de quien esté cobrando. Lo que
                // falla se queda en la cola, que es donde se ve y se resuelve.
                val ok = articlesRepository.sellPackToCustomer(
                    grant.packId,
                    grant.customerId,
                    background = true,
                )
                if (ok) {
                    Log.d("🎟️", "PendingGrantQueue: delivered ${grant.packId} → ${grant.customerId}")
                } else {
                    remaining.add(grant.copy(attempts = grant.attempts + 1))
                }
            }
            persist(remaining)
        } finally {
            isDraining = false
        }
    }

    private fun load(): List<PendingGrant> {
        val raw = prefs.getString("queue", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingGrant>>(raw) }.getOrDefault(emptyList())
    }

    private fun persist(grants: List<PendingGrant>) {
        prefs.edit().putString("queue", json.encodeToString(grants)).apply()
    }
}
