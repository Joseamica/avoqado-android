package com.avoqado.pos.pos.data.model

import java.util.UUID
import kotlin.math.roundToInt

sealed class CartItemType {
    data class ProductItem(val productId: String) : CartItemType()
    data object CustomAmount : CartItemType()

    /** A prepaid credit pack (membresía) sold from the grid. Behaves like a custom
     *  amount for money/order purposes; the packId is carried so the credits can be
     *  granted to the attached customer on payment success. */
    data class CreditPack(val packId: String) : CartItemType()
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
    /** Snapshot line from an area ticket. These rows are server-priced and immutable in caja. */
    val areaTicketId: String? = null,
    val areaTicketLineId: String? = null,
    val locked: Boolean = false,

    // Promociones (combos, paquetes, 2x1) — mismo patrón que areaTicketId:
    // varias líneas PLANAS atadas por un id común, no modificadores dentro de
    // una sola línea.
    /** Instancia de promoción a la que pertenece esta línea. null = línea normal.
     *  Varias líneas comparten el mismo valor: es lo que las agrupa y lo que
     *  hace que se quiten juntas. Es también la llave de idempotencia que el
     *  server usa (`@@unique(orderId, instanceId)`). */
    val promotionInstanceId: String? = null,
    /** Para etiquetar "Combo del día" en el carrito sin volver al catálogo. */
    val promotionName: String? = null,
    /** Qué promoción del catálogo es. La Task 8 lo manda como
     *  `promotionRef.promotionId`: sin él la línea no se puede cobrar como
     *  promoción y el server la trataría como venta normal a precio de lista. */
    val promotionId: String? = null,
    /** Grupo y opción que representa ESTA línea. Agrupadas por
     *  [promotionInstanceId] reconstruyen el `selections: [{groupId, optionId}]`
     *  que el server necesita para volver a resolver el combo. Sin ellos las
     *  líneas se pueden agrupar pero no se pueden mandar. */
    val promotionGroupId: String? = null,
    val promotionOptionId: String? = null,

    // Customizations
    var selectedModifiers: List<SelectedModifier> = emptyList(),
    var itemNote: String? = null,
    var priceAdjustment: Int? = null, // cents — overrides unitPrice if set
    var isCortesia: Boolean = false,
    var cortesiaReason: String? = null,
    var itemDiscountId: String? = null,
    /**
     * Venta por peso (báscula): kg con 3 decimales. Cuando está presente, [unitPrice] es el precio
     * POR KG en centavos, [quantity] queda fija en 1 y el total de línea = round(weightKg × unitPrice)
     * (half-up, igual que el server). Una línea con peso JAMÁS se fusiona con otra (D9).
     */
    val weightKg: Double? = null,
) {
    val modifiersPrice: Int
        get() = selectedModifiers.sumOf { it.priceInCents } * quantity

    val effectiveUnitPrice: Int
        get() = priceAdjustment ?: unitPrice

    val totalPrice: Int
        get() = when {
            isCortesia -> 0
            // Peso: round HALF-UP al centavo (roundToInt = Math.round, ties hacia arriba) — misma
            // aritmética que el server (price × kg a 2 dec). quantity queda en 1 en líneas pesadas.
            weightKg != null ->
                (weightKg * effectiveUnitPrice).roundToInt() + selectedModifiers.sumOf { it.priceInCents }
            else -> (effectiveUnitPrice + selectedModifiers.sumOf { it.priceInCents }) * quantity
        }

    val modifiersSummary: String?
        get() {
            val names = selectedModifiers.map { it.modifierName }
            return if (names.isEmpty()) null else names.joinToString(", ")
        }

    /** "0.435 kg × $420.00/kg" — subtítulo de la línea pesada en carrito/recibo. Null si no hay peso. */
    val weightSummary: String?
        get() = weightKg?.let {
            "${formatWeightKg(it)} kg × $${String.format(java.util.Locale.US, "%.2f", effectiveUnitPrice / 100.0)}/kg"
        }

    /** Línea nacida de una promoción: no se edita suelta y se quita con sus hermanas. */
    val isPromotionLine: Boolean
        get() = promotionInstanceId != null

    val hasCustomizations: Boolean
        get() = selectedModifiers.isNotEmpty() ||
            itemNote != null ||
            priceAdjustment != null ||
            isCortesia
}
