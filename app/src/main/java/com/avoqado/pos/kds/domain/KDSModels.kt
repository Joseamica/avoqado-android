package com.avoqado.pos.kds.domain

// MARK: - Domain Models

data class KDSOrder(
    val id: String,
    val orderNumber: String,
    val orderType: String,
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
