package com.avoqado.pos.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.PlanGateScreen
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.tables.data.DiningTable
import java.util.Locale

// Status palette (floor plan), Square-style: occupied = solid dark block with
// light text + elapsed h:mm timer; free = flat light gray; reserved = amber.
private val OccupiedColor = Color(0xFF1A1A1A)
private val ReservedColor = Color(0xFFE8A33D)
private val FreeFill = Color(0xFFEDEDED)
private val FreeBorder = Color(0xFFC9C9C9)

/**
 * "0:03" = 3 minutes, "1:12" = 1h12m — Square's exact elapsed format on the
 * canvas. [nowMs] is a ticking clock so the label refreshes while on screen.
 */
private fun elapsedLabel(createdAt: String?, nowMs: Long): String? {
    if (createdAt.isNullOrBlank()) return null
    val startMs = try {
        java.time.Instant.parse(createdAt).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
        } catch (_: Exception) {
            return null
        }
    }
    val minutes = ((nowMs - startMs).coerceAtLeast(0L) / 60_000L).toInt()
    return "${minutes / 60}:${String.format(Locale.US, "%02d", minutes % 60)}"
}

/** Shared 30s clock for the floor's timers. */
@Composable
private fun rememberFloorClock(): Long {
    val now = androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now.value
}

/**
 * TABLE_SERVICE (PRO) — Square-style floor plan. Tapping a table opens the
 * DEDICATED table screen ([onOpenTableOrder] → TableOrderScreen: grid + the
 * two-card check panel). Paying still rides the register's proven PAYING seam
 * ([onGoToCheckout] → Cobrar tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    onGoToCheckout: () -> Unit = {},
    onOpenTableOrder: () -> Unit = {},
    viewModel: TablesViewModel = hiltViewModel(),
) {
    // TABLE_SERVICE (PRO) — mirror of the server gate by exact code name.
    if (!viewModel.planManager.hasFeature("TABLE_SERVICE")) {
        PlanGateScreen(featureName = "Servicio de mesas", requiredTierLabel = "PRO")
        return
    }

    val tables by viewModel.tables.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedTableId by viewModel.selectedTableId.collectAsState()

    // "Seleccionar cheques" (Square): modo de selección múltiple del plano.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkAnular by remember { mutableStateOf(false) }
    var showBulkCortesia by remember { mutableStateOf(false) }
    var showBulkAsignar by remember { mutableStateOf(false) }
    var showBulkMover by remember { mutableStateOf(false) }
    var showBulkUnir by remember { mutableStateOf(false) }
    val bulkCtx = androidx.compose.ui.platform.LocalContext.current
    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }
    val selectedOccupied = tables.filter { it.id in selectedIds && it.isOccupied }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AvoqadoTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectionMode) "Seleccionar cheques" else "Mesas",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (selectionMode) {
                // Las acciones viven ABAJO (BulkActionBar): aquí solo cuántas
                // llevas y la salida. Antes los cinco botones se apretaban
                // contra el título y quedaban ilegibles en pantallas angostas.
                Text(
                    text = when (selectedOccupied.size) {
                        0 -> "Ninguna seleccionada"
                        1 -> "1 seleccionada"
                        else -> "${selectedOccupied.size} seleccionadas"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                TextButton(onClick = { exitSelection() }) { Text("Listo") }
            } else {
                val occupied = tables.count { it.isOccupied }
                if (tables.isNotEmpty()) {
                    Text(
                        text = "$occupied/${tables.size} ocupadas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                TextButton(onClick = { selectionMode = true }) { Text("Acciones") }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
                    }
                }
            }
        }

        if (tables.isEmpty() && !isLoading) {
            EmptyFloor()
        } else {
            // Square's flow: tapping an OCCUPIED table goes STRAIGHT to the
            // table screen (no intermediate sheet); long-press keeps the check
            // sheet (cobrar/imprimir/anular). Free tables still open the
            // covers sheet on tap.
            val onTableTap: (DiningTable) -> Unit = { t ->
                if (selectionMode) {
                    if (t.isOccupied) {
                        selectedIds = if (t.id in selectedIds) selectedIds - t.id else selectedIds + t.id
                    }
                } else if (t.isOccupied) {
                    // Entra SIEMPRE a la cuenta principal de la mesa (Square no
                    // interpone un modal): si hay varias, se cambia desde el
                    // selector de la barra de contexto dentro del cheque.
                    viewModel.startOrdering(t, onReady = onOpenTableOrder)
                } else {
                    viewModel.selectTable(t.id)
                }
            }
            val onTableLongPress: (DiningTable) -> Unit = { viewModel.selectTable(it.id) }

            val positioned = tables.filter { it.hasPosition }
            if (positioned.size >= 2) {
                FloorCanvas(
                    tables = tables,
                    onTap = onTableTap,
                    onLongPress = onTableLongPress,
                    selectedIds = if (selectionMode) selectedIds else emptySet(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                TableGrid(
                    tables = tables,
                    onTap = onTableTap,
                    onLongPress = onTableLongPress,
                    selectedIds = if (selectionMode) selectedIds else emptySet(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (selectionMode) {
                BulkActionBar(
                    selectedCount = selectedOccupied.size,
                    onUnir = { showBulkUnir = true },
                    onMover = { showBulkMover = true },
                    onAsignar = { showBulkAsignar = true },
                    onAnular = { showBulkAnular = true },
                    onCortesia = { showBulkCortesia = true },
                )
            }
        }
    }

    if (showBulkUnir) {
        // Hay que elegir CUÁL mesa se queda con la cuenta: el server cierra las
        // otras, así que la decisión no puede ser implícita.
        AlertDialog(
            onDismissRequest = { showBulkUnir = false },
            title = { Text("Unir ${selectedOccupied.size} cuentas") },
            text = {
                Column {
                    Text(
                        "¿En qué mesa se queda la cuenta? Las otras mesas se liberan y TODOS sus artículos pasan a ella, en una sola cuenta.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    selectedOccupied.forEach { table ->
                        TextButton(
                            onClick = {
                                showBulkUnir = false
                                val sources = selectedOccupied.filter { it.id != table.id }
                                viewModel.bulkUnir(table, sources) { ok, fail, err ->
                                    val msg = when {
                                        fail == 0 -> "Cuentas unidas en la mesa ${table.number}"
                                        ok == 0 -> err ?: "No se pudieron unir"
                                        else -> "$ok unidas, $fail no: ${err ?: "error"}"
                                    }
                                    android.widget.Toast.makeText(bulkCtx, msg, android.widget.Toast.LENGTH_LONG).show()
                                    exitSelection()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mesa ${table.number}", modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showBulkUnir = false }) { Text("Cancelar") } },
        )
    }

    if (showBulkAnular) {
        AnularCuentaDialog(
            tableNumber = selectedOccupied.joinToString(", ") { it.number },
            onDismiss = { showBulkAnular = false },
            onConfirm = { reason ->
                showBulkAnular = false
                viewModel.bulkAnular(selectedOccupied, reason) { ok, fail ->
                    android.widget.Toast.makeText(bulkCtx, "$ok anuladas" + (if (fail > 0) ", $fail fallaron" else ""), android.widget.Toast.LENGTH_LONG).show()
                }
                exitSelection()
            },
        )
    }

    if (showBulkCortesia) {
        CortesiaDialog(
            productName = "las cuentas de ${selectedOccupied.size} mesa(s)",
            onDismiss = { showBulkCortesia = false },
            onConfirm = { reason ->
                showBulkCortesia = false
                viewModel.bulkCortesia(selectedOccupied, reason) { ok, fail ->
                    android.widget.Toast.makeText(bulkCtx, "$ok cuentas de cortesía" + (if (fail > 0) ", $fail fallaron" else ""), android.widget.Toast.LENGTH_LONG).show()
                }
                exitSelection()
            },
        )
    }

    if (showBulkAsignar) {
        val cartVM: com.avoqado.pos.pos.presentation.cart.CartViewModel = hiltViewModel()
        androidx.compose.runtime.LaunchedEffect(Unit) { cartVM.fetchStaff() }
        val staffOptions by cartVM.staffOptions.collectAsState()
        val isStaffLoading by cartVM.isStaffLoading.collectAsState()
        val staffError by cartVM.staffError.collectAsState()
        com.avoqado.pos.pos.presentation.cart.StaffSelectorSheet(
            staff = staffOptions,
            selectedStaffId = "",
            isLoading = isStaffLoading,
            error = staffError,
            onStaffSelected = { staff ->
                showBulkAsignar = false
                viewModel.bulkAsignar(selectedOccupied, staff.id) { ok, fail ->
                    android.widget.Toast.makeText(bulkCtx, "$ok asignadas a ${staff.fullName}" + (if (fail > 0) ", $fail fallaron" else ""), android.widget.Toast.LENGTH_LONG).show()
                }
                exitSelection()
            },
            onDismiss = { showBulkAsignar = false },
        )
    }

    if (showBulkMover) {
        val source = selectedOccupied.firstOrNull()
        val freeTables = tables.filter { it.isAvailable }
        if (source != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBulkMover = false },
                title = { Text("Mover cuenta — Mesa ${source.number}") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        var picked by remember { mutableStateOf<String?>(null) }
                        freeTables.forEach { t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBulkMover = false
                                        viewModel.bulkMover(source, t.id) { ok, msg ->
                                            android.widget.Toast.makeText(bulkCtx, msg, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        exitSelection()
                                    }
                                    .padding(vertical = AvoqadoTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Mesa ${t.number}" + (t.areaName?.let { " · $it" } ?: ""))
                            }
                        }
                        if (freeTables.isEmpty()) Text("No hay mesas libres.")
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showBulkMover = false }) { Text("Cancelar") } },
            )
        }
    }

    if (selectedTableId != null) {
        val table = tables.firstOrNull { it.id == selectedTableId }
        if (table != null) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectTable(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                TableCheckSheet(
                    table = table,
                    viewModel = viewModel,
                    onGoToCheckout = onGoToCheckout,
                    onOpenTableOrder = onOpenTableOrder,
                )
            }
        }
    }
}

// MARK: - Empty state

@Composable
private fun EmptyFloor() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.TableRestaurant,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = "No hay mesas configuradas",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Crea las mesas de tu restaurante desde el dashboard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Floor canvas (positioned tables)

/**
 * Acciones masivas del plano (estilo Square): abajo, con icono y etiqueta, no
 * apretadas contra el título. Cada acción dice POR QUÉ está deshabilitada en
 * vez de solo verse gris — "Mover" pide exactamente una mesa y "Unir" al menos
 * dos, y eso no se adivina viendo un botón apagado.
 */
@Composable
private fun BulkActionBar(
    selectedCount: Int,
    onUnir: () -> Unit,
    onMover: () -> Unit,
    onAsignar: () -> Unit,
    onAnular: () -> Unit,
    onCortesia: () -> Unit,
) {
    val hasSel = selectedCount > 0
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AvoqadoTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BulkAction("Unir", Icons.Filled.CallMerge, selectedCount >= 2, onUnir)
            BulkAction("Mover", Icons.Filled.DriveFileMove, selectedCount == 1, onMover)
            BulkAction("Asignar", Icons.Filled.Person, hasSel, onAsignar)
            BulkAction("Cortesía", Icons.Filled.CardGiftcard, hasSel, onCortesia)
            BulkAction("Anular", Icons.Filled.Cancel, hasSel, onAnular, destructive = true)
        }
    }
}

@Composable
private fun BulkAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge))
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * Ancho real de una mesa en el plano. Vive en UN solo lugar a propósito: el
 * bug de las mesas cortadas nació de que el dibujo usaba 1.5× para las
 * rectangulares y el cálculo de posición asumía el tamaño base. Si vuelve a
 * haber dos fórmulas, vuelve el corte.
 */
private fun tableNodeWidth(table: DiningTable, size: Dp): Dp =
    if (table.shape.uppercase(Locale.US) == "RECTANGLE") size * 1.5f else size

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloorCanvas(
    tables: List<DiningTable>,
    onTap: (DiningTable) -> Unit,
    onLongPress: (DiningTable) -> Unit,
    selectedIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val positioned = tables.filter { it.hasPosition }
    val unpositioned = tables.filter { !it.hasPosition }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            val minX = positioned.minOf { it.positionX!! }
            val maxX = positioned.maxOf { it.positionX!! }
            val minY = positioned.minOf { it.positionY!! }
            val maxY = positioned.maxOf { it.positionY!! }
            val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f
            val spanY = (maxY - minY).takeIf { it > 0f } ?: 1f

            val tableSize = 64.dp
            val availH = maxHeight - tableSize

            positioned.forEach { table ->
                val fx = (table.positionX!! - minX) / spanX
                val fy = (table.positionY!! - minY) / spanY
                // 🔴 El ancho se descuenta POR MESA, no con el tamaño base: una
                // mesa RECTANGLE mide 1.5× y la que caía pegada a la derecha se
                // salía media mesa del plano (se veía cortada; las redondas no,
                // porque esas sí miden lo que se descontaba).
                TableNode(
                    table = table,
                    size = tableSize,
                    selected = table.id in selectedIds,
                    modifier = Modifier
                        .offset(x = (maxWidth - tableNodeWidth(table, tableSize)) * fx, y = availH * fy)
                        .combinedClickable(
                            onClick = { onTap(table) },
                            onLongClick = { onLongPress(table) },
                        ),
                )
            }
        }

        if (unpositioned.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "Sin ubicación en el plano",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(vertical = AvoqadoTheme.spacing.sm),
            ) {
                items(unpositioned, key = { it.id }) { table ->
                    TableCard(table = table, onTap = { onTap(table) }, onLongPress = { onLongPress(table) }, selected = table.id in selectedIds)
                }
            }
        }
    }
}

// MARK: - Grid fallback (no floor layout)

@Composable
private fun TableGrid(
    tables: List<DiningTable>,
    onTap: (DiningTable) -> Unit,
    onLongPress: (DiningTable) -> Unit,
    selectedIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val byArea = tables.groupBy { it.areaName ?: "General" }
    LazyColumn(modifier = modifier) {
        byArea.forEach { (area, areaTables) ->
            item(key = "area_$area") {
                Text(
                    text = area,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                )
            }
            item(key = "grid_$area") {
                val rows = areaTables.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                            row.forEach { table ->
                                Box(modifier = Modifier.weight(1f)) {
                                    TableCard(table = table, onTap = { onTap(table) }, onLongPress = { onLongPress(table) }, selected = table.id in selectedIds)
                                }
                            }
                            repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Table visuals

private fun statusColor(table: DiningTable): Color = when {
    table.isOccupied -> OccupiedColor
    table.isReserved -> ReservedColor
    else -> Success
}

@Composable
private fun TableNode(table: DiningTable, size: Dp, selected: Boolean = false, modifier: Modifier = Modifier) {
    val shape = when (table.shape.uppercase(Locale.US)) {
        "ROUND" -> CircleShape
        else -> RoundedCornerShape(12.dp)
    }
    // Square-style: occupied = solid dark block, light text, elapsed h:mm;
    // free = flat light gray; reserved keeps the amber accent border.
    val occupied = table.isOccupied
    val fill = if (occupied) OccupiedColor else FreeFill
    val content = if (occupied) Color.White else Color(0xFF1A1A1A)
    val nowMs = rememberFloorClock()

    Column(
        modifier = modifier
            .size(width = tableNodeWidth(table, size), height = size)
            .clip(shape)
            .background(fill)
            .border(
                width = if (selected) 3.dp else if (table.isReserved) 2.dp else 1.dp,
                color = if (selected) Success else if (table.isReserved) ReservedColor else if (occupied) OccupiedColor else FreeBorder,
                shape = shape,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = table.number,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            textAlign = TextAlign.Center,
        )
        val sub = if (occupied) {
            if (table.openOrders.size > 1) {
                "${table.openOrders.size} cuentas"
            } else {
                elapsedLabel(table.currentOrder?.createdAt, nowMs) ?: table.currentOrder?.totalDisplay
            }
        } else {
            table.currentOrder?.totalDisplay
        }
        sub?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableCard(table: DiningTable, onTap: () -> Unit, onLongPress: () -> Unit = {}, selected: Boolean = false) {
    // Square-style, same language as the canvas nodes: occupied = solid dark
    // card with light text + "total · h:mm"; free = flat light gray.
    val occupied = table.isOccupied
    val fill = if (occupied) OccupiedColor else FreeFill
    val content = if (occupied) Color.White else Color(0xFF1A1A1A)
    val nowMs = rememberFloorClock()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(fill)
            .border(
                width = if (selected) 3.dp else if (table.isReserved) 2.dp else 1.dp,
                color = if (selected) Success else if (table.isReserved) ReservedColor else if (occupied) OccupiedColor else FreeBorder,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(AvoqadoTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Mesa ${table.number}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
        Text(
            text = when {
                occupied -> {
                    val total = table.currentOrder?.totalDisplay ?: "Ocupada"
                    val elapsed = elapsedLabel(table.currentOrder?.createdAt, nowMs)
                    if (elapsed != null) "$total · $elapsed" else total
                }
                table.isReserved -> "Reservada"
                else -> "Libre"
            },
            style = MaterialTheme.typography.bodySmall,
            color = content.copy(alpha = 0.75f),
        )
    }
}

// MARK: - Check sheet (Square's "Check" view)

@Composable
private fun TableCheckSheet(
    table: DiningTable,
    viewModel: TablesViewModel,
    onGoToCheckout: () -> Unit,
    onOpenTableOrder: () -> Unit,
) {
    val actionState by viewModel.actionState.collectAsState()
    var covers by remember { mutableIntStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg)
            .padding(bottom = AvoqadoTheme.spacing.xxl),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Mesa ${table.number}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    table.isOccupied -> "Ocupada"
                    table.isReserved -> "Reservada"
                    else -> "Libre"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(table),
                fontWeight = FontWeight.SemiBold,
            )
        }
        table.areaName?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        when (val state = actionState) {
            is TableActionState.Error -> StatusLine(state.message, MaterialTheme.colorScheme.error)
            is TableActionState.Success -> StatusLine(state.message, Success)
            else -> {}
        }

        when {
            // ── Free table: covers + open → NORMAL selling flow ─────────
            table.isAvailable -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Comensales", modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (covers > 1) covers-- }) { Icon(Icons.Filled.Remove, null) }
                    Text("$covers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { covers++ }) { Icon(Icons.Filled.Add, null) }
                }
                PrimaryButton(
                    text = if (actionState is TableActionState.Working) "Abriendo..." else "Abrir mesa y ordenar",
                    onClick = { viewModel.openTableAndStartOrdering(table, covers, onReady = onOpenTableOrder) },
                    enabled = actionState !is TableActionState.Working,
                    fullWidth = true,
                )
                // Escape de Square: abrir sin contar comensales (barra, para
                // llevar). Sin conteo no hay asientos ni propina por grupo.
                TextButton(
                    onClick = { viewModel.openTableAndStartOrdering(table, 0, onReady = onOpenTableOrder) },
                    enabled = actionState !is TableActionState.Working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sin comensales") }
            }

            // ── Occupied: check summary + the 3 Square actions ───────────
            table.isOccupied -> {
                val order = table.currentOrder
                // Comp dialog target: the line the user tapped (null = closed).
                var compTarget by remember { mutableStateOf<com.avoqado.pos.tables.data.TableOrderItem?>(null) }

                if (order != null && order.items.isNotEmpty()) {
                    Text(
                        text = "Cuenta (${order.itemCount})" + (order.waiter?.let { " · ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Square's course grouping: items under their tiempo header
                    // (only when at least one item carries a course).
                    val hasCourses = order.items.any { it.course != null }
                    val grouped = if (hasCourses) {
                        order.items.groupBy { it.course ?: "Inmediato" }
                    } else {
                        mapOf<String?, List<com.avoqado.pos.tables.data.TableOrderItem>>(null to order.items)
                    }
                    grouped.forEach { (course, courseItems) ->
                        if (course != null && hasCourses) {
                            Text(
                                text = course,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
                            )
                        }
                        courseItems.forEach { item ->
                            // Tap a line to comp it ("Dar de cortesía", Square-style).
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !item.isCortesia) { compTarget = item }
                                    .padding(vertical = 3.dp),
                            ) {
                                Text("${item.quantity}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.productName)
                                    if (item.isCortesia) {
                                        Text(
                                            text = "Cortesía" + (item.cortesiaReason?.let { " · $it" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Success,
                                        )
                                    }
                                }
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", item.total)}",
                                    color = if (item.isCortesia) Success else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm))
                    Row {
                        Text("Total", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(order.totalDisplay, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "Sin productos todavía — agrega la primera ronda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                // Square's flow: items are added on the dedicated table screen.
                PrimaryButton(
                    text = "Agregar productos",
                    onClick = { viewModel.startOrdering(table, onReady = onOpenTableOrder) },
                    fullWidth = true,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                if (order != null && order.total > 0) {
                    // Una sola puerta de cobro (auditoría): el register sembraba el
                    // TOTAL COMPLETO sin restar pagos parciales. Ahora este botón
                    // abre la pantalla de mesa, cuyo Pagar cobra solo lo restante.
                    PrimaryButton(
                        text = "Cobrar cuenta • ${order.totalDisplay}",
                        onClick = { viewModel.startOrdering(table, onReady = onOpenTableOrder) },
                        fullWidth = true,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                    TextButton(
                        onClick = { viewModel.printPreBill(table) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Imprimir cuenta")
                    }
                }

                compTarget?.let { target ->
                    CortesiaDialog(
                        productName = target.productName,
                        onDismiss = { compTarget = null },
                        onConfirm = { reason ->
                            compTarget = null
                            viewModel.compItem(table, target.id, reason)
                        },
                    )
                }

                // Square's "Anular cuenta": red text action, reason mandatory.
                var showAnularDialog by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showAnularDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Anular cuenta", color = Color(0xFFD32F2F))
                }
                if (showAnularDialog) {
                    AnularCuentaDialog(
                        tableNumber = table.number,
                        onDismiss = { showAnularDialog = false },
                        onConfirm = { reason ->
                            showAnularDialog = false
                            viewModel.anularCuenta(table, reason)
                        },
                    )
                }
            }

            else -> {
                Text(
                    text = "Mesa reservada — se abre desde el check-in de la reservación.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Square's "Artículo gratuito" dialog: comp one line with a MANDATORY reason
 * (`39_cortesia.png`). Confirm stays disabled until a reason is picked.
 */
@Composable
internal fun CortesiaDialog(
    productName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val reasons = listOf(
        "Error de entrada",
        "El cliente cambió de parecer",
        "Reclamo del cliente",
        "Amigos y familia",
        "Descuento de empleado",
        "Especial del administrador",
    )
    var selected by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dar de cortesía") },
        text = {
            Column {
                Text(
                    "\"$productName\" se queda en la cuenta pero deja de cobrarse.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text("Motivo", fontWeight = FontWeight.SemiBold)
                reasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = reason }
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == reason,
                            onClick = { selected = reason },
                        )
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text("Dar de cortesía")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Square's "Anular 1 cuenta" dialog: warning + mandatory reason (radio) —
 * confirm stays disabled until a reason is picked (`53_anular_dialog.png`).
 */
@Composable
internal fun AnularCuentaDialog(
    tableNumber: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val reasons = listOf(
        "Error de entrada",
        "El artículo ya no está disponible",
        "El cliente cambió de parecer",
    )
    var selected by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anular cuenta — Mesa $tableNumber") },
        text = {
            Column {
                Text(
                    "Se eliminarán todos los artículos y se cerrará la cuenta inmediatamente.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text("Razón de la anulación", fontWeight = FontWeight.SemiBold)
                reasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = reason }
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == reason,
                            onClick = { selected = reason },
                        )
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) { Text("Anular", color = if (selected != null) Color(0xFFD32F2F) else Color.Gray) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun StatusLine(message: String, color: Color) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
    )
}
