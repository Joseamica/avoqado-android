package com.avoqado.pos.tables.domain

import com.avoqado.pos.tables.data.FloorPlanElement
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reglas de dibujo del plano (spec 2026-10-08 §4.2-4.3), copiadas POR VALOR de avoqado-web-dashboard
 * `src/pages/Settings/FloorPlan/model/floorGeometry.ts` (gridOf, tableSizeCells, rotatedExtent), `planMapping.ts`
 * (0–1 → cuadros de SU área), `editorReducer.ts` (normalizeRectElement) y `editor/FloorDrawing.tsx` (abatimiento de la
 * puerta, letra del número y de los nombres). Espejo exacto: avoqado-ios `Tables/FloorGeometry.swift`.
 *
 * 🔴 Si cambias una regla, cámbiala en los TRES (dashboard, Android, iOS) y en sus pruebas (`floorGeometry.test.ts`,
 * `FloorGeometryTest`, `FloorGeometryTests`): así los tres dibujan igual.
 */
object FloorGeometry {

    data class Grid(val cols: Int, val rows: Int)

    data class CellSize(val w: Double, val h: Double)

    data class CellPoint(val x: Double, val y: Double)

    /** Un elemento en cuadros de SU área. Barra/zona/puerta: (x, y) = esquina superior izquierda. Pared: (x, y) → (x2, y2). */
    data class CellElement(
        val id: String,
        val type: String,
        val x: Double,
        val y: Double,
        val w: Double? = null,
        val h: Double? = null,
        val rotation: Int = 0,
        val x2: Double? = null,
        val y2: Double? = null,
        val label: String? = null,
    )

    /** La puerta lista para dibujar: su caja, la hoja (bisagra → punta) y el arco del abatimiento, ya volteada si gira 180. */
    data class DoorDrawing(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val leafFrom: CellPoint,
        val leafTo: CellPoint,
        val arc: List<CellPoint>,
    )

    private val SIDES = doubleArrayOf(3.0, 4.0, 5.0, 6.0)

    /** Como `Math.round` de JavaScript (la mitad sube), a 6 decimales. */
    fun round6(n: Double): Double = floor(n * 1e6 + 0.5) / 1e6

    /** Cuadrícula de cada forma de área. El lado largo mide 40 cuadros. */
    fun gridOf(shape: String?): Grid = when (shape?.uppercase(Locale.ROOT)) {
        "SQUARE" -> Grid(40, 40)
        "TALL" -> Grid(25, 40)
        else -> Grid(40, 25)
    }

    /** Tamaño de una mesa en cuadros: lo deciden su forma y sus personas (0 personas = «sin dato» ⇒ como ≤2). */
    fun tableSizeCells(shape: String, capacity: Int): CellSize {
        val band = when {
            capacity <= 2 -> 0
            capacity <= 4 -> 1
            capacity <= 6 -> 2
            else -> 3
        }
        if (shape.uppercase(Locale.ROOT) == "RECTANGLE") {
            val w = when (band) {
                2 -> 8.0
                3 -> min(2 + capacity, 14).toDouble()
                else -> 6.0
            }
            return CellSize(w, if (band == 0) 3.0 else 4.0)
        }
        val side = SIDES[band]
        return CellSize(side, side)
    }

    /** Caja que ocupa una pieza girada. */
    fun rotatedExtent(w: Double, h: Double, rotation: Int): CellSize {
        val r = rotation * PI / 180.0
        val c = abs(cos(r))
        val s = abs(sin(r))
        return CellSize(round6(w * c + h * s), round6(w * s + h * c))
    }

    /** El área más grande con la proporción de su forma que cabe en (availW × availH), en las mismas unidades. */
    fun fitAreaBox(availW: Double, availH: Double, shape: String?): CellSize {
        if (availW <= 0.0 || availH <= 0.0) return CellSize(0.0, 0.0)
        val g = gridOf(shape)
        val aspect = g.cols.toDouble() / g.rows
        return if (availW / availH > aspect) CellSize(availH * aspect, availH) else CellSize(availW, availW / aspect)
    }

    private fun isRect(type: String) = type == "BAR_COUNTER" || type == "SERVICE_AREA" || type == "DOOR"

    /**
     * Barra, zona y puerta no guardan 90/270 (R13): 90 ⇒ lados intercambiados sobre el mismo centro y 0; 270 ⇒ igual (la
     * puerta, 180); cualquier otro giro ⇒ 0, salvo el 180 de una puerta (abre hacia el otro lado). Lo viejo de la PAX
     * llega así y se dibuja como el dashboard.
     */
    fun normalizeRectElement(e: CellElement): CellElement {
        if (!isRect(e.type)) return e
        val r = ((e.rotation % 360) + 360) % 360
        if (r % 180 == 90) {
            val w = e.w ?: 1.0
            val h = e.h ?: 1.0
            return e.copy(
                w = h,
                h = w,
                x = round6(e.x + w / 2 - h / 2),
                y = round6(e.y + h / 2 - w / 2),
                rotation = if (e.type == "DOOR" && r == 270) 180 else 0,
            )
        }
        val rotation = if (e.type == "DOOR" && r == 180) 180 else 0
        return if (rotation == e.rotation) e else e.copy(rotation = rotation)
    }

    /** Elemento del servidor (0–1 de su área) → cuadros de esa área, ya normalizado. */
    fun elementToCells(e: FloorPlanElement, grid: Grid): CellElement {
        val sx = { v: Double? -> v?.let { round6(it * grid.cols) } }
        val sy = { v: Double? -> v?.let { round6(it * grid.rows) } }
        return normalizeRectElement(
            CellElement(
                id = e.id,
                type = e.type.uppercase(Locale.ROOT),
                x = round6(e.positionX * grid.cols),
                y = round6(e.positionY * grid.rows),
                w = sx(e.width),
                h = sy(e.height),
                rotation = e.rotation,
                x2 = sx(e.endX),
                y2 = sy(e.endY),
                label = e.label,
            ),
        )
    }

    /**
     * Abatimiento de la puerta (`doorSwingPath` de FloorDrawing.tsx). Ángulos en grados con y hacia abajo (positivo =
     * sentido del reloj).
     * - Acostada (w ≥ h): bisagra abajo a la izquierda; la hoja sube; el arco cierra en la esquina de abajo a la derecha.
     * - Parada (h > w): bisagra arriba a la izquierda; la hoja va a la derecha; el arco cierra abajo a la izquierda.
     * - Con giro 180: todo volteado sobre el centro de su caja.
     */
    fun doorDrawing(e: CellElement, arcSegments: Int = 12): DoorDrawing {
        val w = e.w ?: 1.0
        val h = e.h ?: 1.0
        val vertical = h > w
        val hinge = if (vertical) CellPoint(e.x, e.y) else CellPoint(e.x, e.y + h)
        val start = if (vertical) 0.0 else -90.0
        val radius = if (vertical) h else w
        fun at(deg: Double): CellPoint {
            val rad = deg * PI / 180.0
            return CellPoint(round6(hinge.x + radius * cos(rad)), round6(hinge.y + radius * sin(rad)))
        }
        var leafFrom = hinge
        var leafTo = at(start)
        var arc = (0..arcSegments).map { at(start + 90.0 * it / arcSegments) }
        if (e.rotation == 180) {
            val cx = e.x + w / 2
            val cy = e.y + h / 2
            val flip = { p: CellPoint -> CellPoint(round6(2 * cx - p.x), round6(2 * cy - p.y)) }
            leafFrom = flip(leafFrom)
            leafTo = flip(leafTo)
            arc = arc.map(flip)
        }
        return DoorDrawing(e.x, e.y, w, h, leafFrom, leafTo, arc)
    }

    /**
     * Letra del número de mesa, en cuadros (`tableNumberSize` de FloorDrawing.tsx): la de siempre, pero un número largo se
     * achica hasta caber a lo ancho de la mesa tal como se ve (el texto va derecho aunque la mesa esté girada).
     */
    fun tableNumberFontCells(number: String, shape: String, capacity: Int, rotation: Int): Double {
        val size = tableSizeCells(shape, capacity)
        val turn = ((rotation % 180) + 180) % 180
        val across = if (shape.uppercase(Locale.ROOT) == "ROUND") {
            size.w * 0.78
        } else {
            (if (turn == 0) size.w else if (turn == 90) size.h else min(size.w, size.h)) * 0.86
        }
        return round6(min(1.5, min(min(size.w, size.h) * 0.42, across / (0.62 * max(1, number.length)))))
    }

    /** Letra del nombre de una barra o zona (`fitLabel` de FloorDrawing.tsx), en cuadros. */
    fun labelFontCells(label: String, w: Double, h: Double): Double =
        round6(min(1.2, min(h * 0.45, (w * 0.9) / (0.6 * max(1, label.length)))))
}
