package com.avoqado.pos.tables.data

import kotlinx.serialization.Serializable

/**
 * TABLE_SERVICE (PRO) — floor-plan tables with live status, mirror of the
 * server's TableStatusResponse (GET /mobile/venues/:venueId/tables — same
 * payload the TPV terminal consumes).
 */
@Serializable
data class DiningTable(
    val id: String,
    val number: String,
    val capacity: Int = 2,
    val positionX: Float? = null,
    val positionY: Float? = null,
    val shape: String = "SQUARE", // SQUARE | ROUND | RECTANGLE
    val rotation: Int = 0,
    val status: String = "AVAILABLE", // AVAILABLE | OCCUPIED | RESERVED | ...
    val areaId: String? = null,
    val areaName: String? = null,
    val currentOrder: TableOrder? = null,
) {
    val isAvailable: Boolean get() = status == "AVAILABLE"
    val isOccupied: Boolean get() = status == "OCCUPIED"
    val isReserved: Boolean get() = status == "RESERVED"
    val hasPosition: Boolean get() = positionX != null && positionY != null
}

@Serializable
data class TableOrder(
    val id: String,
    val orderNumber: String,
    val covers: Int? = null,
    /** Pesos (major units) — the server serializes Decimal as Number. */
    val total: Double = 0.0,
    val itemCount: Int = 0,
    /** Order.version for optimistic concurrency on add-round. */
    val version: Int = 1,
    val items: List<TableOrderItem> = emptyList(),
    val waiter: TableWaiter? = null,
    val createdAt: String? = null,
) {
    val totalDisplay: String get() = "$${String.format(java.util.Locale.US, "%.2f", total)}"
}

@Serializable
data class TableOrderItem(
    val id: String,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    /** TABLE_SERVICE course/tiempo. Null = "Inmediato". */
    val course: String? = null,
    /** Comped line ("Dar de cortesía"): stays on the check but costs 0. */
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
)

@Serializable
data class TableWaiter(val id: String, val name: String)

// MARK: - Request/response envelopes

@Serializable
data class TablesResponse(val success: Boolean = true, val data: List<DiningTable> = emptyList())

@Serializable
data class OpenTableRequest(val covers: Int = 1)

@Serializable
data class OpenTableResponse(val success: Boolean = true, val data: OpenTableResult? = null)

@Serializable
data class OpenTableResult(val order: OpenedOrder? = null, val isNewOrder: Boolean = false)

@Serializable
data class OpenedOrder(val id: String, val orderNumber: String? = null, val version: Int = 1)

/** One line of a round to append to an open order (mirror of the server's AddOrderItemInput). */
@Serializable
data class AddOrderItemRequest(
    /** Catalog product — null for a custom-amount line (customName + customUnitPriceCents). */
    val productId: String? = null,
    val quantity: Int,
    val notes: String? = null,
    /** Modifier ids picked with the normal product detail panel. */
    val modifierIds: List<String>? = null,
    /** TABLE_SERVICE course/tiempo ("Aperitivos"...). Null = preparar de inmediato. */
    val course: String? = null,
    /** Custom-amount line: label + unit price in cents (productId null). */
    val customName: String? = null,
    val customUnitPriceCents: Int? = null,
)

@Serializable
data class AddItemsRequest(val items: List<AddOrderItemRequest>, val version: Int)

@Serializable
data class AddItemsResponse(val success: Boolean = true, val data: UpdatedOrder? = null)

@Serializable
data class UpdatedOrder(val id: String, val version: Int = 1, val total: Double? = null)

/** POST /mobile/venues/:id/orders/:orderId/pay body (existing endpoint). */
@Serializable
data class PayCashRequest(val amount: Int, val tip: Int = 0)

/** DELETE /mobile/venues/:id/orders/:orderId body — "Anular cuenta" reason. */
@Serializable
data class CancelOrderRequest(val reason: String)

/** POST .../orders/:orderId/items/:itemId/comp body — "Dar de cortesía". */
@Serializable
data class CompItemRequest(val reason: String)

/** Minimal `{ success }` envelope for mutations whose payload we don't consume
 *  (pay-cash, clear-table). Retrofit + kotlinx can't deserialize `Any`. */
@Serializable
data class SimpleSuccessResponse(val success: Boolean = true, val message: String? = null)

// MARK: - Full order detail (check panel)

/** GET /mobile/venues/:id/orders/:orderId — note the envelope key is `order`, not `data`. */
@Serializable
data class OrderDetailResponse(val success: Boolean = true, val order: OrderDetail? = null)

/**
 * The table's full check for the Square-style panel: sent items grouped by
 * course with their send time. In the table flow items are created exactly
 * when the round is fired, so [OrderDetailItem.createdAt] IS the send time.
 */
@Serializable
data class OrderDetail(
    val id: String,
    val orderNumber: String = "",
    val status: String = "",
    val paymentStatus: String = "",
    /** Pesos (major units). */
    val subtotal: Double = 0.0,
    val total: Double = 0.0,
    val items: List<OrderDetailItem> = emptyList(),
)

@Serializable
data class OrderDetailItem(
    val id: String,
    /** Null for custom-amount lines. Needed to re-route comanda reprints. */
    val productId: String? = null,
    val productName: String? = null,
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    val notes: String? = null,
    val modifiers: List<OrderDetailModifier> = emptyList(),
    /** Course/tiempo the line was fired under. Null = "Inmediato". */
    val course: String? = null,
    /** ISO timestamp — the moment the round was sent to the kitchen. */
    val createdAt: String? = null,
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
)

@Serializable
data class OrderDetailModifier(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
)
