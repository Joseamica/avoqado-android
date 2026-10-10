package com.avoqado.pos.tables.domain

import com.avoqado.pos.tables.data.FloorPlanElement
import com.avoqado.pos.tables.domain.FloorGeometry.CellElement
import com.avoqado.pos.tables.domain.FloorGeometry.CellPoint
import com.avoqado.pos.tables.domain.FloorGeometry.CellSize
import com.avoqado.pos.tables.domain.FloorGeometry.Grid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los MISMOS casos que avoqado-web-dashboard `model/__tests__/floorGeometry.test.ts` y `planMapping.test.ts`, más el
 * dibujo de la puerta de `FloorDrawing.tsx` (spec 2026-10-09 §4.4). Espejo: avoqado-ios `FloorGeometryTests`.
 */
class FloorGeometryTest {

    @Test
    fun `cada forma de area tiene su cuadricula y null es ancha`() {
        assertEquals(Grid(40, 25), FloorGeometry.gridOf("WIDE"))
        assertEquals(Grid(40, 40), FloorGeometry.gridOf("SQUARE"))
        assertEquals(Grid(25, 40), FloorGeometry.gridOf("TALL"))
        assertEquals(Grid(40, 25), FloorGeometry.gridOf(null))
    }

    @Test
    fun `la mesa mide en cuadros segun forma y personas`() {
        val casos = listOf(
            Triple("SQUARE", 2, CellSize(3.0, 3.0)),
            Triple("SQUARE", 4, CellSize(4.0, 4.0)),
            Triple("SQUARE", 6, CellSize(5.0, 5.0)),
            Triple("SQUARE", 10, CellSize(6.0, 6.0)),
            Triple("ROUND", 2, CellSize(3.0, 3.0)),
            Triple("ROUND", 4, CellSize(4.0, 4.0)),
            Triple("ROUND", 5, CellSize(5.0, 5.0)),
            Triple("ROUND", 8, CellSize(6.0, 6.0)),
            Triple("RECTANGLE", 2, CellSize(6.0, 3.0)),
            Triple("RECTANGLE", 4, CellSize(6.0, 4.0)),
            Triple("RECTANGLE", 6, CellSize(8.0, 4.0)),
            Triple("RECTANGLE", 8, CellSize(10.0, 4.0)),
            Triple("RECTANGLE", 20, CellSize(14.0, 4.0)),
        )
        casos.forEach { (forma, personas, esperado) ->
            assertEquals("$forma de $personas", esperado, FloorGeometry.tableSizeCells(forma, personas))
        }
    }

    @Test
    fun `una mesa con 0 personas se dibuja como una de 2`() {
        listOf("SQUARE", "ROUND", "RECTANGLE").forEach { forma ->
            assertEquals(forma, FloorGeometry.tableSizeCells(forma, 2), FloorGeometry.tableSizeCells(forma, 0))
        }
    }

    @Test
    fun `girar 90 intercambia ancho y alto y 45 ocupa mas`() {
        assertEquals(CellSize(4.0, 6.0), FloorGeometry.rotatedExtent(6.0, 4.0, 90))
        assertEquals(5.656854, FloorGeometry.rotatedExtent(4.0, 4.0, 45).w, 1e-6)
    }

    @Test
    fun `el area usa todo el espacio sin deformarse`() {
        assertEquals(CellSize(800.0, 500.0), FloorGeometry.fitAreaBox(1000.0, 500.0, "WIDE"))
        assertEquals(CellSize(500.0, 500.0), FloorGeometry.fitAreaBox(1000.0, 500.0, "SQUARE"))
        assertEquals(CellSize(312.5, 500.0), FloorGeometry.fitAreaBox(1000.0, 500.0, "TALL"))
        assertEquals(CellSize(300.0, 187.5), FloorGeometry.fitAreaBox(300.0, 1000.0, null))
        assertEquals(CellSize(0.0, 0.0), FloorGeometry.fitAreaBox(0.0, 500.0, "WIDE"))
    }

    @Test
    fun `barras y puertas viejas giradas se normalizan como en el dashboard`() {
        // planMapping.test.ts «mapeo — ronda 1»: 3×1 con esquina en (10, 10), centro (11.5, 10.5).
        fun viejo(id: String, type: String, rotation: Int) = FloorPlanElement(
            id = id, type = type, areaId = "a1", positionX = 0.25, positionY = 0.4, width = 0.075, height = 0.04, rotation = rotation,
        )
        val g = FloorGeometry.gridOf("WIDE")
        assertEquals(CellElement("d90", "DOOR", 11.0, 9.0, 1.0, 3.0, 0), FloorGeometry.elementToCells(viejo("d90", "DOOR", 90), g))
        assertEquals(CellElement("d270", "DOOR", 11.0, 9.0, 1.0, 3.0, 180), FloorGeometry.elementToCells(viejo("d270", "DOOR", 270), g))
        assertEquals(CellElement("b45", "BAR_COUNTER", 10.0, 10.0, 3.0, 1.0, 0), FloorGeometry.elementToCells(viejo("b45", "BAR_COUNTER", 45), g))
        assertEquals(180, FloorGeometry.elementToCells(viejo("d180", "DOOR", 180), g).rotation)
        assertEquals(0, FloorGeometry.elementToCells(viejo("z180", "SERVICE_AREA", 180), g).rotation)
    }

    @Test
    fun `las posiciones del plano se convierten a cuadros de su area`() {
        val pared = FloorPlanElement(id = "e1", type = "WALL", positionX = 0.0, positionY = 0.0, endX = 1.0, endY = 0.0)
        val letrero = FloorPlanElement(id = "e2", type = "LABEL", positionX = 0.1, positionY = 0.9, label = "VIP")
        assertEquals(CellElement("e1", "WALL", 0.0, 0.0, null, null, 0, 40.0, 0.0), FloorGeometry.elementToCells(pared, Grid(40, 25)))
        assertEquals(CellElement("e2", "LABEL", 4.0, 22.5, label = "VIP"), FloorGeometry.elementToCells(letrero, Grid(40, 25)))
        assertEquals(CellElement("e1", "WALL", 0.0, 0.0, null, null, 0, 25.0, 0.0), FloorGeometry.elementToCells(pared, Grid(25, 40)))
    }

    @Test
    fun `la puerta acostada abre hacia arriba desde abajo a la izquierda`() {
        val d = FloorGeometry.doorDrawing(CellElement("p", "DOOR", 10.0, 10.0, 3.0, 1.0, 0))
        assertEquals(CellPoint(10.0, 11.0), d.leafFrom)
        assertEquals(CellPoint(10.0, 8.0), d.leafTo)
        assertEquals(CellPoint(10.0, 8.0), d.arc.first())
        assertEquals(CellPoint(13.0, 11.0), d.arc.last())
        assertEquals(13, d.arc.size)
    }

    @Test
    fun `la puerta parada abre hacia la derecha desde arriba a la izquierda`() {
        val d = FloorGeometry.doorDrawing(CellElement("p", "DOOR", 11.0, 9.0, 1.0, 3.0, 0))
        assertEquals(CellPoint(11.0, 9.0), d.leafFrom)
        assertEquals(CellPoint(14.0, 9.0), d.leafTo)
        assertEquals(CellPoint(11.0, 12.0), d.arc.last())
    }

    @Test
    fun `la puerta con giro 180 abre hacia el otro lado`() {
        val d = FloorGeometry.doorDrawing(CellElement("p", "DOOR", 10.0, 10.0, 3.0, 1.0, 180))
        assertEquals(CellPoint(13.0, 10.0), d.leafFrom)
        assertEquals(CellPoint(13.0, 13.0), d.leafTo)
        assertEquals(CellPoint(10.0, 10.0), d.arc.last())
    }

    @Test
    fun `el numero de mesa se achica si es largo y segun el giro`() {
        assertEquals(1.5, FloorGeometry.tableNumberFontCells("7", "SQUARE", 4, 0), 1e-9)
        assertEquals(1.26, FloorGeometry.tableNumberFontCells("12", "ROUND", 2, 0), 1e-9)
        assertEquals(0.554839, FloorGeometry.tableNumberFontCells("FULLTEST-1", "SQUARE", 4, 0), 1e-9)
        assertEquals(0.832258, FloorGeometry.tableNumberFontCells("FULLTEST-1", "RECTANGLE", 4, 0), 1e-9)
        assertEquals(0.554839, FloorGeometry.tableNumberFontCells("FULLTEST-1", "RECTANGLE", 4, 90), 1e-9)
    }

    @Test
    fun `sin nada que sobresalga el margen es de un cuadro`() {
        val ancha = FloorGeometry.gridOf("WIDE")
        assertEquals(FloorGeometry.Insets(1, 1, 1, 1), FloorGeometry.contentInsets(emptyList(), ancha))
        // Una pared sobre el borde de arriba sale medio grosor (0.3 cuadros) y una barra pegada al borde no sale: 1 cuadro.
        val pegadas = listOf(
            CellElement("w", "WALL", 0.0, 0.0, x2 = 40.0, y2 = 0.0),
            CellElement("b", "BAR_COUNTER", 32.0, 20.0, w = 8.0, h = 5.0),
        )
        assertEquals(FloorGeometry.Insets(1, 1, 1, 1), FloorGeometry.contentInsets(pegadas, ancha))
    }

    @Test
    fun `una puerta que abre hacia afuera agranda el margen de su lado`() {
        // La entrada: puerta horizontal de 4 cuadros en la pared de abajo, girada 180 ⇒ abre hacia afuera (3.5 cuadros).
        val entrada = CellElement("d", "DOOR", 10.0, 24.5, w = 4.0, h = 0.5, rotation = 180)
        assertEquals(FloorGeometry.Insets(top = 1, left = 1, bottom = 4, right = 1), FloorGeometry.contentInsets(listOf(entrada), FloorGeometry.gridOf("WIDE")))
    }

    @Test
    fun `el nombre de barra o cocina cabe en su caja`() {
        assertEquals(1.2, FloorGeometry.labelFontCells("Cocina", 8.0, 6.0), 1e-9)
        assertEquals(0.9, FloorGeometry.labelFontCells("Barra", 8.0, 2.0), 1e-9)
    }
}
