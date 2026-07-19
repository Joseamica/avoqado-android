package com.avoqado.pos.pos.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Product(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("price") val priceValue: Double = 0.0,
    val priceType: String? = null,
    val imageUrl: String? = null,
    val color: String? = null,
    val categoryId: String? = null,
    val category: ProductCategory? = null,
    val variants: List<ProductVariant>? = null,
    val active: Boolean? = null,
    val featured: Boolean? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val gtin: String? = null,
    val type: String? = null,
    /**
     * Service duration in minutes, populated by the server for SERVICE / APPOINTMENTS_SERVICE /
     * CLASS products. Used by the reservations flow to seed the booking length; null on
     * non-bookable products like RETAIL.
     */
    val duration: Int? = null,
    val maxParticipants: Int? = null,
    val layoutConfig: JsonElement? = null,
    val modifierGroups: List<ProductModifierGroupEntry>? = null,
    val trackInventory: Boolean? = null,
    val availableQuantity: Int? = null,
) {
    val priceInCents: Int get() = (priceValue * 100).toInt()

    /**
     * Agotado SOLO cuando el venue rastrea inventario de ESTE producto y el
     * server calculó stock 0 (QUANTITY = currentStock, RECIPE = porciones).
     * Venues sin seguimiento (trackInventory false/null o availableQuantity
     * null) ordenan libre — el inventario es opcional por producto.
     */
    val isOutOfStock: Boolean
        get() = trackInventory == true && (availableQuantity ?: 1) <= 0

    val displayPrice: String get() = "$${String.format("%.2f", priceValue)}"

    val hasModifiers: Boolean
        get() = !modifierGroups.isNullOrEmpty() &&
            modifierGroups.any { it.group != null && it.group.modifiers.isNotEmpty() }

    /** Flattened modifier groups for UI consumption. */
    val sortedModifierGroups: List<ProductModifierGroup>
        get() = modifierGroups
            ?.mapNotNull { entry ->
                entry.group?.let { group ->
                    ProductModifierGroup(
                        id = group.id,
                        name = group.name,
                        required = group.required,
                        multipleSelect = group.allowMultiple,
                        minSelections = group.minSelections,
                        maxSelections = group.maxSelections,
                        sortOrder = entry.displayOrder,
                        modifiers = group.modifiers,
                    )
                }
            }
            ?.sortedWith(compareBy({ -(it.sortOrder ?: 0) }, { it.name }))
            ?: emptyList()
}

@Serializable
data class ProductCategory(
    val id: String,
    val name: String,
    val color: String? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class ProductVariant(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val sku: String? = null,
)

/**
 * Matches the server's join-table response: ProductModifierGroup → ModifierGroup.
 * Server returns: { id, productId, groupId, displayOrder, group: { ... } }
 */
@Serializable
data class ProductModifierGroupEntry(
    val id: String? = null,
    val groupId: String? = null,
    val displayOrder: Int? = null,
    val group: ModifierGroupData? = null,
)

/**
 * Matches the server's ModifierGroup model nested inside ProductModifierGroupEntry.
 */
@Serializable
data class ModifierGroupData(
    val id: String,
    val name: String,
    val required: Boolean = false,
    val allowMultiple: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val modifiers: List<Modifier> = emptyList(),
)

/**
 * Flattened modifier group for UI consumption (used by ModifierGroupSection, ProductDetailPanel).
 */
data class ProductModifierGroup(
    val id: String,
    val name: String,
    val required: Boolean = false,
    val multipleSelect: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val sortOrder: Int? = null,
    val modifiers: List<Modifier> = emptyList(),
)

@Serializable
data class Modifier(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val isDefault: Boolean = false,
    val sortOrder: Int? = null,
) {
    val priceInCents: Int get() = (price * 100).toInt()
}

data class SelectedModifier(
    val groupId: String,
    val groupName: String,
    val modifierId: String,
    val modifierName: String,
    val priceInCents: Int,
)

// Product catalog response
@Serializable
data class ProductsResponse(
    val success: Boolean = true,
    val message: String? = null,
    val data: List<Product> = emptyList(),
)

@Serializable
data class CategoriesResponse(
    val success: Boolean = true,
    val data: List<ProductCategory> = emptyList(),
)
