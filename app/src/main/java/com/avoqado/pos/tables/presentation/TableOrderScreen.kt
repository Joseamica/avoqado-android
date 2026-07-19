package com.avoqado.pos.tables.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductGridView
import com.avoqado.pos.pos.presentation.search.SearchOverlayView
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TABLE_SERVICE (PRO) — the DEDICATED table screen (Square's check view).
 * Isolated from the quick-sale register: left = the shared product grid,
 * right = the two-card check panel (sent per course with fire times + local
 * pending course slots). "Pagar" hands off to the register's proven PAYING
 * seam; everything ordering-related lives here.
 */
@Composable
fun TableOrderScreen(
    isTablet: Boolean,
    onExit: () -> Unit,
    onPagar: () -> Unit,
    viewModel: TableOrderViewModel = hiltViewModel(),
    catalogViewModel: CartViewModel = hiltViewModel(),
) {
    // SNAPSHOT, not collected: this screen OWNS the session — clearing it on
    // send/exit must not recompose into the null-guard (that double-fired
    // onExit and popped the NavHost empty → blank screen).
    // Snapshot MUTABLE: colectar la sesión causaba double-pop, pero el selector
    // de cuenta necesita reemplazarlo al cambiar de cheque.
    var session by remember { mutableStateOf(viewModel.tableSession.current()) }
    val check by viewModel.check.collectAsState()
    val isLoadingCheck by viewModel.isLoadingCheck.collectAsState()
    val pendingLines by viewModel.pending.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val extraCourses by viewModel.extraCourses.collectAsState()
    val hideSent by viewModel.hideSent.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val loyalty by viewModel.loyalty.collectAsState()
    val serviceChargeOptions by viewModel.serviceCharges.collectAsState()
    val menus by viewModel.menus.collectAsState()
    val selectedMenuId by viewModel.selectedMenuId.collectAsState()
    val menuCategoryIds by viewModel.menuCategoryIds.collectAsState()
    val floorTables by viewModel.floorTables.collectAsState()

    val context = LocalContext.current
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // Square's right-panel tabs: Cuenta (the check) / Acciones (the catalog).
    var panelTab by remember { mutableStateOf(PanelTab.CUENTA) }
    var showCustomAmount by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showAssignSheet by remember { mutableStateOf(false) }
    var showWholeCortesia by remember { mutableStateOf(false) }
    var showNameNotesDialog by remember { mutableStateOf(false) }
    var showCoversDialog by remember { mutableStateOf(false) }
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showFulfillmentDialog by remember { mutableStateOf(false) }
    var showDiscountsDialog by remember { mutableStateOf(false) }
    var showSplitCheckDialog by remember { mutableStateOf(false) }
    var showSortCartDialog by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }
    var showTimeClock by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showRewards by remember { mutableStateOf(false) }
    var showCheckSwitcher by remember { mutableStateOf(false) }
    var showServiceCharges by remember { mutableStateOf(false) }
    var showMenus by remember { mutableStateOf(false) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    // Menú … por tiempo (¡Listo!/Repetir). Par (curso, abierto).
    var courseMenuTarget by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showAnularDialog by remember { mutableStateOf(false) }
    var compTarget by remember { mutableStateOf<OrderDetailItem?>(null) }
    var showPhoneCheck by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadCheck() }
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeActionMessage()
        }
    }

    // No session (e.g. process restore) — nothing to work on, back to the floor.
    var exited by remember { mutableStateOf(false) }
    fun exitOnce() {
        if (!exited) {
            exited = true
            onExit()
        }
    }
    if (session == null) {
        LaunchedEffect(Unit) { exitOnce() }
        return
    }
    val active = session ?: return
    val floorTable = floorTables.firstOrNull { it.id == active.tableId }

    fun requestExit() {
        if (pendingLines.isNotEmpty()) showDiscardDialog = true
        else {
            viewModel.exitToFloor()
            exitOnce()
        }
    }
    BackHandler { requestExit() }

    fun handleProductTap(product: Product) {
        if (product.hasModifiers) selectedProduct = product else viewModel.addProduct(product)
    }

    fun fireSend() {
        viewModel.sendRound { ok, msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            if (ok) exitOnce()
        }
    }

    fun firePagar() {
        if (viewModel.preparePagar()) onPagar()
        else android.widget.Toast.makeText(context, "La cuenta no tiene cargos todavía", android.widget.Toast.LENGTH_SHORT).show()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TableContextBar(
            label = active.label,
            covers = floorTable?.currentOrder?.covers,
            openedAt = floorTable?.currentOrder?.createdAt,
            onBack = { requestExit() },
            // Multi-cheque: cuál de las cuentas de la mesa estamos viendo.
            checkIndex = floorTable?.openOrders?.indexOfFirst { it.id == active.orderId }?.takeIf { it >= 0 },
            checkCount = floorTable?.openOrders?.size ?: 0,
            onSwitchCheck = { showCheckSwitcher = true },
        )
        HorizontalDivider()

        if (isTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.5f).fillMaxHeight()) {
                    if (showSearch) {
                        SearchOverlayView(
                            viewModel = catalogViewModel,
                            onProductTap = { handleProductTap(it); showSearch = false },
                            onDismiss = { showSearch = false },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TableSearchBar(onSearchTap = { showSearch = true })
                            ProductGridView(
                                viewModel = catalogViewModel,
                                onProductTap = { handleProductTap(it) },
                                menuCategoryIds = menuCategoryIds,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier.width(1.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Box(modifier = Modifier.weight(0.5f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PanelTabsRow(selected = panelTab, onSelect = { panelTab = it })
                        when (panelTab) {
                            PanelTab.CUENTA -> TableCheckPanel(
                                check = check,
                                isLoadingCheck = isLoadingCheck,
                                pendingLines = pendingLines,
                                selectedCourse = selectedCourse,
                                extraCourses = extraCourses,
                                hideSent = hideSent,
                                isSending = isSending,
                                onToggleHideSent = { viewModel.toggleHideSent() },
                                onSelectCourse = { viewModel.selectCourse(it) },
                                onAddCourse = { viewModel.addExtraCourse() },
                                onRemovePending = { viewModel.removePending(it) },
                                onCycleSeat = { id ->
                                    val maxSeats = (check?.covers ?: floorTable?.currentOrder?.covers ?: 4).coerceAtLeast(1)
                                    viewModel.cyclePendingSeat(id, maxSeats)
                                },
                                onSentItemTap = { item -> if (!item.isCortesia) compTarget = item },
                                onCourseMenu = { c -> courseMenuTarget = c to true },
                                pendingCount = viewModel.pendingCount,
                                pendingTotalCents = viewModel.pendingTotalCents,
                                onEnviar = { fireSend() },
                                onPagar = { firePagar() },
                                onGuardar = { requestExit() },
                            )
                            PanelTab.ACCIONES -> TableActionsPanel(
                                hasPending = pendingLines.isNotEmpty(),
                                hasSent = !check?.items.isNullOrEmpty(),
                                onClearPending = { viewModel.clearPending(); panelTab = PanelTab.CUENTA },
                                onAnular = { showAnularDialog = true },
                                onPrintPreBill = { viewModel.printPreBill() },
                                onReprintComandas = { viewModel.reprintComandas() },
                                onCustomAmount = { showCustomAmount = true },
                                onMover = {
                                    if (pendingLines.isNotEmpty()) {
                                        android.widget.Toast.makeText(context, "Envía o borra los artículos pendientes antes de mover", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        showMoveDialog = true
                                    }
                                },
                                onAsignar = {
                                    catalogViewModel.fetchStaff()
                                    showAssignSheet = true
                                },
                                onCompWhole = { showWholeCortesia = true },
                                onDividir = {
                                    if (pendingLines.isNotEmpty()) {
                                        android.widget.Toast.makeText(context, "Envía o borra los artículos pendientes antes de dividir", android.widget.Toast.LENGTH_LONG).show()
                                    } else if (viewModel.preparePagar(openSplit = true)) {
                                        exited = true
                                        onPagar()
                                    } else {
                                        android.widget.Toast.makeText(context, "La cuenta no tiene cargos todavía", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNameNotes = { showNameNotesDialog = true },
                                onCovers = { showCoversDialog = true },
                                onCumplimiento = { showFulfillmentDialog = true },
                                onDescuentos = { showDiscountsDialog = true },
                                onSepararCuenta = { showSplitCheckDialog = true },
                                onOrdenarCarrito = { showSortCartDialog = true },
                                onCalculadora = { showCalculator = true },
                                onMarcarEntrada = { showTimeClock = true },
                                onCajaAbierta = { viewModel.openCashDrawer() },
                                hasCashDrawer = viewModel.hasCashDrawer,
                                onEscanear = { showBarcodeScanner = true },
                                onRecompensas = { showRewards = true },
                                onCobrosServicio = { viewModel.loadServiceCharges(); showServiceCharges = true },
                                onMenus = { viewModel.loadMenus(); showMenus = true },
                                hasLoyalty = loyalty?.canRedeem == true,
                            )
                            PanelTab.CLIENTE -> TableClientePanel(
                                customerName = check?.customerName,
                                onPickCustomer = { showCustomerPicker = true },
                                onDetachCustomer = { viewModel.updateDetails(customerId = "") },
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSearch) {
                    Box(modifier = Modifier.weight(1f)) {
                        SearchOverlayView(
                            viewModel = catalogViewModel,
                            onProductTap = { handleProductTap(it); showSearch = false },
                            onDismiss = { showSearch = false },
                        )
                    }
                } else {
                    TableSearchBar(onSearchTap = { showSearch = true })
                    Box(modifier = Modifier.weight(1f)) {
                        ProductGridView(
                            viewModel = catalogViewModel,
                            onProductTap = { handleProductTap(it) },
                        )
                    }
                }
                // Phone bottom bar → full-screen check
                val totalCents = checkTotalCents(check) + viewModel.pendingTotalCents
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .clickable { showPhoneCheck = true }
                        .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Ver cheque",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = centsDisplay(totalCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
            if (showPhoneCheck) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { showPhoneCheck = false }) { Text("Cerrar") }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(active.label, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        PanelTabsRow(selected = panelTab, onSelect = { panelTab = it })
                        when (panelTab) {
                            PanelTab.CUENTA -> TableCheckPanel(
                                check = check,
                                isLoadingCheck = isLoadingCheck,
                                pendingLines = pendingLines,
                                selectedCourse = selectedCourse,
                                extraCourses = extraCourses,
                                hideSent = hideSent,
                                isSending = isSending,
                                onToggleHideSent = { viewModel.toggleHideSent() },
                                onSelectCourse = { viewModel.selectCourse(it) },
                                onAddCourse = { viewModel.addExtraCourse() },
                                onRemovePending = { viewModel.removePending(it) },
                                onCycleSeat = { id ->
                                    val maxSeats = (check?.covers ?: floorTable?.currentOrder?.covers ?: 4).coerceAtLeast(1)
                                    viewModel.cyclePendingSeat(id, maxSeats)
                                },
                                onSentItemTap = { item -> if (!item.isCortesia) compTarget = item },
                                onCourseMenu = { c -> courseMenuTarget = c to true },
                                pendingCount = viewModel.pendingCount,
                                pendingTotalCents = viewModel.pendingTotalCents,
                                onEnviar = { showPhoneCheck = false; fireSend() },
                                onPagar = { showPhoneCheck = false; firePagar() },
                                onGuardar = { showPhoneCheck = false; requestExit() },
                            )
                            PanelTab.ACCIONES -> TableActionsPanel(
                                hasPending = pendingLines.isNotEmpty(),
                                hasSent = !check?.items.isNullOrEmpty(),
                                onClearPending = { viewModel.clearPending(); panelTab = PanelTab.CUENTA },
                                onAnular = { showAnularDialog = true },
                                onPrintPreBill = { viewModel.printPreBill() },
                                onReprintComandas = { viewModel.reprintComandas() },
                                onCustomAmount = { showCustomAmount = true },
                                onMover = {
                                    if (pendingLines.isNotEmpty()) {
                                        android.widget.Toast.makeText(context, "Envía o borra los artículos pendientes antes de mover", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        showMoveDialog = true
                                    }
                                },
                                onAsignar = {
                                    catalogViewModel.fetchStaff()
                                    showAssignSheet = true
                                },
                                onCompWhole = { showWholeCortesia = true },
                                onDividir = {
                                    if (pendingLines.isNotEmpty()) {
                                        android.widget.Toast.makeText(context, "Envía o borra los artículos pendientes antes de dividir", android.widget.Toast.LENGTH_LONG).show()
                                    } else if (viewModel.preparePagar(openSplit = true)) {
                                        exited = true
                                        onPagar()
                                    } else {
                                        android.widget.Toast.makeText(context, "La cuenta no tiene cargos todavía", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNameNotes = { showNameNotesDialog = true },
                                onCovers = { showCoversDialog = true },
                                onCumplimiento = { showFulfillmentDialog = true },
                                onDescuentos = { showDiscountsDialog = true },
                                onSepararCuenta = { showSplitCheckDialog = true },
                                onOrdenarCarrito = { showSortCartDialog = true },
                                onCalculadora = { showCalculator = true },
                                onMarcarEntrada = { showTimeClock = true },
                                onCajaAbierta = { viewModel.openCashDrawer() },
                                hasCashDrawer = viewModel.hasCashDrawer,
                                onEscanear = { showBarcodeScanner = true },
                                onRecompensas = { showRewards = true },
                                onCobrosServicio = { viewModel.loadServiceCharges(); showServiceCharges = true },
                                onMenus = { viewModel.loadMenus(); showMenus = true },
                                hasLoyalty = loyalty?.canRedeem == true,
                            )
                            PanelTab.CLIENTE -> TableClientePanel(
                                customerName = check?.customerName,
                                onPickCustomer = { showCustomerPicker = true },
                                onDetachCustomer = { viewModel.updateDetails(customerId = "") },
                            )
                        }
                    }
                }
            }
        }
    }

    // Product detail (modifiers/notes) — adds land on the SELECTED course.
    selectedProduct?.let { product ->
        ProductDetailPanel(
            product = product,
            isTablet = isTablet,
            onAddToCart = { quantity, modifiers, note, isCortesia, cortesiaReason, _, _ ->
                viewModel.addProductWithModifiers(
                    product, quantity, modifiers, note,
                    isCortesia = isCortesia, cortesiaReason = cortesiaReason,
                )
                selectedProduct = null
            },
            onDismiss = { selectedProduct = null },
        )
    }

    if (showMoveDialog) {
        val freeTables = floorTables.filter { it.isAvailable && it.id != active.tableId }
        MoveTableDialog(
            tables = freeTables,
            onDismiss = { showMoveDialog = false },
            onConfirm = { targetId ->
                showMoveDialog = false
                viewModel.moveTable(targetId) { ok, msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    if (ok) exitOnce()
                }
            },
        )
    }

    if (showAssignSheet) {
        val staffOptions by catalogViewModel.staffOptions.collectAsState()
        val isStaffLoading by catalogViewModel.isStaffLoading.collectAsState()
        val staffError by catalogViewModel.staffError.collectAsState()
        com.avoqado.pos.pos.presentation.cart.StaffSelectorSheet(
            staff = staffOptions,
            selectedStaffId = "",
            isLoading = isStaffLoading,
            error = staffError,
            onStaffSelected = { staff ->
                showAssignSheet = false
                viewModel.assignWaiter(staff.id, staff.fullName)
            },
            onDismiss = { showAssignSheet = false },
        )
    }

    courseMenuTarget?.takeIf { it.second }?.let { (course, _) ->
        val count = check?.items?.filter { it.course == course }?.sumOf { it.quantity } ?: 0
        AlertDialog(
            onDismissRequest = { courseMenuTarget = null },
            title = { Text(course ?: "Inmediato") },
            text = { Text("$count artículo(s) en este tiempo.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        courseMenuTarget = null
                        viewModel.marcharCourse(course)
                    }) { Text("¡Listo!") }
                    TextButton(onClick = {
                        courseMenuTarget = null
                        viewModel.repeatCourse(course)
                        panelTab = PanelTab.CUENTA
                    }) { Text("Repetir") }
                }
            },
            dismissButton = { TextButton(onClick = { courseMenuTarget = null }) { Text("Cancelar") } },
        )
    }

    // "Escanear" (Acciones): mismo escáner que la venta rápida — el código se
    // busca contra el catálogo YA cacheado (sku/gtin viajan en /products).
    if (showBarcodeScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView(
                onBarcodeScanned = { barcode ->
                    showBarcodeScanner = false
                    val matched = catalogViewModel.products.value.find { product ->
                        product.sku == barcode || product.barcode == barcode || product.gtin == barcode
                    }
                    if (matched != null) {
                        handleProductTap(matched)
                        panelTab = PanelTab.CUENTA
                    } else {
                        unknownBarcode = barcode
                    }
                },
                onDismiss = { showBarcodeScanner = false },
            )
        }
    }

    unknownBarcode?.let { scannedCode ->
        AlertDialog(
            onDismissRequest = { unknownBarcode = null },
            title = { Text("Producto no encontrado") },
            text = { Text("Ningún producto del catálogo tiene el código $scannedCode.") },
            confirmButton = { TextButton(onClick = { unknownBarcode = null }) { Text("Entendido") } },
        )
    }

    if (showCheckSwitcher) {
        val cuentas = floorTable?.openOrders ?: emptyList()
        AlertDialog(
            onDismissRequest = { showCheckSwitcher = false },
            title = { Text("Cuentas de la mesa") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    cuentas.forEachIndexed { index, cuenta ->
                        val esActual = cuenta.id == session?.orderId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !esActual) {
                                    showCheckSwitcher = false
                                    viewModel.switchToCheck(cuenta)?.let { session = it }
                                }
                                .padding(vertical = AvoqadoTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cuenta.name?.takeIf { it.isNotBlank() } ?: "Cuenta ${index + 1}",
                                    fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    text = "${cuenta.itemCount} artículo(s)" + (cuenta.waiterName?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(cuenta.totalDisplay, fontWeight = FontWeight.SemiBold)
                            if (esActual) {
                                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                                Text("Actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCheckSwitcher = false }) { Text("Cerrar") } },
        )
    }

    if (showMenus) {
        MenusDialog(
            menus = menus,
            selectedMenuId = selectedMenuId,
            onDismiss = { showMenus = false },
            onPick = { id ->
                showMenus = false
                viewModel.selectMenu(id)
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showServiceCharges) {
        ServiceChargesDialog(
            options = serviceChargeOptions,
            applied = check?.serviceCharges ?: emptyList(),
            onDismiss = { showServiceCharges = false },
            onApply = { id -> viewModel.applyServiceCharge(id); panelTab = PanelTab.CUENTA },
            onRemove = { id -> viewModel.removeServiceCharge(id) },
        )
    }

    if (showRewards) {
        loyalty?.let { l ->
            RewardsDialog(
                loyalty = l,
                onDismiss = { showRewards = false },
                onRedeem = { pts ->
                    showRewards = false
                    viewModel.redeemPoints(pts)
                    panelTab = PanelTab.CUENTA
                },
            )
        }
    }

    if (showSortCartDialog) {
        SortCartDialog(
            onDismiss = { showSortCartDialog = false },
            onPick = { mode ->
                showSortCartDialog = false
                viewModel.sortPending(mode)
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showCalculator) {
        CalculatorDialog(onDismiss = { showCalculator = false })
    }

    if (showTimeClock) {
        com.avoqado.pos.timeclock.presentation.TimeClockSheet(
            repository = viewModel.timeEntryRepository,
            onDismiss = { showTimeClock = false },
        )
    }

    if (showSplitCheckDialog) {
        SplitCheckDialog(
            items = check?.items ?: emptyList(),
            onDismiss = { showSplitCheckDialog = false },
            onConfirm = { ids ->
                showSplitCheckDialog = false
                viewModel.splitItems(ids)
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showFulfillmentDialog) {
        FulfillmentDialog(
            current = check?.orderType ?: "DINE_IN",
            onDismiss = { showFulfillmentDialog = false },
            onConfirm = { type ->
                showFulfillmentDialog = false
                viewModel.updateDetails(orderType = type)
            },
        )
    }

    if (showDiscountsDialog) {
        val allDiscounts by catalogViewModel.discountsRepository.discounts.collectAsState()
        OrderDiscountsDialog(
            available = allDiscounts.filter { it.scope == "ORDER" && it.active },
            applied = check?.discounts ?: emptyList(),
            onApply = { id -> viewModel.applyDiscount(id) },
            onRemove = { id -> viewModel.removeDiscount(id) },
            onDismiss = { showDiscountsDialog = false },
        )
    }

    if (showWholeCortesia) {
        WholeCortesiaDialog(
            onDismiss = { showWholeCortesia = false },
            onConfirm = { reason ->
                showWholeCortesia = false
                viewModel.compWholeCheck(reason)
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showNameNotesDialog) {
        NameNotesDialog(
            initialName = check?.customerName.orEmpty(),
            initialNotes = check?.specialRequests.orEmpty(),
            onDismiss = { showNameNotesDialog = false },
            onConfirm = { name, notes ->
                showNameNotesDialog = false
                viewModel.updateDetails(name = name, notes = notes)
            },
        )
    }

    if (showCoversDialog) {
        CoversDialog(
            initial = check?.covers ?: floorTable?.currentOrder?.covers ?: 2,
            onDismiss = { showCoversDialog = false },
            onConfirm = { covers ->
                showCoversDialog = false
                viewModel.updateDetails(covers = covers)
            },
        )
    }

    if (showCustomerPicker) {
        val customersViewModel: com.avoqado.pos.customers.presentation.CustomersViewModel =
            androidx.hilt.navigation.compose.hiltViewModel()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            com.avoqado.pos.customers.presentation.CustomersView(
                viewModel = customersViewModel,
                onCustomerSelected = { customer ->
                    showCustomerPicker = false
                    viewModel.updateDetails(customerId = customer.id)
                },
                onDismiss = { showCustomerPicker = false },
                onCreateCustomer = { },
                canCreateCustomer = false,
            )
        }
    }

    if (showCustomAmount) {
        CustomAmountDialog(
            onDismiss = { showCustomAmount = false },
            onConfirm = { name, cents ->
                showCustomAmount = false
                viewModel.addCustomAmount(name, cents)
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Artículos sin enviar") },
            text = { Text("Tienes ${viewModel.pendingCount} artículo(s) sin enviar a cocina. Si sales ahora se descartan.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    viewModel.exitToFloor()
                    exitOnce()
                }) { Text("Descartar y salir", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("Seguir aquí") } },
        )
    }

    compTarget?.let { target ->
        CortesiaDialog(
            productName = target.productName ?: "Artículo",
            onDismiss = { compTarget = null },
            onConfirm = { reason ->
                compTarget = null
                viewModel.compSentItem(target.id, reason)
            },
        )
    }

    if (showAnularDialog) {
        AnularCuentaDialog(
            tableNumber = active.tableNumber,
            onDismiss = { showAnularDialog = false },
            onConfirm = { reason ->
                showAnularDialog = false
                viewModel.anularCuenta(reason) { ok, msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    if (ok) exitOnce()
                }
            },
        )
    }
}

// MARK: - Context bar

@Composable
private fun TableContextBar(
    label: String,
    covers: Int?,
    openedAt: String?,
    onBack: () -> Unit,
    checkIndex: Int? = null,
    checkCount: Int = 0,
    onSwitchCheck: () -> Unit = {},
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver al plano", modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val parts = buildList {
                covers?.let { add("$it persona${if (it == 1) "" else "s"}") }
                elapsedSince(openedAt, nowMs)?.let { add(it) }
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Solo aparece cuando la mesa TIENE varias cuentas: deja claro en cuál
        // estás y permite cambiar sin volver al plano.
        if (checkCount > 1 && checkIndex != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onSwitchCheck)
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cuenta ${checkIndex + 1} de $checkCount",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Cambiar de cuenta",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// MARK: - Check panel (the two Square cards)

@Composable
internal fun TableCheckPanel(
    check: OrderDetail?,
    isLoadingCheck: Boolean,
    pendingLines: List<TableOrderViewModel.PendingLine>,
    selectedCourse: String?,
    extraCourses: List<String>,
    hideSent: Boolean,
    isSending: Boolean,
    onToggleHideSent: () -> Unit,
    onSelectCourse: (String?) -> Unit,
    onAddCourse: () -> Unit,
    onRemovePending: (String) -> Unit,
    onCycleSeat: (String) -> Unit = {},
    onSentItemTap: (OrderDetailItem) -> Unit,
    onCourseMenu: (String?) -> Unit = {},
    pendingCount: Int,
    pendingTotalCents: Int,
    onEnviar: () -> Unit,
    onPagar: () -> Unit,
    onGuardar: () -> Unit,
) {
    val sentItems = check?.items.orEmpty()
    val hasPending = pendingLines.isNotEmpty()
    val subtotalCents = checkTotalCents(check) + pendingTotalCents
    val itemCount = sentItems.sumOf { it.quantity } + pendingCount

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.md),
        ) {
            if (isLoadingCheck && check == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
            }

            // ── Gray card: what the kitchen already has ──────────────────
            if (sentItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Ocultar artículos enviados a la cocina",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = hideSent, onCheckedChange = { onToggleHideSent() })
                }
                if (!hideSent) {
                    SentCard(sentItems = sentItems, onSentItemTap = onSentItemTap, onCourseMenu = onCourseMenu)
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                }
            }

            // ── White card: pending course slots ─────────────────────────
            PendingCard(
                pendingLines = pendingLines,
                selectedCourse = selectedCourse,
                extraCourses = extraCourses,
                onSelectCourse = onSelectCourse,
                onAddCourse = onAddCourse,
                onRemovePending = onRemovePending,
                onCycleSeat = onCycleSeat,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // Subtotal (N) + Total — Square shows both on the check card.
            val sentSubtotalCents = check?.let {
                val sub = kotlin.math.round(it.subtotal * 100).toInt()
                if (sub > 0) sub else kotlin.math.round(it.total * 100).toInt()
            } ?: 0
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AvoqadoTheme.spacing.xs)) {
                Text(
                    text = "Subtotal ($itemCount)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = centsDisplay(sentSubtotalCents + pendingTotalCents),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            check?.takeIf { it.discountAmount > 0 }?.let { c ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AvoqadoTheme.spacing.xs)) {
                    Text(
                        text = "Descuentos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Success,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "-" + centsDisplay(kotlin.math.round(c.discountAmount * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Success,
                    )
                }
            }
            // Cobros por servicio: SUMAN (ingreso gravable), por eso van con "+"
            // y sin el verde de descuento.
            check?.takeIf { it.serviceChargeAmount > 0 }?.let { c ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AvoqadoTheme.spacing.xs)) {
                    Text(
                        text = "Cobros por servicio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "+" + centsDisplay(kotlin.math.round(c.serviceChargeAmount * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AvoqadoTheme.spacing.xs)) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = centsDisplay(subtotalCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── CTA hierarchy swap (Square) ─────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            if (hasPending) {
                OutlinedButton(
                    onClick = onPagar,
                    enabled = false,
                    modifier = Modifier.weight(0.35f).height(AvoqadoTheme.dimensions.buttonLarge),
                    shape = RoundedCornerShape(50),
                ) { Text("Pagar") }
                PrimaryButton(
                    text = if (isSending) "Enviando..." else "Enviar",
                    onClick = { if (!isSending) onEnviar() },
                    enabled = !isSending,
                    modifier = Modifier.weight(0.65f),
                )
            } else {
                PrimaryButton(
                    text = "Pagar ${centsDisplay(subtotalCents)}",
                    onClick = onPagar,
                    enabled = subtotalCents > 0,
                    modifier = Modifier.weight(0.5f),
                )
                OutlinedButton(
                    onClick = onGuardar,
                    modifier = Modifier.weight(0.5f).height(AvoqadoTheme.dimensions.buttonLarge),
                    shape = RoundedCornerShape(50),
                ) { Text("Guardar") }
            }
        }
    }
}

/** Sent blocks: grouped by course, ordered by fire time, black divider between. */
@Composable
private fun SentCard(
    sentItems: List<OrderDetailItem>,
    onSentItemTap: (OrderDetailItem) -> Unit,
    onCourseMenu: (String?) -> Unit = {},
) {
    // Course = the grouping unit (Square). Header time = the course's first fire.
    val groups = sentItems
        .groupBy { it.course }
        .entries
        .sortedBy { (_, items) -> items.mapNotNull { parseIsoMs(it.createdAt) }.minOrNull() ?: Long.MAX_VALUE }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        groups.forEachIndexed { index, (course, items) ->
            if (index > 0) {
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.padding(AvoqadoTheme.spacing.md)) {
                val sentAt = items.mapNotNull { parseIsoMs(it.createdAt) }.minOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course ?: "Inmediato",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Menú por tiempo de Square: ¡Listo! / Repetir.
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "Acciones del tiempo",
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onCourseMenu(course) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sentAt?.let {
                    Text(
                        text = "Enviado a la cocina a las ${timeDisplay(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !item.isCortesia) { onSentItemTap(item) }
                            .padding(vertical = 3.dp),
                    ) {
                        Text("${item.quantity}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.productName ?: "Artículo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.modifiers.isNotEmpty()) {
                                Text(
                                    text = item.modifiers.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            item.notes?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = "Nota: $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF9500),
                                )
                            }
                            item.seat?.let { seatN ->
                                Text(
                                    text = "Asiento $seatN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                            textDecoration = if (item.isCortesia) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}

/** Pending slots: every course always visible; the selected one gets the black
 *  outline AND expands with its lines; "Más platos" appends numbered slots. */
@Composable
private fun PendingCard(
    pendingLines: List<TableOrderViewModel.PendingLine>,
    selectedCourse: String?,
    extraCourses: List<String>,
    onSelectCourse: (String?) -> Unit,
    onAddCourse: () -> Unit,
    onRemovePending: (String) -> Unit,
    onCycleSeat: (String) -> Unit = {},
) {
    val allCourses: List<String?> = TableOrderViewModel.BASE_COURSES + extraCourses
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .padding(AvoqadoTheme.spacing.xs),
    ) {
        allCourses.forEach { course ->
            val isSelected = selectedCourse == course
            val lines = pendingLines.filter { it.course == course }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelectCourse(course) }
                    .padding(AvoqadoTheme.spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course ?: "Inmediato",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (lines.isEmpty()) "No enviado" else "${lines.sumOf { it.item.quantity }} artículo(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected && lines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${line.item.quantity}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(line.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                line.item.modifiersSummary?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Asiento (Square seats): chip que cicla — → A1 → ... → An.
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (line.seat != null) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable { onCycleSeat(line.item.id) }
                                    .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 3.dp),
                            ) {
                                Text(
                                    text = line.seat?.let { "A$it" } ?: "A—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (line.seat != null) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                            Text(text = centsDisplay(line.item.totalPrice))
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Quitar",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemovePending(line.item.id) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // "Más platos" — Square's last row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddCourse)
                .padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text("Más platos", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// MARK: - Search bar (simplified pill, table mode)

@Composable
private fun TableSearchBar(onSearchTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onSearchTap)
                .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text(
                text = "Buscar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Helpers

private fun checkTotalCents(check: OrderDetail?): Int =
    check?.let { kotlin.math.round(it.total * 100).toInt() } ?: 0

private fun centsDisplay(cents: Int): String = "$${String.format(Locale.US, "%.2f", cents / 100.0)}"

private fun parseIsoMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

private fun timeDisplay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

/** "0:45" elapsed since [iso] — same format the floor canvas uses. */
private fun elapsedSince(iso: String?, nowMs: Long): String? {
    val start = parseIsoMs(iso) ?: return null
    val minutes = ((nowMs - start).coerceAtLeast(0L) / 60_000L).toInt()
    return "${minutes / 60}:${String.format(Locale.US, "%02d", minutes % 60)}"
}

// MARK: - Right-panel tabs (Square: Cuenta · Acciones)

internal enum class PanelTab(val label: String) { CUENTA("Cuenta"), ACCIONES("Acciones"), CLIENTE("Cliente") }

@Composable
internal fun PanelTabsRow(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.md)
            .padding(top = AvoqadoTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        PanelTab.entries.forEach { tab ->
            val isSelected = selected == tab
            Column(modifier = Modifier.clickable { onSelect(tab) }) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        ),
                )
            }
        }
    }
}

// MARK: - Acciones panel (level 1 — only actions that actually work today;
// Mover/Asignar/Cortesía-de-cuenta land with their server endpoints)

@Composable
internal fun TableActionsPanel(
    hasPending: Boolean,
    hasSent: Boolean,
    onClearPending: () -> Unit,
    onAnular: () -> Unit,
    onPrintPreBill: () -> Unit,
    onReprintComandas: () -> Unit,
    onCustomAmount: () -> Unit,
    onMover: () -> Unit = {},
    onAsignar: () -> Unit = {},
    onCompWhole: () -> Unit = {},
    onDividir: () -> Unit = {},
    onNameNotes: () -> Unit = {},
    onCovers: () -> Unit = {},
    onCumplimiento: () -> Unit = {},
    onDescuentos: () -> Unit = {},
    onSepararCuenta: () -> Unit = {},
    onOrdenarCarrito: () -> Unit = {},
    onCalculadora: () -> Unit = {},
    onMarcarEntrada: () -> Unit = {},
    onCajaAbierta: () -> Unit = {},
    hasCashDrawer: Boolean = false,
    onEscanear: () -> Unit = {},
    onRecompensas: () -> Unit = {},
    onCobrosServicio: () -> Unit = {},
    onMenus: () -> Unit = {},
    hasLoyalty: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Borrar nuevos artículos",
                enabled = hasPending,
                onClick = onClearPending,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Mover",
                enabled = true,
                onClick = onMover,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Anular cuenta",
                enabled = true,
                destructive = true,
                onClick = onAnular,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Asignar",
                enabled = true,
                onClick = onAsignar,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Cortesía en la cuenta",
                enabled = hasSent,
                onClick = onCompWhole,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Dividir la cuenta",
                enabled = hasSent,
                onClick = onDividir,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Separar en otra cuenta",
                enabled = hasSent,
                onClick = onSepararCuenta,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Ordenar carrito",
                enabled = hasPending,
                onClick = onOrdenarCarrito,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Imprimir cuenta",
                enabled = hasSent,
                onClick = onPrintPreBill,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Volver a imprimir pedido",
                enabled = hasSent,
                onClick = onReprintComandas,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Escanear",
                enabled = true,
                onClick = onEscanear,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Text(
            text = "Detalles",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = AvoqadoTheme.spacing.md),
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Nombre y notas",
                enabled = true,
                onClick = onNameNotes,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Conteo de clientes",
                enabled = true,
                onClick = onCovers,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Cumplimiento",
                enabled = true,
                onClick = onCumplimiento,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Descuentos",
                enabled = true,
                onClick = onDescuentos,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Recompensas",
                enabled = hasLoyalty,
                onClick = onRecompensas,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Cobros por servicio",
                enabled = true,
                onClick = onCobrosServicio,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Menús",
                enabled = true,
                onClick = onMenus,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Text(
            text = "Otras acciones",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = AvoqadoTheme.spacing.md),
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Calculadora",
                enabled = true,
                onClick = onCalculadora,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Importe personalizado",
                enabled = true,
                onClick = onCustomAmount,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Marcar entrada/salida",
                enabled = true,
                onClick = onMarcarEntrada,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Caja abierta",
                enabled = hasCashDrawer,
                onClick = onCajaAbierta,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Importe personalizado

@Composable
private fun CustomAmountDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    val cents = amountText.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importe personalizado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                Text(
                    "Se agrega al tiempo seleccionado y se envía a la cuenta con la ronda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Concepto (opcional)") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = amountText,
                    onValueChange = { input -> amountText = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Monto (pesos)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, cents) }, enabled = cents > 0) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}


// MARK: - Mover cuenta a otra mesa

@Composable
private fun MoveTableDialog(
    tables: List<com.avoqado.pos.tables.data.DiningTable>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover cuenta") },
        text = {
            if (tables.isEmpty()) {
                Text("No hay mesas libres a dónde mover la cuenta.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "La cuenta completa (artículos y tiempos) se mueve a la mesa que elijas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    tables.forEach { table ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = table.id }
                                .padding(vertical = AvoqadoTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedId == table.id,
                                onClick = { selectedId = table.id },
                            )
                            Text(
                                "Mesa ${table.number}" + (table.areaName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selectedId?.let(onConfirm) }, enabled = selectedId != null) { Text("Mover") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}


// MARK: - Pestaña Cliente (Square: buscador + adjuntar cliente al cheque)

@Composable
internal fun TableClientePanel(
    customerName: String?,
    onPickCustomer: () -> Unit,
    onDetachCustomer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        if (customerName.isNullOrBlank()) {
            Text(
                text = "Sin cliente asignado a la cuenta.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(customerName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cliente de la cuenta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDetachCustomer) { Text("Quitar", color = Color(0xFFD32F2F)) }
            }
        }
        ActionPill(
            label = if (customerName.isNullOrBlank()) "Asignar cliente" else "Cambiar cliente",
            enabled = true,
            onClick = onPickCustomer,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: - Cortesía en la cuenta (toda)

@Composable
private fun WholeCortesiaDialog(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cortesía en la cuenta") },
        text = {
            Column {
                Text(
                    "TODOS los artículos se quedan en la cuenta pero dejan de cobrarse.",
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
                        androidx.compose.material3.RadioButton(selected = selected == reason, onClick = { selected = reason })
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) { Text("Dar de cortesía") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// MARK: - Nombre y notas de la cuenta

@Composable
private fun NameNotesDialog(
    initialName: String,
    initialNotes: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre y notas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la cuenta") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notas (alergias, ocasión...)") },
                    minLines = 2,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, notes) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// MARK: - Conteo de clientes (comensales)

@Composable
private fun CoversDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var covers by remember { mutableStateOf(initial.coerceAtLeast(1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conteo de clientes") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Comensales", modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { if (covers > 1) covers-- }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Menos")
                }
                Text(
                    text = "$covers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.sm),
                )
                androidx.compose.material3.IconButton(onClick = { if (covers < 200) covers++ }) {
                    Icon(Icons.Filled.Add, contentDescription = "Más")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(covers) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}


// MARK: - Cumplimiento (Square: comer aquí / para llevar / entrega / pickup)

internal val FULFILLMENT_OPTIONS = listOf(
    "DINE_IN" to "En tienda",
    "TAKEOUT" to "Para llevar",
    "DELIVERY" to "Entrega",
    "PICKUP" to "Pickup",
)

@Composable
private fun FulfillmentDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cumplimiento") },
        text = {
            Column {
                FULFILLMENT_OPTIONS.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = code }
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected == code, onClick = { selected = code })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// MARK: - Descuentos de orden en el cheque

@Composable
private fun OrderDiscountsDialog(
    available: List<com.avoqado.pos.pos.data.model.Discount>,
    applied: List<com.avoqado.pos.tables.data.AppliedOrderDiscount>,
    onApply: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descuentos") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (applied.isNotEmpty()) {
                    Text("Aplicados", fontWeight = FontWeight.SemiBold)
                    applied.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "-" + centsDisplay(kotlin.math.round(d.amount * 100).toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Success,
                                )
                            }
                            TextButton(onClick = { onRemove(d.id) }) { Text("Quitar", color = Color(0xFFD32F2F)) }
                        }
                    }
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                }
                Text("Disponibles", fontWeight = FontWeight.SemiBold)
                if (available.isEmpty()) {
                    Text(
                        "No hay descuentos de orden configurados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                available.forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApply(d.id) }
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (d.type == "PERCENTAGE") "${d.value.toInt()}%" else centsDisplay(kotlin.math.round(d.value * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Aplicar", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}


// MARK: - Menús por horario

@Composable
private fun MenusDialog(
    menus: List<com.avoqado.pos.tables.data.VenueMenu>,
    selectedMenuId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Menús") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Cambia qué productos muestra la cuadrícula. El menú con horario se elige solo según la hora del local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

                // Sin filtro: el catálogo completo, como antes de los menús.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Todos los productos",
                        fontWeight = if (selectedMenuId == null) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedMenuId == null) {
                        Text("Actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                menus.forEach { menu ->
                    val esActual = menu.id == selectedMenuId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(menu.id) }
                            .padding(vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(menu.name, fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal)
                            val sub = buildList {
                                menu.scheduleDisplay?.let { add(it) }
                                if (menu.appliesNow) add("aplica ahora")
                                add("${menu.categoryIds.size} categorías")
                            }
                            Text(
                                text = sub.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (esActual) {
                            Text("Actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

// MARK: - Cobros por servicio (SUMAN al total: ingreso gravable)

@Composable
private fun ServiceChargesDialog(
    options: List<com.avoqado.pos.tables.data.ServiceChargeOption>,
    applied: List<com.avoqado.pos.tables.data.AppliedServiceCharge>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cobros por servicio") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (applied.isNotEmpty()) {
                    Text("Aplicados", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    applied.forEach { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(a.name)
                                Text(
                                    text = "+" + money(a.amount) + if (a.isAutomatic) " · automático" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Un cargo automático se retira solo al corregir los
                            // comensales; quitarlo a mano volvería a aparecer.
                            if (!a.isAutomatic) {
                                TextButton(onClick = { onRemove(a.id) }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                }

                val appliedIds = applied.mapNotNull { it.name }.toSet()
                val disponibles = options.filter { it.name !in appliedIds }
                Text("Disponibles", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (disponibles.isEmpty()) {
                    Text(
                        "No hay más cobros configurados para este local.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                disponibles.forEach { op ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(op.name)
                            Text(
                                text = op.valueDisplay + (op.autoApplyMinCovers?.let { " · automático desde $it comensales" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onApply(op.id) }) { Text("Aplicar") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

// MARK: - Recompensas (canje de puntos de lealtad)

@Composable
private fun RewardsDialog(
    loyalty: com.avoqado.pos.tables.data.CustomerLoyalty,
    onDismiss: () -> Unit,
    onRedeem: (Int) -> Unit,
) {
    // Arranca en el máximo canjeable: es lo que el mesero pide casi siempre.
    var points by remember { mutableStateOf(loyalty.maxRedeemablePoints) }
    val step = loyalty.minPointsRedeem.coerceAtLeast(1)
    val value = points * loyalty.redemptionRate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recompensas") },
        text = {
            Column {
                Text(
                    text = loyalty.customerName ?: "Cliente",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${loyalty.balance} puntos disponibles · vale ${money(loyalty.balanceValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { points = (points - step).coerceAtLeast(0) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Menos puntos")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$points pts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "− ${money(value)} en la cuenta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { points = (points + step).coerceAtMost(loyalty.maxRedeemablePoints) }) {
                        Icon(Icons.Default.Add, contentDescription = "Más puntos")
                    }
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Mínimo ${loyalty.minPointsRedeem} puntos. Si quitas la recompensa de la cuenta, los puntos regresan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRedeem(points) },
                enabled = points >= loyalty.minPointsRedeem && points <= loyalty.maxRedeemablePoints,
            ) { Text("Canjear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun money(v: Double): String = "$" + String.format(java.util.Locale.US, "%.2f", v)

// MARK: - Ordenar carrito (Square's sort cart)

@Composable
private fun SortCartDialog(
    onDismiss: () -> Unit,
    onPick: (TableOrderViewModel.CartSort) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordenar carrito") },
        text = {
            Column {
                Text(
                    "Reordena los artículos sin enviar. Lo que ya está en cocina no cambia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                TableOrderViewModel.CartSort.entries.forEach { mode ->
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(mode) }
                            .padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// MARK: - Calculadora (Square's calculator — local, no toca la cuenta)

@Composable
private fun CalculatorDialog(onDismiss: () -> Unit) {
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var freshEntry by remember { mutableStateOf(true) }

    fun currentValue(): Double = display.replace(",", ".").toDoubleOrNull() ?: 0.0

    fun showNumber(v: Double) {
        display = if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)
    }

    fun applyPending() {
        val acc = accumulator
        val op = pendingOp
        if (acc == null || op == null) {
            accumulator = currentValue()
            return
        }
        val rhs = currentValue()
        val result = when (op) {
            "+" -> acc + rhs
            "−" -> acc - rhs
            "×" -> acc * rhs
            "÷" -> if (rhs == 0.0) 0.0 else acc / rhs
            else -> rhs
        }
        accumulator = result
        showNumber(result)
    }

    fun digit(d: String) {
        display = if (freshEntry || display == "0") d else display + d
        freshEntry = false
    }

    fun operator(op: String) {
        applyPending()
        pendingOp = op
        freshEntry = true
    }

    val keys = listOf(
        listOf("7", "8", "9", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("1", "2", "3", "−"),
        listOf("0", ".", "=", "+"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calculadora") },
        text = {
            Column {
                Text(
                    text = display,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.md),
                )
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                        row.forEach { key ->
                            TextButton(
                                onClick = {
                                    when (key) {
                                        "=" -> {
                                            applyPending()
                                            pendingOp = null
                                            freshEntry = true
                                        }
                                        "+", "−", "×", "÷" -> operator(key)
                                        "." -> if (!display.contains(".")) {
                                            display = if (freshEntry) "0." else "$display."
                                            freshEntry = false
                                        }
                                        else -> digit(key)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(key, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                display = "0"; accumulator = null; pendingOp = null; freshEntry = true
            }) { Text("Limpiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

// MARK: - Separar en otra cuenta (Square's separate checks)

@Composable
private fun SplitCheckDialog(
    items: List<OrderDetailItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Separar en otra cuenta") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Los artículos elegidos se mueven a una cuenta NUEVA de esta mesa (debe quedar al menos uno).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            }
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = item.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + item.id else selected - item.id
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${item.quantity}× ${item.productName ?: "Artículo"}", style = MaterialTheme.typography.bodyMedium)
                            item.seat?.let { Text("Asiento $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("$${String.format(java.util.Locale.US, "%.2f", item.total)}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty() && selected.size < items.size,
            ) { Text("Separar (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
