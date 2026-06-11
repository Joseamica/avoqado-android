package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanManagerTest {

    private val secureStorage = mockk<SecureStorage>()
    private val planManager = PlanManager(secureStorage)

    private fun stubPlan(tier: String?, exempt: Boolean = false) {
        every { secureStorage.planTier } returns tier
        every { secureStorage.planExempt } returns exempt
    }

    // MARK: - Fail-open (THE LAW: a gating bug must never brick a POS)

    @Test
    fun `null tier (old server or never fetched) allows everything`() {
        stubPlan(tier = null)
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertTrue(planManager.hasFeature("PROMOTIONS"))
        assertTrue(planManager.hasFeature("REFERRAL_PROGRAM"))
        assertTrue(planManager.hasFeature("ADVANCED_REPORTS"))
        assertTrue(planManager.hasFeature("INVENTORY_TRACKING"))
        assertTrue(planManager.hasFeature("CFDI"))
    }

    @Test
    fun `unknown tier string allows everything`() {
        stubPlan(tier = "GOLD_PLATINUM")
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertTrue(planManager.hasFeature("INVENTORY_TRACKING"))
    }

    @Test
    fun `blank tier string allows everything`() {
        stubPlan(tier = "   ")
        assertTrue(planManager.hasFeature("RESERVATIONS"))
    }

    @Test
    fun `unknown feature code allows on any tier (default-allow)`() {
        stubPlan(tier = "FREE")
        assertTrue(planManager.hasFeature("ORDERS"))
        assertTrue(planManager.hasFeature("PAYMENTS"))
        assertTrue(planManager.hasFeature("SOME_FUTURE_CODE"))
    }

    @Test
    fun `null tier reports no upgrade needed (no badges on fail-open)`() {
        stubPlan(tier = null)
        assertFalse(planManager.requiresUpgrade("RESERVATIONS"))
        assertFalse(planManager.requiresUpgrade("INVENTORY_TRACKING"))
    }

    // MARK: - Exempt venues (grandfathered legacy / demo) bypass all gates

    @Test
    fun `exempt FREE venue has every feature`() {
        stubPlan(tier = "FREE", exempt = true)
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertTrue(planManager.hasFeature("PROMOTIONS"))
        assertTrue(planManager.hasFeature("REFERRAL_PROGRAM"))
        assertTrue(planManager.hasFeature("ADVANCED_REPORTS"))
        assertTrue(planManager.hasFeature("INVENTORY_TRACKING"))
        assertTrue(planManager.hasFeature("CFDI"))
    }

    @Test
    fun `exempt venue shows no badges`() {
        stubPlan(tier = "FREE", exempt = true)
        assertFalse(planManager.requiresUpgrade("RESERVATIONS"))
        assertFalse(planManager.requiresUpgrade("INVENTORY_TRACKING"))
    }

    // MARK: - FREE tier: Pro and Premium features gated

    @Test
    fun `FREE lacks all PRO features`() {
        stubPlan(tier = "FREE")
        assertFalse(planManager.hasFeature("RESERVATIONS"))
        assertFalse(planManager.hasFeature("PROMOTIONS"))
        assertFalse(planManager.hasFeature("REFERRAL_PROGRAM"))
        assertFalse(planManager.hasFeature("ADVANCED_REPORTS"))
    }

    @Test
    fun `FREE lacks all PREMIUM features`() {
        stubPlan(tier = "FREE")
        assertFalse(planManager.hasFeature("INVENTORY_TRACKING"))
        assertFalse(planManager.hasFeature("CFDI"))
    }

    @Test
    fun `FREE requires upgrade for gated features`() {
        stubPlan(tier = "FREE")
        assertTrue(planManager.requiresUpgrade("RESERVATIONS"))
        assertTrue(planManager.requiresUpgrade("CFDI"))
        assertFalse(planManager.requiresUpgrade("ORDERS"))
    }

    // MARK: - PRO tier: Pro features yes, Premium features no

    @Test
    fun `PRO has all PRO features`() {
        stubPlan(tier = "PRO")
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertTrue(planManager.hasFeature("PROMOTIONS"))
        assertTrue(planManager.hasFeature("REFERRAL_PROGRAM"))
        assertTrue(planManager.hasFeature("ADVANCED_REPORTS"))
    }

    @Test
    fun `PRO lacks PREMIUM features`() {
        stubPlan(tier = "PRO")
        assertFalse(planManager.hasFeature("INVENTORY_TRACKING"))
        assertFalse(planManager.hasFeature("CFDI"))
    }

    // MARK: - PREMIUM tier: everything gated so far

    @Test
    fun `PREMIUM has PRO and PREMIUM features`() {
        stubPlan(tier = "PREMIUM")
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertTrue(planManager.hasFeature("PROMOTIONS"))
        assertTrue(planManager.hasFeature("REFERRAL_PROGRAM"))
        assertTrue(planManager.hasFeature("ADVANCED_REPORTS"))
        assertTrue(planManager.hasFeature("INVENTORY_TRACKING"))
        assertTrue(planManager.hasFeature("CFDI"))
    }

    // MARK: - ENTERPRISE tier: superset of everything

    @Test
    fun `ENTERPRISE has every gated feature`() {
        stubPlan(tier = "ENTERPRISE")
        PlanManager.FEATURE_REQUIRED_TIER.keys.forEach { code ->
            assertTrue("ENTERPRISE should have $code", planManager.hasFeature(code))
        }
    }

    // MARK: - Tier parsing

    @Test
    fun `tier parsing is case-insensitive and trimmed`() {
        stubPlan(tier = "  pro ")
        assertEquals(PlanTier.PRO, planManager.tier)
        assertTrue(planManager.hasFeature("RESERVATIONS"))
        assertFalse(planManager.hasFeature("CFDI"))
    }

    @Test
    fun `tier ranks are strictly ordered`() {
        assertTrue(PlanTier.FREE.rank < PlanTier.PRO.rank)
        assertTrue(PlanTier.PRO.rank < PlanTier.PREMIUM.rank)
        assertTrue(PlanTier.PREMIUM.rank < PlanTier.ENTERPRISE.rank)
    }

    // MARK: - requiredTierLabel (for badges / upsell copy)

    @Test
    fun `requiredTierLabel maps gated codes to display labels`() {
        assertEquals("Pro", planManager.requiredTierLabel("RESERVATIONS"))
        assertEquals("Pro", planManager.requiredTierLabel("PROMOTIONS"))
        assertEquals("Pro", planManager.requiredTierLabel("REFERRAL_PROGRAM"))
        assertEquals("Pro", planManager.requiredTierLabel("ADVANCED_REPORTS"))
        assertEquals("Premium", planManager.requiredTierLabel("INVENTORY_TRACKING"))
        assertEquals("Premium", planManager.requiredTierLabel("CFDI"))
    }

    @Test
    fun `requiredTierLabel is null for ungated codes`() {
        assertNull(planManager.requiredTierLabel("ORDERS"))
    }
}
