package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class PlanManagerTest {

    private val secureStorage = mockk<SecureStorage>()
    private val planManager = PlanManager(secureStorage)

    private fun stubPlan(tier: String?, exempt: Boolean = false) {
        every { secureStorage.planTier } returns tier
        every { secureStorage.planExempt } returns exempt
        every { secureStorage.planSnapshot } returns null
    }

    @Test
    fun `table service and offline hub respect exact paid access`() {
        stubPlan("PREMIUM")
        every { secureStorage.planSnapshot } returns PlanSnapshot(tier = "PREMIUM", accessSchemaVersion = 1, grantedFeatureCodes = emptyList())
        assertFalse(planManager.hasFeature("TABLE_SERVICE"))
        assertFalse(planManager.hasFeature("OFFLINE_LAN_HUB"))
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

    @Test
    fun `exact grants survive serialization and ignore missing old and unsupported snapshots`() {
        val json = Json { ignoreUnknownKeys = true }
        val paid = json.decodeFromString<PlanSnapshot>("""{"tier":"FREE","accessSchemaVersion":1,"accessObservedAt":"2026-09-27T10:00:00.000Z","grantedFeatureCodes":["CFDI"]}""")
        val restarted = json.decodeFromString<PlanSnapshot>(json.encodeToString(paid))
        stubPlan("FREE")
        every { secureStorage.planSnapshot } returns restarted
        assertTrue(planManager.hasFeature("CFDI"))
        assertFalse(planManager.hasFeature("INVENTORY_TRACKING"))
        assertTrue(planManager.hasFeature("ORDERS"))
        assertEquals(paid, retainNewestPlan(paid, null))
        assertEquals(paid, retainNewestPlan(paid, PlanSnapshot(tier = "PREMIUM")))
        assertEquals(paid, retainNewestPlan(paid, paid.copy(accessSchemaVersion = 2)))
        assertEquals(paid, retainNewestPlan(paid, paid.copy(accessObservedAt = "2026-09-27T09:00:00.000Z", grantedFeatureCodes = emptyList())))
        assertEquals(paid, retainNewestPlan(paid, paid.copy(accessObservedAt = "invalid")))
        val next = paid.copy(tier = "PREMIUM", accessObservedAt = "2026-09-27T11:00:00.000Z", grantedFeatureCodes = emptyList())
        every { secureStorage.planSnapshot } returns retainNewestPlan(paid, next)
        assertFalse(planManager.hasFeature("CFDI"))
    }

    // MARK: - Mesas y hub LAN (27-sep-2026): no estaban en el mapa y el default-allow
    // los regalaba en cualquier plan. Espejo de basePlan.service.ts por nombre exacto.

    @Test
    fun `TABLE_SERVICE is PRO`() {
        stubPlan(tier = "FREE")
        assertFalse(planManager.hasFeature("TABLE_SERVICE"))
        stubPlan(tier = "PRO")
        assertTrue(planManager.hasFeature("TABLE_SERVICE"))
        stubPlan(tier = "PREMIUM")
        assertTrue(planManager.hasFeature("TABLE_SERVICE"))
        assertEquals("Pro", planManager.requiredTierLabel("TABLE_SERVICE"))
    }

    @Test
    fun `OFFLINE_LAN_HUB is PREMIUM, not PRO`() {
        stubPlan(tier = "FREE")
        assertFalse(planManager.hasFeature("OFFLINE_LAN_HUB"))
        stubPlan(tier = "PRO")
        assertFalse(planManager.hasFeature("OFFLINE_LAN_HUB"))
        stubPlan(tier = "PREMIUM")
        assertTrue(planManager.hasFeature("OFFLINE_LAN_HUB"))
        assertEquals("Premium", planManager.requiredTierLabel("OFFLINE_LAN_HUB"))
    }

    @Test
    fun `exempt venue keeps tables and LAN hub on FREE`() {
        stubPlan(tier = "FREE", exempt = true)
        assertTrue(planManager.hasFeature("TABLE_SERVICE"))
        assertTrue(planManager.hasFeature("OFFLINE_LAN_HUB"))
    }

    @Test
    fun `unknown plan keeps tables and LAN hub (fail-open)`() {
        stubPlan(tier = null)
        assertTrue(planManager.hasFeature("TABLE_SERVICE"))
        assertTrue(planManager.hasFeature("OFFLINE_LAN_HUB"))
    }

    // MARK: - Pantalla de cocina (etapa 3, decisión D-A del 27-sep)

    @Test
    fun `KITCHEN_DISPLAY is PRO`() {
        stubPlan(tier = "FREE")
        assertFalse(planManager.hasFeature("KITCHEN_DISPLAY"))
        stubPlan(tier = "PRO")
        assertTrue(planManager.hasFeature("KITCHEN_DISPLAY"))
        assertEquals("Pro", planManager.requiredTierLabel("KITCHEN_DISPLAY"))
    }

    /**
     * Candado estructural: TODO código que la app consulta al plan tiene que estar en el mapa.
     * Un código que falte no truena — `hasFeature` lo deja pasar en cualquier plan (así se
     * regalaron `TABLE_SERVICE` y `OFFLINE_LAN_HUB`). Si agregas un gate nuevo, agrega su
     * código al mapa con el MISMO tier que `avoqado-server` `basePlan.service.ts`.
     */
    @Test
    fun `every feature code the app gates on is in the tier map`() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("No encontré src/main/java desde ${java.io.File(".").absolutePath}")
        val patterns = listOf(
            Regex("""hasFeature\("([A-Z0-9_]+)"\)"""),
            Regex("""requiresUpgrade\("([A-Z0-9_]+)"\)"""),
            Regex("""requiredTierLabel\("([A-Z0-9_]+)"\)"""),
            Regex("""FEATURE_CODE\s*=\s*"([A-Z0-9_]+)""""),
        )
        val used = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> patterns.flatMap { p -> p.findAll(file.readText()).map { it.groupValues[1] }.toList() } }
            .toSet()
        assertTrue("El barrido no encontró ningún código: la regex se rompió", used.size >= 5)
        val missing = used - PlanManager.FEATURE_REQUIRED_TIER.keys
        assertTrue("Códigos consultados al plan que NO están en el mapa (se regalan): $missing", missing.isEmpty())
    }
}
