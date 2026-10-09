package com.avoqado.pos.kds.domain

import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.TicketPlan
import kotlinx.serialization.Serializable

@Serializable
data class PreparationDeliveryJob(
    val kind: String = "PREPARATION_DELIVERY",
    val intentId: String, val venueId: String, val staffId: String, val deviceId: String,
    val commands: List<PreparationPeerProgress>,
    val orderNumber: String? = null,
    val urgencyNeedsScreen: Boolean? = null,
    val paper: TrabajoPendiente? = null,
    val paperState: String = if (paper == null) "NONE" else "QUEUED",
    val receipts: Map<String, String> = emptyMap(),
    val done: Boolean = false,
    val message: String? = null,
    val phase: String = "ACTION",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val sendCursor: Int = 0,
    val screenReceipts: Set<String> = emptySet(),
    val manualPaper: Boolean = false,
    val resolvedByStaffId: String? = null,
)

data class PreparationPaperIssue(val intentId: String, val orderNumber: String, val state: String,
    val message: String, val canRetry: Boolean)
data class PreparationPaperPage(val items: List<PreparationPaperIssue>, val total: Int, val hasMore: Boolean, val cursor: String?)

/** No catalog lookup or SKU consolidation: exactly the selected units, station and frozen course. */
fun preparationReleasePaper(selected: List<PreparationLine>, quantity: Int, config: PrintConfig,
    venueId: String, urgent: Boolean = false): TrabajoPendiente? {
    require(quantity > 0 && selected.size in 1..100 && selected.map { it.stableKey }.distinct().size == selected.size)
    selected.forEach { require(it.preparation.HELD >= if (urgent) 1 else quantity) }
    val plans = selected.groupBy { it.stationId }.map { (station, rows) -> TicketPlan(station, station == null,
        rows.map { row -> ConsolidatedLine(row.productName, if (urgent) row.preparation.HELD else quantity, row.modifiers.orEmpty(), row.notes,
            listOf(row.orderItemId ?: row.externalId ?: row.id), row.serviceCourse, row.externalId, row.orderPromotionId) }) }
    return TrabajoPendiente(planes = plans, config = config, orderNumber = selected.first().orderNumber,
        orderType = "${if (urgent) "URGENTE" else "LIBERAR"} · ${selected.map { it.serviceCourse?.label }.distinct().singleOrNull() ?: "Productos"}", serverName = null,
        comboNames = emptyMap(), venueId = venueId, orderId = selected.first().orderId)
}

fun preparationDeliveryJob(venueId: String, actor: String, deviceId: String, intentId: String,
    selected: List<PreparationLine>, action: PreparationAction, quantity: Int,
    from: PreparationState?, reason: String?, config: PrintConfig): PreparationDeliveryJob {
    val eligible = selected.filter { it.sourceKey != null && it.externalId != null }
    val batches = eligible.groupBy { it.stationId }.values.flatMap { it.chunked(20) }
    val commands = batches.mapIndexed { index, rows -> PreparationPeerProgress(version = if (action.priority) 2 else 1,
        venueId = venueId, deviceId = deviceId, staffId = actor, deliveryId = "$intentId:$index", intentId = intentId,
        action = action, quantity = quantity, from = from, reason = reason,
        items = rows.map { PreparationPeerItem(it.sourceKey!!, it.externalId!!, it.stationId, it.quantity, it.preparationRevision, it.preparation) }
    ).also { it.validate() } }
    val held = selected.filter { it.preparation.HELD > 0 }
    return PreparationDeliveryJob(intentId = intentId, venueId = venueId, staffId = actor, deviceId = deviceId, commands = commands,
        orderNumber = selected.firstOrNull()?.orderNumber,
        urgencyNeedsScreen = (action == PreparationAction.URGENT && (held.isEmpty() || selected.any { it.preparation.PENDING + it.preparation.PREPARING > 0 })).takeIf { action == PreparationAction.URGENT },
        paper = when {
            action == PreparationAction.RELEASE -> preparationReleasePaper(selected, quantity, config, venueId)
            action == PreparationAction.URGENT && held.isNotEmpty() -> preparationReleasePaper(held, 1, config, venueId, urgent = true)
            else -> null
        })
}

/** One additive delivery record may accompany up to 100 real product snapshots, under the original actor. */
fun validatePreparationSnapshots(intentId: String, venueId: String, actor: String?,
    snapshots: List<com.avoqado.pos.core.data.local.database.CachedPayloadEntity>) {
    require(!actor.isNullOrBlank() && snapshots.size in 1..101)
    require(snapshots.map { it.cacheKey }.distinct().size == snapshots.size)
    require(snapshots.all { it.venueId == venueId && it.cacheKey.startsWith("preparation:$venueId:") })
    val deliveries = snapshots.filter { it.cacheKey.startsWith("preparation:$venueId:delivery:") }
    require(deliveries.size <= 1 && snapshots.size - deliveries.size in 1..100)
    require(snapshots.none { it.cacheKey.startsWith("preparation:$venueId:receipt:") })
    deliveries.singleOrNull()?.let { record ->
        require(record.cacheKey == "preparation:$venueId:delivery:$intentId")
        val job = PreparationPeerProtocol.json.decodeFromString<PreparationDeliveryJob>(record.json)
        require(job.kind == "PREPARATION_DELIVERY" && job.venueId == venueId && job.staffId == actor &&
            job.intentId == intentId && job.deviceId.isNotBlank() && job.phase == "ACTION" && !job.done && job.commands.size <= 100)
        require(job.paper == null || job.paper.venueId == venueId)
        job.commands.forEach { command ->
            command.validate()
            require(command.venueId == venueId && command.staffId == actor && command.deviceId == job.deviceId &&
                command.intentId == intentId && command.phase == "ACTION")
        }
        val items = job.commands.flatMap { it.items }
        require(items.size <= 100 && items.map { it.stableKey }.distinct().size == items.size)
    }
}

/** Check inside the SQLite write transaction, so a peer cannot be overwritten between selection and enqueue. */
fun validatePreparationPredecessors(job: PreparationDeliveryJob,
    next: List<com.avoqado.pos.core.data.local.database.CachedPayloadEntity>,
    prior: List<com.avoqado.pos.core.data.local.database.CachedPayloadEntity>) {
    val before = prior.associateBy { it.cacheKey }
    val after = next.associateBy { it.cacheKey }
    for (command in job.commands) for (item in command.items) {
        val key = "preparation:${job.venueId}:${item.stableKey}"
        val saved = before[key] ?: error("Este producto cambió. Actualiza su estado.")
        val proposed = after[key] ?: error("Falta el producto de esta acción")
        val local = PreparationPeerProtocol.json.decodeFromString<PreparationLocalLine>(saved.json).projected()
        val incoming = PreparationPeerProtocol.json.decodeFromString<PreparationLocalLine>(proposed.json)
        require(local.stableKey == item.stableKey && local.quantity == item.quantity &&
            local.preparationRevision == item.expectedRevision && local.preparation == item.before) {
            "Este producto cambió en otro aparato. Actualiza su estado."
        }
        require(incoming.base.stableKey == item.stableKey && incoming.base.quantity == item.quantity &&
            incoming.effects.lastOrNull() == PreparationEffect(job.intentId, item.expectedRevision, command.action,
                command.quantity, command.from, command.reason)) { "La acción no coincide con el producto guardado" }
    }
}
