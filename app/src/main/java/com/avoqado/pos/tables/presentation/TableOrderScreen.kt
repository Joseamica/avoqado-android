package com.avoqado.pos.tables.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.OutlinedIconButton
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
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost

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
    val splittableItems by viewModel.splittableItems.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val blockedNotice by viewModel.blockedNotice.collectAsState()
    val isLoadingCheck by viewModel.isLoadingCheck.collectAsState()
    val pendingLines by viewModel.pending.collectAsState()
    val queuedLines by viewModel.queued.collectAsState()
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
    // Propiedad de mesa: cheque de otro mesero + switch del venue encendido y
    // sin 'tables:manage-all' → pantalla read-only (el server refuerza con 403).
    val readOnlyCheck by viewModel.readOnlyCheck.collectAsState()
    val readOnlyForPayment by viewModel.readOnlyForPayment.collectAsState()
    val lockOwnerName by viewModel.lockOwnerName.collectAsState()

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
    var pendingSwitchTarget by remember { mutableStateOf<com.avoqado.pos.tables.data.OpenCheckSummary?>(null) }
    var showServiceCharges by remember { mutableStateOf(false) }
    var showMenus by remember { mutableStateOf(false) }
    var showSplitModes by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    // Pago dedicado (Square): el flujo de pago se presenta AQUÍ, sin brincar al
    // register de retail. Mismo PaymentFlowViewModel, solo cambia el cascarón.
    var showPayment by remember { mutableStateOf(false) }
    var paymentSeedCents by remember { mutableStateOf(0) }
    var pendingSplitConfig by remember { mutableStateOf(com.avoqado.pos.payment.presentation.SplitConfig()) }
    var committedPaymentCompletion by remember {
        mutableStateOf<com.avoqado.pos.payment.presentation.PaymentCompletion?>(null)
    }
    var showSplitImporte by remember { mutableStateOf(false) }
    val tablesViewModel: TablesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    // Menú … por tiempo (¡Listo!/Repetir). Par (curso, abierto).
    var courseMenuTarget by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showAnularDialog by remember { mutableStateOf(false) }
    var compTarget by remember { mutableStateOf<OrderDetailItem?>(null) }
    var showPhoneCheck by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadCheck() }
    // TODOS los avisos de esta pantalla pasan por aquí: por qué no se puede mover
    // una cuenta, que un producto está agotado, que la cuenta no tiene cargos.
    //
    // Iban en Toast, que es la peor opción posible en este equipo. El Toast lo
    // dibuja el sistema fuera de la app: no respeta el tema, se lo puede comer
    // otra ventana, y en una Sunmi con pantalla de cliente no hay garantía de en
    // cuál de las dos aparece. Ya pasó con "Imprimir corte", cuyo Toast de "no
    // disponible" nadie vio nunca.
    //
    // Un Snackbar vive DENTRO de la app: sale siempre donde está el mesero, se ve
    // igual en claro y oscuro, y —esto importa para no volver a discutirlo— sí
    // aparece en el árbol de accesibilidad, así que se puede verificar que salió.
    val isReprinting by viewModel.isReprinting.collectAsState()
    val actionIsError by viewModel.actionIsError.collectAsState()
    val actionHint by viewModel.actionHint.collectAsState()
    // El aviso lo pinta el DESIGN SYSTEM (AvoqadoSuccessToast / AvoqadoErrorToast):
    // centrado, con el color que corresponde y con subtítulo para decir qué hacer.
    //
    // 🔴 Antes era un `SnackbarHost` crudo de Material anclado abajo: salía gris
    // —igual un éxito que un rechazo—, descentrado, y encima TAPABA el botón
    // "Pagar". Un fallo tiene que verse como fallo.
    //
    // El consumo ocurre en el `onDismiss` del toast, nunca antes de mostrarlo:
    // limpiar el estado primero cancelaba el aviso antes de que se pintara.

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

    /** Read-only por propiedad de mesa: avisa y bloquea la acción de EDITAR. */
    fun blockIfReadOnly(): Boolean {
        if (!readOnlyCheck) return false
        viewModel.showMessage("Mesa de ${lockOwnerName ?: "otro mesero"} — solo lectura")
        return true
    }

    /**
     * Lo mismo, pero para COBRAR — y cobrar no es editar.
     *
     * 🔴 El server exime la ruta de cobro de la propiedad de mesa con
     * `tables:pay-any`, que es justo lo que estrena el CAJERO. Mientras "Pagar"
     * usó [blockIfReadOnly], la caja no podía liquidar ninguna mesa abierta por un
     * mesero —su trabajo literal— y el gate del cliente era lo ÚNICO que
     * bloqueaba: el 403 nunca llegaba porque la llamada nunca salía.
     */
    fun blockIfCannotPay(): Boolean {
        if (!readOnlyForPayment) return false
        viewModel.showMessage("Mesa de ${lockOwnerName ?: "otro mesero"} — pídele que la cobre él")
        return true
    }

    fun handleProductTap(product: Product) {
        if (blockIfReadOnly()) return
        if (product.hasModifiers) selectedProduct = product else viewModel.addProduct(product)
    }

    fun fireSend() {
        if (blockIfReadOnly()) return
        // Square: Enviar se queda en la mesa — el panel muestra la ronda recién
        // enviada como bloque nuevo; se sale con Regresar/Guardar.
        viewModel.sendRound { _, msg ->
            viewModel.showMessage(msg)
        }
    }

    fun firePagar() {
        if (blockIfCannotPay()) return
        if (viewModel.preparePagar()) {
            paymentSeedCents = viewModel.tableSession.current()?.totalCents ?: 0
            if (paymentSeedCents <= 0) paymentSeedCents = viewModel.payableTotalCents
            pendingSplitConfig = com.avoqado.pos.payment.presentation.SplitConfig()
            committedPaymentCompletion = null
            showPayment = true
        }
        // El motivo lo pone `preparePagar()`, que es quien SABE por qué no se
        // puede: sin cargos, ya pagada, o cuenta ajena. Antes se ponía aquí un
        // texto fijo ("no tiene cargos") que era falso en la mayoría de los
        // casos. Sigue siendo un aviso PERSISTENTE y no un Toast: en la Sunmi
        // los Toast quedan detrás de la pantalla del cliente y el toque parecía
        // no hacer nada.
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Square: el header (regresar + mesa + comensales) vive en la COLUMNA
        // IZQUIERDA — el panel del cheque gana toda la altura en tablet.
        val contextBar: @Composable () -> Unit = {
            Column {
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
                if (readOnlyCheck) {
                    ReadOnlyOwnershipBanner(ownerName = lockOwnerName, puedeCobrar = !readOnlyForPayment)
                }
            }
        }

        if (isTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.5f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        contextBar()
                        HorizontalDivider()
                        Box(modifier = Modifier.weight(1f)) {
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
                    }
                }
                Box(
                    modifier = Modifier.width(1.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Box(modifier = Modifier.weight(0.5f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 🔴 Sin esto las pestañas nacen en y=0, DEBAJO de la
                            // barra de estado (31px en la D3): el 60% del botón
                            // "Acciones"/"Cliente" no recibía el toque y el
                            // mesero no tenía forma de saber por qué. Medido en
                            // hardware el 2026-07-28.
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        PanelTabsRow(selected = panelTab, onSelect = { if (it == PanelTab.CUENTA || !blockIfReadOnly()) panelTab = it })
                        when (panelTab) {
                            PanelTab.CUENTA -> TableCheckPanel(
                                check = check,
                                isLoadingCheck = isLoadingCheck,
                                pendingLines = pendingLines,
                                queuedLines = queuedLines,
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
                                onSentItemTap = { item -> if (!item.isCortesia && !blockIfReadOnly()) compTarget = item },
                                onCourseMenu = { c -> courseMenuTarget = c to true },
                                pendingCount = viewModel.pendingCount,
                                pendingTotalCents = viewModel.pendingTotalCents,
                                onEnviar = { fireSend() },
                                onPrintCuenta = { viewModel.printPreBill() },
                                onBlocked = { viewModel.showBlockedReason(it) },
                                printBlockedReason = viewModel.printPreBillBlockedReason,
                                blockedNotice = blockedNotice,
                                onDismissNotice = { viewModel.dismissBlockedNotice() },
                                onPagar = { firePagar() },
                                onGuardar = { requestExit() },
                            )
                            PanelTab.ACCIONES -> TableActionsPanel(
                                hasPending = pendingLines.isNotEmpty(),
                                hasSent = !check?.items.isNullOrEmpty(),
                                // Separar SÍ funciona sin red: las líneas enviadas
                                // offline se referencian por su externalId. Sin esto
                                // el botón quedaba gris justo cuando más se necesita.
                                // Hacen falta 2: uno se va y otro se queda.
                                canSeparar = splittableItems.size >= 2,
                                separarBlockedReason = if (splittableItems.isEmpty()) {
                                    "Primero envía a cocina lo que quieras separar."
                                } else {
                                    "Necesitas al menos 2 artículos: uno se va a la cuenta nueva y otro se queda en esta."
                                },
                                isOffline = !isConnected,
                                onBlocked = { motivo -> viewModel.showBlockedReason(motivo) },
                                blockedNotice = blockedNotice,
                                onDismissNotice = { viewModel.dismissBlockedNotice() },
                                onClearPending = { viewModel.clearPending(); panelTab = PanelTab.CUENTA },
                                onAnular = { showAnularDialog = true },
                                onPrintPreBill = { viewModel.printPreBill() },
                                onReprintComandas = { viewModel.reprintComandas() },
                                isReprinting = isReprinting,
                                onCustomAmount = { showCustomAmount = true },
                                onMover = {
                                    if (pendingLines.isNotEmpty()) {
                                        viewModel.showMessage("Envía o borra los artículos pendientes antes de mover")
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
                                        viewModel.showMessage("Envía o borra los artículos pendientes antes de dividir")
                                    } else {
                                        showSplitModes = true
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
                                onFusionar = { showMerge = true },
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
                contextBar()
                HorizontalDivider()
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
                        PanelTabsRow(selected = panelTab, onSelect = { if (it == PanelTab.CUENTA || !blockIfReadOnly()) panelTab = it })
                        when (panelTab) {
                            PanelTab.CUENTA -> TableCheckPanel(
                                check = check,
                                isLoadingCheck = isLoadingCheck,
                                pendingLines = pendingLines,
                                queuedLines = queuedLines,
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
                                onSentItemTap = { item -> if (!item.isCortesia && !blockIfReadOnly()) compTarget = item },
                                onCourseMenu = { c -> courseMenuTarget = c to true },
                                pendingCount = viewModel.pendingCount,
                                pendingTotalCents = viewModel.pendingTotalCents,
                                onEnviar = { showPhoneCheck = false; fireSend() },
                                onPrintCuenta = { viewModel.printPreBill() },
                                onBlocked = { viewModel.showBlockedReason(it) },
                                printBlockedReason = viewModel.printPreBillBlockedReason,
                                blockedNotice = blockedNotice,
                                onDismissNotice = { viewModel.dismissBlockedNotice() },
                                onPagar = { showPhoneCheck = false; firePagar() },
                                onGuardar = { showPhoneCheck = false; requestExit() },
                            )
                            PanelTab.ACCIONES -> TableActionsPanel(
                                hasPending = pendingLines.isNotEmpty(),
                                hasSent = !check?.items.isNullOrEmpty(),
                                // Separar SÍ funciona sin red: las líneas enviadas
                                // offline se referencian por su externalId. Sin esto
                                // el botón quedaba gris justo cuando más se necesita.
                                // Hacen falta 2: uno se va y otro se queda.
                                canSeparar = splittableItems.size >= 2,
                                separarBlockedReason = if (splittableItems.isEmpty()) {
                                    "Primero envía a cocina lo que quieras separar."
                                } else {
                                    "Necesitas al menos 2 artículos: uno se va a la cuenta nueva y otro se queda en esta."
                                },
                                isOffline = !isConnected,
                                onBlocked = { motivo -> viewModel.showBlockedReason(motivo) },
                                blockedNotice = blockedNotice,
                                onDismissNotice = { viewModel.dismissBlockedNotice() },
                                onClearPending = { viewModel.clearPending(); panelTab = PanelTab.CUENTA },
                                onAnular = { showAnularDialog = true },
                                onPrintPreBill = { viewModel.printPreBill() },
                                onReprintComandas = { viewModel.reprintComandas() },
                                isReprinting = isReprinting,
                                onCustomAmount = { showCustomAmount = true },
                                onMover = {
                                    if (pendingLines.isNotEmpty()) {
                                        viewModel.showMessage("Envía o borra los artículos pendientes antes de mover")
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
                                        viewModel.showMessage("Envía o borra los artículos pendientes antes de dividir")
                                    } else {
                                        showSplitModes = true
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
                                onFusionar = { showMerge = true },
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
                    viewModel.showMessage(msg)
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
        AvoqadoDialog(
            title = course ?: "Inmediato",
            description = "$count artículo(s) en este tiempo.",
            onDismiss = { courseMenuTarget = null },
            actionButton = {
                PrimaryButton(
                    text = "¡Listo!",
                    onClick = {
                        courseMenuTarget = null
                        viewModel.marcharCourse(course)
                    },
                    fullWidth = true,
                )
            },
        ) {
            // "Repetir" es la acción secundaria: queda sobre el botón primario,
            // no compitiendo a su lado como en el AlertDialog anterior.
            TextButton(
                onClick = {
                    courseMenuTarget = null
                    viewModel.repeatCourse(course)
                    panelTab = PanelTab.CUENTA
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Repetir") }
        }
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
        AvoqadoDialog(
            title = "Producto no encontrado",
            description = "Ningún producto del catálogo tiene el código $scannedCode.",
            onDismiss = { unknownBarcode = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { unknownBarcode = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    if (showCheckSwitcher) {
        val cuentas = floorTable?.openOrders ?: emptyList()
        AvoqadoDialog(
            title = "Cuentas de la mesa",
            onDismiss = { showCheckSwitcher = false },
        ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    cuentas.forEachIndexed { index, cuenta ->
                        val esActual = cuenta.id == session?.orderId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !esActual) {
                                    showCheckSwitcher = false
                                    if (pendingLines.isNotEmpty()) {
                                        pendingSwitchTarget = cuenta
                                    } else {
                                        viewModel.switchToCheck(cuenta)?.let { session = it }
                                    }
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
        }
    }

    if (showSplitImporte) {
        com.avoqado.pos.payment.presentation.SplitPaymentSheet(
            totalCents = paymentSeedCents,
            items = listOf(
                com.avoqado.pos.pos.data.model.CartItem(
                    type = com.avoqado.pos.pos.data.model.CartItemType.CustomAmount,
                    name = "Cuenta Mesa ${session?.tableNumber ?: ""}",
                    unitPrice = paymentSeedCents,
                ),
            ),
            onDismiss = { showSplitImporte = false },
            onConfirm = { config ->
                showSplitImporte = false
                pendingSplitConfig = config
                committedPaymentCompletion = null
                showPayment = true
            },
        )
    }

    if (showPayment) {
        // 🔴 Semilla ESTABLE (auditoría): construir el CartState inline generaba
        // un id nuevo por recompose y PaymentFlowScreen (LaunchedEffect(cartState))
        // REINICIABA el flujo — hasta con un cargo en vuelo en la terminal.
        // 🔴 El desglose del cobro mostraba "Subtotal $107.10 / Total $107.10"
        // con un descuento de $11.90 aplicado: la línea se sembraba con el
        // total YA descontado, así que el descuento era INVISIBLE para el
        // cliente que lo pidió, y dos renglones idénticos no dicen nada.
        //
        // Se siembra el subtotal REAL y el descuento como línea aparte. El
        // total no cambia (subtotal - descuento = lo mismo que antes), así que
        // no toca lo que se cobra: sólo lo que se ve.
        val descuentoCents = remember(check?.discountAmount) {
            check?.discountAmount?.let { kotlin.math.round(it * 100).toInt() } ?: 0
        }
        val paymentCart = remember(paymentSeedCents, session?.orderId, descuentoCents) {
            val cobrable = paymentSeedCents
            // Sólo se desglosa cuando el descuento CABE en lo que se va a
            // cobrar. En un pago parcial/split la semilla es menor que el
            // cheque completo y desglosarlo daría un total equivocado.
            val desglosable = descuentoCents > 0 && descuentoCents < cobrable
            com.avoqado.pos.pos.presentation.cart.CartState(
                items = listOf(
                    com.avoqado.pos.pos.data.model.CartItem(
                        type = com.avoqado.pos.pos.data.model.CartItemType.CustomAmount,
                        name = "Cuenta Mesa ${session?.tableNumber ?: ""}",
                        unitPrice = if (desglosable) cobrable + descuentoCents else cobrable,
                    ),
                ),
                orderDiscount = if (desglosable) {
                    com.avoqado.pos.pos.data.model.Discount(
                        id = "order-discount",
                        name = check?.discounts?.firstOrNull()?.name ?: "Descuento",
                        value = descuentoCents / 100.0,
                        type = "FIXED",
                    )
                } else {
                    null
                },
            )
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { /* la X del flujo cierra; el back no lo tumba a medias */ },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            // 🔴 Un Dialog de Compose abre su PROPIA ventana, y esa ventana NO
            // hereda el modo inmersivo de la Activity: la barra de navegación
            // de Android reaparecía justo durante el COBRO, encimada con el tab
            // bar de la app. Además la ventana se encogía (1920x972 en vez de
            // 1080) para dejarle sitio, recortando el contenido.
            ImmersiveDialogWindow()
            // Oculta el tab bar de la app mientras dura el cobro: asomaba por
            // debajo del diálogo y era tocable a media transacción.
            DisposableEffect(Unit) {
                com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(true)
                onDispose { com.avoqado.pos.payment.domain.PaymentOverlayState.setPaying(false) }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                com.avoqado.pos.payment.presentation.PaymentFlowScreen(
                    cartState = paymentCart,
                    splitConfig = pendingSplitConfig,
                    // El cheque ya trae su cliente asignado; el recibo lo
                    // muestra en vez de pedirlo otra vez al cerrar la mesa.
                    preselectedCustomerId = check?.customerId,
                    preselectedCustomerName = check?.customerName,
                    onSplitImporte = {
                        showPayment = false
                        showSplitImporte = true
                    },
                    onPaymentCommitted = { completion ->
                        committedPaymentCompletion = completion
                        if (completion.remainingBalanceCents > 0) {
                            // Pago parcial (split): la sesión ya debe SOLO el resto.
                            // No cambiamos todavía el seed visible: hacerlo aquí
                            // reiniciaría PaymentFlowScreen y ocultaría el recibo.
                            tablesViewModel.updateTableSessionRemaining(completion.remainingBalanceCents)
                        } else {
                            // Pagada por completo: liberar la mesa de inmediato. La
                            // pantalla de recibo sigue visible hasta que el operador
                            // pulse "Venta nueva".
                            tablesViewModel.finishTableAfterPayment()
                        }
                    },
                    onDone = {
                        val completion = committedPaymentCompletion
                        committedPaymentCompletion = null
                        when {
                            completion == null -> showPayment = false
                            completion.remainingBalanceCents == 0 -> {
                                showPayment = false
                                pendingSplitConfig = com.avoqado.pos.payment.presentation.SplitConfig()
                                // Square: efectivo exacto → después del recibo regresa
                                // al plano; la mesa ya quedó libre al confirmar el pago.
                                exitOnce()
                            }
                            else -> {
                                // Ahora sí iniciar el siguiente cobro por el saldo,
                                // después de que el operador terminó con el recibo.
                                paymentSeedCents = completion.remainingBalanceCents
                                pendingSplitConfig = com.avoqado.pos.payment.presentation.SplitConfig()
                                viewModel.loadCheck()
                            }
                        }
                    },
                    onCancel = {
                        showPayment = false
                        pendingSplitConfig = com.avoqado.pos.payment.presentation.SplitConfig()
                        committedPaymentCompletion = null
                        // 🔴 La sesión NO puede quedarse en PAYING (auditoría): el tab
                        // Cobrar la detectaría y sembraría el register con esta mesa —
                        // una venta retail acabaría registrada contra la orden de la mesa.
                        viewModel.tableSession.current()?.let { s ->
                            viewModel.tableSession.start(s.copy(mode = com.avoqado.pos.tables.data.TableSession.Mode.ORDERING))
                        }
                        viewModel.loadCheck()
                    },
                )
            }
        }
    }

    pendingSwitchTarget?.let { target ->
        AvoqadoDialog(
            title = "Artículos sin enviar",
            description = "Tienes artículos sin enviar a cocina. Si cambias de cuenta ahora se descartan.",
            onDismiss = { pendingSwitchTarget = null },
            // Se descarta comanda: no se sale por un toque al vacío.
            dismissOnClickOutside = false,
            actionButton = {
                PrimaryButton(
                    text = "Descartar y cambiar",
                    onClick = {
                        pendingSwitchTarget = null
                        viewModel.switchToCheck(target)?.let { session = it }
                    },
                    fullWidth = true,
                    destructive = true,
                )
            },
        ) {}
    }

    if (showMerge) {
        val otras = floorTables.flatMap { t -> t.openOrders.map { t to it } }
            .filter { (_, cuenta) -> cuenta.id != session?.orderId }
        MergeOrdersDialog(
            otras = otras,
            onDismiss = { showMerge = false },
            onPick = { sourceId ->
                showMerge = false
                viewModel.mergeFrom(sourceId) { ok, msg ->
                    if (ok) {
                        viewModel.showMessage(msg)
                    } else {
                        // El motivo lo da el server; el QUÉ HACER lo ponemos
                        // nosotros — un mesero con fila necesita su siguiente
                        // movimiento, no un diagnóstico.
                        viewModel.showError(msg, "Cobra el resto de esa cuenta por separado, o reversa su pago antes de fusionarla.")
                    }
                }
                panelTab = PanelTab.CUENTA
            },
        )
    }

    if (showSplitModes) {
        SplitModesDialog(
            seatsWithItems = viewModel.seatsWithItems,
            onDismiss = { showSplitModes = false },
            onPorPuesto = {
                showSplitModes = false
                viewModel.splitBySeat { ok, msg ->
                    viewModel.showMessage(msg)
                    if (ok) exitOnce()
                }
            },
            onPorArticulo = { showSplitModes = false; showSplitCheckDialog = true },
            onPartesIguales = {
                showSplitModes = false
                if (viewModel.preparePagar()) {
                    paymentSeedCents = viewModel.tableSession.current()?.totalCents ?: 0
                    if (paymentSeedCents <= 0) paymentSeedCents = viewModel.payableTotalCents
                    showSplitImporte = true
                } else {
                    viewModel.showMessage("La cuenta no tiene cargos todavía")
                }
            },
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
            items = splittableItems,
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
        AvoqadoDialog(
            title = "Artículos sin enviar",
            description = "Tienes ${viewModel.pendingCount} artículo(s) sin enviar a cocina. Si sales ahora se descartan.",
            onDismiss = { showDiscardDialog = false },
            dismissOnClickOutside = false,
            actionButton = {
                PrimaryButton(
                    text = "Descartar y salir",
                    onClick = {
                        showDiscardDialog = false
                        viewModel.exitToFloor()
                        exitOnce()
                    },
                    fullWidth = true,
                    destructive = true,
                )
            },
        ) {}
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
                    viewModel.showMessage(msg)
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
    /** Offline-first: rondas enviadas SIN red (impresas + en outbox), esperando sync. */
    queuedLines: List<TableOrderViewModel.PendingLine> = emptyList(),
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
    /**
     * Un botón gris SIN explicación es un toque mudo: el mesero no sabe si la app
     * falló o si le falta un paso. Todo CTA deshabilitado aquí sigue siendo
     * tappable y dice POR QUÉ. Mismo criterio que los ActionPill.
     */
    onBlocked: (String) -> Unit = {},
    /** Por qué el botón de imprimir la pre-cuenta no está disponible (null = sí). */
    printBlockedReason: String? = null,
    blockedNotice: String? = null,
    onDismissNotice: () -> Unit = {},
    /** Ícono de impresora junto a Pagar/Guardar (Square) — imprime la pre-cuenta. */
    onPrintCuenta: () -> Unit = {},
    onPagar: () -> Unit,
    onGuardar: () -> Unit,
) {
    val sentItems = check?.items.orEmpty()
    val hasPending = pendingLines.isNotEmpty()
    // Las rondas offline aún no existen en el server: su importe se suma local
    // para que el subtotal visible nunca mienta mientras esperan sync.
    val queuedCents = queuedLines.sumOf { it.item.effectiveUnitPrice * it.item.quantity }
    val subtotalCents = checkTotalCents(check) + pendingTotalCents + queuedCents
    val itemCount = sentItems.sumOf { it.quantity } + pendingCount + queuedLines.sumOf { it.item.quantity }

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

            // ── Offline: rondas impresas esperando sincronizar ───────────
            if (queuedLines.isNotEmpty()) {
                QueuedRoundsCard(queuedLines = queuedLines)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
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
        if (blockedNotice != null) {
            Box(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm)) {
                BlockedNoticeCard(blockedNotice, onDismissNotice)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            if (hasPending) {
                BlockedTapOverlay(
                    reason = "Primero envía los productos a cocina. Toca «Enviar» y después podrás cobrar.",
                    onBlocked = onBlocked,
                    modifier = Modifier.weight(0.35f),
                ) {
                    OutlinedButton(
                        onClick = onPagar,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(AvoqadoTheme.dimensions.buttonLarge),
                        shape = RoundedCornerShape(50),
                    ) { Text("Pagar") }
                }
                PrimaryButton(
                    text = if (isSending) "Enviando..." else "Enviar",
                    onClick = { if (!isSending) onEnviar() },
                    enabled = !isSending,
                    modifier = Modifier.weight(0.65f),
                )
            } else {
                // Square: ícono de impresora a la izquierda de los CTAs.
                // Se ve deshabilitado, pero sigue siendo tappable para poder
                // decir POR QUÉ. Sin impresora configurada el botón lucía
                // activo y el toque no hacía nada visible.
                val printBlocked = printBlockedReason
                    ?: "Aún no hay nada que imprimir: esta cuenta no tiene cargos."
                        .takeIf { subtotalCents <= 0 }
                BlockedTapOverlay(
                    reason = printBlocked,
                    onBlocked = onBlocked,
                ) {
                    OutlinedIconButton(
                        onClick = onPrintCuenta,
                        enabled = printBlocked == null,
                        modifier = Modifier.size(AvoqadoTheme.dimensions.buttonLarge),
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(Icons.Outlined.Print, contentDescription = "Imprimir cuenta")
                    }
                }
                // 🔴 Una cuenta en \$0 POR DESCUENTO sí se puede cobrar.
                //
                // Antes se bloqueaba con "todavía no tiene cargos" — que además
                // era falso: la cuenta tenía artículos, el total era 0 porque el
                // descuento se los comía. La única salida era anularla, y una
                // anulación NO es lo mismo que una venta con 100% de descuento:
                // el producto salió del almacén y la promoción no queda
                // registrada en ningún lado.
                //
                // Se distingue por los artículos, no por el importe.
                val sinCargos = itemCount <= 0
                BlockedTapOverlay(
                    reason = "Esta cuenta todavía no tiene cargos. Agrega productos y envíalos antes de cobrar."
                        .takeIf { sinCargos },
                    onBlocked = onBlocked,
                    modifier = Modifier.weight(0.5f),
                ) {
                    PrimaryButton(
                        text = "Pagar ${centsDisplay(subtotalCents)}",
                        onClick = onPagar,
                        enabled = !sinCargos,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(
                    onClick = onGuardar,
                    modifier = Modifier.weight(0.5f).height(AvoqadoTheme.dimensions.buttonLarge),
                    shape = RoundedCornerShape(50),
                ) { Text("Guardar") }
            }
        }
    }
}

/** Sent blocks: agrupados por RONDA (course + hora de envío), divisor negro entre grupos. */
@Composable
private fun SentCard(
    sentItems: List<OrderDetailItem>,
    onSentItemTap: (OrderDetailItem) -> Unit,
    onCourseMenu: (String?) -> Unit = {},
) {
    // RONDA = la unidad de agrupación (modelo Square verificado en el POS real):
    // cada Enviar repite el encabezado del tiempo con SU propia hora — líneas
    // idénticas de rondas distintas nunca se fusionan. sentToKitchenAt agrupa
    // exacto; filas viejas (null) caen a createdAt truncado al minuto para no
    // partir una ronda por los ms que separan sus create.
    val groups = sentItems
        .groupBy { item ->
            val round = parseIsoMs(item.sentToKitchenAt)
                ?: parseIsoMs(item.createdAt)?.let { it / 60000 * 60000 }
            item.course to round
        }
        .entries
        .sortedBy { (key, _) -> key.second ?: Long.MAX_VALUE }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        groups.forEachIndexed { index, (key, items) ->
            val course = key.first
            if (index > 0) {
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.padding(AvoqadoTheme.spacing.md)) {
                val sentAt = key.second
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

/**
 * Aplica el modo inmersivo a la ventana del Dialog que la contiene.
 *
 * Los Dialog de Compose crean una ventana aparte que arranca con las system
 * bars visibles, sin importar lo que haga la Activity. En un POS eso significa
 * que el cliente ve —y puede tocar— los botones de Android en plena pantalla de
 * cobro.
 *
 * ⚠️ LÍMITE CONOCIDO: esto quita la barra de Android, pero la ventana del
 * Dialog sigue naciendo con 1920x972 en la Sunmi (reserva el sitio de las
 * barras aunque estén ocultas), así que por esos 108px de abajo todavía asoma
 * el tab bar de la app. Probados SIN éxito: `setLayout(MATCH_PARENT)` y
 * `FLAG_LAYOUT_NO_LIMITS` — ninguno cambió el tamaño. Lo que probablemente lo
 * cierra es dejar de presentar el cobro como Dialog y hacerlo una ruta/pantalla
 * normal, que es refactor mayor.
 */
@Composable
private fun ImmersiveDialogWindow() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(dialogWindow) {
        val window = dialogWindow ?: return@LaunchedEffect
        // La ventana del Dialog nace ENCOGIDA (1920x972 en la Sunmi) porque
        // reserva sitio para las system bars. Sin esto, por debajo del cobro
        // asomaba el tab bar de la app y el mesero podía tocar "Inventario" a
        // media transacción.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

/**
 * El "por qué está gris". Se renderiza en AMBAS pestañas (Cuenta y Acciones):
 * vivía solo en Acciones, así que tocar un CTA bloqueado desde la Cuenta
 * escribía el motivo en un sitio que el mesero nunca veía — un toque mudo
 * con más pasos. Persistente: se queda hasta que lo cierren.
 */
@Composable
private fun BlockedNoticeCard(motivo: String?, onDismiss: () -> Unit) {
    motivo ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onDismiss)
            .padding(AvoqadoTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = motivo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Entendido",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Deja tappable un control deshabilitado para poder decir POR QUÉ lo está.
 * La capa va DESPUÉS del contenido: un botón `enabled=false` consume el
 * pointer, así que el overlay tiene que quedar encima para recibir el toque.
 */
@Composable
private fun BlockedTapOverlay(
    reason: String?,
    onBlocked: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        if (reason != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBlocked(reason) },
            )
        }
    }
}

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

/** Hora del NEGOCIO, no la del aparato: una tablet con la zona mal puesta pintaría
 *  "Enviado a la cocina" con horas de diferencia. */
private fun timeDisplay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

/** "0:45" elapsed since [iso] — same format the floor canvas uses. */
private fun elapsedSince(iso: String?, nowMs: Long): String? {
    val start = parseIsoMs(iso) ?: return null
    val minutes = ((nowMs - start).coerceAtLeast(0L) / 60_000L).toInt()
    return "${minutes / 60}:${String.format(Locale.US, "%02d", minutes % 60)}"
}

// MARK: - Propiedad de mesa (read-only)

/** Banner "Mesa de {mesero} — solo lectura". Informativo, no bloqueante (naranja
 *  suave, nunca modal): las acciones ya están gateadas y el server refuerza. */
@Composable
internal fun ReadOnlyOwnershipBanner(ownerName: String?, puedeCobrar: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 🔴 "Solo lectura" a secas MIENTE cuando el cajero sí puede cobrarla:
            // esconde la única acción que le queda y lo manda a buscar al gerente.
            text = if (puedeCobrar) {
                "Mesa de ${ownerName ?: "otro mesero"} — puedes cobrarla, no editarla"
            } else {
                "Mesa de ${ownerName ?: "otro mesero"} — solo lectura"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Offline: rondas esperando sincronizar

/** Rondas enviadas SIN red: ya se imprimieron en cocina y viven en el outbox.
 *  Relojito, nunca error — offline es estado normal, no falla. */
@Composable
internal fun QueuedRoundsCard(queuedLines: List<TableOrderViewModel.PendingLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.spacing.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Por sincronizar — enviado a cocina",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        queuedLines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${line.item.quantity}× ${line.item.name}" + (line.course?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = centsDisplay(line.item.effectiveUnitPrice * line.item.quantity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Right-panel tabs (Square: Cuenta · Acciones)

internal enum class PanelTab(val label: String) { CUENTA("Cuenta"), ACCIONES("Acciones"), CLIENTE("Cliente") }

@Composable
internal fun PanelTabsRow(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AvoqadoTheme.dimensions.touchTarget)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = AvoqadoTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            PanelTab.entries.forEach { tab ->
                val isSelected = selected == tab
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 150),
                    label = "panel_tab_content",
                )
                val indicatorColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    animationSpec = tween(durationMillis = 150),
                    label = "panel_tab_indicator",
                )

                Box(
                    modifier = Modifier
                        .width(AvoqadoTheme.dimensions.touchTarget * 2)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(50))
                                .background(indicatorColor),
                        )
                    }
                }
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
    /** Hay algo que separar: líneas del server O enviadas sin red (externalId). */
    canSeparar: Boolean = hasSent,
    /** Por qué no se puede separar: distingue "nada enviado" de "un solo artículo". */
    separarBlockedReason: String = "Primero envía a cocina lo que quieras separar.",
    /** Sin conexión: cambia el motivo del bloqueo y pinta el ícono de nube. */
    isOffline: Boolean = false,
    /** Se dispara al tocar un botón gris, con el motivo en lenguaje de mesero. */
    onBlocked: (String) -> Unit = {},
    /** Motivo del último botón gris tocado. Persiste hasta cerrarlo. */
    blockedNotice: String? = null,
    onDismissNotice: () -> Unit = {},
    onClearPending: () -> Unit,
    onAnular: () -> Unit,
    onPrintPreBill: () -> Unit,
    onReprintComandas: () -> Unit,
    /** Reimprimiendo AHORA: la etiqueta lo dice y el botón no acepta otro toque.
     *  Sin esto, el timeout de una impresora inalcanzable (~10 s) se veía como
     *  que el botón no hacía nada, y el mesero volvía a picarle. */
    isReprinting: Boolean = false,
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
    onFusionar: () -> Unit = {},
    hasLoyalty: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        // El "por qué está gris" aparece AQUÍ, junto al botón que el mesero
        // acaba de tocar, y se queda hasta que lo cierre.
        BlockedNoticeCard(blockedNotice, onDismissNotice)
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Borrar nuevos artículos",
                enabled = hasPending,
                blockedReason = "No hay artículos nuevos que borrar.",
                onBlocked = onBlocked,
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
                blockedReason = "Primero envía la ronda a cocina. La cortesía se aplica sobre lo ya enviado.",
                onBlocked = onBlocked,
                onClick = onCompWhole,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Dividir la cuenta",
                enabled = hasSent,
                blockedReason = if (isOffline) {
                    "Dividir por puesto necesita internet. Se habilita solo cuando vuelva la señal."
                } else {
                    "Primero envía la ronda a cocina."
                },
                blockedByConnection = isOffline,
                onBlocked = onBlocked,
                onClick = onDividir,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Separar en otra cuenta",
                enabled = canSeparar,
                // 🔴 Separar exige AL MENOS DOS artículos: uno se va a la cuenta
                // nueva y otro tiene que quedarse. Con uno solo, el diálogo abría
                // y "Separar (0)" NUNCA podía habilitarse — el mesero quedaba en
                // un callejón sin salida. (Encontrado en la D3, mesa M2.)
                blockedReason = separarBlockedReason,
                onBlocked = onBlocked,
                onClick = onSepararCuenta,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = "Fusionar cuentas",
                enabled = true,
                onClick = onFusionar,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Ordenar carrito",
                enabled = hasPending,
                blockedReason = "No hay artículos nuevos que ordenar.",
                onBlocked = onBlocked,
                onClick = onOrdenarCarrito,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ActionPill(
                label = "Imprimir cuenta",
                enabled = hasSent,
                blockedReason = "Todavía no hay nada enviado a cocina, así que no hay cuenta que imprimir.",
                onBlocked = onBlocked,
                onClick = onPrintPreBill,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = if (isReprinting) "Reimprimiendo…" else "Volver a imprimir pedido",
                enabled = hasSent && !isReprinting,
                blockedReason = if (isReprinting) null else "Aún no se ha enviado ningún pedido a cocina.",
                onBlocked = onBlocked,
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
                // Único ActionPill que se quedó sin motivo: el mesero lo tocaba
                // y no pasaba NADA. El resto ya explicaba por qué estaba gris.
                blockedReason = "Esta cuenta no tiene puntos para canjear. Agrega el cliente en la pestaña «Cliente».",
                onBlocked = onBlocked,
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
                blockedReason = "No hay impresora de recibos con cajón configurada. Ve a Más › Impresora.",
                onBlocked = onBlocked,
                onClick = onCajaAbierta,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 🔴 UX de meseros: un botón gris que NO explica por qué está gris es el peor
 * estado posible. El mesero toca, no pasa nada, y no sabe si está roto, si se
 * equivocó, o si es temporal — y en plena comida no puede ponerse a investigar.
 *
 * Por eso un pill deshabilitado SIGUE siendo tocable: al tocarlo dispara
 * [onBlocked] con el motivo en lenguaje de mesero. Se distinguen dos causas,
 * porque piden reacciones distintas:
 *   - [blockedByConnection] = "espera, vuelve solo"  → ícono de nube tachada
 *   - motivo de estado       = "te falta un paso"    → sin ícono
 */
@Composable
private fun ActionPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    /** Por qué está gris, en lenguaje de mesero. Null = gris mudo (evitar). */
    blockedReason: String? = null,
    /** True cuando el bloqueo es por falta de internet (pinta el ícono). */
    blockedByConnection: Boolean = false,
    onBlocked: (String) -> Unit = {},
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
            .clickable(
                // Deshabilitado PERO tocable si hay motivo: el toque explica.
                enabled = enabled || blockedReason != null,
                onClick = { if (enabled) onClick() else blockedReason?.let(onBlocked) },
            )
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (!enabled && blockedByConnection) {
            // El mesero aprende el símbolo en un turno y deja de intentarlo.
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = "Necesita internet",
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        }
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

    AvoqadoDialog(
        title = "Importe personalizado",
        description = "Se agrega al tiempo seleccionado y se envía a la cuenta con la ronda.",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Agregar",
                onClick = { onConfirm(name, cents) },
                enabled = cents > 0,
                fullWidth = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
            LabeledPillField(
                label = "Concepto (opcional)",
                value = name,
                onValueChange = { name = it },
                placeholder = "Ej. descorche",
            )
            LabeledPillField(
                label = "Monto (pesos)",
                value = amountText,
                onValueChange = { input -> amountText = input.filter { it.isDigit() || it == '.' } },
                placeholder = "0.00",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            )
        }
    }
}


// MARK: - Mover cuenta a otra mesa

@Composable
private fun MoveTableDialog(
    tables: List<com.avoqado.pos.tables.data.DiningTable>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    AvoqadoDialog(
        title = "Mover cuenta",
        description = if (tables.isEmpty()) {
            "No hay mesas libres a dónde mover la cuenta."
        } else {
            "La cuenta completa (artículos y tiempos) se mueve a la mesa que elijas."
        },
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Mover",
                onClick = { selectedId?.let(onConfirm) },
                enabled = selectedId != null,
                fullWidth = true,
            )
        },
    ) {
            if (tables.isNotEmpty()) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
    }
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
    AvoqadoDialog(
        title = "Cortesía en la cuenta",
        description = "TODOS los artículos se quedan en la cuenta pero dejan de cobrarse.",
        onDismiss = onDismiss,
        // Regala la cuenta entera: no se sale por un toque al vacío.
        dismissOnClickOutside = false,
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
                    androidx.compose.material3.RadioButton(selected = selected == reason, onClick = { selected = reason })
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
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
    AvoqadoDialog(
        title = "Nombre y notas",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = { onConfirm(name, notes) },
                fullWidth = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
            // Rótulo FUERA del campo: el placeholder de la píldora desaparece en
            // cuanto hay texto, y con la cuenta ya nombrada no había forma de
            // saber cuál campo era cuál. El `label` de Material sí flotaba.
            LabeledPillField(
                label = "Nombre de la cuenta",
                value = name,
                onValueChange = { name = it },
                placeholder = "Ej. Mesa de Juan",
            )
            LabeledPillField(
                label = "Notas (alergias, ocasión...)",
                value = notes,
                onValueChange = { notes = it },
                placeholder = "Ej. sin cebolla",
            )
        }
    }
}

@Composable
private fun LabeledPillField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AvoqadoPillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardType = keyboardType,
        )
    }
}

// MARK: - Conteo de clientes (comensales)

@Composable
private fun CoversDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var covers by remember { mutableStateOf(initial.coerceAtLeast(1)) }
    AvoqadoDialog(
        title = "Conteo de clientes",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = { onConfirm(covers) },
                fullWidth = true,
            )
        },
    ) {
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
    }
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
    AvoqadoDialog(
        title = "Cumplimiento",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = { onConfirm(selected) },
                fullWidth = true,
            )
        },
    ) {
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
    }
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
    AvoqadoDialog(
        title = "Descuentos",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Listo",
                onClick = onDismiss,
                fullWidth = true,
            )
        },
    ) {
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
    }
}


// MARK: - Fusionar cuentas (el inverso de dividir)

@Composable
private fun MergeOrdersDialog(
    otras: List<Pair<com.avoqado.pos.tables.data.DiningTable, com.avoqado.pos.tables.data.OpenCheckSummary>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AvoqadoDialog(
        title = "Fusionar cuentas",
        description = "Los artículos de la cuenta que elijas se pasan a ESTA cuenta y aquella se cierra. Útil cuando dos mesas se juntan.",
        onDismiss = onDismiss,
    ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (otras.isEmpty()) {
                    Text(
                        "No hay otras cuentas abiertas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                otras.forEach { (mesa, cuenta) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(cuenta.id) }
                            .padding(vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cuenta.name?.takeIf { it.isNotBlank() } ?: "Cuenta ${cuenta.orderNumber.takeLast(4)}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Mesa ${mesa.number} · ${cuenta.itemCount} artículo(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(cuenta.totalDisplay, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
    }
}

// MARK: - Dividir la cuenta (los 3 modos de Square)

@Composable
private fun SplitModesDialog(
    seatsWithItems: Int,
    onDismiss: () -> Unit,
    onPorPuesto: () -> Unit,
    onPorArticulo: () -> Unit,
    onPartesIguales: () -> Unit,
) {
    AvoqadoDialog(
        title = "Dividir la cuenta",
        onDismiss = onDismiss,
    ) {
            Column {
                // Por puesto: usa los asientos ya asignados a cada línea.
                val puedePorPuesto = seatsWithItems >= 2
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = puedePorPuesto, onClick = onPorPuesto)
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                ) {
                    Text(
                        "Por puesto",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (puedePorPuesto) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (puedePorPuesto) "Un cheque por asiento ($seatsWithItems asientos con artículos)"
                        else "Asigna asientos a los artículos para usar esta opción",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPorArticulo)
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                ) {
                    Text("Por artículo", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Eliges qué artículos se van a una cuenta nueva",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPartesIguales)
                        .padding(vertical = AvoqadoTheme.spacing.sm),
                ) {
                    Text("En partes iguales", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Parte el IMPORTE al cobrar, sin crear cuentas nuevas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

// MARK: - Menús por horario

@Composable
private fun MenusDialog(
    menus: List<com.avoqado.pos.tables.data.VenueMenu>,
    selectedMenuId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AvoqadoDialog(
        title = "Menús",
        description = "Cambia qué productos muestra la cuadrícula. El menú con horario se elige solo según la hora del local.",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(text = "Listo", onClick = onDismiss, fullWidth = true)
        },
    ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
    }
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
    AvoqadoDialog(
        title = "Cobros por servicio",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(text = "Listo", onClick = onDismiss, fullWidth = true)
        },
    ) {
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

                val appliedIds = applied.mapNotNull { it.serviceChargeId }.toSet()
                val disponibles = options.filter { it.id !in appliedIds }
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
    }
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

    AvoqadoDialog(
        title = "Recompensas",
        description = "${loyalty.customerName ?: "Cliente"} · ${loyalty.balance} puntos disponibles, valen ${money(loyalty.balanceValue)}",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Canjear",
                onClick = { onRedeem(points) },
                enabled = points >= loyalty.minPointsRedeem && points <= loyalty.maxRedeemablePoints,
                fullWidth = true,
            )
        },
    ) {
            Column {
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
    }
}

private fun money(v: Double): String = "$" + String.format(java.util.Locale.US, "%.2f", v)

// MARK: - Ordenar carrito (Square's sort cart)

@Composable
private fun SortCartDialog(
    onDismiss: () -> Unit,
    onPick: (TableOrderViewModel.CartSort) -> Unit,
) {
    AvoqadoDialog(
        title = "Ordenar carrito",
        description = "Reordena los artículos sin enviar. Lo que ya está en cocina no cambia.",
        onDismiss = onDismiss,
    ) {
        Column {
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
    }
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

    AvoqadoDialog(
        title = "Calculadora",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Limpiar",
                onClick = {
                    display = "0"; accumulator = null; pendingOp = null; freshEntry = true
                },
                fullWidth = true,
            )
        },
    ) {
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
    }
}

// MARK: - Separar en otra cuenta (Square's separate checks)

@Composable
private fun SplitCheckDialog(
    items: List<OrderDetailItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AvoqadoDialog(
        title = "Separar en otra cuenta",
        description = "Los artículos elegidos se mueven a una cuenta NUEVA de esta mesa (debe quedar al menos uno).",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Separar (${selected.size})",
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty() && selected.size < items.size,
                fullWidth = true,
            )
        },
    ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                            Text("${item.quantity}× ${item.productName ?: "Articulo"}", style = MaterialTheme.typography.bodyMedium)
                            item.seat?.let { Text("Asiento $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("$${String.format(java.util.Locale.US, "%.2f", item.total)}")
                    }
                }
            }
    }
}
