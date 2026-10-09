package com.avoqado.pos.kds.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** Additive LAN contract. These projections never create orders or adopt another employee's outbox. */
@Serializable
data class PreparationPeerItem(
    val sourceKey: String, val externalId: String, val stationId: String? = null,
    val quantity: Int, val expectedRevision: Int, val before: PreparationCounts,
) {
    val stableKey get() = "${sourceKey.length}:$sourceKey:${externalId.length}:$externalId:${stationId.orEmpty()}"
}

@Serializable
data class PreparationPeerProgress(
    @SerialName("v") val version: Int = 1,
    val op: String = "preparation_progress",
    val venueId: String, val deviceId: String, val staffId: String,
    val deliveryId: String, val intentId: String,
    val phase: String = "ACTION",
    val action: PreparationAction, val quantity: Int,
    val from: PreparationState? = null, val reason: String? = null,
    val items: List<PreparationPeerItem>,
) {
    fun validate() {
        require(version in 1..2 && (!action.priority || version == 2) && op == "preparation_progress" && phase in setOf("ACTION", "ACKED", "REJECTED"))
        require(listOf(venueId, deviceId, staffId, deliveryId, intentId).all { it.isNotBlank() && it.length <= 200 })
        require(quantity > 0 && items.size in 1..20 && items.map { it.stableKey }.distinct().size == items.size)
        items.forEach {
            require(it.sourceKey.isNotBlank() && it.externalId.isNotBlank() && it.expectedRevision in 0 until Int.MAX_VALUE)
            require(it.before.validFor(it.quantity))
            it.before.move(action, quantity, from, reason, intentId)
        }
        require(PreparationPeerProtocol.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8).size <= 60 * 1024)
    }
}

@Serializable
data class PreparationPeerAck(
    @SerialName("v") val version: Int = 1, val status: String,
    val deliveryId: String, val phase: String, val message: String? = null,
    val displayStations: List<String> = emptyList(),
)

object PreparationPeerProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun acknowledged(line: String?, command: PreparationPeerProgress): Boolean {
        val ack = line?.let { runCatching { json.decodeFromString<PreparationPeerAck>(it) }.getOrNull() } ?: return false
        return ack.version == command.version && ack.status == "ok" && ack.deliveryId == command.deliveryId && ack.phase == command.phase
    }
    fun displayed(line: String?, command: PreparationPeerProgress): Boolean = acknowledged(line, command) &&
        runCatching { json.decodeFromString<PreparationPeerAck>(line!!).displayStations }.getOrDefault(emptyList())
            .contains(command.items.first().stationId)
}

/** Apply only to a known line at the exact predecessor. A different action at the same revision is a conflict. */
fun applyPreparationPeerProgress(saved: PreparationLocalLine, item: PreparationPeerItem,
    command: PreparationPeerProgress): PreparationLocalLine {
    command.validate()
    require(item in command.items)
    require(saved.base.stableKey == item.stableKey && saved.base.quantity == item.quantity &&
        saved.base.serviceCourse?.preparationVersion == 1) { "Este producto no corresponde a la preparación guardada" }
    val prior = saved.effects.firstOrNull { it.intentId == command.intentId }
    val effect = PreparationEffect(command.intentId, item.expectedRevision, command.action, command.quantity, command.from, command.reason)
    if (prior != null) require(prior == effect) { "El identificador de esta acción ya tiene otro contenido" }
    if (command.phase == "REJECTED") return saved.copy(effects = saved.effects.filterNot { it.intentId == command.intentId })
    if (prior != null) return saved
    val current = saved.projected()
    require(current.preparationRevision == item.expectedRevision &&
        (if (command.version == 1 && item.before.urgency == null) current.preparation.copy(urgency = null) else current.preparation) == item.before) {
        "El estado cambió en otro aparato. Actualiza el producto antes de continuar."
    }
    require(saved.effects.size < 20) { "Conecta para sincronizar las acciones guardadas de este producto" }
    return saved.copy(effects = saved.effects + effect)
}
