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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.PlanGateScreen
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.domain.FloorLayoutBuilder
import java.util.Locale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet

// Status palette (floor plan), Square-style: occupied = solid dark block with
// light text + elapsed h:mm timer; free = flat light gray; reserved = amber.
internal val OccupiedColor = Color(0xFF1A1A1A)
internal val ReservedColor = Color(0xFFE8A33D)
internal val FreeFill = Color(0xFFEDEDED)
internal val FreeBorder = Color(0xFFC9C9C9)

/**
 * "0:03" = 3 minutes, "1:12" = 1h12m — Square's exact elapsed format on the
 * canvas. [nowMs] is a ticking clock so the label refreshes while on screen.
 */
internal fun elapsedLabel(createdAt: String?, nowMs: Long): String? {
    // El mismo parseo que la etiqueta del lector de pantalla (un solo lugar, enmienda A4).
    val minutes = FloorLayoutBuilder.minutesSince(createdAt, nowMs) ?: return null
    val hours = minutes / 60
    // Pasado un día, "116:03" deja de comunicar (se lee como 116 minutos) y
    // además ESCONDE el problema real: una mesa lleva días sin cerrarse.
    // Mostrar "4d 20h" lo hace evidente de un vistazo.
    if (hours >= 24) return "${hours / 24}d ${hours % 24}h"
    return "$hours:${String.format(Locale.US, "%02d", minutes % 60)}"
}

/** Shared 30s clock for the floor's timers. */
@Composable
internal fun rememberFloorClock(): Long {
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
    isTablet: Boolean = false,
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
    val actionMessage by viewModel.actionMessage.collectAsState()
    // El aviso lo pinta el DESIGN SYSTEM, igual que en la pantalla del cheque:
    // centrado, rojo cuando es un fallo, y con subtítulo para decir qué hacer.
    // El `SnackbarHost` crudo salía gris —igual un éxito que un rechazo— y
    // descentrado. El consumo va en el `onDismiss`, nunca antes de pintar.
    val actionIsError by viewModel.actionIsError.collectAsState()
    val actionHint by viewModel.actionHint.collectAsState()
    val bulkCtx = androidx.compose.ui.platform.LocalContext.current
    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }
    val selectedOccupied = tables.filter { it.id in selectedIds && it.isOccupied }

    val layout by viewModel.layout.collectAsState()
    val rememberedTab by viewModel.rememberedTab.collectAsState()
    // Tablet con plano ⇒ el lienzo; tablet sin plano y teléfono ⇒ la lista con pestañas (spec 2026-10-09 §4.2-4.3).
    val showPlan = isTablet && layout.hasPlan
    val activeTabKey = FloorLayoutBuilder.pickActiveTab(if (showPlan) layout.tabs else layout.listTabs, rememberedTab)
    var showUnplaced by remember { mutableStateOf(false) }

    // Square's flow: tapping an OCCUPIED table goes STRAIGHT to the
    // table screen (no intermediate sheet); long-press keeps the check
    // sheet (cobrar/imprimir/anular). Free tables still open the covers
    // sheet on tap. (Plano en el POS: mismas dos acciones, ahora también desde la hoja «sin acomodar».)
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

    // Consulta sólo con la pantalla abierta y en primer plano (spec §4.1). Al volver de segundo plano entra de nuevo:
    // pinta lo guardado y pide lo fresco.
    LifecycleResumeEffect(Unit) {
        viewModel.startPolling()
        onPauseOrDispose { viewModel.stopPolling() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AvoqadoTheme.spacing.lg),
    ) {
        if (showPlan) {
            FloorTopBar(
                tabs = layout.tabs,
                activeKey = activeTabKey,
                onSelectTab = viewModel::selectTab,
                unplacedCount = layout.unplaced.size,
                onShowUnplaced = { showUnplaced = true },
                selectionMode = selectionMode,
                selectedCount = selectedOccupied.size,
                occupiedCount = tables.count { it.isOccupied },
                totalCount = tables.size,
                isLoading = isLoading,
                onRefresh = { viewModel.refresh() },
                onAcciones = { selectionMode = true },
                onListo = { exitSelection() },
            )
        } else {
            TablesHeader(
                selectionMode = selectionMode,
                selectedCount = selectedOccupied.size,
                occupiedCount = tables.count { it.isOccupied },
                totalCount = tables.size,
                isLoading = isLoading,
                onAcciones = { selectionMode = true },
                onListo = { exitSelection() },
                onRefresh = { viewModel.refresh() },
            )
            if (tables.isNotEmpty()) {
                FloorAreaTabs(
                    tabs = layout.listTabs,
                    activeKey = activeTabKey,
                    onSelect = viewModel::selectTab,
                    modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
                )
            }
        }

        if (tables.isEmpty() && !isLoading) {
            EmptyFloor()
        } else {
            if (showPlan) {
                if (layout.overLimit) {
                    Text(
                        text = "Este plano tiene más piezas de las que el POS puede mostrar. Revísalo en tu computadora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
                    )
                }
                val tab = layout.tabs.firstOrNull { it.key == activeTabKey }
                if (tab != null) {
                    FloorPlanCanvas(
                        tab = tab,
                        placed = layout.placedByTab[tab.key].orEmpty(),
                        elements = layout.elementsByTab[tab.key].orEmpty(),
                        selectedIds = if (selectionMode) selectedIds else emptySet(),
                        onTap = onTableTap,
                        onLongPress = onTableLongPress,
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = AvoqadoTheme.spacing.md),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                if (isTablet && !layout.hasPlan && viewModel.canConfigureTables) {
                    Text(
                        text = "Arma el plano en tu computadora: Configuración › Mesas y plano",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
                    )
                }
                if (tables.isEmpty()) {
                    Spacer(modifier = Modifier.weight(1f)) // primera carga sin nada guardado
                } else {
                    TableAreaList(
                        tables = activeTabKey?.let { layout.listByTab[it] }.orEmpty(),
                        onTap = onTableTap,
                        onLongPress = onTableLongPress,
                        selectedIds = if (selectionMode) selectedIds else emptySet(),
                        modifier = Modifier.weight(1f),
                    )
                }
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

        // El resultado de una acción masiva se ancla abajo, dentro de la app: un
        // Toast lo dibuja el sistema fuera y en la Sunmi con pantalla de cliente
        // no hay garantía de en cuál de las dos aparece.
        actionMessage?.let { mensaje ->
            if (actionIsError) {
                AvoqadoErrorToast(
                    message = mensaje,
                    subtitle = actionHint,
                    onDismiss = { viewModel.consumeActionMessage() },
                )
            } else {
                AvoqadoSuccessToast(
                    message = mensaje,
                    onDismiss = { viewModel.consumeActionMessage() },
                )
            }
        }
    }

    if (showBulkUnir) {
        // Hay que elegir CUÁL mesa se queda con la cuenta: el server cierra las
        // otras, así que la decisión no puede ser implícita.
        AvoqadoDialog(
            title = "Unir ${selectedOccupied.size} cuentas",
            description = "¿En qué mesa se queda la cuenta? Las otras mesas se liberan y TODOS sus artículos pasan a ella, en una sola cuenta.",
            onDismiss = { showBulkUnir = false },
        ) {
            Column {
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
                                if (fail > 0) viewModel.showError(msg) else viewModel.showMessage(msg)
                                exitSelection()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Mesa ${table.number}", modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }

    if (showBulkAnular) {
        AnularCuentaDialog(
            tableNumber = selectedOccupied.joinToString(", ") { it.number },
            onDismiss = { showBulkAnular = false },
            onConfirm = { reason ->
                showBulkAnular = false
                viewModel.bulkAnular(selectedOccupied, reason) { ok, fail, motivo ->
                    val msg = "$ok anuladas" + (if (fail > 0) ", $fail fallaron" else "")
                    // El motivo de la primera que falló va como PISTA: con fila enfrente lo que
                    // hace falta es el siguiente movimiento, no sólo el conteo.
                    if (fail > 0) viewModel.showError(msg, motivo) else viewModel.showMessage(msg)
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
                    val msg = "$ok cuentas de cortesía" + (if (fail > 0) ", $fail fallaron" else "")
                    if (fail > 0) viewModel.showError(msg) else viewModel.showMessage(msg)
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
                    viewModel.showMessage("$ok asignadas a ${staff.fullName}" + (if (fail > 0) ", $fail fallaron" else ""))
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
            AvoqadoDialog(
                title = "Mover cuenta — Mesa ${source.number}",
                onDismiss = { showBulkMover = false },
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    freeTables.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBulkMover = false
                                    viewModel.bulkMover(source, t.id) { ok, msg ->
                                        viewModel.showMessage(msg)
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
            }
        }
    }

    if (showUnplaced) {
        UnplacedTablesSheet(
            tables = layout.unplaced,
            selectionMode = selectionMode,
            selectedIds = if (selectionMode) selectedIds else emptySet(),
            onTap = onTableTap,
            onLongPress = onTableLongPress,
            onDismiss = { showUnplaced = false },
        )
    }

    if (selectedTableId != null) {
        val table = tables.firstOrNull { it.id == selectedTableId }
        if (table != null) {
            AvoqadoModalBottomSheet(
                onDismissRequest = { viewModel.selectTable(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                com.avoqado.pos.designsystem.components.ImmersiveWindow()
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

// MARK: - Encabezado (teléfono y tablet sin plano): el de siempre

@Composable
private fun TablesHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    occupiedCount: Int,
    totalCount: Int,
    isLoading: Boolean,
    onAcciones: () -> Unit,
    onListo: () -> Unit,
    onRefresh: () -> Unit,
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
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
                }
            }
        }
    }
}

// MARK: - Acciones masivas

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
            BulkAction("Unir", Icons.AutoMirrored.Filled.CallMerge, selectedCount >= 2, onUnir)
            BulkAction("Mover", Icons.AutoMirrored.Filled.DriveFileMove, selectedCount == 1, onMover)
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

// MARK: - Table visuals

private fun statusColor(table: DiningTable): Color = when {
    table.isOccupied -> OccupiedColor
    table.isReserved -> ReservedColor
    else -> Success
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableCard(table: DiningTable, onTap: () -> Unit, onLongPress: () -> Unit = {}, selected: Boolean = false) {
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
                    val total = table.primaryCheck?.totalDisplay ?: "Ocupada"
                    val elapsed = elapsedLabel(table.primaryCheck?.createdAt, nowMs)
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
                // El dinero se lee del resumen, NO del puntero denormalizado: si
                // `currentOrder` viene nulo (M9 con 2 cuentas), el sheet escondía
                // "Cobrar" por completo y decía "Sin productos todavía" sobre una
                // mesa con $364 vivos.
                val check = table.primaryCheck
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
                        text = if (check != null && check.total > 0) {
                            // Hay cuenta y hay dinero, sólo no llegaron los renglones:
                            // decir "sin productos" sería mentir sobre el saldo.
                            "Cuenta ${check.orderNumber} • ${check.totalDisplay} — ábrela para ver el detalle."
                        } else {
                            "Sin productos todavía — agrega la primera ronda."
                        },
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

                if (check != null && check.total > 0) {
                    // Una sola puerta de cobro (auditoría): el register sembraba el
                    // TOTAL COMPLETO sin restar pagos parciales. Ahora este botón
                    // abre la pantalla de mesa, cuyo Pagar cobra solo lo restante.
                    PrimaryButton(
                        text = "Cobrar cuenta • ${check.totalDisplay}",
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

    AvoqadoDialog(
        title = "Dar de cortesía",
        description = "\"$productName\" se queda en la cuenta pero deja de cobrarse.",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Dar de cortesía",
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                fullWidth = true,
                destructive = true,
            )
        },
    ) {
        Column {
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
    }
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

    AvoqadoDialog(
        title = "Anular cuenta — Mesa $tableNumber",
        description = "Se eliminarán todos los artículos y se cerrará la cuenta inmediatamente.",
        onDismiss = onDismiss,
        // Destructivo e irreversible: fuera del click al vacío. Se sale por la X
        // o por Atrás, nunca por un toque accidental en el fondo.
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Anular",
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                fullWidth = true,
                destructive = true,
            )
        },
    ) {
        Column {
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
    }
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
