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
    /**
     * Opciones de modificadores obligatorios YA resueltas por la regla —
     * elegidas en el dashboard al crear/editar (spec 2026-08-16, B3).
     *
     * 🔴 Vacío tiene DOS significados que este campo NO distingue: el producto
     * no pide nada, o el server degradó la regla en silencio porque el catálogo
     * cambió. El resolver local (`UpsellResolver`) trata ambos igual: si el
     * producto SÍ tiene un grupo obligatorio y esto llega vacío, la tarjeta se
     * descarta — nunca se asume "vacío = sin obligatorios" a ciegas.
     *
     * El server la fuerza a `[]` en sus tres caminos de falla (nunca null), pero
     * el default sigue siendo defensivo: si algún día llega null, el POS no
     * debe reventar.
     */
    val suggestedModifiers: List<ResolvedModifier> = emptyList(),
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

/**
 * Una opción de modificador obligatorio YA resuelta por la regla. Nombre y
 * precio vienen calculados del server (spec 2026-08-16, B3) — NUNCA se
 * recalculan a mano en el POS.
 */
@Serializable
data class ResolvedModifier(
    val groupId: String,
    val modifierId: String,
    /** Para pintar la tarjeta sin ir al catálogo. */
    val name: String,
    /** En PESOS, no centavos — igual que el resto de este DTO y que `Modifier.price`. */
    val price: Double,
) {
    val priceInCents: Int get() = (price * 100).toInt()
}

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
    /**
     * Precio YA con el descuento ligado Y los modificadores obligatorios
     * resueltos. Debe coincidir EXACTO con lo que se cobre — ver
     * `CounterUpsellAcceptor`.
     */
    val displayPriceCents: Int,
    val imageUrl: String?,
    /** Null = se usa `name`. */
    val headline: String?,
    /** Ya formateado por el server ("-20%", "-$15"). Null si no hay descuento. */
    val badge: String?,
    /**
     * 🔴 El descuento ligado COMPLETO, no sólo su id.
     *
     * Con el id solo, el carrito no puede calcular nada: se cobraba precio de
     * lista mientras la tarjeta prometía el precio rebajado. La línea necesita
     * `type` y `value` para congelar el mismo descuento que el server va a
     * aplicar — ver [CartItem.itemDiscountType].
     */
    val linkedDiscount: LinkedDiscount? = null,
    /**
     * Modificadores obligatorios YA resueltos (spec 2026-08-16, B3). Vacío = el
     * producto no pide nada. Es lo que arma la línea del carrito al aceptar —
     * ver `CounterUpsellAcceptor.accept()`.
     */
    val modifiers: List<ResolvedModifier> = emptyList(),
) {
    /**
     * `displayPriceCents` en PESOS. COMPUTADO, no guardado — así los dos NUNCA
     * pueden divergir, ni siquiera si algún día alguien hace `.copy(displayPriceCents = …)`
     * sin pasar por `toCard()`.
     */
    val priceWithModifiers: Double get() = displayPriceCents / 100.0

    /** Atajo para los consumidores que sólo necesitan el id. */
    val linkedDiscountId: String? get() = linkedDiscount?.id
}
