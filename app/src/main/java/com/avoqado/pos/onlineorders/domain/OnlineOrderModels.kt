package com.avoqado.pos.onlineorders.domain

import java.time.Instant

/** Estados del pedido en línea según el SERVIDOR; lo que no conocemos es [UNKNOWN] (nunca una excepción). */
enum class OnlineOrderStatus {
    PENDING_ACCEPTANCE, ACCEPTED, READY, COMPLETED, REJECTED, EXPIRED, CANCELLED_BY_CUSTOMER, CANCELLED_BY_STAFF, UNKNOWN;

    companion object {
        fun from(raw: String?): OnlineOrderStatus =
            entries.firstOrNull { it != UNKNOWN && it.name == raw?.trim() } ?: UNKNOWN
    }
}

data class OnlineOrderModifier(val name: String, val quantity: Int)

data class OnlineOrderItem(
    val productId: String?,
    val categoryId: String?,
    val name: String,
    val quantity: Int,
    val note: String?,
    val modifiers: List<OnlineOrderModifier>,
)

/** Dinero en centavos enteros: la bandeja manda pesos y se convierten con decimal exacto. */
data class OnlineOrder(
    val orderId: String,
    val onlineOrderId: String,
    val orderNumber: String,
    val status: OnlineOrderStatus,
    val paymentMode: String,
    val paymentStatus: String,
    val customerName: String?,
    val customerPhone: String?,
    val note: String?,
    val createdAt: Instant,
    val acceptDeadlineAt: Instant?,
    val estimatedReadyAt: Instant?,
    val totalCents: Long,
    val remainingCents: Long,
    val needsPrint: Boolean,
    val items: List<OnlineOrderItem>,
)

/** [pending] y [active] vienen en TODA respuesta (conteos exactos del servidor, aunque la lista sea de otro filtro). */
data class Inbox(
    val serverTime: Instant,
    val pending: Int,
    val active: Int,
    val done: Int,
    val orders: List<OnlineOrder>,
    val storeOff: Boolean,
    val truncated: Boolean,
    val nextCursor: String?,
)

sealed interface AccionResultado {
    data object Hecho : AccionResultado
    data class YaNoDisponible(val status: OnlineOrderStatus) : AccionResultado
    data object SinConexion : AccionResultado
    data class Error(val mensaje: String) : AccionResultado
}
