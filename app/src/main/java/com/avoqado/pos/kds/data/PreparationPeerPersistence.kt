package com.avoqado.pos.kds.data

import androidx.room.withTransaction
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.kds.domain.*
import kotlinx.serialization.json.Json

/** One SQLite transaction for all known products and the receipt, before the transport may acknowledge. */
class PreparationPeerPersistence(private val database: AvoqadoDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun receive(command: PreparationPeerProgress): Boolean {
        command.validate()
        val cache = database.cachedPayloadDao()
        return database.withTransaction {
            val capability = cache.get("preparation_capabilities:${command.venueId}")
                ?.takeIf { it.venueId == command.venueId }
                ?.let { json.decodeFromString<PreparationCapabilities>(it.json) }
            check(capability?.version == 1 && capability.enabled) { "Conecta para confirmar preparación por producto" }
            check(command.version == 1 || capability.urgencyVersion == 1) { "Conecta para confirmar los avisos urgentes" }
            val receiptKey = "preparation:${command.venueId}:receipt:${command.deliveryId}"
            val previous = cache.get(receiptKey)?.let { json.decodeFromString<PreparationPeerProgress>(it.json) }
            if (previous != null) {
                check(previous.copy(phase = "ACTION") == command.copy(phase = "ACTION")) { "Esta acción ya tiene otro contenido" }
                if (previous.phase == command.phase) return@withTransaction true
                check(previous.phase == "ACTION") { "Esta acción ya tiene una resolución diferente" }
            }
            val keys = command.items.map { "preparation:${command.venueId}:${it.stableKey}" }
            val saved = cache.preparationSnapshots(command.venueId, keys).associateBy { it.cacheKey }
            val photos = command.items.map { item ->
                val key = "preparation:${command.venueId}:${item.stableKey}"
                val photo = saved[key] ?: error("Carga el producto en este aparato antes de recibir su avance")
                val local = json.decodeFromString<PreparationLocalLine>(photo.json)
                check(local.base.unavailableReason == null) { "Revisa la ronda antes de recibir su avance" }
                val corrected = if (command.phase == "ACKED" && local.base.preparationRevision > item.expectedRevision) local
                    else applyPreparationPeerProgress(local, item, command)
                photo.copy(json = json.encodeToString(PreparationLocalLine.serializer(), corrected), updatedAt = System.currentTimeMillis())
            }
            cache.upsertAll(photos + CachedPayloadEntity(receiptKey, command.venueId,
                json.encodeToString(PreparationPeerProgress.serializer(), command), System.currentTimeMillis()))
            true
        }
    }
}
