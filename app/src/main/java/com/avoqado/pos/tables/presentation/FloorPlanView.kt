package com.avoqado.pos.tables.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.domain.FloorGeometry
import com.avoqado.pos.tables.domain.FloorLayoutBuilder
import com.avoqado.pos.tables.domain.FloorTab
import com.avoqado.pos.tables.domain.PlacedTable
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

// Plano en el POS (spec docs/superpowers/specs/2026-10-09-plano-de-mesas-pos-design.md §4.2). Espejo: avoqado-ios
// Tables/FloorPlanView.swift. Las reglas de dibujo viven en FloorGeometry (copia del dashboard).

/** Una pestaña «Sin área» sola no dice nada: el local no tiene áreas. */
private fun soloSinArea(tabs: List<FloorTab>) = tabs.isEmpty() || (tabs.size == 1 && tabs[0].key == FloorLayoutBuilder.SIN_AREA_KEY)

// MARK: - Pestañas de área

@Composable
internal fun FloorAreaTabs(
    tabs: List<FloorTab>,
    activeKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (soloSinArea(tabs)) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.key }) { tab ->
            val active = tab.key == activeKey
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                    .semantics {
                        selected = active
                        role = Role.Tab
                    }
                    .clickable { onSelect(tab.key) }
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
            )
        }
    }
}

// MARK: - La barra única de la tablet (D4: pestañas · «N sin acomodar» · ocupadas · Acciones · refrescar)

@Composable
internal fun FloorTopBar(
    tabs: List<FloorTab>,
    activeKey: String?,
    onSelectTab: (String) -> Unit,
    unplacedCount: Int,
    onShowUnplaced: () -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    occupiedCount: Int,
    totalCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onAcciones: () -> Unit,
    onListo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) { FloorAreaTabs(tabs, activeKey, onSelectTab) }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        // En los DOS modos (corrección C7): una mesa sin acomodar también se selecciona para «Acciones» desde la hoja.
        if (unplacedCount > 0) {
            TextButton(onClick = onShowUnplaced) {
                Text("$unplacedCount sin acomodar", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (selectionMode) {
            Text(
                text = when (selectedCount) {
                    0 -> "Ninguna seleccionada"
                    1 -> "1 seleccionada"
                    else -> "$selectedCount seleccionadas"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            TextButton(onClick = onListo) { Text("Listo") }
        } else {
            if (totalCount > 0) {
                Text(
                    text = "$occupiedCount/$totalCount ocupadas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            TextButton(onClick = onAcciones) { Text("Acciones") }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") }
            }
        }
    }
}

// MARK: - El área, con su forma, sin deformarse

@Composable
internal fun FloorPlanCanvas(
    tab: FloorTab,
    placed: List<PlacedTable>,
    elements: List<FloorGeometry.CellElement>,
    selectedIds: Set<String>,
    onTap: (DiningTable) -> Unit,
    onLongPress: (DiningTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = FloorGeometry.gridOf(tab.floorShape)
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // 1 cuadro de margen alrededor del área, como la vista del mesero del dashboard (`viewBox="-1 -1 cols+2 rows+2"`,
        // enmienda A2): una pared en el borde se ve completa y una puerta que abre hacia afuera no se corta.
        val cellPx = with(density) { min(maxWidth.toPx() / (grid.cols + 2), maxHeight.toPx() / (grid.rows + 2)) }
        val areaW = with(density) { (grid.cols * cellPx).toDp() }
        val areaH = with(density) { (grid.rows * cellPx).toDp() }
        val margin = with(density) { cellPx.toDp() }
        val nowMs = rememberFloorClock()
        val areaShape = RoundedCornerShape(with(density) { (0.4f * cellPx).toDp() })
        // Sin recorte: lo que asoma del borde del área cae en el margen.
        Box(modifier = Modifier.size(areaW + margin * 2, areaH + margin * 2)) {
            Box(
                modifier = Modifier
                    .offset(margin, margin)
                    .size(areaW, areaH)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), areaShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, areaShape),
            ) {
                // Debajo y apagados (spec §4.2); su capa no se redibuja cuando cambia el estado de una mesa.
                FloorElementsLayer(ElementsLayerModel(elements, cellPx), Modifier.fillMaxSize())
                placed.forEach { p ->
                    key(p.table.id) {
                        FloorTableNode(
                            p = p,
                            cellPx = cellPx,
                            selected = p.table.id in selectedIds,
                            nowMs = nowMs,
                            onTap = { onTap(p.table) },
                            onLongPress = { onLongPress(p.table) },
                        )
                    }
                }
            }
        }
    }
}

/** Debajo del número: ocupada ⇒ tiempo o «N cuentas» (como hoy); libre o reservada ⇒ personas, si el lado corto mide ≥4. */
private fun nodeSubtitle(p: PlacedTable, nowMs: Long): String? {
    val t = p.table
    return when {
        t.isOccupied -> if (t.openOrders.size > 1) {
            "${t.openOrders.size} cuentas"
        } else {
            elapsedLabel(t.primaryCheck?.createdAt, nowMs) ?: t.primaryCheck?.totalDisplay
        }
        t.capacity > 0 && min(p.w, p.h) >= 4.0 -> "${t.capacity}p"
        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloorTableNode(
    p: PlacedTable,
    cellPx: Float,
    selected: Boolean,
    nowMs: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val table = p.table
    val density = LocalDensity.current
    val shape = if (table.shape.uppercase(Locale.ROOT) == "ROUND") CircleShape else RoundedCornerShape(with(density) { (0.6f * cellPx).toDp() })
    val occupied = table.isOccupied
    val fill = if (occupied) OccupiedColor else FreeFill
    val content = if (occupied) Color.White else Color(0xFF1A1A1A)
    val widthDp = with(density) { (p.w * cellPx).toFloat().toDp() }
    val heightDp = with(density) { (p.h * cellPx).toFloat().toDp() }
    val left = ((p.cx - p.w / 2) * cellPx).roundToInt()
    val top = ((p.cy - p.h / 2) * cellPx).roundToInt()
    val numberCells = FloorGeometry.tableNumberFontCells(table.number, table.shape, table.capacity, p.rotation)
    val numberSp = with(density) { (numberCells * cellPx).toFloat().toDp().toSp() }
    val subSp = with(density) { (0.75f * cellPx).toDp().toSp() }
    val sub = nodeSubtitle(p, nowMs)
    val label = FloorLayoutBuilder.tableAccessibilityLabel(table, nowMs)
    Box(
        modifier = Modifier
            .offset { IntOffset(left, top) }
            .size(widthDp, heightDp)
            .graphicsLayer { rotationZ = p.rotation.toFloat() }
            .clip(shape)
            .background(fill)
            .border(
                width = if (selected) 3.dp else if (table.isReserved) 2.dp else 1.dp,
                color = if (selected) Success else if (table.isReserved) ReservedColor else if (occupied) OccupiedColor else FreeBorder,
                shape = shape,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        // El texto se contra-gira: se lee derecho aunque la mesa esté girada (como el dashboard). El interlineado va con
        // la letra: el de la tipografía (24 sp) separaba el número de las personas en una mesa chica.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer { rotationZ = -p.rotation.toFloat() }
                .clearAndSetSemantics { },
        ) {
            Text(
                text = table.number,
                fontSize = numberSp,
                lineHeight = numberSp,
                fontWeight = FontWeight.Bold,
                color = content,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            sub?.let { Text(text = it, fontSize = subSp, lineHeight = subSp, color = content.copy(alpha = 0.75f), maxLines = 1) }
        }
    }
}

// MARK: - Paredes, barra, cocina, puertas y letreros (decorativos)

/** Modelo estable: Compose compara por igualdad y salta la capa si sólo cambió el estado de las mesas (spec §8). */
@Immutable
internal data class ElementsLayerModel(val elements: List<FloorGeometry.CellElement>, val cellPx: Float)

@Composable
private fun FloorElementsLayer(model: ElementsLayerModel, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val wall = muted.copy(alpha = 0.55f)
    val barFill = muted.copy(alpha = 0.28f)
    val zoneFill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val zoneStroke = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val doorStroke = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val c = model.cellPx
        fun pt(x: Double, y: Double) = Offset((x * c).toFloat(), (y * c).toFloat())
        fun boxSize(e: FloorGeometry.CellElement) = Size(((e.w ?: 1.0) * c).toFloat(), ((e.h ?: 1.0) * c).toFloat())
        fun centeredLabel(text: String, e: FloorGeometry.CellElement) {
            val w = e.w ?: 1.0
            val h = e.h ?: 1.0
            val fontPx = (FloorGeometry.labelFontCells(text, w, h) * c).toFloat()
            val layout = measurer.measure(text, TextStyle(color = muted, fontSize = fontPx.toDp().toSp(), fontWeight = FontWeight.Medium))
            val center = pt(e.x + w / 2, e.y + h / 2)
            drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
        }
        model.elements.forEach { e ->
            when (e.type) {
                "WALL" -> drawLine(
                    color = wall,
                    start = pt(e.x, e.y),
                    end = pt(e.x2 ?: e.x, e.y2 ?: e.y),
                    strokeWidth = 0.6f * c,
                    cap = StrokeCap.Round,
                )
                "LABEL" -> e.label?.let { text ->
                    val layout = measurer.measure(text, TextStyle(color = muted, fontSize = (1.4f * c).toDp().toSp(), fontWeight = FontWeight.Medium))
                    drawText(layout, topLeft = pt(e.x, e.y))
                }
                "BAR_COUNTER" -> {
                    drawRoundRect(color = barFill, topLeft = pt(e.x, e.y), size = boxSize(e), cornerRadius = CornerRadius(0.35f * c))
                    e.label?.let { centeredLabel(it, e) }
                }
                "SERVICE_AREA" -> {
                    drawRoundRect(color = zoneFill, topLeft = pt(e.x, e.y), size = boxSize(e), cornerRadius = CornerRadius(0.25f * c))
                    drawRoundRect(
                        color = zoneStroke,
                        topLeft = pt(e.x, e.y),
                        size = boxSize(e),
                        cornerRadius = CornerRadius(0.25f * c),
                        style = Stroke(width = 0.12f * c, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5f * c, 0.35f * c))),
                    )
                    e.label?.let { centeredLabel(it, e) }
                }
                "DOOR" -> {
                    val d = FloorGeometry.doorDrawing(e)
                    drawRect(color = doorStroke, topLeft = pt(d.x, d.y), size = Size((d.w * c).toFloat(), (d.h * c).toFloat()), style = Stroke(width = 0.12f * c))
                    drawLine(color = doorStroke, start = pt(d.leafFrom.x, d.leafFrom.y), end = pt(d.leafTo.x, d.leafTo.y), strokeWidth = 0.1f * c)
                    val arc = Path().apply {
                        d.arc.forEachIndexed { i, q ->
                            val o = pt(q.x, q.y)
                            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                        }
                    }
                    drawPath(
                        path = arc,
                        color = doorStroke,
                        style = Stroke(width = 0.1f * c, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.3f * c, 0.2f * c))),
                    )
                }
            }
        }
    }
}

// MARK: - «N sin acomodar» (D4): una hoja con las mesas que no están en el plano

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnplacedTablesSheet(
    tables: List<DiningTable>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onTap: (DiningTable) -> Unit,
    onLongPress: (DiningTable) -> Unit,
    onDismiss: () -> Unit,
) {
    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxl),
        ) {
            Text("Sin acomodar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "Estas mesas no están en el plano. Acomódalas en tu computadora: Configuración › Mesas y plano.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(AvoqadoTheme.spacing.md))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(tables, key = { it.id }) { t ->
                    // Tocar una tarjeta hace lo mismo que tocar la mesa; fuera de Acciones, la hoja se cierra primero.
                    TableCard(
                        table = t,
                        onTap = {
                            if (!selectionMode) onDismiss()
                            onTap(t)
                        },
                        onLongPress = {
                            onDismiss()
                            onLongPress(t)
                        },
                        selected = t.id in selectedIds,
                    )
                }
            }
        }
    }
}
