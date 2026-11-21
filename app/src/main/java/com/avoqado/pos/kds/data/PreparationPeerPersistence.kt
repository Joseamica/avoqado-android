package com.avoqado.pos.kds.data

import androidx.room.withTransaction
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.kds.domain.*
import kotlinx.serialization.json.Json

/**
 * D16 (3.ª pasada con aparatos): el avance es de productos que ESTE aparato no tiene cargados (p. ej. la pantalla de cocina movió
 * una cuenta que la caja nunca abrió). No es un desacuerdo: quien lo recibe lo toma como recibido en silencio y lo verá con la
 * siguiente lectura del servidor cuando cargue ese producto. Un tipo propio para no distinguirlo por el texto.
 */
class ProductoNoCargadoAqui : IllegalStateException("Carga el producto en este aparato antes de recibir su avance")

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
            // D16: lo que este aparato no tiene cargado se salta (lo trae la siguiente lectura del servidor); si no tiene NADA, se
            // dice con su propio tipo y no se guarda el recibo.
            val photos = command.items.mapNotNull { item ->
                val key = "preparation:${command.venueId}:${item.stableKey}"
                val photo = saved[key] ?: return@mapNotNull null
                val local = json.decodeFromString<PreparationLocalLine>(photo.json)
                check(local.base.unavailableReason == null) { "Revisa la ronda antes de recibir su avance" }
                val corrected = if (command.phase == "ACKED" && local.base.preparationRevision > item.expectedRevision) local
                    else applyPreparationPeerProgress(local, item, command)
                photo.copy(json = json.encodeToString(PreparationLocalLine.serializer(), corrected), updatedAt = System.currentTimeMillis())
            }
            if (photos.isEmpty()) throw ProductoNoCargadoAqui()
            cache.upsertAll(photos + CachedPayloadEntity(receiptKey, command.venueId,
                json.encodeToString(PreparationPeerProgress.serializer(), command), System.currentTimeMillis()))
            true
        }
    }
}
