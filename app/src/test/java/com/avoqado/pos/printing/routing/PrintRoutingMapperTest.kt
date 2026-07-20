package com.avoqado.pos.printing.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintRoutingMapperTest {

    private val cocina = StationInfo(id = "st_cocina", name = "Cocina", active = true)
    private val barra = StationInfo(id = "st_barra", name = "Barra", active = true)
    private val config = PrintConfig(
        stations = listOf(cocina, barra),
        defaultStationId = null,
        categoryRouting = listOf(CategoryRoute("cat_food", "st_cocina"), CategoryRoute("cat_drinks", "st_barra")),
        productOverrides = listOf(ProductOverride("p_postre", "st_barra")),
    )

    @Test
    fun `toRoutingConfig keeps only active stations`() {
        val cfg = PrintRoutingMapper.toRoutingConfig(config.copy(stations = listOf(cocina, barra.copy(active = false))))
        assertEquals(setOf("st_cocina"), cfg.activeStationIds)
    }

    @Test
    fun `splits cart into cocina vs barra by category (AC1)`() {
        val plans = PrintRoutingMapper.buildComandas(
            listOf(
                RoutableItem("oi_1", "p_taco", "cat_food", "Taco", 2),
                RoutableItem("oi_2", "p_cerveza", "cat_drinks", "Cerveza", 1),
            ),
            config,
        )
        assertEquals(2, plans.size)
        val c = plans.first { it.stationId == "st_cocina" }
        val b = plans.first { it.stationId == "st_barra" }
        assertEquals(listOf("Taco"), c.lines.map { it.productName })
        assertEquals(2, c.lines[0].quantity)
        assertEquals(listOf("Cerveza"), b.lines.map { it.productName })
    }

    @Test
    fun `product override wins over category`() {
        // Flan's category routes to Cocina, but the product override sends it to Barra.
        val plans = PrintRoutingMapper.buildComandas(listOf(RoutableItem("oi_1", "p_postre", "cat_food", "Flan", 1)), config)
        assertEquals("st_barra", plans[0].stationId)
    }

    @Test
    fun `unrouted item with no default forms a single SIN ESTACION plan`() {
        val plans = PrintRoutingMapper.buildComandas(listOf(RoutableItem("oi_1", "p_x", "cat_unknown", "Misterio", 1)), config)
        assertEquals(1, plans.size)
        assertNull(plans[0].stationId)
        assertTrue(plans[0].unrouted)
    }

    @Test
    fun `stationName resolves an id and is null for the unrouted bucket`() {
        assertEquals("Cocina", PrintRoutingMapper.stationName("st_cocina", config))
        assertNull(PrintRoutingMapper.stationName(null, config))
    }
}
