package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan tiers, ranked. Mirrors the backend's base-plan tiers by EXACT name
 * (avoqado-server src/services/access/basePlan.service.ts). A name mismatch
 * fails silently, so never rename these.
 */
enum class PlanTier(val rank: Int, val displayLabel: String) {
    FREE(0, "Free"),
    PRO(1, "Pro"),
    PREMIUM(2, "Premium"),
    ENTERPRISE(3, "Enterprise"),
    ;

    companion object {
        /** Unknown/null raw values map to null → caller fails open. */
        fun fromStorage(raw: String?): PlanTier? {
            val normalized = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

/** Server observation persisted as one venue-scoped record, including exact paid capabilities. */
@Serializable
data class PlanSnapshot(
    val tier: String? = null,
    val grandfathered: Boolean = false,
    val exempt: Boolean = false,
    val accessSchemaVersion: Int? = null,
    val accessObservedAt: String? = null,
    val grantedFeatureCodes: List<String>? = null,
)

fun retainNewestPlan(previous: PlanSnapshot?, next: PlanSnapshot?): PlanSnapshot? {
    if (next == null || PlanTier.fromStorage(next.tier) == null) return previous
    if (next.accessSchemaVersion == null) return if (previous?.accessSchemaVersion == 1) previous else next
    if (next.accessSchemaVersion != 1) return previous
    val codes = next.grantedFeatureCodes ?: return previous
    if (codes.size > 100 || codes.distinct().size != codes.size || codes.any { !it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }) return previous
    val observed = runCatching { Instant.parse(next.accessObservedAt) }.getOrNull() ?: return previous
    val priorTime = runCatching { Instant.parse(previous?.accessObservedAt) }.getOrNull()
    return if (priorTime != null && !observed.isAfter(priorTime)) previous else next
}

/**
 * Client-side plan gating (Phase ① — UI gating with upsell, server enforcement
 * comes later). Reads the plan persisted from the venue-settings response.
 *
 * FAIL-OPEN IS THE LAW: if the plan is absent (old server), unknown, or never
 * fetched, every feature is allowed — exactly today's behavior. A gating bug
 * must never brick a POS during service.
 */
@Singleton
class PlanManager @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    /** Current venue's plan tier, or null when unknown (→ fail-open). */
    val tier: PlanTier?
        get() = PlanTier.fromStorage(secureStorage.planTier)

    /** Grandfathered legacy or demo venues bypass ALL gates and badges. */
    val isExempt: Boolean
        get() = secureStorage.planExempt

    /**
     * True when [featureCode] is available on the current plan.
     *
     * - exempt venue → true
     * - plan unknown/absent → true (fail-open)
     * - feature code not in the gated map → true (default-allow: orders,
     *   payments, menu, basic stock view, cash drawer, time clock, customers
     *   are FREE and never gated)
     * - otherwise → tier rank comparison (FREE < PRO < PREMIUM < ENTERPRISE)
     */
    fun hasFeature(code: String): Boolean {
        val requiredTier = FEATURE_REQUIRED_TIER[code] ?: return true
        val snapshot = secureStorage.planSnapshot
        if (snapshot?.exempt ?: isExempt) return true
        snapshot?.takeIf { it.accessSchemaVersion == 1 }?.let {
            return code in it.grantedFeatureCodes.orEmpty()
        }
        val currentTier = PlanTier.fromStorage(snapshot?.tier) ?: tier ?: return true
        return currentTier.rank >= requiredTier.rank
    }

    /**
     * True when the feature should render a tier badge / upsell teaser:
     * the feature is gated AND this venue's plan lacks it. Exempt venues
     * never see badges.
     */
    fun requiresUpgrade(code: String): Boolean = !hasFeature(code)

    /** Display label ("Pro"/"Premium") of the tier required for [code], or null if ungated. */
    fun requiredTierLabel(code: String): String? = FEATURE_REQUIRED_TIER[code]?.displayLabel

    companion object {
        // Mirror of the backend feature→tier map by EXACT code name.
        // PRO: RESERVATIONS, PROMOTIONS, REFERRAL_PROGRAM, ADVANCED_REPORTS.
        // PREMIUM: INVENTORY_TRACKING, CFDI.
        val FEATURE_REQUIRED_TIER: Map<String, PlanTier> = mapOf(
            "RESERVATIONS" to PlanTier.PRO,
            "TABLE_SERVICE" to PlanTier.PRO,
            "OFFLINE_LAN_HUB" to PlanTier.PREMIUM,
            "PROMOTIONS" to PlanTier.PRO,
            "REFERRAL_PROGRAM" to PlanTier.PRO,
            "ADVANCED_REPORTS" to PlanTier.PRO,
            "AREA_TICKETS" to PlanTier.PRO,
            "VARIABLE_WEIGHT_BARCODE" to PlanTier.PRO,
            // Etiquetas de precio para el anaquel (2026-09-23). Sin endpoint: se imprime en la
            // impresora del local, así que el candado vive sólo en el cliente. Espejo en iOS.
            "PRICE_LABELS" to PlanTier.PRO,
            "INVENTORY_TRACKING" to PlanTier.PREMIUM,
            "CFDI" to PlanTier.PREMIUM,
            "SCALE_INTEGRATION" to PlanTier.PREMIUM,
        )
    }
}
