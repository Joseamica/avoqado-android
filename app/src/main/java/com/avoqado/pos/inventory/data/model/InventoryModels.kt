package com.avoqado.pos.inventory.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class StockItem(
    val id: String,
    val name: String = "",
    val sku: String? = null,
    val gtin: String? = null,
    val imageUrl: String? = null,
    val onHand: Double = 0.0,
    val available: Double = 0.0,
    val onOrder: Double = 0.0,
    val categoryName: String? = null,
    val unit: String? = null,
) {
    // UI compatibility: existing code references productName
    val productName: String get() = name

    // UI compatibility: existing code references currentQuantity
    val currentQuantity: Double get() = onHand

    val currentQuantityDisplay: String
        get() = if (currentQuantity == currentQuantity.toLong().toDouble()) {
            currentQuantity.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", currentQuantity)
        }

    // UI compatibility: existing code references category
    val category: String? get() = categoryName

    val initials: String
        get() = name.take(2).uppercase()

    val isLowStock: Boolean
        get() = onHand > 0 && onHand <= 5
}

@Serializable
data class StockCount(
    val id: String,
    val type: StockCountType = StockCountType.FULL,
    val status: String = "IN_PROGRESS",
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val createdBy: String? = null,
    val note: String? = null,
    val items: List<StockCountItem> = emptyList(),
) {
    val statusDisplay: String
        get() = when (status) {
            "DRAFT" -> "Borrador"
            "IN_PROGRESS" -> "En progreso"
            "COMPLETED" -> "Completado"
            else -> status
        }
}

@Serializable
enum class StockCountType(val label: String, val description: String) {
    FULL("Conteo completo", "Contar todos los productos"),
    CYCLE("Conteo ciclico", "Contar productos seleccionados"),
}

@Serializable
data class StockCountItem(
    val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val sku: String? = null,
    val gtin: String? = null,
    val imageUrl: String? = null,
    val expected: Double = 0.0,
    var counted: Double = 0.0,
    var difference: Double = 0.0,
    /// Measurement unit — was dropped when building count items, so quantities
    /// rendered as bare numbers ("5") ambiguous between kg/L/piezas.
    val unit: String? = null,
) {
    val expectedDisplay: String get() = formatInvQty(expected) + unitSuffixOf(unit)
    val countedDisplay: String get() = formatInvQty(counted) + unitSuffixOf(unit)
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
    val items: List<StockItem> = emptyList(),
    val pagination: PaginationInfo? = null,
)

@Serializable
data class StockCountsResponse(
    val success: Boolean = true,
    val counts: List<StockCount> = emptyList(),
)

@Serializable
data class CreateStockCountResponse(
    val success: Boolean = true,
    val count: StockCount? = null,
)

@Serializable
data class UpdateStockCountResponse(
    val success: Boolean = true,
)

@Serializable
data class PaginationInfo(
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pageCount: Int = 1,
)


/** "2.5"/"5" formatting: decimals only when needed. */
fun formatInvQty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", value)

/** Short Spanish unit suffix ("kg","g","L"…). Empty for count/piece units. */
fun unitSuffixOf(unit: String?): String = when (unit?.uppercase()) {
    "KILOGRAM" -> " kg"
    "GRAM" -> " g"
    "LITER", "LITRE" -> " L"
    "MILLILITER", "MILLILITRE" -> " ml"
    "METER", "METRE" -> " m"
    "POUND" -> " lb"
    "OUNCE" -> " oz"
    else -> ""
}

/** StockItem on-hand with unit, no truncation ("2.5 kg" not "2"). */
val StockItem.onHandDisplay: String get() = formatInvQty(onHand) + unitSuffixOf(unit)
