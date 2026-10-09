package com.avoqado.pos.kds.domain

import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import kotlinx.serialization.Serializable

@Serializable
enum class PreparationState(val label: String) {
    HELD("En espera de liberar"), PENDING("Pendiente"), PREPARING("Preparando"),
    READY("Listo"), DELIVERED("Entregado"), CANCELLED("Cancelado"),
}

@Serializable
enum class PreparationAction(val label: String) {
    RELEASE("Liberar"), START("Preparar"), READY("Marcar listo"), DELIVER("Entregar"),
    CANCEL("Cancelar preparación"), REOPEN("Reabrir preparación"),
    URGENT("Enviar urgente"), ACK_URGENT("Ya lo vi"), CLEAR_URGENT("Quitar urgencia");
    val priority get() = this in setOf(URGENT, ACK_URGENT, CLEAR_URGENT)
    val permissions: List<String> get() = when (this) {
        RELEASE, DELIVER, URGENT, CLEAR_URGENT -> listOf("orders:update", "orders:create")
        CANCEL, REOPEN -> listOf("orders:update", "orders:cancel")
        else -> listOf("orders:update")
    }
    val defaultSource: PreparationState? get() = when (this) {
        RELEASE -> PreparationState.HELD
        START -> PreparationState.PENDING
        READY -> PreparationState.PREPARING
        DELIVER -> PreparationState.READY
        else -> null
    }
}

/** Wire keys and transitions mirror kitchenPreparation.ts; quantities never imply payment/refund. */
@Serializable
data class PreparationUrgency(val requestId: String, val acknowledged: Boolean = false) {
    fun valid() = requestId.isNotBlank() && requestId.length <= 200
}

@Serializable
data class PreparationCounts(
    val HELD: Int = 0, val PENDING: Int = 0, val PREPARING: Int = 0,
    val READY: Int = 0, val DELIVERED: Int = 0, val CANCELLED: Int = 0,
    val urgency: PreparationUrgency? = null,
) {
    operator fun get(state: PreparationState): Int = when (state) {
        PreparationState.HELD -> HELD; PreparationState.PENDING -> PENDING
        PreparationState.PREPARING -> PREPARING; PreparationState.READY -> READY
        PreparationState.DELIVERED -> DELIVERED; PreparationState.CANCELLED -> CANCELLED
    }
    fun validFor(quantity: Int) = quantity > 0 && PreparationState.entries.all { this[it] >= 0 } &&
        PreparationState.entries.sumOf { this[it].toLong() } == quantity.toLong() && (urgency?.valid() != false)
    val unfinished get() = HELD + PENDING + PREPARING
    val urgent get() = urgency != null && unfinished > 0
    val terminal get() = HELD + PENDING + PREPARING + READY == 0
    fun available(action: PreparationAction): Int = action.defaultSource?.let { this[it] } ?: when (action) {
        PreparationAction.CANCEL -> HELD + PENDING + PREPARING + READY
        PreparationAction.REOPEN -> CANCELLED + READY + DELIVERED
        PreparationAction.URGENT -> if (unfinished > 0 && !urgent) 1 else 0
        PreparationAction.ACK_URGENT -> if (urgent && urgency?.acknowledged == false) 1 else 0
        PreparationAction.CLEAR_URGENT -> if (urgent) 1 else 0
        else -> 0
    }
    fun move(action: PreparationAction, quantity: Int, from: PreparationState? = null, reason: String? = null,
        urgencyRequestId: String? = null): PreparationCounts {
        require(quantity > 0) { "Selecciona una cantidad mayor a cero" }
        if (action.priority) {
            require(quantity == 1 && from == null) { "La prioridad se aplica al producto, sin modificar cantidades" }
            if (action == PreparationAction.CLEAR_URGENT) return copy(urgency = null)
            require(unfinished > 0) { "Este producto ya está listo o terminado. Revisa su estado." }
            if (action == PreparationAction.ACK_URGENT) {
                require(urgency != null) { "Este producto ya no tiene un aviso urgente" }
                return copy(urgency = urgency.copy(acknowledged = true))
            }
            require(!urgencyRequestId.isNullOrBlank() && urgencyRequestId.length <= 200) { "Falta identificar la solicitud urgente" }
            return copy(HELD = 0, PENDING = PENDING + HELD, urgency = PreparationUrgency(urgencyRequestId))
        }
        if (from == null && action.defaultSource == null) {
            require(!reason.isNullOrBlank() && reason.trim().length <= 500) { "Escribe un motivo de hasta 500 caracteres" }
            require(available(action) >= quantity) { "La cantidad o el estado cambió. Revisa el producto." }
            val sources = if (action == PreparationAction.CANCEL) listOf(PreparationState.HELD, PreparationState.PENDING, PreparationState.PREPARING, PreparationState.READY)
                else listOf(PreparationState.CANCELLED, PreparationState.READY, PreparationState.DELIVERED)
            var result = this; var remaining = quantity
            for (source in sources) {
                val amount = minOf(remaining, result[source])
                if (amount > 0) result = result.move(action, amount, source, reason)
                remaining -= amount
            }
            return result
        }
        val source = action.defaultSource ?: from ?: error("Selecciona el estado que quieres corregir")
        require(from == null || from == source) { "La transición no corresponde al estado" }
        val target = when (action) {
            PreparationAction.RELEASE, PreparationAction.REOPEN -> PreparationState.PENDING
            PreparationAction.START -> PreparationState.PREPARING
            PreparationAction.READY -> PreparationState.READY
            PreparationAction.DELIVER -> PreparationState.DELIVERED
            PreparationAction.CANCEL -> PreparationState.CANCELLED
            else -> error("Selecciona una transición válida")
        }
        if (action == PreparationAction.CANCEL || action == PreparationAction.REOPEN) {
            require(!reason.isNullOrBlank() && reason.trim().length <= 500) { "Escribe un motivo de hasta 500 caracteres" }
            val allowed = if (action == PreparationAction.CANCEL) setOf(PreparationState.HELD, PreparationState.PENDING, PreparationState.PREPARING, PreparationState.READY)
                else setOf(PreparationState.READY, PreparationState.DELIVERED, PreparationState.CANCELLED)
            require(source in allowed) { "Este estado no admite la acción" }
        }
        require(this[source] >= quantity) { "La cantidad o el estado cambió. Revisa el producto." }
        val values = PreparationState.entries.associateWith { this[it] }.toMutableMap()
        values[source] = this[source] - quantity
        values[target] = this[target] + quantity
        return PreparationCounts(values.getValue(PreparationState.HELD), values.getValue(PreparationState.PENDING),
            values.getValue(PreparationState.PREPARING), values.getValue(PreparationState.READY),
            values.getValue(PreparationState.DELIVERED), values.getValue(PreparationState.CANCELLED), urgency)
            .let { if (it.unfinished == 0) it.copy(urgency = null) else it }
    }
    companion object { fun initial(quantity: Int, standard: Boolean): PreparationCounts {
        require(quantity > 0)
        return if (standard) PreparationCounts(HELD = quantity) else PreparationCounts(PENDING = quantity)
    } }
}

@Serializable
data class PreparationCapabilities(val version: Int = 0, val enabled: Boolean = false, val maxItems: Int = 100,
    val replayLaneVersion: Int = 0, val urgencyVersion: Int = 0)
@Serializable data class PreparationCapabilitiesResponse(val data: PreparationCapabilities)

@Serializable
data class PreparationLine(
    val id: String, val orderId: String? = null, val orderItemId: String? = null,
    val externalId: String? = null, val sourceKey: String? = null, val stationId: String? = null,
    val orderNumber: String = "", val productName: String, val quantity: Int,
    val serviceCourse: ServiceCourseSnapshot? = null, val orderPromotionId: String? = null,
    val preparation: PreparationCounts, val preparationRevision: Int = 0,
    val stationCount: Int = 1, val modifiers: List<String>? = null, val notes: String? = null,
    val localOrderId: String? = null,
    val unavailableReason: String? = null,
    val draftRoundKey: String? = null,
) {
    /** Survives a LAN provisional row becoming a server row. Never identifies a component by SKU. */
    val stableKey: String get() = if (externalId != null && sourceKey != null)
        "${sourceKey.length}:$sourceKey:${externalId.length}:$externalId:${stationId.orEmpty()}" else id
    val urgencyProductKey: String get() = if (externalId != null && sourceKey != null) {
        val round = sourceKey.substringBeforeLast(':')
        "${round.length}:$round:${externalId.length}:$externalId"
    } else orderItemId ?: id
}

/** A component is selected by its immutable identity, never by SKU or the whole combo. */
fun selectUrgentProducts(rows: List<PreparationLine>, keys: Set<String>): List<PreparationLine> {
    require(keys.isNotEmpty()) { "Selecciona los productos que quieres enviar urgentes" }
    val groups = rows.groupBy { it.urgencyProductKey }
    val selected = keys.flatMap { key ->
        val stations = groups[key] ?: error("La selección cambió. Revisa los productos.")
        require(stations.all { it.stationCount == stations.size }) { "Carga las demás estaciones del producto antes de enviarlo urgente" }
        require(stations.none { it.unavailableReason != null }) { "Revisa los productos guardados antes de continuar" }
        val eligible = stations.filter { it.preparation.available(PreparationAction.URGENT) > 0 }
        require(eligible.isNotEmpty()) { "Este producto ya está listo o es urgente. Revisa la selección." }
        eligible
    }
    require(selected.size <= 100) { "Selecciona hasta 100 renglones de preparación por envío" }
    require(selected.map { it.orderId }.distinct().size == 1) { "Selecciona productos de una misma cuenta" }
    return selected
}
@Serializable data class PreparationPage(
    val version: Int, val items: List<PreparationLine>, val total: Int,
    val hasMore: Boolean, val nextCursor: String? = null, val limit: Int = 50,
    @kotlinx.serialization.Transient val cachedOnly: Boolean = false,
)
@Serializable data class PreparationPageResponse(val data: PreparationPage)

@Serializable data class PreparationEffect(
    val intentId: String, val expectedRevision: Int, val action: PreparationAction,
    val quantity: Int, val from: PreparationState? = null, val reason: String? = null,
)
@Serializable data class PreparationLocalLine(val base: PreparationLine, val effects: List<PreparationEffect> = emptyList()) {
    fun projected(rejected: Set<String> = emptySet()): PreparationLine {
        var line = base
        for (effect in effects) {
            if (effect.intentId in rejected || effect.expectedRevision < line.preparationRevision) continue
            check(effect.expectedRevision == line.preparationRevision) {
                "Hay un avance de cocina pendiente cuya acción anterior no fue aceptada. Revisa el producto con cocina y sincroniza antes de continuar."
            }
            line = line.copy(preparation = line.preparation.move(effect.action, effect.quantity, effect.from, effect.reason, effect.intentId),
                preparationRevision = line.preparationRevision + 1)
        }
        return line
    }
}
