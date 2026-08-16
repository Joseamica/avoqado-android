package com.avoqado.pos.pos.data.model

import kotlinx.serialization.Serializable

/**
 * Catálogo de promociones (combos, paquetes, 2x1) — cache-first, igual que
 * UpsellRule: se baja, se guarda, y el panel de cobro lo lee LOCALMENTE.
 *
 * Los nombres son los del server, verbatim. `quantity`/`chargedQuantity` son
 * lo que permite escribir "Entran 2, pagas 1"; `productPriceCents` es SÓLO
 * para el estimado en pantalla — el precio que se cobra lo calcula el server.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-2-brief.md
 */
@Serializable
data class PromotionOption(
    val id: String,
    val productId: String,
    val priceDeltaCents: Int = 0,
    val quantity: Int = 1,
    val chargedQuantity: Int = 1,
    val productName: String = "",
    /** Precio de lista, sólo para el estimado que se muestra. El precio real lo calcula el server. */
    val productPriceCents: Int = 0,
)

@Serializable
data class PromotionGroup(val id: String, val name: String, val options: List<PromotionOption> = emptyList())

@Serializable
data class Promotion(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val type: String = "BUNDLE",
    val pricingMode: String = "FIXED_TOTAL",
    val priceCents: Int = 0,
    /** Sólo en las próximas: a qué hora abre. */
    val startsAt: String? = null,
    val groups: List<PromotionGroup> = emptyList(),
) {
    /** Un grupo con varias opciones obliga a preguntar; si ninguno la tiene, entra directo. */
    val requiereEleccion: Boolean get() = groups.any { it.options.size > 1 }
}

@Serializable
data class PromotionsPayload(val active: List<Promotion> = emptyList(), val upcoming: List<Promotion> = emptyList())

/** Envoltura de red — GET mobile/venues/{venueId}/promotions. */
@Serializable
data class PromotionsResponse(
    val success: Boolean = true,
    val data: PromotionsPayload = PromotionsPayload(),
)
