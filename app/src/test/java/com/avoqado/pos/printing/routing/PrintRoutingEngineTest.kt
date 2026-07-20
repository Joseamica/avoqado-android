package com.avoqado.pos.printing.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SHARED GOLDEN VECTORS — must stay identical to the server suite
 * (avoqado-server/tests/unit/services/printing/printRouting.engine.test.ts).
 * If a case changes here, change it there too: this is the anti-drift contract.
 */
class PrintRoutingEngineTest {

    private val cocina = "st_cocina"
    private val barra = "st_barra"
    private val defaultSt = "st_default"

    private fun cfg(
        defaultStationId: String? = null,
        active: Set<String> = setOf(cocina, barra, defaultSt),
    ) = RoutingConfig(defaultStationId = defaultStationId, activeStationIds = active)

    private fun item(
        orderItemId: String = "oi_1",
        productStationId: String? = null,
        categoryStationId: String? = null,
        productName: String = "Taco",
        quantity: Int = 1,
        modifiers: List<String> = emptyList(),
        notes: String? = null,
        productId: String = "p_1",
    ) = RoutingItemInput(
        orderItemId = orderItemId,
        productId = productId,
        productStationId = productStationId,
        categoryStationId = categoryStationId,
        productName = productName,
        quantity = quantity,
        modifiers = modifiers,
        notes = notes,
    )

    // ── cascade ────────────────────────────────────────────────────────
    @Test
    fun `product override wins over category default`() {
        assertEquals(barra, PrintRoutingEngine.resolveStationId(item(productStationId = barra, categoryStationId = cocina), cfg()))
    }

    @Test
    fun `category default applies when no product override`() {
        assertEquals(cocina, PrintRoutingEngine.resolveStationId(item(categoryStationId = cocina), cfg()))
    }

    @Test
    fun `venue default applies when neither product nor category resolve`() {
        assertEquals(defaultSt, PrintRoutingEngine.resolveStationId(item(), cfg(defaultStationId = defaultSt)))
    }

    @Test
    fun `returns null (unrouted) when nothing resolves and no default (I9 - NOT fail-open-to-all)`() {
        assertNull(PrintRoutingEngine.resolveStationId(item(), cfg()))
    }

    @Test
    fun `inactive station id falls through the cascade`() {
        assertEquals(cocina, PrintRoutingEngine.resolveStationId(item(productStationId = "st_gone", categoryStationId = cocina), cfg()))
        assertNull(PrintRoutingEngine.resolveStationId(item(categoryStationId = "st_gone"), cfg()))
    }

    @Test
    fun `an inactive default station is treated as no default`() {
        assertNull(PrintRoutingEngine.resolveStationId(item(), cfg(defaultStationId = "st_inactive_default")))
    }

    // ── grouping ───────────────────────────────────────────────────────
    @Test
    fun `splits an order into one ticket per station with only its items (AC1)`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "oi_taco1", productName = "Taco", quantity = 2, categoryStationId = cocina),
                item(orderItemId = "oi_cerveza", productName = "Cerveza", quantity = 1, categoryStationId = barra),
            ),
            cfg(),
        )
        assertEquals(2, plans.size)
        val cocinaPlan = plans.first { it.stationId == cocina }
        val barraPlan = plans.first { it.stationId == barra }
        assertEquals(listOf("Taco"), cocinaPlan.lines.map { it.productName })
        assertEquals(2, cocinaPlan.lines[0].quantity)
        assertEquals(listOf("Cerveza"), barraPlan.lines.map { it.productName })
        assertFalse(cocinaPlan.unrouted)
    }

    @Test
    fun `groups unrouted items into a SINGLE unrouted plan, never fanned out (I9)`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "a", productId = "p_a", productName = "Misterio1"),
                item(orderItemId = "b", productId = "p_b", productName = "Misterio2"),
            ),
            cfg(),
        )
        assertEquals(1, plans.size)
        assertNull(plans[0].stationId)
        assertTrue(plans[0].unrouted)
        assertEquals(2, plans[0].lines.size)
    }

    @Test
    fun `routes unrouted items to the venue default when one exists`() {
        val plans = PrintRoutingEngine.buildTicketPlans(listOf(item(productName = "Misterio")), cfg(defaultStationId = defaultSt))
        assertEquals(1, plans.size)
        assertEquals(defaultSt, plans[0].stationId)
        assertFalse(plans[0].unrouted)
    }

    // ── consolidation ──────────────────────────────────────────────────
    @Test
    fun `consolidates identical lines into Nx and preserves all source orderItemIds`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "oi_1", productName = "Taco", quantity = 1, categoryStationId = cocina),
                item(orderItemId = "oi_2", productName = "Taco", quantity = 3, categoryStationId = cocina),
            ),
            cfg(),
        )
        assertEquals(1, plans[0].lines.size)
        assertEquals(4, plans[0].lines[0].quantity)
        assertEquals(listOf("oi_1", "oi_2"), plans[0].lines[0].orderItemIds.sorted())
    }

    @Test
    fun `does NOT consolidate lines with different modifiers or notes`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "oi_1", productName = "Taco", modifiers = listOf("sin cebolla"), categoryStationId = cocina),
                item(orderItemId = "oi_2", productName = "Taco", modifiers = listOf("extra queso"), categoryStationId = cocina),
                item(orderItemId = "oi_3", productName = "Taco", notes = "bien dorado", categoryStationId = cocina),
            ),
            cfg(),
        )
        assertEquals(3, plans[0].lines.size)
    }

    @Test
    fun `empty-string productId is name-keyed (parity with TS), so two blank-id products do NOT merge`() {
        // Anti-drift edge case: "" must behave like absent (name-keyed), NOT collapse to a single "id:" bucket.
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "a", productId = "", productName = "A", categoryStationId = cocina),
                item(orderItemId = "b", productId = "", productName = "B", categoryStationId = cocina),
            ),
            cfg(),
        )
        assertEquals(1, plans.size)
        assertEquals(2, plans[0].lines.size) // A and B stay separate, not merged under one key
        assertEquals(listOf("A", "B"), plans[0].lines.map { it.productName })
    }

    @Test
    fun `treats modifier order as insignificant and sorts them deterministically`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "oi_1", productName = "Taco", modifiers = listOf("b", "a"), categoryStationId = cocina),
                item(orderItemId = "oi_2", productName = "Taco", modifiers = listOf("a", "b"), categoryStationId = cocina),
            ),
            cfg(),
        )
        assertEquals(1, plans[0].lines.size)
        assertEquals(2, plans[0].lines[0].quantity)
        assertEquals(listOf("a", "b"), plans[0].lines[0].modifiers)
    }

    // ── edge cases ─────────────────────────────────────────────────────
    @Test
    fun `returns no plans for an empty order`() {
        assertEquals(emptyList<TicketPlan>(), PrintRoutingEngine.buildTicketPlans(emptyList(), cfg()))
    }

    @Test
    fun `defensively skips zero or negative-quantity lines`() {
        val plans = PrintRoutingEngine.buildTicketPlans(
            listOf(
                item(orderItemId = "oi_ok", productName = "Taco", quantity = 2, categoryStationId = cocina),
                item(orderItemId = "oi_void", productName = "Taco", quantity = 0, categoryStationId = cocina),
            ),
            cfg(),
        )
        assertEquals(1, plans.size)
        assertEquals(2, plans[0].lines[0].quantity)
        assertEquals(listOf("oi_ok"), plans[0].lines[0].orderItemIds)
    }

    @Test
    fun `keeps an item routed with an explicit product station even when alone`() {
        val plans = PrintRoutingEngine.buildTicketPlans(listOf(item(productStationId = barra)), cfg())
        assertEquals(1, plans.size)
        assertEquals(barra, plans[0].stationId)
    }
}
