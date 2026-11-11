package com.avoqado.pos.printing.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // MARK: - «No lleva comanda» (10-nov-2026)

    private val tienda = StationInfo(id = "st_tienda", name = "Tienda", active = true, sendsComanda = false)
    private val conTienda = config.copy(
        stations = listOf(cocina, barra, tienda),
        categoryRouting = config.categoryRouting + CategoryRoute("cat_tienda", "st_tienda"),
    )
    private val takis = RoutableItem("oi_t", "p_takis", "cat_tienda", "Takis", 1)
    private val cafe = RoutableItem("oi_c", "p_cafe", "cat_drinks", "Café", 1)

    @Test
    fun omite_sin_comanda_si_se_pide() {
        val ruteo = PrintRoutingMapper.rutear(listOf(takis), conTienda, omitirSinComanda = true)
        assertTrue(ruteo.plans.isEmpty())
        assertEquals(1, ruteo.omitidasSinComanda)
        assertTrue(PrintRoutingMapper.esSinComanda("st_tienda", conTienda))
    }

    @Test
    fun sin_omitir_es_como_hoy() {
        val ruteo = PrintRoutingMapper.rutear(listOf(takis), conTienda, omitirSinComanda = false)
        assertEquals(listOf("st_tienda"), ruteo.plans.map { it.stationId })
        assertEquals(0, ruteo.omitidasSinComanda)
        assertEquals(ruteo.plans, PrintRoutingMapper.buildComandas(listOf(takis), conTienda))
    }

    /** Review Focus 1: venta mixta café + Takis — el café sale en Barra y sólo Barra cuenta. */
    @Test
    fun mezcla_cafe_y_tienda() {
        val ruteo = PrintRoutingMapper.rutear(listOf(cafe, takis), conTienda, omitirSinComanda = true)
        assertEquals(listOf("st_barra"), ruteo.plans.map { it.stationId })
        assertEquals(listOf("Café"), ruteo.plans.single().lines.map { it.productName })
        assertEquals(1, ruteo.omitidasSinComanda)
    }

    /** Una estación desactivada no es estación: lo suyo sigue la cascada y, sin default, cae en «SIN ESTACIÓN» y avisa. */
    @Test
    fun sin_comanda_inactiva_no_cuenta() {
        val inactiva = conTienda.copy(stations = listOf(cocina, barra, tienda.copy(active = false)))
        val ruteo = PrintRoutingMapper.rutear(listOf(takis), inactiva, omitirSinComanda = true)
        assertTrue(ruteo.plans.single().unrouted)
        assertEquals(0, ruteo.omitidasSinComanda)
        assertFalse(PrintRoutingMapper.esSinComanda("st_tienda", inactiva))
        assertFalse(PrintRoutingMapper.esSinComanda(null, conTienda))
        assertFalse(PrintRoutingMapper.esSinComanda("st_cocina", conTienda))
    }

    /** Servidor anterior (sin el campo) o config guardada de antes ⇒ la estación SÍ lleva comanda. */
    @Test
    fun config_sin_campo_es_true() {
        val json = """{"stations":[{"id":"st_cocina","name":"Cocina","active":true}],"version":"v1"}"""
        val leida = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(PrintConfig.serializer(), json)
        assertTrue(leida.stations.single().sendsComanda)
        assertFalse(PrintRoutingMapper.esSinComanda("st_cocina", leida))
    }
}
