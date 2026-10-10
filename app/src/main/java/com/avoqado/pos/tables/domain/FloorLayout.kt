package com.avoqado.pos.tables.domain

import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.FloorPlanPayload
import java.util.Locale

/** Una pestaña de área. `floorShape` decide la proporción del lienzo (null ⇒ WIDE). */
data class FloorTab(val key: String, val title: String, val floorShape: String?)

/** Una mesa ya puesta en su área, en cuadros: centro (cx, cy), tamaño (w × h) y giro. */
data class PlacedTable(
    val table: DiningTable,
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
    val rotation: Int,
)

/** Todo lo que la pantalla de Mesas necesita para dibujar (tablet) o listar (teléfono). */
data class FloorLayout(
    /** Pestañas del PLANO (tablet). */
    val tabs: List<FloorTab> = emptyList(),
    /** Pestañas de la LISTA (teléfono y tablet sin plano). */
    val listTabs: List<FloorTab> = emptyList(),
    val placedByTab: Map<String, List<PlacedTable>> = emptyMap(),
    val elementsByTab: Map<String, List<FloorGeometry.CellElement>> = emptyMap(),
    val listByTab: Map<String, List<DiningTable>> = emptyMap(),
    /** «N sin acomodar»: sin posición o con un área que ya no existe. */
    val unplaced: List<DiningTable> = emptyList(),
    /** Alguna mesa con posición o algún elemento (spec §4.2; reemplaza el umbral viejo de ≥2 mesas). */
    val hasPlan: Boolean = false,
    val overLimit: Boolean = false,
)

/**
 * Arma el plano del POS a partir de las mesas (`/tables`) y del plano (`/floor-plan`) — spec 2026-10-09 §4.2-4.3.
 * Puro y sin Android. Espejo exacto: avoqado-ios `Tables/FloorLayout.swift`.
 */
object FloorLayoutBuilder {
    const val SIN_AREA_KEY = "__sin_area__"
    const val SIN_AREA_TITLE = "Sin área"

    private data class AreaInfo(val id: String, val name: String, val floorShape: String?, val sortOrder: Int)

    /** Áreas en el orden del dueño: `sortOrder`, empate por nombre sin mayúsculas, luego id. Sin plano: de las mesas. */
    private fun areasOf(tables: List<DiningTable>, plan: FloorPlanPayload?): List<FloorTab> {
        val areas = if (plan != null) {
            plan.areas.map { AreaInfo(it.id, it.name, it.floorShape, it.sortOrder) }
        } else {
            tables.mapNotNull { t -> t.areaId?.let { AreaInfo(it, t.areaName ?: "Área", t.areaFloorShape, t.areaSortOrder ?: 0) } }
                .distinctBy { it.id }
        }
        return areas
            .sortedWith(compareBy<AreaInfo>({ it.sortOrder }, { it.name.lowercase(Locale.ROOT) }, { it.id }))
            .map { FloorTab(it.id, it.name, it.floorShape) }
    }

    /**
     * `tablesFloorPlanVersion`: la versión del plano que anunciaron las mesas (`/tables`). Si el plano guardado es OTRO
     * (llegaron las mesas y falló `/floor-plan`), está atrasado: pestañas, orden y forma salen de las mesas, y sólo se
     * dibujan los elementos de áreas que siguen existiendo. Nunca mesas nuevas sobre un área vieja (auditoría Codex C6).
     */
    fun build(tables: List<DiningTable>, plan: FloorPlanPayload?, tablesFloorPlanVersion: String? = null): FloorLayout {
        val planAlDia = plan != null && (tablesFloorPlanVersion == null || plan.floorPlanVersion == tablesFloorPlanVersion)
        val areas = areasOf(tables, plan.takeIf { planAlDia })
        val areaIds = areas.map { it.key }.toSet()
        val elements = when {
            plan == null -> emptyList()
            planAlDia -> plan.elements
            else -> plan.elements.filter { it.areaId != null && it.areaId in areaIds }
        }
        val sinArea = FloorTab(SIN_AREA_KEY, SIN_AREA_TITLE, null)

        // Tablet: «Sin área» sólo si hay mesas con posición y sin área (o elementos y ninguna área donde ponerlos).
        val needsSinArea = tables.any { it.hasPosition && it.areaId == null } || (areas.isEmpty() && elements.isNotEmpty())
        val tabs = if (needsSinArea) areas + sinArea else areas
        val shapeOf = tabs.associate { it.key to it.floorShape }

        val placed = linkedMapOf<String, MutableList<PlacedTable>>()
        val unplaced = mutableListOf<DiningTable>()
        tables.forEach { t ->
            val key = when {
                !t.hasPosition -> null
                t.areaId == null -> SIN_AREA_KEY
                t.areaId in areaIds -> t.areaId
                else -> null
            }
            if (key == null) {
                unplaced += t
                return@forEach
            }
            val g = FloorGeometry.gridOf(shapeOf[key])
            val size = FloorGeometry.tableSizeCells(t.shape, t.capacity)
            placed.getOrPut(key) { mutableListOf() } += PlacedTable(
                table = t,
                cx = FloorGeometry.round6(t.positionX!!.toDouble() * g.cols),
                cy = FloorGeometry.round6(t.positionY!!.toDouble() * g.rows),
                w = size.w,
                h = size.h,
                rotation = t.rotation,
            )
        }

        // Un elemento sin área (o con un área borrada) cae en la primera pestaña, como en el dashboard.
        val elementsByTab = linkedMapOf<String, MutableList<FloorGeometry.CellElement>>()
        val firstKey = tabs.firstOrNull()?.key
        if (firstKey != null) {
            elements.forEach { e ->
                val key = if (e.areaId != null && e.areaId in areaIds) e.areaId else firstKey
                elementsByTab.getOrPut(key) { mutableListOf() } += FloorGeometry.elementToCells(e, FloorGeometry.gridOf(shapeOf[key]))
            }
        }

        // Teléfono (y tablet sin plano): las mismas áreas; lo que no tiene un área conocida va a «Sin área».
        val listKey = { t: DiningTable -> if (t.areaId != null && t.areaId in areaIds) t.areaId else SIN_AREA_KEY }
        val listTabs = if (tables.any { listKey(it) == SIN_AREA_KEY }) areas + sinArea else areas

        return FloorLayout(
            tabs = tabs,
            listTabs = listTabs,
            placedByTab = placed,
            elementsByTab = elementsByTab,
            listByTab = tables.groupBy(listKey),
            unplaced = unplaced,
            hasPlan = tables.any { it.hasPosition } || elements.isNotEmpty(),
            overLimit = plan?.overLimit == true,
        )
    }

    /** La pestaña recordada si todavía existe; si no, la primera. */
    fun pickActiveTab(tabs: List<FloorTab>, remembered: String?): String? =
        tabs.firstOrNull { it.key == remembered }?.key ?: tabs.firstOrNull()?.key

    /** «Mesa 7, ocupada, 12 minutos» — lo que dice un lector de pantalla al tocar una mesa (spec §4.2). */
    fun tableAccessibilityLabel(table: DiningTable, nowMs: Long): String {
        val base = "Mesa ${table.number}"
        return when {
            table.isOccupied -> {
                val detalle = if (table.openOrders.size > 1) {
                    "${table.openOrders.size} cuentas"
                } else {
                    minutesSince(table.primaryCheck?.createdAt, nowMs)?.let { durationText(it) }
                }
                listOfNotNull(base, "ocupada", detalle).joinToString(", ")
            }
            table.isReserved -> "$base, reservada"
            table.capacity > 0 -> "$base, libre, ${table.capacity} ${if (table.capacity == 1) "persona" else "personas"}"
            else -> "$base, libre"
        }
    }

    fun minutesSince(iso: String?, nowMs: Long): Int? {
        if (iso.isNullOrBlank()) return null
        val startMs = try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                return null
            }
        }
        return ((nowMs - startMs).coerceAtLeast(0L) / 60_000L).toInt()
    }

    fun durationText(minutes: Int): String {
        fun plural(n: Int, uno: String, varios: String) = "$n ${if (n == 1) uno else varios}"
        if (minutes < 60) return plural(minutes, "minuto", "minutos")
        if (minutes < 24 * 60) {
            val horas = plural(minutes / 60, "hora", "horas")
            val resto = minutes % 60
            return if (resto == 0) horas else "$horas y ${plural(resto, "minuto", "minutos")}"
        }
        return plural(minutes / (24 * 60), "día", "días")
    }
}
