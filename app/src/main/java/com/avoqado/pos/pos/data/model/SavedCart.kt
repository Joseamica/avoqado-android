package com.avoqado.pos.pos.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedCart(
    val id: String,
    val name: String,
    val items: List<SavedCartItem>,
    val orderDiscount: Discount? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
}

@Serializable
data class SavedCartItem(
    val productId: String?,
    val name: String,
    val unitPrice: Int,
    val quantity: Int,
    val modifiers: List<SavedModifier> = emptyList(),
    val note: String? = null,
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
    val priceAdjustment: Int? = null,
    val itemDiscountId: String? = null,
)

@Serializable
data class SavedModifier(
    val groupId: String,
    val groupName: String,
    val modifierId: String,
    val modifierName: String,
    val priceInCents: Int,
)
