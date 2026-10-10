package com.avoqado.pos.tables.domain

import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.FloorPlanArea
import com.avoqado.pos.tables.data.FloorPlanElement
import com.avoqado.pos.tables.data.FloorPlanPayload
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.domain.FloorLayoutBuilder.SIN_AREA_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Cómo se arma el plano del POS (spec 2026-10-09 §4.2-4.3). Espejo: avoqado-ios `FloorLayoutTests`. */
class FloorLayoutTest {

    private fun mesa(
        id: String,
        areaId: String? = null,
        x: Float? = null,
        y: Float? = null,
        capacity: Int = 4,
        areaName: String? = null,
        areaSortOrder: Int? = null,
        areaFloorShape: String? = null,
    ) = DiningTable(
        id = id, number = id.removePrefix("t"), capacity = capacity, positionX = x, positionY = y, areaId = areaId,
        areaName = areaName, areaSortOrder = areaSortOrder, areaFloorShape = areaFloorShape,
    )

    private val plano = FloorPlanPayload(
        floorPlanVersion = "p1",
        areas = listOf(FloorPlanArea("a2", "Terraza", "TALL", 1), FloorPlanArea("a1", "Salón", null, 0)),
        elements = listOf(
            FloorPlanElement(id = "e1", type = "WALL", areaId = "a1", positionX = 0.0, positionY = 0.0, endX = 1.0, endY = 0.0),
            FloorPlanElement(id = "e2", type = "LABEL", areaId = null, positionX = 0.1, positionY = 0.9, label = "VIP"),
        ),
    )

    private val t1 = mesa("t1", areaId = "a1", x = 0.25f, y = 0.4f)
    private val t2 = mesa("t2", x = 0.5f, y = 0.5f) // con posición y sin área
    private val t3 = mesa("t3", areaId = "a2") // sin posición
    private val t4 = mesa("t4", areaId = "borrada", x = 0.3f, y = 0.3f) // su área ya no existe
    private val mesas = listOf(t1, t2, t3, t4)

    @Test
    fun `las pestanas siguen el orden del dueno y empatan por nombre`() {
        val layout = FloorLayoutBuilder.build(mesas, plano)
        assertEquals(listOf("a1", "a2", SIN_AREA_KEY), layout.tabs.map { it.key })
        assertEquals(listOf("Salón", "Terraza", "Sin área"), layout.tabs.map { it.title })
        val empate = FloorPlanPayload(areas = listOf(FloorPlanArea("b", "Barra", null, 0), FloorPlanArea("a", "azotea", null, 0)))
        assertEquals(listOf("a", "b"), FloorLayoutBuilder.build(emptyList(), empate).tabs.map { it.key })
    }

    @Test
    fun `un area con acento empata como en el dashboard`() {
        // El dashboard: `a.name.localeCompare(b.name, 'es')` ⇒ ['Área común', 'azotea', 'Barra'] (node, 9-oct). Por código
        // de punto «Área común» (Á = U+00C1) caería al final.
        val empate = FloorPlanPayload(
            areas = listOf(FloorPlanArea("b", "Barra", null, 0), FloorPlanArea("a2", "Área común", null, 0), FloorPlanArea("a1", "azotea", null, 0)),
        )
        assertEquals(listOf("Área común", "azotea", "Barra"), FloorLayoutBuilder.build(emptyList(), empate).tabs.map { it.title })
    }

    @Test
    fun `con el plano truncado un elemento de un area cortada no se dibuja`() {
        // overLimit: el servidor mandó sólo las primeras 30 áreas. Lo de un área cortada no cae en la primera pestaña.
        val truncado = FloorPlanPayload(
            floorPlanVersion = "p1",
            areas = listOf(FloorPlanArea("a1", "Salón", null, 0)),
            elements = listOf(
                FloorPlanElement(id = "e1", type = "WALL", areaId = "a1", endX = 1.0, endY = 0.0),
                FloorPlanElement(id = "e2", type = "WALL", areaId = "cortada", endX = 1.0, endY = 0.0),
                FloorPlanElement(id = "e3", type = "LABEL", areaId = null, label = "VIP"),
            ),
            overLimit = true,
        )
        assertEquals(listOf("e1", "e3"), FloorLayoutBuilder.build(listOf(t1), truncado).elementsByTab["a1"]?.map { it.id })
    }

    @Test
    fun `una posicion de seis decimales cae exacta en su cuadro`() {
        // 0.525f como Double es 0.52499997…: sin redondear antes, 0.525 × 40 daba 20.999999 (iOS y el dashboard: 21.0).
        val placed = FloorLayoutBuilder.build(listOf(mesa("t1", areaId = "a1", x = 0.525f, y = 0.4f)), plano).placedByTab["a1"]!!.single()
        assertEquals(21.0, placed.cx, 0.0)
        assertEquals(10.0, placed.cy, 0.0)
    }

    @Test
    fun `con el plano atrasado y el area con otra forma sus elementos no se dibujan`() {
        // Guardado «p1»: Salón ancho (null) y Terraza larga. Las mesas anuncian «p2» y dicen que la Terraza ahora es
        // CUADRADA: las coordenadas 0–1 viejas de su barra caerían en otro lugar. El Salón no cambió de forma.
        val guardado = plano.copy(
            elements = listOf(
                FloorPlanElement(id = "e1", type = "WALL", areaId = "a1", endX = 1.0, endY = 0.0),
                FloorPlanElement(id = "e3", type = "BAR_COUNTER", areaId = "a2", positionX = 0.1, positionY = 0.1, width = 0.2, height = 0.1),
            ),
        )
        val enSalon = mesa("t1", areaId = "a1", x = 0.5f, y = 0.5f, areaName = "Salón", areaSortOrder = 0, areaFloorShape = null)
        val enTerraza = mesa("t2", areaId = "a2", x = 0.5f, y = 0.5f, areaName = "Terraza", areaSortOrder = 1, areaFloorShape = "SQUARE")
        val layout = FloorLayoutBuilder.build(listOf(enSalon, enTerraza), guardado, tablesFloorPlanVersion = "p2")
        assertEquals(listOf("e1"), layout.elementsByTab["a1"]?.map { it.id })
        assertNull(layout.elementsByTab["a2"])
    }

    @Test
    fun `una mesa con posicion y sin area va a la pestana Sin area`() {
        val layout = FloorLayoutBuilder.build(mesas, plano)
        assertEquals(listOf(PlacedTable(t2, 20.0, 12.5, 4.0, 4.0, 0)), layout.placedByTab[SIN_AREA_KEY])
        // Sin mesas así, no hay pestaña «Sin área» en la tablet.
        assertEquals(listOf("a1", "a2"), FloorLayoutBuilder.build(listOf(t1, t3), plano).tabs.map { it.key })
    }

    @Test
    fun `sin acomodar son las mesas sin posicion o con un area que ya no existe`() {
        assertEquals(listOf(t3, t4), FloorLayoutBuilder.build(mesas, plano).unplaced)
    }

    @Test
    fun `si el plano guardado esta atrasado las areas salen de las mesas`() {
        // /tables ya anuncia el plano «p2» (la Terraza ahora es CUADRADA y hay un Patio nuevo), pero /floor-plan falló y el
        // guardado sigue en «p1» (auditoría Codex C6).
        val enTerraza = mesa("t5", areaId = "a2", x = 0.5f, y = 0.5f, areaName = "Terraza", areaSortOrder = 1, areaFloorShape = "SQUARE")
        val enPatio = mesa("t6", areaId = "a9", x = 0.5f, y = 0.5f, areaName = "Patio", areaSortOrder = 2, areaFloorShape = "WIDE")
        val layout = FloorLayoutBuilder.build(listOf(enTerraza, enPatio), plano, tablesFloorPlanVersion = "p2")
        assertEquals(listOf("a2", "a9"), layout.tabs.map { it.key })
        assertEquals("SQUARE", layout.tabs.first { it.key == "a2" }.floorShape)
        assertEquals(emptyList<DiningTable>(), layout.unplaced) // el Patio nuevo NO cae en «sin acomodar»
        assertEquals(listOf(PlacedTable(enTerraza, 20.0, 20.0, 4.0, 4.0, 0)), layout.placedByTab["a2"])
        // e1 es de un área que ya no se ve y e2 no tiene área: con el plano atrasado no se adivinan.
        assertEquals(0, layout.elementsByTab.size)
    }

    @Test
    fun `con el plano al dia manda el plano`() {
        assertEquals(FloorLayoutBuilder.build(mesas, plano), FloorLayoutBuilder.build(mesas, plano, tablesFloorPlanVersion = "p1"))
    }

    @Test
    fun `las posiciones se convierten a cuadros de su area`() {
        val enTerraza = mesa("t9", areaId = "a2", x = 0.5f, y = 0.5f, capacity = 8)
        val layout = FloorLayoutBuilder.build(listOf(t1, enTerraza), plano)
        assertEquals(listOf(PlacedTable(t1, 10.0, 10.0, 4.0, 4.0, 0)), layout.placedByTab["a1"])
        assertEquals(listOf(PlacedTable(enTerraza, 12.5, 20.0, 6.0, 6.0, 0)), layout.placedByTab["a2"])
    }

    @Test
    fun `un elemento sin area cae en la primera area`() {
        val layout = FloorLayoutBuilder.build(mesas, plano)
        assertEquals(
            listOf(
                FloorGeometry.CellElement("e1", "WALL", 0.0, 0.0, null, null, 0, 40.0, 0.0),
                FloorGeometry.CellElement("e2", "LABEL", 4.0, 22.5, label = "VIP"),
            ),
            layout.elementsByTab["a1"],
        )
        assertNull(layout.elementsByTab["a2"])
    }

    @Test
    fun `hay plano si hay una mesa con posicion o un elemento`() {
        assertFalse(FloorLayoutBuilder.build(listOf(t3), FloorPlanPayload(areas = plano.areas)).hasPlan)
        assertTrue(FloorLayoutBuilder.build(listOf(t3), plano).hasPlan)
        assertTrue(FloorLayoutBuilder.build(listOf(t1), FloorPlanPayload(areas = plano.areas)).hasPlan)
        assertFalse(FloorLayoutBuilder.build(emptyList(), null).hasPlan)
    }

    @Test
    fun `sin plano del servidor las pestanas salen de las mesas`() {
        val patio = mesa("t1", areaId = "x", x = 0.5f, y = 0.5f, areaName = "Patio", areaSortOrder = 2, areaFloorShape = "SQUARE")
        val bar = mesa("t2", areaId = "y", areaName = "Bar", areaSortOrder = 1)
        val layout = FloorLayoutBuilder.build(listOf(patio, bar), null)
        assertEquals(listOf(FloorTab("y", "Bar", null), FloorTab("x", "Patio", "SQUARE")), layout.tabs)
        assertEquals(listOf(PlacedTable(patio, 20.0, 20.0, 4.0, 4.0, 0)), layout.placedByTab["x"])
    }

    @Test
    fun `la pestana recordada se respeta y si ya no existe gana la primera`() {
        val tabs = FloorLayoutBuilder.build(mesas, plano).tabs
        assertEquals("a2", FloorLayoutBuilder.pickActiveTab(tabs, "a2"))
        assertEquals("a1", FloorLayoutBuilder.pickActiveTab(tabs, "area-borrada"))
        assertEquals("a1", FloorLayoutBuilder.pickActiveTab(tabs, null))
        assertNull(FloorLayoutBuilder.pickActiveTab(emptyList(), "a1"))
    }

    @Test
    fun `el telefono agrupa por area con las mismas pestanas`() {
        val conPatio = plano.copy(areas = plano.areas + FloorPlanArea("a3", "Patio", "WIDE", 2))
        val layout = FloorLayoutBuilder.build(mesas, conPatio)
        assertEquals(listOf("a1", "a2", "a3", SIN_AREA_KEY), layout.listTabs.map { it.key })
        assertEquals(listOf(t1), layout.listByTab["a1"])
        assertEquals(listOf(t3), layout.listByTab["a2"])
        assertNull(layout.listByTab["a3"])
        assertEquals(listOf(t2, t4), layout.listByTab[SIN_AREA_KEY])
    }

    @Test
    fun `la etiqueta para lectores de pantalla dice numero estado y tiempo`() {
        val inicio = "2026-10-09T12:00:00.000Z"
        val ahora = { minutos: Long -> Instant.parse(inicio).toEpochMilli() + minutos * 60_000L }
        val ocupada = DiningTable(id = "t7", number = "7", openOrders = listOf(OpenCheckSummary(id = "o1", createdAt = inicio)))
        assertEquals("Mesa 7, ocupada, 12 minutos", FloorLayoutBuilder.tableAccessibilityLabel(ocupada, ahora(12)))
        assertEquals("Mesa 7, ocupada, 1 minuto", FloorLayoutBuilder.tableAccessibilityLabel(ocupada, ahora(1)))
        assertEquals("Mesa 7, ocupada, 1 hora y 15 minutos", FloorLayoutBuilder.tableAccessibilityLabel(ocupada, ahora(75)))
        assertEquals("Mesa 7, ocupada, 2 horas", FloorLayoutBuilder.tableAccessibilityLabel(ocupada, ahora(120)))
        assertEquals("Mesa 7, ocupada, 2 días", FloorLayoutBuilder.tableAccessibilityLabel(ocupada, ahora(2 * 24 * 60)))
        val dos = ocupada.copy(openOrders = listOf(OpenCheckSummary(id = "o1"), OpenCheckSummary(id = "o2")))
        assertEquals("Mesa 7, ocupada, 2 cuentas", FloorLayoutBuilder.tableAccessibilityLabel(dos, ahora(5)))
        assertEquals("Mesa 7, libre, 4 personas", FloorLayoutBuilder.tableAccessibilityLabel(DiningTable(id = "t7", number = "7", capacity = 4), 0))
        assertEquals("Mesa 7, libre, 1 persona", FloorLayoutBuilder.tableAccessibilityLabel(DiningTable(id = "t7", number = "7", capacity = 1), 0))
        assertEquals("Mesa 7, libre", FloorLayoutBuilder.tableAccessibilityLabel(DiningTable(id = "t7", number = "7", capacity = 0), 0))
        assertEquals("Mesa 7, reservada", FloorLayoutBuilder.tableAccessibilityLabel(DiningTable(id = "t7", number = "7", status = "RESERVED"), 0))
    }
}
