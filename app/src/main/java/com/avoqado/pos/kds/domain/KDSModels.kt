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
    /**
     * ¿Falta que un aparato reclame e imprima esta comanda?
     *
     * Sólo llega en `true` para pedidos de marketplace: los que manda un mesero desde una
     * tablet ya se imprimen en ese mismo gesto. Aquí no hay gesto humano — el pedido aparece
     * en todas las pantallas a la vez y alguien tiene que reclamar el trabajo.
     */
    val needsPrint: Boolean = false,
    val items: List<KDSOrderItem>,
    val createdAt: Long,
    var status: KDSOrderStatus,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    /** Folio de la comanda (spec 2026-09-27 §1): `sale:…`, `round:…`, `order:…`. `null` = Uber o fila vieja. */
    val sourceKey: String? = null,
    /** Estación a la que el servidor la repartió. `null` = «Sin estación»: sale en todas las pantallas. */
    val printStationId: String? = null,
)

data class KDSOrderItem(
    val id: String,
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
    /** Para RUTEAR el renglón a su estación. `null` = no supimos de qué producto es. */
    val productId: String? = null,
    val categoryId: String? = null,
)

/**
 * Etapa 3 del KDS (3.5, D8): una comanda guardada en ESTE aparato — llegó por el WiFi del local (antes de acusar) o se
 * marcó LISTO sin red (`listaEnMillis`). Espejo de `KdsTicketLocal` de iOS.
 */
data class KdsTicketLocal(
    val sourceKey: String,
    val venueId: String,
    val stationId: String,
    val orderNumber: String,
    val orderType: String,
    val items: List<KDSOrderItem>,
    val recibidaEnMillis: Long,
    val listaEnMillis: Long?,
)

enum class KDSOrderStatus(val label: String) {
    NEW("Nuevo"),
    PREPARING("En preparacion"),
    READY("Listo"),
    COMPLETED("Completado"),
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
