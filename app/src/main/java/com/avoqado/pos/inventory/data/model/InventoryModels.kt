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
    /**
     * Cómo lleva su existencia este artículo. `RECIPE` significa que NO tiene
     * stock propio: se calcula desde sus ingredientes, y por eso el server
     * rechaza contarlo (`inventoryMethod: { not: 'RECIPE' }`).
     *
     * El server siempre lo mandó y la app lo ignoraba, así que el conteo ofrecía
     * artículos que era imposible contar.
     */
    val inventoryMethod: String? = null,
) {
    /** Se puede contar físicamente: los de receta no tienen existencia propia. */
    val isCountable: Boolean get() = inventoryMethod != "RECIPE"

    // UI compatibility: existing code references productName
    val productName: String get() = name

    // UI compatibility: existing code references currentQuantity
    val currentQuantity: Double get() = onHand

    /**
     * Existencia como la lee un humano, CON unidad y con los decimales que la
     * unidad merece. Antes truncaba a entero y sin sufijo: 8.065 kg de jamón se
     * mostraba "8", escondiendo 65 g. En un producto por peso el decimal es el
     * inventario, no un adorno.
     */
    val currentQuantityDisplay: String
        get() = formatInvQty(currentQuantity, unit) + unitSuffixOf(unit)

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
    CYCLE("Conteo cíclico", "Contar productos seleccionados"),
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
    // Ingredient (raw material) lines — null on product lines and on servers
    // older than the ingredient-counting rollout. `productId` carries the raw
    // material id as compat fallback, so switch on itemType, not on productId.
    val rawMaterialId: String? = null,
    val itemType: String? = null,
    /**
     * Cuándo se contó esta línea. **null = todavía NO se ha contado.**
     *
     * El server ya lo distinguía y la app lo ignoraba: un conteo a medias
     * mostraba "Contado 0" con diferencias de -98, -100, -84 en rojo, como si
     * faltara media bodega. Lo que faltaba era contar. Visto en la tablet
     * 2026-08-03 sobre un conteo EN PROGRESO.
     */
    val countedAt: String? = null,
) {
    val isIngredient: Boolean get() = itemType == "RAW_MATERIAL"

    /** Si esta línea ya se contó. Un 0 contado es un dato; un 0 sin contar, no. */
    val yaSeConto: Boolean get() = countedAt != null

    val expectedDisplay: String get() = formatInvQty(expected, unit) + unitSuffixOf(unit)
    val countedDisplay: String get() = formatInvQty(counted, unit) + unitSuffixOf(unit)
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


/**
 * Cuántos decimales tiene sentido enseñar según la unidad.
 *
 * En kilos y litros el tercer decimal es el gramo y el mililitro: redondear a
 * dos escondía existencia real (8.065 kg de jamón se leía "8.07", y truncando,
 * "8"). En piezas nadie quiere ver "47.00".
 */
private fun invDecimalsFor(unit: String?): Int = when (unit?.uppercase()) {
    "KILOGRAM", "LITER", "LITRE" -> 3
    else -> 2
}

/** "8.065 kg" · "2.5" · "47": decimales sólo los que hacen falta. */
fun formatInvQty(value: Double, unit: String? = null): String =
    String.format(java.util.Locale.US, "%.${invDecimalsFor(unit)}f", value)
        .trimEnd('0')
        .trimEnd('.')

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
val StockItem.onHandDisplay: String get() = formatInvQty(onHand, unit) + unitSuffixOf(unit)
