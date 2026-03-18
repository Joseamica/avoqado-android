package com.avoqado.pos.articles.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

// MARK: - Enums

enum class ArticleSection(val label: String, val iconName: String) {
    PRODUCTS("Productos", "inventory_2"),
    CATEGORIES("Categorías", "category"),
    MODIFIERS("Modificadores", "tune"),
    DISCOUNTS("Descuentos", "local_offer"),
    COUPONS("Cupones", "confirmation_number"),
    CREDIT_PACKS("Paquetes de crédito", "card_giftcard"),
}

enum class ProductType(val label: String) {
    REGULAR("Regular"),
    FOOD_AND_BEV("Alimentos y bebidas"),
    APPOINTMENTS_SERVICE("Servicio de cita"),
    OTHER("Otro"),
}

enum class PriceType {
    FIXED,
    VARIABLE,
}

enum class InventoryMethod(val label: String, val description: String) {
    QUANTITY("Por cantidad", "Controla el inventario por unidades disponibles"),
    RECIPE("Por receta", "Descuenta ingredientes según la receta del producto"),
}

enum class ModifierInventoryMode(val label: String, val description: String) {
    ADDITION("Adición", "Agrega ingredientes al descontar inventario"),
    SUBSTITUTION("Sustitución", "Reemplaza ingredientes al descontar inventario"),
}

enum class MeasurementUnit(val label: String, val abbreviation: String) {
    UNIT("Unidad", "u"),
    PIECE("Pieza", "pza"),
    KILOGRAM("Kilogramo", "kg"),
    GRAM("Gramo", "g"),
    LITER("Litro", "L"),
    MILLILITER("Mililitro", "ml"),
    OUNCE("Onza", "oz"),
    POUND("Libra", "lb"),
    DOZEN("Docena", "doc"),
}

enum class DiscountType(val label: String) {
    PERCENTAGE("Porcentaje"),
    FIXED("Monto fijo"),
    COMP("Cortesía"),
}

enum class DiscountScope(val label: String) {
    ORDER("Orden"),
    ITEM("Artículo"),
    CATEGORY("Categoría"),
    MODIFIER("Modificador"),
    MODIFIER_GROUP("Grupo de modificadores"),
    CUSTOMER_GROUP("Grupo de clientes"),
    QUANTITY("Cantidad"),
}

// MARK: - Category Models

@Serializable
data class ArticleCategory(
    val id: String,
    val name: String = "",
    val color: String? = null,
    val description: String? = null,
    val displayOrder: Int? = null,
    val active: Boolean? = null,
    @SerialName("_count") val count: CategoryCount? = null,
) {
    val productCount: Int get() = count?.products ?: 0
}

@Serializable
data class CategoryCount(
    val products: Int = 0,
)

// MARK: - Product Models

@Serializable
data class ArticleProduct(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val type: String? = null,
    val sku: String? = null,
    val gtin: String? = null,
    val price: Double? = null,
    val cost: Double? = null,
    val priceType: String? = null,
    val taxRate: Double? = null,
    val active: Boolean? = null,
    val trackInventory: Boolean? = null,
    val inventoryMethod: String? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    val category: ArticleCategory? = null,
    val modifierGroups: List<ProductModifierGroupJoin>? = null,
) {
    val initials: String
        get() = name.take(2).uppercase(Locale.US)

    val displayPrice: String
        get() = when {
            priceType == "VARIABLE" -> "Variable"
            price != null -> "$${String.format(Locale.US, "%.2f", price)}"
            else -> "—"
        }

    val productType: ProductType
        get() = when (type) {
            "REGULAR" -> ProductType.REGULAR
            "FOOD_AND_BEV" -> ProductType.FOOD_AND_BEV
            "APPOINTMENTS_SERVICE" -> ProductType.APPOINTMENTS_SERVICE
            "OTHER" -> ProductType.OTHER
            else -> ProductType.REGULAR
        }
}

@Serializable
data class ProductModifierGroupJoin(
    val id: String,
    val groupId: String? = null,
    val displayOrder: Int? = null,
    val group: ModifierGroup? = null,
)

// MARK: - Modifier Group & Modifier Models

@Serializable
data class ModifierGroup(
    val id: String,
    val name: String = "",
    val required: Boolean = false,
    val allowMultiple: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val active: Boolean? = null,
    val modifiers: List<ArticleModifier>? = null,
) {
    val modifierCount: Int get() = modifiers?.size ?: 0
}

/**
 * Named ArticleModifier (NOT Modifier) to avoid collision with androidx.compose.ui.Modifier.
 */
@Serializable
data class ArticleModifier(
    val id: String,
    val name: String = "",
    val price: Double? = null,
    val active: Boolean? = null,
    val rawMaterialId: String? = null,
    val inventoryMode: String? = null,
    val quantityPerUnit: Double? = null,
    val unit: String? = null,
) {
    val displayPrice: String
        get() = if (price != null && price != 0.0) {
            "+$${String.format(Locale.US, "%.2f", price)}"
        } else {
            ""
        }
}

// MARK: - Discount Models

@Serializable
data class AdminDiscount(
    val id: String,
    val name: String = "",
    val type: String = "PERCENTAGE",
    val value: Double = 0.0,
    val scope: String = "ORDER",
    val active: Boolean? = null,
    val requiresApproval: Boolean? = null,
) {
    val discountType: DiscountType
        get() = when (type) {
            "PERCENTAGE" -> DiscountType.PERCENTAGE
            "FIXED_AMOUNT" -> DiscountType.FIXED
            "COMP" -> DiscountType.COMP
            else -> DiscountType.PERCENTAGE
        }

    val discountScope: DiscountScope
        get() = when (scope) {
            "ORDER" -> DiscountScope.ORDER
            "ITEM" -> DiscountScope.ITEM
            "CATEGORY" -> DiscountScope.CATEGORY
            "MODIFIER" -> DiscountScope.MODIFIER
            "MODIFIER_GROUP" -> DiscountScope.MODIFIER_GROUP
            "CUSTOMER_GROUP" -> DiscountScope.CUSTOMER_GROUP
            "QUANTITY" -> DiscountScope.QUANTITY
            else -> DiscountScope.ORDER
        }

    val formattedValue: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "${String.format(Locale.US, "%.0f", value)}%"
            DiscountType.FIXED -> "$${String.format(Locale.US, "%.2f", value)}"
            DiscountType.COMP -> "Cortesía"
        }

    val emoji: String
        get() {
            val lower = name.lowercase(Locale.US)
            return when {
                lower.contains("cumpleaños") || lower.contains("birthday") -> "🎂"
                lower.contains("empleado") || lower.contains("staff") -> "👤"
                lower.contains("vip") || lower.contains("cliente") -> "⭐"
                lower.contains("happy hour") || lower.contains("hora feliz") -> "🍹"
                lower.contains("cortesía") || lower.contains("cortesia") || discountType == DiscountType.COMP -> "🎁"
                lower.contains("promo") || lower.contains("oferta") -> "🏷️"
                else -> "💰"
            }
        }
}

// MARK: - Coupon Models

@Serializable
data class AdminCoupon(
    val id: String,
    val discountId: String = "",
    val code: String = "",
    val maxUses: Int? = null,
    val maxUsesPerCustomer: Int? = null,
    val currentUses: Int? = null,
    val minPurchaseAmount: Double? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val active: Boolean? = null,
    val discount: AdminDiscount? = null,
) {
    val isExpired: Boolean
        get() {
            val until = validUntil ?: return false
            return try {
                val expiry = java.time.LocalDateTime.parse(
                    until,
                    java.time.format.DateTimeFormatter.ISO_DATE_TIME,
                )
                expiry.isBefore(java.time.LocalDateTime.now())
            } catch (_: Exception) {
                false
            }
        }

    val usageText: String
        get() {
            val uses = currentUses ?: 0
            return if (maxUses != null) {
                "$uses / $maxUses usos"
            } else {
                "$uses usos"
            }
        }
}

// MARK: - Credit Pack Models

@Serializable
data class CreditPack(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val price: Double = 0.0,
    val currency: String? = null,
    val validityDays: Int? = null,
    val maxPerCustomer: Int? = null,
    val active: Boolean? = null,
    val displayOrder: Int? = null,
    val items: List<CreditPackItem>? = null,
) {
    val displayPrice: String
        get() = "$${String.format(Locale.US, "%.2f", price)}"

    val itemCount: Int
        get() = items?.size ?: 0
}

@Serializable
data class CreditPackItem(
    val id: String,
    val productId: String = "",
    val quantity: Int = 1,
    val product: CreditPackItemProduct? = null,
)

@Serializable
data class CreditPackItemProduct(
    val id: String,
    val name: String = "",
)

// MARK: - Raw Material & Recipe Models

@Serializable
data class RawMaterial(
    val id: String,
    val name: String = "",
    val sku: String? = null,
    val category: String? = null,
    val currentStock: Double? = null,
    val unit: String? = null,
    val costPerUnit: Double? = null,
    val active: Boolean? = null,
)

@Serializable
data class Recipe(
    val id: String,
    val productId: String = "",
    val portionYield: Double? = null,
    val totalCost: Double? = null,
    val prepTime: Int? = null,
    val cookTime: Int? = null,
    val notes: String? = null,
    val lines: List<RecipeLine>? = null,
)

@Serializable
data class RecipeLine(
    val id: String,
    val rawMaterialId: String = "",
    val quantity: Double = 0.0,
    val unit: String? = null,
    val isOptional: Boolean? = null,
    val substituteNotes: String? = null,
    val rawMaterial: RawMaterial? = null,
)
