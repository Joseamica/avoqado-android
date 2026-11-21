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
    fun normal(c: PreparationCounts) = if (command.version == 1 && item.before.urgency == null) c.copy(urgency = null) else c
    // Un ACKED es lo que ya confirmó el SERVIDOR. Si la copia guardada es justo la anterior (misma revisión y mismo estado) y sólo hay
    // avances guardados de esa misma revisión, la copia se adelanta a lo confirmado (lo que traería refrescarla del servidor), en vez de
    // rechazarlo en cada pasada. Va ANTES de converger (Codex r5 #3): si después se rechaza el avance propio, la copia ya dice lo confirmado.
    if (command.phase == "ACKED" && saved.base.preparationRevision == item.expectedRevision &&
        normal(saved.base.preparation) == item.before && saved.effects.all { it.expectedRevision == item.expectedRevision }) {
        return saved.copy(base = saved.base.copy(preparationRevision = item.expectedRevision + 1,
            preparation = saved.base.preparation.move(command.action, command.quantity, command.from, command.reason, command.intentId)))
    }
    // QA del conjunto (20-nov): este aparato ya tiene el MISMO avance pendiente (los dos liberan el producto completo, o los dos lo dan
    // por listo) desde la misma revisión Y el mismo estado (Codex r5 #2: un mismo número de revisión puede venir de avances distintos).
    // Converge: se acepta sin cambiar nada, igual que el SERVIDOR (que contesta éxito a la segunda).
    if (saved.effects.any { propio -> convergen(propio, effect, item.before) && runCatching {
            saved.copy(effects = saved.effects.takeWhile { it != propio }).projected()
                .let { antes -> antes.preparationRevision == item.expectedRevision && normal(antes.preparation) == item.before }
        }.getOrDefault(false) }) return saved
    val current = saved.projected()
    require(current.preparationRevision == item.expectedRevision && normal(current.preparation) == item.before) {
        "El estado cambió en otro aparato. Actualiza el producto antes de continuar."
    }
    require(saved.effects.size < 20) { "Conecta para sincronizar las acciones guardadas de este producto" }
    return saved.copy(effects = saved.effects + effect)
}

/**
 * Dos avances iguales desde la misma revisión que dejan el producto COMPLETO en su destino (nada retenido tras «Liberar», nada
 * preparando tras «Marcar listo»). Lo parcial no converge: igual que en el SERVIDOR (`accionYaCumplida`), dos «libera 1 de 3» siguen
 * siendo un choque.
 */
internal fun convergen(propio: PreparationEffect, otro: PreparationEffect, antes: PreparationCounts): Boolean {
    if (propio.expectedRevision != otro.expectedRevision || propio.action != otro.action || propio.quantity != otro.quantity ||
        propio.from != otro.from) return false
    val despues = runCatching { antes.move(otro.action, otro.quantity, otro.from, otro.reason, otro.intentId) }.getOrNull() ?: return false
    return when (otro.action) {
        PreparationAction.RELEASE -> despues.HELD == 0
        PreparationAction.READY -> despues.PREPARING == 0
        else -> false
    }
}
