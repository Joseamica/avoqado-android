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
    /// Walk-in class link + attached customer — dropped on save before, so a
    /// restored cart lost its reservation link and its customer.
    val reservationId: String? = null,
    val attachedCustomerId: String? = null,
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
    /// El descuento congelado de la línea. Sin él, un carrito guardado con "-20%"
    /// volvía a precio de lista: se cobraba de más y la orden quedaba rebajada.
    /// Mismo defecto que tuvieron `packId` y la promoción. Nullable con default
    /// para que los carritos guardados por versiones viejas sigan abriendo.
    val itemDiscountType: String? = null,
    val itemDiscountValue: Double? = null,
    val itemDiscountName: String? = null,
    /// Credit-pack (membresía) identity — before, a saved pack line restored
    /// as a plain CustomAmount: charged, never granted, gate bypassed.
    val packId: String? = null,
    /// Promoción de la que nació la línea. Sin esto un carrito guardado con un
    /// 2x1 se restauraba como dos productos sueltos a precio de lista: se cobra
    /// de más y el combo no llega al server. Mismo defecto que tenía `packId`.
    val promotionInstanceId: String? = null,
    val promotionName: String? = null,
    val promotionId: String? = null,
    val promotionGroupId: String? = null,
    val promotionOptionId: String? = null,
)

@Serializable
data class SavedModifier(
    val groupId: String,
    val groupName: String,
    val modifierId: String,
    val modifierName: String,
    val priceInCents: Int,
)
