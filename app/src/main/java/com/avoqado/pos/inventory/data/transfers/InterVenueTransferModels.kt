package com.avoqado.pos.inventory.data.transfers

import kotlinx.serialization.Serializable

/**
 * Traslados de insumos entre sucursales (CEDIS) — espejo EXACTO del wire de
 * avoqado-server (`interVenueTransfer.schema.ts` + `interVenueTransfer.service.ts`)
 * y del cliente web (`avoqado-web-dashboard/src/services/interVenueTransfer.service.ts`).
 *
 * Todas las cantidades viajan como STRING (serialización de Prisma Decimal) —
 * se parsean sólo para mostrar/validar, nunca se re-serializan como Double.
 *
 * Los enums se modelan como String + constantes (no enum class) para que un valor
 * futuro del server no tire el parseo del APK viejo (misma razón que el fail-safe
 * de capacityMode en reservas).
 */
object TransferStatus {
    const val REQUESTED = "REQUESTED"
    const val APPROVED = "APPROVED"
    const val IN_TRANSIT = "IN_TRANSIT"
    const val PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED"
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_VARIANCE = "COMPLETED_WITH_VARIANCE"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"

    /** Etiqueta en español para chips/badges. Un estado desconocido se muestra tal cual. */
    fun label(status: String): String = when (status) {
        REQUESTED -> "Solicitado"
        APPROVED -> "Aprobado"
        IN_TRANSIT -> "En tránsito"
        PARTIALLY_RECEIVED -> "Recibido parcial"
        COMPLETED -> "Recibido"
        COMPLETED_WITH_VARIANCE -> "Recibido con diferencias"
        REJECTED -> "Rechazado"
        CANCELLED -> "Cancelado"
        else -> status
    }
}

object TransferMode {
    const val PULL = "PULL"
    const val PUSH = "PUSH"
}

@Serializable
data class TransferVenue(
    val id: String,
    val name: String,
    val operationalRole: String? = null, // STORE | CEDIS | HYBRID
    val salesEnabled: Boolean? = null,
)

@Serializable
data class TransferCounts(
    val items: Int = 0,
    val receipts: Int = 0,
)

@Serializable
data class InterVenueTransferListItem(
    val id: String,
    val number: String,
    val externalReference: String? = null,
    val mode: String,
    val status: String,
    val sourceVenueId: String,
    val destinationVenueId: String,
    val sourceVenue: TransferVenue,
    val destinationVenue: TransferVenue,
    val requestedAt: String? = null,
    val createdAt: String? = null,
    val _count: TransferCounts = TransferCounts(),
)

@Serializable
data class TransferRawMaterialRef(
    val id: String,
    val name: String,
    val sku: String? = null,
    val unit: String? = null,
)

@Serializable
data class InterVenueTransferItem(
    val id: String,
    val unit: String? = null,
    val quantityRequested: String,
    val quantityDispatched: String = "0",
    val quantityReceived: String = "0",
    val quantityVarianceResolved: String = "0",
    val dispatchShortfallReason: String? = null,
    val notes: String? = null,
    val sourceRawMaterial: TransferRawMaterialRef,
    val destinationRawMaterial: TransferRawMaterialRef,
)

@Serializable
data class InterVenueTransferDetail(
    val id: String,
    val number: String,
    val externalReference: String? = null,
    val mode: String,
    val status: String,
    val organizationId: String? = null,
    val sourceVenueId: String,
    val destinationVenueId: String,
    val sourceVenue: TransferVenue,
    val destinationVenue: TransferVenue,
    val notes: String? = null,
    val requestedAt: String? = null,
    val approvedAt: String? = null,
    val rejectedAt: String? = null,
    val rejectionReason: String? = null,
    val cancelledAt: String? = null,
    val cancellationReason: String? = null,
    val dispatchedAt: String? = null,
    val completedAt: String? = null,
    val items: List<InterVenueTransferItem> = emptyList(),
)

@Serializable
data class TransferListPage(
    val items: List<InterVenueTransferListItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 50,
    val totalPages: Int = 1,
)

// MARK: - Bodies de request (espejo de los Zod schemas del server)

@Serializable
data class CreateTransferItemInput(
    val sourceRawMaterialId: String,
    val destinationRawMaterialId: String,
    val quantity: Double,
    val notes: String? = null,
)

@Serializable
data class CreateTransferInput(
    val mode: String, // este MVP siempre manda PULL (la tienda pide)
    val sourceVenueId: String,
    val destinationVenueId: String,
    val externalReference: String? = null,
    val notes: String? = null,
    val items: List<CreateTransferItemInput>,
)

@Serializable
data class TransferReasonBody(val reason: String)

@Serializable
data class DispatchItemInput(
    val itemId: String,
    val quantity: Double,
    val shortfallReason: String? = null,
)

@Serializable
data class DispatchTransferBody(val items: List<DispatchItemInput>)

@Serializable
data class ReceiveItemInput(
    val itemId: String,
    val quantity: Double,
)

@Serializable
data class ReceiveTransferBody(
    val notes: String? = null,
    val items: List<ReceiveItemInput>,
)

// MARK: - Insumos del venue origen (picker de crear)

@Serializable
data class TransferPickerRawMaterial(
    val id: String,
    val name: String,
    val sku: String? = null,
    val unit: String? = null,
    val currentStock: String? = null,
)

// MARK: - Máquina de estados (espejo de ALLOWED_ACTIONS en interVenueTransfer.domain.ts)

enum class TransferAction { APPROVE, REJECT, DISPATCH, RECEIVE, CANCEL }

/**
 * Acciones disponibles para el venue ACTUAL sobre un traslado. Espejo de la tabla
 * `ALLOWED_ACTIONS` del server MÁS el lado que puede ejecutarla (el server valida
 * permisos y contexto; esto sólo decide qué botones pintar):
 *
 * - REQUESTED:  origen → Aprobar/Rechazar · ambos lados → Cancelar
 * - APPROVED:   origen → Despachar · ambos lados → Cancelar
 * - IN_TRANSIT / PARTIALLY_RECEIVED: destino → Recibir
 * - COMPLETED / COMPLETED_WITH_VARIANCE / REJECTED / CANCELLED: nada
 *
 * (RESOLVE_VARIANCE existe en el server pero está fuera de alcance en el POS v1.)
 */
fun availableTransferActions(status: String, isSource: Boolean, isDestination: Boolean): Set<TransferAction> {
    if (!isSource && !isDestination) return emptySet()
    return when (status) {
        TransferStatus.REQUESTED -> buildSet {
            if (isSource) { add(TransferAction.APPROVE); add(TransferAction.REJECT) }
            add(TransferAction.CANCEL)
        }
        TransferStatus.APPROVED -> buildSet {
            if (isSource) add(TransferAction.DISPATCH)
            add(TransferAction.CANCEL)
        }
        TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED ->
            if (isDestination) setOf(TransferAction.RECEIVE) else emptySet()
        else -> emptySet()
    }
}
