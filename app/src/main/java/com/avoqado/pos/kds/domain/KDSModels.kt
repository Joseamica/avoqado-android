package com.avoqado.pos.kds.domain

// MARK: - Domain Models

data class KDSOrder(
    val id: String,
    /**
     * El id de la ORDEN de venta, distinto del id de esta comanda. Es el que se necesita
     * para aceptar o rechazar el pedido en la app de delivery.
     */
    val orderId: String? = null,
    val orderNumber: String,
    val orderType: String,
    /**
     * ¿Falta que alguien acepte este pedido en la app de delivery?
     *
     * Sólo pasa en canales configurados en MANUAL: la venta entra pendiente porque NADIE le
     * ha dicho que sí al proveedor, y el plazo (~11.5 min en Uber) ya está corriendo. Si
     * nadie lo acepta, el proveedor lo cancela y el cliente se queda sin comida.
     */
    val needsAcceptance: Boolean = false,
    val items: List<KDSOrderItem>,
    val createdAt: Long,
    var status: KDSOrderStatus,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
)

data class KDSOrderItem(
    val id: String,
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
)

enum class KDSOrderStatus(val label: String) {
    NEW("Nuevo"),
    PREPARING("En preparacion"),
    READY("Listo"),
    COMPLETED("Completado"),
}

enum class KDSFilter(val label: String) {
    ALL("Todos"),
    NEW("Nuevos"),
    PREPARING("Prep"),
    READY("Listos"),
}

/**
 * Un canal de reparto tal como lo necesita el POS: nada de secretos ni configuración.
 *
 * `pausado=true` con `pausadoHasta=null` es la pausa INDEFINIDA que puso el dueño desde el
 * dashboard: se muestra, pero sin cuenta regresiva y sin botón de reanudar — desde el piso
 * no se reabre lo que el dueño cerró.
 */
data class CanalReparto(
    val id: String,
    val proveedor: String,
    val pausado: Boolean,
    val pausadoHasta: String?,
)
