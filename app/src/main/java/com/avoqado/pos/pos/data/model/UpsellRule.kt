package com.avoqado.pos.pos.data.model

import kotlinx.serialization.Serializable

/**
 * Upsell "¿Algo más?" — la tabla de reglas que el POS cachea y resuelve LOCALMENTE.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md
 *
 * 🔴 El POS NO le pregunta al server qué sugerir en el momento del cobro. Baja esta
 * tabla, la guarda, y al dar Cobrar la resuelve contra el carrito SIN RED. Eso es lo
 * que hace que el upsell siga funcionando en un apagón de WiFi, igual que el resto
 * del cobro.
 */
@Serializable
data class UpsellRule(
    val id: String,
    /** PRODUCT | CATEGORY | ALWAYS */
    val triggerType: String = "ALWAYS",
    val triggerProductIds: List<String> = emptyList(),
    val triggerCategoryIds: List<String> = emptyList(),
    val suggestedProductId: String,
    /** Gancho que lee el cliente. Null = se usa el nombre del producto. */
    val headline: String? = null,
    val priority: Int = 0,
    /**
     * Sólo en reglas nacidas de los datos del venue. Es LIFT, no confianza: la
     * confianza premia al producto ubicuo (si el 90% de los tickets llevan refresco,
     * "café → refresco" sale con 90% y no significa nada).
     */
    val lift: Double? = null,
    /** 0=domingo .. 6=sábado. Vacío = todos los días. */
    val daysOfWeek: List<Int> = emptyList(),
    /** "HH:mm" hora LOCAL del venue. */
    val timeFrom: String? = null,
    /** Si es MENOR que timeFrom, la ventana CRUZA medianoche (22:00–02:00). */
    val timeUntil: String? = null,
    val linkedDiscount: LinkedDiscount? = null,
)

/**
 * Descuento que acompaña la tarjeta. El `badge` viene YA FORMATEADO del server a
 * propósito: dos apps formateando el mismo descuento es la vía rápida a que Android
 * diga "-20%" y iOS "20% off".
 */
@Serializable
data class LinkedDiscount(
    val id: String,
    /** PERCENTAGE | FIXED_AMOUNT. COMP y 2x1 se rechazan al crear la regla. */
    val type: String,
    val value: Double,
    val badge: String,
)

/** Las tres perillas por venue. */
@Serializable
data class UpsellSurfaces(
    val counter: Boolean = true,
    val tableOrdering: Boolean = true,
    /**
     * 🔴 EXIGE RED. Ahí el producto se agrega a una orden que ya vive en el server;
     * sin red, un ADD_ITEMS rechazado con el cobro aplicado deja al local cobrando
     * de más.
     */
    val tablePaying: Boolean = true,
)

@Serializable
data class UpsellRulesPayload(
    val rules: List<UpsellRule> = emptyList(),
    val surfaces: UpsellSurfaces = UpsellSurfaces(),
    /** Porcentaje de momentos que NO ven tarjetas, para medir el aumento real. */
    val holdoutPercent: Int = 10,
)

@Serializable
data class UpsellRulesResponse(
    val success: Boolean = true,
    val data: UpsellRulesPayload = UpsellRulesPayload(),
)

/**
 * Una tarjeta ya resuelta, lista para pintar. El resolver produce esto; la pantalla
 * sólo lo dibuja.
 */
data class UpsellCard(
    val ruleId: String,
    val productId: String,
    val name: String,
    /** Precio YA con el descuento ligado aplicado. Debe coincidir con lo que se cobre. */
    val displayPriceCents: Int,
    val imageUrl: String?,
    /** Null = se usa `name`. */
    val headline: String?,
    /** Ya formateado por el server ("-20%", "-$15"). Null si no hay descuento. */
    val badge: String?,
    val linkedDiscountId: String?,
)
