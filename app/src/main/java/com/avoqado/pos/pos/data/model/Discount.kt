package com.avoqado.pos.pos.data.model
import com.avoqado.pos.core.util.formatMoney

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

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
    /**
     * Descuento máximo del catálogo, en PESOS (`null` o ausente = sin tope). El servidor lo aplica siempre
     * (`calculateDiscountPesos`: `min(descuento, tope)`); sin leerlo aquí la caja cobraba de menos y la venta quedaba
     * debiendo. La línea del carrito lo congela en [CartItem.itemDiscountMax].
     */
    val maxDiscountAmount: Double? = null,
    /**
     * El código si este descuento salió de un cupón (D5, 5-nov). Viaja al crear la venta: el SERVIDOR lo revalida, calcula el monto y
     * liga la fila, y el uso cuenta al quedar pagada. Sin él se quedaba como un descuento libre y el cupón nunca contaba.
     */
    val couponCode: String? = null,
) {
    val discountScope: DiscountScope
        get() = when (scope.uppercase()) {
            "ITEM" -> DiscountScope.ITEM
            "CATEGORY" -> DiscountScope.CATEGORY
            else -> DiscountScope.ORDER
        }

    /**
     * 🔴 `FIXED_AMOUNT` es lo que manda el server — su enum sólo tiene
     * `PERCENTAGE | FIXED_AMOUNT | COMP` (`schema.prisma`). Reconocer sólo
     * `"FIXED"` hacía que un descuento de **$15 se leyera como 15%**, porque
     * cualquier otra cosa cae en el `else` porcentual. iOS ya aceptaba las dos
     * ortografías (`CartModels.swift:551`); Android no, y por eso divergían.
     */
    val discountType: DiscountType
        get() = when (type.uppercase()) {
            "FIXED", "FIXED_AMOUNT" -> DiscountType.FIXED
            else -> DiscountType.PERCENTAGE
        }

    val displayValue: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> formatMoney(value)
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
        // Espejo de `calcularDescuentoExactoCents` del servidor: todo en BigDecimal (con Double, 375¢ × 9.2 % daba 34 por
        // 3449.9999…), el tope del catálogo (pesos → centavos) ANTES de redondear, centavos half-up al final, y nunca más que la base.
        val base = BigDecimal(subtotalCents.coerceAtLeast(0))
        val valor = BigDecimal.valueOf(value)
        var bruto = when (discountType) {
            DiscountType.PERCENTAGE -> base.multiply(valor).divide(BigDecimal(100))
            DiscountType.FIXED -> valor.multiply(BigDecimal(100)).min(base)
        }
        maxDiscountAmount?.let { bruto = bruto.min(BigDecimal.valueOf(it).multiply(BigDecimal(100))) }
        return bruto.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).toInt().coerceAtMost(subtotalCents.coerceAtLeast(0))
    }
}

/**
 * D2 (descuento del cliente): el PIN de un gerente para `discounts:apply`, atado a ESE cliente, ESE descuento y hasta [topeCents]
 * (el monto que vio el gerente; el servidor lo emitió con `descuento-cliente:<customerId>:<discountId>:<topeCents>`). Vive en el
 * carrito y viaja con la venta (carrito guardado, foto, cola sin red): el servidor lo acepta 7 días, cada venta hasta su tope.
 */
@Serializable
data class AutorizacionDelDescuento(
    val token: String,
    val topeCents: Int,
    val customerId: String,
    val discountId: String,
)

/**
 * D13: recorta el descuento [brutoCents] al descuento máximo del catálogo ([maxPesos], en PESOS; `null` = sin tope):
 * `min(bruto, round(maxPesos × 100))`, redondeo half-up como el resto de la aritmética. Un tope en 0 deja el descuento en 0,
 * igual que el servidor. La única fuente del tope: la usan [descuentoDeLineaCents] y [Discount.calculateDiscount].
 */
internal fun conTopeEnCents(brutoCents: Int, maxPesos: Double?): Int =
    if (maxPesos == null) brutoCents
    else minOf(brutoCents.toLong(), Math.round(maxPesos * 100)).coerceAtLeast(0L).toInt()

/**
 * El descuento, EN CENTAVOS, que el server le aplicaría a una línea cuyo total
 * —producto + modificadores, ya multiplicado por la cantidad— es [baseCents].
 *
 * 🔴 ESPEJO EXACTO de `calculateDiscountPesos`
 * (`avoqado-server/src/services/shared/discount.service.ts`), que el server llama
 * sobre `itemTotal` en `order.mobile.service.ts`. Es la ÚNICA fuente de esta
 * aritmética en el POS: la usan la línea del carrito ([CartItem.itemDiscountCents])
 * y la tarjeta de upsell (`UpsellResolver.toCard`), justamente para que no puedan
 * divergir — que el cliente vea un precio y se le cobre otro es el bug que esto
 * existe para impedir.
 *
 * Dos detalles que costaron dinero:
 *  - **HALF-UP, no truncado.** El server hace `Math.round(x * 100) / 100`.
 *    Truncar desviaba un centavo en cada línea con decimales.
 *  - **El monto fijo es PLANO por línea**, no por unidad: el server aplica
 *    `roundPesos(value)` una sola vez sobre el total de la línea.
 *
 * Un tipo desconocido devuelve 0 — se cobra completo, que es la dirección segura:
 * nunca inventar un descuento que el server no va a reconocer.
 *
 * D13: [maxPesos] es el descuento máximo del catálogo (`Discount.maxDiscountAmount`, en pesos). Tras calcular el bruto
 * se recorta a `round(maxPesos × 100)` —igual que el server: un tope en 0 deja el descuento en 0— y luego el clamp a la
 * línea. `null` = sin tope.
 */
fun descuentoDeLineaCents(type: String?, value: Double?, baseCents: Int, maxPesos: Double? = null): Int {
    val valor = value ?: return 0
    if (baseCents <= 0) return 0
    val bruto = when (type?.uppercase()) {
        "PERCENTAGE" -> Math.round(baseCents * valor / 100.0).toInt()
        "FIXED", "FIXED_AMOUNT" -> Math.round(valor * 100).toInt()
        // El server trata COMP como "toda la línea" (100%).
        "COMP" -> baseCents
        else -> 0
    }
    return conTopeEnCents(bruto, maxPesos).coerceIn(0, baseCents)
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
    /** Descuento máximo del catálogo, en pesos: sin él, la caja cobraría de menos (como [Discount.maxDiscountAmount]). */
    val maxDiscountAmount: Double? = null,
) {
    /** Misma razón que en [Discount.discountType]: el server dice `FIXED_AMOUNT`. */
    val discountType: DiscountType
        get() = when (type.uppercase()) {
            "FIXED", "FIXED_AMOUNT" -> DiscountType.FIXED
            else -> DiscountType.PERCENTAGE
        }

    val displayValue: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "${value.toInt()}%"
            DiscountType.FIXED -> formatMoney(value)
        }

    /** Convert to Discount for applying to cart. */
    fun toDiscount(): Discount = Discount(
        id = "coupon_$id",
        // El mismo nombre que le pone el SERVIDOR a la fila de la orden: lo que ve la cajera es lo que sale en el reporte.
        name = "$name ($code)",
        value = value,
        type = type,
        scope = "ORDER",
        maxDiscountAmount = maxDiscountAmount,
        couponCode = code,
    )
}

/**
 * 🔴 La respuesta REAL de `POST /mobile/venues/:id/coupons/validate` (`coupon.mobile.controller.ts`): todo viene en `data` y el descuento
 * va anidado. Con `coupon` en la raíz (como antes) se leía siempre `null`: todo cupón válido salía «Cupon no valido» (D5, 5-nov).
 */
@Serializable
data class CouponValidationResponse(
    val success: Boolean = true,
    val message: String? = null,
    val data: CouponValidationData? = null,
)

@Serializable
data class CouponValidationData(
    val valid: Boolean = false,
    val coupon: CuponDelServidor? = null,
    val error: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class CuponDelServidor(
    val id: String,
    val code: String,
    val discount: DescuentoDelCupon,
) {
    fun aCoupon() = CouponCode(
        id = id,
        code = code,
        name = discount.name,
        // Una cortesía regala la base entera: para el carrito es un 100 %. El SERVIDOR pide su permiso al crear la venta.
        type = if (discount.type.equals("COMP", ignoreCase = true)) "PERCENTAGE" else discount.type,
        value = if (discount.type.equals("COMP", ignoreCase = true)) 100.0 else discount.value,
        maxDiscountAmount = discount.maxDiscountAmount,
    )
}

@Serializable
data class DescuentoDelCupon(
    val id: String,
    val name: String,
    val type: String,
    val value: Double,
    val scope: String = "ORDER",
    val maxDiscountAmount: Double? = null,
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
