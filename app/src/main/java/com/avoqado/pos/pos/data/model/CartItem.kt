package com.avoqado.pos.pos.data.model

import java.util.UUID

sealed class CartItemType {
    data class ProductItem(val productId: String) : CartItemType()
    data object CustomAmount : CartItemType()
}

data class CartItem(
    val id: String = UUID.randomUUID().toString(),
    val type: CartItemType,
    val name: String,
    val subtitle: String? = null,
    val unitPrice: Int, // in cents
    var quantity: Int = 1,
    val imageUrl: String? = null,
    val colorHex: String? = null,
    val categoryId: String? = null,

    // Customizations
    var selectedModifiers: List<SelectedModifier> = emptyList(),
    var itemNote: String? = null,
    var priceAdjustment: Int? = null, // cents — overrides unitPrice if set
    var isCortesia: Boolean = false,
    var cortesiaReason: String? = null,
    var itemDiscountId: String? = null,
) {
    val modifiersPrice: Int
        get() = selectedModifiers.sumOf { it.priceInCents } * quantity

    val effectiveUnitPrice: Int
        get() = priceAdjustment ?: unitPrice

    val totalPrice: Int
        get() = if (isCortesia) {
            0
        } else {
            (effectiveUnitPrice + selectedModifiers.sumOf { it.priceInCents }) * quantity
        }

    val modifiersSummary: String?
        get() {
            val names = selectedModifiers.map { it.modifierName }
            return if (names.isEmpty()) null else names.joinToString(", ")
        }

    val hasCustomizations: Boolean
        get() = selectedModifiers.isNotEmpty() ||
            itemNote != null ||
            priceAdjustment != null ||
            isCortesia
}
