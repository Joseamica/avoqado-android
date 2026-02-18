package com.avoqado.pos.inventory.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StockItem(
    val id: String,
    val productId: String,
    val productName: String,
    val sku: String? = null,
    val gtin: String? = null,
    val imageUrl: String? = null,
    val currentQuantity: Int = 0,
    val onHand: Double = 0.0,
    val available: Double = 0.0,
    val onOrder: Double = 0.0,
    val category: String? = null,
) {
    val initials: String
        get() = productName.take(2).uppercase()

    val isLowStock: Boolean
        get() = onHand > 0 && onHand <= 5
}

@Serializable
data class StockCount(
    val id: String,
    val name: String,
    val type: StockCountType = StockCountType.FULL,
    val status: String = "DRAFT",
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val createdBy: String? = null,
    val note: String? = null,
) {
    val statusDisplay: String
        get() = when (status) {
            "DRAFT" -> "Borrador"
            "IN_PROGRESS" -> "En progreso"
            "COMPLETED" -> "Completado"
            else -> status
        }
}

enum class StockCountType(
    val label: String,
    val description: String,
) {
    FULL("Conteo completo", "Contar todos los productos"),
    CYCLE("Conteo por ciclo", "Contar productos seleccionados"),
}

data class StockCountItem(
    val productId: String,
    val productName: String,
    val sku: String? = null,
    val gtin: String? = null,
    val expectedQuantity: Int = 0,
    var countedQuantity: Int? = null,
) {
    val variance: Int?
        get() = countedQuantity?.let { it - expectedQuantity }

    val hasVariance: Boolean
        get() = variance != null && variance != 0
}

enum class StockSortOption(val label: String) {
    NAME_ASC("Nombre A-Z"),
    NAME_DESC("Nombre Z-A"),
    STOCK_LOW("Menor existencia"),
    STOCK_HIGH("Mayor existencia"),
}

@Serializable
data class StockOverviewResponse(
    val success: Boolean = true,
    val data: List<StockItem> = emptyList(),
)

@Serializable
data class StockCountsResponse(
    val success: Boolean = true,
    val data: List<StockCount> = emptyList(),
)
