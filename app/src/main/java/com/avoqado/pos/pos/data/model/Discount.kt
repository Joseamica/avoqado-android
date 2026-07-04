package com.avoqado.pos.pos.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DiscountScope {
    ORDER,    // Applies to entire order subtotal
    ITEM,     // Only applies to products in targetItemIds
    CATEGORY, // Only applies to products in targetCategoryIds
}

enum class DiscountType {
    PERCENTAGE,
    FIXED,
}

@Serializable
data class Discount(
    val id: String,
    val name: String,
    val value: Double, // percentage (0-100) or fixed amount in currency
    val type: String = "PERCENTAGE", // "PERCENTAGE" or "FIXED"
    val scope: String = "ORDER", // "ORDER", "ITEM", "CATEGORY"
    val active: Boolean = true,
    val targetItemIds: List<String>? = null,
    val targetCategoryIds: List<String>? = null,
    /**
     * Provenance tag used by the cart to tell apart discounts the cashier
     * picked manually from those applied automatically by a feature flow
     * (e.g. `REFERRAL_NEW_CUSTOMER` from the referrals capture section).
     *
     * Optional / nullable + default null — purely a client-side hint, the
     * backend doesn't consume this column; safe for all serialization paths.
     */
    val source: String? = null,
) {
    val discountScope: DiscountScope
        get() = when (scope.uppercase()) {
            "ITEM" -> DiscountScope.ITEM
            "CATEGORY" -> DiscountScope.CATEGORY
            else -> DiscountScope.ORDER
        }

    val discountType: DiscountType
        get() = when (type.uppercase()) {
            "FIXED" -> DiscountType.FIXED
            else -> DiscountType.PERCENTAGE
        }

    val displayValue: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> "$${String.format("%.2f", value)}"
        }

    /**
     * Per-item applicability check used by the cart's item-level discount
     * picker. Mirrors iOS's `Discount.appliesToProduct` (CartModels.swift)
     * exactly:
     * - ITEM: applies if `targetItemIds` is empty/null (wildcard — applies
     *   to every product) OR contains `productId`.
     * - CATEGORY: same wildcard rule against `targetCategoryIds`.
     * - ORDER: always false — ORDER-scoped discounts belong to the
     *   checkout's order-level discount flow (see ShortcutsGridView.kt,
     *   which filters ORDER scope out before building the per-item picker),
     *   never to a single line item. The backend's
     *   `validateDiscountScopeForItem` now rejects the whole order if an
     *   ORDER-scoped discount is sent as a per-item discountId, so this
     *   picker must never offer one.
     */
    fun appliesTo(productId: String?, categoryId: String?): Boolean {
        return when (discountScope) {
            DiscountScope.ORDER -> false
            DiscountScope.ITEM -> {
                val targetIds = targetItemIds
                targetIds.isNullOrEmpty() || (productId != null && targetIds.contains(productId))
            }
            DiscountScope.CATEGORY -> {
                val targetIds = targetCategoryIds
                targetIds.isNullOrEmpty() || (categoryId != null && targetIds.contains(categoryId))
            }
        }
    }

    fun calculateDiscount(subtotalCents: Int): Int {
        return when (discountType) {
            DiscountType.PERCENTAGE -> (subtotalCents * value / 100.0).toInt()
            DiscountType.FIXED -> (value * 100).toInt().coerceAtMost(subtotalCents)
        }
    }
}

@Serializable
data class DiscountsResponse(
    val success: Boolean = true,
    val data: List<Discount> = emptyList(),
)

// MARK: - Coupon models (matching iOS CouponCode / CouponValidationResponse)

@Serializable
data class CouponCode(
    val id: String,
    val code: String,
    val name: String,
    val type: String = "PERCENTAGE",
    val value: Double = 0.0,
    val usesRemaining: Int? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val isValid: Boolean = true,
    val invalidReason: String? = null,
    val estimatedSavings: Double? = null,
) {
    val discountType: DiscountType
        get() = when (type.uppercase()) {
            "FIXED" -> DiscountType.FIXED
            else -> DiscountType.PERCENTAGE
        }

    val displayValue: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> "$${String.format("%.2f", value)}"
        }

    /** Convert to Discount for applying to cart. */
    fun toDiscount(): Discount = Discount(
        id = "coupon_$id",
        name = name,
        value = value,
        type = type,
        scope = "ORDER",
    )
}

@Serializable
data class CouponValidationResponse(
    val success: Boolean = true,
    val message: String? = null,
    val coupon: CouponCode? = null,
    val error: String? = null,
)

// MARK: - Create Product response

@Serializable
data class CreateProductResponse(
    val message: String? = null,
    val data: Product? = null,
)

@Serializable
data class CreateProductRequest(
    val name: String,
    val price: Double,
    val type: String = "FOOD_AND_BEV",
    val categoryId: String,
    val sku: String? = null,
    val gtin: String? = null,
    val duration: Int? = null,
    val maxParticipants: Int? = null,
)
