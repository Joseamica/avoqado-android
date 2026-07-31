package com.avoqado.pos.pos.presentation.checkout

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.areatickets.presentation.AreaTicketOperationsViewModel
import com.avoqado.pos.customers.presentation.CreateCustomerView
import com.avoqado.pos.customers.presentation.CustomersView
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.presentation.PaymentFlowScreen
import com.avoqado.pos.payment.presentation.SplitConfig
import com.avoqado.pos.payment.presentation.SplitPaymentSheet
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartPanelView
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.ScannedBarcodeResult
import com.avoqado.pos.pos.presentation.cart.StaffSelectorSheet
import com.avoqado.pos.pos.presentation.product.CreateProductView
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductGridView
import com.avoqado.pos.pos.presentation.product.WeightCapturePanel
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.presentation.search.SearchOverlayView
import com.avoqado.pos.scale.ScaleCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InputTab(val label: String) {
    KEYPAD("Teclado"),
    SHORTCUTS("Shortcuts"),
    PRODUCTS("Todos los productos"),
    MOSAIC("Configurar"),
}

private enum class CustomerSelectionContext {
    GENERAL,
    PAY_LATER,
}

@Composable
fun CheckoutScreen(
    isTablet: Boolean,
    roleManager: RoleManager? = null,
    cartViewModel: CartViewModel = hiltViewModel(),
) {
    val creditsViewModel: com.avoqado.pos.customers.presentation.CustomerCreditsViewModel = hiltViewModel()
    val cartState by cartViewModel.cartState.collectAsState()
    val isLoading by cartViewModel.isLoading.collectAsState()
    val staffOptions by cartViewModel.staffOptions.collectAsState()
    val isStaffLoading by cartViewModel.isStaffLoading.collectAsState()
    val staffError by cartViewModel.staffError.collectAsState()
    val referralCodeState by cartViewModel.referralCode.collectAsState()
    val referralUiState by cartViewModel.referralValidation.collectAsState()
    val areaTicketOperations: AreaTicketOperationsViewModel = hiltViewModel()
    val areaOperationsState by areaTicketOperations.state.collectAsState()
    val scaleCaptureViewModel: ScaleCaptureViewModel = hiltViewModel()
    val scaleState by scaleCaptureViewModel.state.collectAsState()

    // Walk-in class flow: if a class was just reserved on the class screen,
    // drop it into the cart on arrival (Square-style: service enters the sale).
    LaunchedEffect(Unit) {
        cartViewModel.consumePendingClassSeed()
        cartViewModel.restoreAreaTicketSession()
    }

    // TABLE_SERVICE (PRO) — table ORDERING lives on the dedicated
    // TableOrderScreen now; the register only keeps the PAYING seam: the
    // session seeds the cart with the check total so the NORMAL payment flow
    // (tips, terminal, split) charges the EXISTING table order. With no
    // session active everything below is inert.
    val tablesViewModel: com.avoqado.pos.tables.presentation.TablesViewModel = hiltViewModel()
    val tableSessionActive by tablesViewModel.tableSession.active.collectAsState()
    LaunchedEffect(tableSessionActive?.orderId, tableSessionActive?.mode) {
        cartViewModel.consumePendingTableCobrar()
    }
    // Class-seed conflict: the walk-in class wasn't added because the cart
    // already links a different reservation — tell the cashier instead of
    // silently dropping it.
    val seedConflict by cartViewModel.seedConflict.collectAsState()
    val seedCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(seedConflict) {
        if (seedConflict) {
            android.widget.Toast.makeText(
                seedCtx,
                "El carrito ya está ligado a otra reserva. Cobra o vacía el carrito antes de inscribir otra clase.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            cartViewModel.clearSeedConflict()
        }
    }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    // Venta por peso (báscula): producto por peso pendiente de capturar peso.
    var weightProduct by remember { mutableStateOf<Product?>(null) }
    var selectedTab by remember { mutableStateOf(InputTab.KEYPAD) }
    var showSearch by remember { mutableStateOf(false) }
    var amountCents by remember { mutableIntStateOf(0) }
    var showPaymentFlow by remember { mutableStateOf(false) }
    var paymentCartSnapshot by remember { mutableStateOf<CartState?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var currentNote by remember { mutableStateOf("") }
    var showIPhoneCart by remember { mutableStateOf(false) }
    var selectedCartItem by remember { mutableStateOf<CartItem?>(null) }
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    // Membresías: a credit-pack sale needs a customer; grant captured at charge time.
    var showPackCustomerRequired by remember { mutableStateOf(false) }
    var showPackNoSplitAlert by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }
    var pendingPackGrant by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showCustomersSheet by remember { mutableStateOf(false) }
    var showCreateCustomer by remember { mutableStateOf(false) }
    var createCustomerSearchText by remember { mutableStateOf("") }
    var showSavedSnackbar by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showCreateProduct by remember { mutableStateOf(false) }
    var createProductInitialName by remember { mutableStateOf("") }
    var createProductInitialGtin by remember { mutableStateOf("") }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var barcodeError by remember { mutableStateOf<String?>(null) }
    var areaTicketAddedCount by remember { mutableStateOf<Int?>(null) }
    var showSplitPayment by remember { mutableStateOf(false) }
    // "Dividir la cuenta" from the table panel: auto-open the split sheet once
    // on arrival (flag consumed so re-compositions don't re-open it).
    LaunchedEffect(tableSessionActive?.openSplitOnArrival) {
        tableSessionActive?.takeIf { it.openSplitOnArrival }?.let { session ->
            tablesViewModel.tableSession.start(session.copy(openSplitOnArrival = false))
            showSplitPayment = true
        }
    }
    var showStaffSelector by remember { mutableStateOf(false) }
    var pendingSplitConfig by remember { mutableStateOf(SplitConfig()) }
    var customerSelectionContext by remember { mutableStateOf(CustomerSelectionContext.GENERAL) }
    var isSubmittingPayLater by remember { mutableStateOf(false) }
    var payLaterError by remember { mutableStateOf<String?>(null) }
    var showPayLaterSuccessToast by remember { mutableStateOf(false) }
    var reopenPayLaterToken by remember { mutableIntStateOf(0) }
    val checkoutScope = rememberCoroutineScope()
    val context = LocalContext.current
    val customersViewModel: CustomersViewModel = hiltViewModel()
    val pdfDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val export = areaTicketOperations.state.value.pdfExport
        if (uri == null || export == null) {
            areaTicketOperations.cancelPendingPdfExport()
        } else {
            checkoutScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(export.bytes)
                        } ?: error("No se pudo abrir el archivo seleccionado.")
                    }
                }
                result
                    .onSuccess {
                        areaTicketOperations.confirmPendingPdfSaved {
                            cartViewModel.clearCart()
                        }
                    }
                    .onFailure { error ->
                        areaTicketOperations.failPendingPdfExport(
                            error.message ?: "No se pudo guardar el PDF.",
                        )
                    }
            }
        }
    }

    LaunchedEffect(areaOperationsState.pdfExport?.code) {
        areaOperationsState.pdfExport?.let { export ->
            runCatching { pdfDocumentLauncher.launch(export.fileName) }
                .onFailure { error ->
                    areaTicketOperations.failPendingPdfExport(
                        error.message ?: "Este dispositivo no tiene un selector de archivos disponible.",
                    )
                }
        }
    }

    // Sync customer selection to the CartViewModel so the referral flow can
    // read it and reset on switch (Plan 5B).
    LaunchedEffect(selectedCustomer?.id) {
        cartViewModel.setSelectedCustomer(selectedCustomer?.id)
    }

    val openGeneralCustomerPicker: () -> Unit = {
        customerSelectionContext = CustomerSelectionContext.GENERAL
        showCustomersSheet = true
    }
    val openPayLaterCustomerPicker: () -> Unit = {
        customerSelectionContext = CustomerSelectionContext.PAY_LATER
        showCustomersSheet = true
    }
    fun confirmPayLaterOrder() {
        if (isSubmittingPayLater) return
        val customerId = selectedCustomer?.id
        if (customerId.isNullOrBlank()) {
            openPayLaterCustomerPicker()
            return
        }
        checkoutScope.launch {
            isSubmittingPayLater = true
            val payLaterResult = cartViewModel.createPayLaterOrder(customerId)
            isSubmittingPayLater = false
            payLaterResult.fold(
                onSuccess = {
                    cartViewModel.clearCart()
                    selectedCustomer = null
                    customerSelectionContext = CustomerSelectionContext.GENERAL
                    showPayLaterSuccessToast = true
                },
                onFailure = { error ->
                    payLaterError = error.message ?: "No se pudo registrar la venta como pagar después"
                },
            )
        }
    }

    fun runPrimaryAction(closePhoneCart: Boolean = false) {
        if (areaOperationsState.issueWorkspace) {
            areaTicketOperations.issue(cartState) {
                cartViewModel.clearCart()
                if (closePhoneCart) showIPhoneCart = false
            }
            return
        }
        if (cartViewModel.hasCreditPack && selectedCustomer == null) {
            showPackCustomerRequired = true
            return
        }
        pendingPackGrant = selectedCustomer?.id?.let { customerId ->
            val ids = cartState.items.mapNotNull { (it.type as? CartItemType.CreditPack)?.packId }
            if (ids.isEmpty()) null else customerId to ids
        }
        if (closePhoneCart) showIPhoneCart = false
        pendingSplitConfig = SplitConfig()
        paymentCartSnapshot = cartState.paymentSnapshot()
        showPaymentFlow = true
    }

    if (isTablet) {
        // iPad-style: 50/50 split with left=input, right=cart
        Row(modifier = Modifier.fillMaxSize()) {
            // Left panel - Input area
            Box(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (showSearch) {
                    SearchOverlayView(
                        viewModel = cartViewModel,
                        onProductTap = { product ->
                            handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                            showSearch = false
                        },
                        onCreateProduct = if (roleManager?.canCreateProducts != false) {
                            { searchName ->
                                showSearch = false
                                createProductInitialName = searchName
                                showCreateProduct = true
                            }
                        } else null,
                        onDismiss = { showSearch = false },
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Search bar + refresh + barcode
                        SearchBarView(
                            isLoading = isLoading,
                            onSearchTap = { showSearch = true },
                            onRefresh = { cartViewModel.refreshProducts() },
                            onBarcodeScan = { showBarcodeScanner = true },
                        )

                        // Tab selector
                        TabSelectorView(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                        )

                        // Tab content
                        when (selectedTab) {
                            InputTab.KEYPAD -> {
                                NumericKeypadView(
                                    amountCents = amountCents,
                                    onAmountChange = { amountCents = it },
                                    onAddToCart = {
                                        if (amountCents > 0) {
                                            cartViewModel.addCustomAmount(
                                                name = currentNote.ifBlank { "Importe personalizado" },
                                                amountCents = amountCents,
                                            )
                                            amountCents = 0
                                            currentNote = ""
                                        }
                                    },
                                    onNoteTap = { showNoteDialog = true },
                                    noteText = currentNote,
                                    useCompactSizing = true,
                                )
                            }
                            InputTab.SHORTCUTS -> {
                                ShortcutsGridView(
                                    cartViewModel = cartViewModel,
                                    discountsRepository = cartViewModel.discountsRepository,
                                    onCustomerSearch = openPayLaterCustomerPicker,
                                    reopenPayLaterToken = reopenPayLaterToken,
                                    selectedPayLaterCustomerName = selectedCustomer?.fullName,
                                    onConfirmPayLater = ::confirmPayLaterOrder,
                                    isConfirmingPayLater = isSubmittingPayLater,
                                    onCreateItem = { showCreateProduct = true },
                                    onProductTap = { product ->
                                        handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                                    },
                                    canCreateProducts = roleManager?.canCreateProducts ?: true,
                                )
                            }
                            InputTab.PRODUCTS -> {
                                ProductGridView(
                                    viewModel = cartViewModel,
                                    onProductTap = { product ->
                                        handleProductTap(
                                            product,
                                            cartViewModel,
                                            { selectedProduct = it },
                                            { weightProduct = it },
                                        )
                                    },
                                    onPackTap = if (roleManager?.canReadCreditPacks == true) {
                                        { cartViewModel.addCreditPack(it) }
                                    } else {
                                        null
                                    },
                                )
                            }
                            InputTab.MOSAIC -> {
                                MosaicConfigView(
                                    cartViewModel = cartViewModel,
                                )
                            }
                        }
                    }
                }
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // Right panel - Cart
            Box(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight(),
            ) {
                CartPanelView(
                    cartState = cartState,
                    onItemTap = { item -> selectedCartItem = item },
                    onOrderTypeChange = { cartViewModel.setOrderType(it) },
                    onCharge = {
                        runPrimaryAction()
                    },
                    onClearCart = { showClearCartConfirm = true },
                    onSaveCart = {
                        if (cartViewModel.saveCurrentCart()) {
                            showSavedSnackbar = true
                        }
                    },
                    onAddCustomAmount = { selectedTab = InputTab.KEYPAD },
                    onRemoveItem = { cartViewModel.removeItem(it) },
                    onApplyTaxPercent = { cartViewModel.applyOrderTaxPercent(it) },
                    customerName = selectedCustomer?.fullName,
                    customerId = selectedCustomer?.id,
                    onCustomerTap = openGeneralCustomerPicker,
                    staffName = cartState.selectedStaffName,
                    onStaffTap = {
                        cartViewModel.fetchStaff()
                        showStaffSelector = true
                    },
                    onSplitPayment = {
                        // Membresías grant only on FULL payment — a split sale
                        // charged the pack without ever granting credits.
                        if (cartViewModel.hasCreditPack) showPackNoSplitAlert = true
                        else showSplitPayment = true
                    },
                    referralCode = referralCodeState,
                    referralUiState = referralUiState,
                    customerSelectedForReferral = selectedCustomer != null,
                    onReferralCodeChange = { cartViewModel.onReferralCodeChange(it) },
                    onValidateReferral = { cartViewModel.validateReferralCode() },
                    onClearReferral = { cartViewModel.clearReferral() },
                    onForceOverrideReferral = {
                        // v1: placeholder hook — the manager-PIN dialog lands in v2.
                        // Disabled at the button level, but keep the lambda wired
                        // so future work just flips the `enabled` flag.
                    },
                    referralPlanAllowed = cartViewModel.referralPlanAllowed,
                    primaryActionLabel = if (areaOperationsState.issueWorkspace) {
                        "Emitir vale ${cartState.totalDisplay}"
                    } else {
                        null
                    },
                )
            }
        }
    } else {
        // iPhone-style: full-screen with bottom cart bar
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSearch) {
                    SearchOverlayView(
                        viewModel = cartViewModel,
                        onProductTap = { product ->
                            handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                            showSearch = false
                        },
                        onCreateProduct = if (roleManager?.canCreateProducts != false) {
                            { searchName ->
                                showSearch = false
                                createProductInitialName = searchName
                                showCreateProduct = true
                            }
                        } else null,
                        onDismiss = { showSearch = false },
                    )
                } else {
                    // Search bar
                    SearchBarView(
                        isLoading = isLoading,
                        onSearchTap = { showSearch = true },
                        onRefresh = { cartViewModel.refreshProducts() },
                        onBarcodeScan = { showBarcodeScanner = true },
                    )

                    // Tab selector
                    TabSelectorView(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                    )

                    // Tab content
                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            InputTab.KEYPAD -> {
                                NumericKeypadView(
                                    amountCents = amountCents,
                                    onAmountChange = { amountCents = it },
                                    onAddToCart = {
                                        if (amountCents > 0) {
                                            cartViewModel.addCustomAmount(
                                                name = currentNote.ifBlank { "Importe personalizado" },
                                                amountCents = amountCents,
                                            )
                                            amountCents = 0
                                            currentNote = ""
                                        }
                                    },
                                    onNoteTap = { showNoteDialog = true },
                                    noteText = currentNote,
                                    useCompactSizing = false,
                                )
                            }
                            InputTab.SHORTCUTS -> {
                                ShortcutsGridView(
                                    cartViewModel = cartViewModel,
                                    discountsRepository = cartViewModel.discountsRepository,
                                    onCustomerSearch = openPayLaterCustomerPicker,
                                    reopenPayLaterToken = reopenPayLaterToken,
                                    selectedPayLaterCustomerName = selectedCustomer?.fullName,
                                    onConfirmPayLater = ::confirmPayLaterOrder,
                                    isConfirmingPayLater = isSubmittingPayLater,
                                    onCreateItem = { showCreateProduct = true },
                                    onProductTap = { product ->
                                        handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                                    },
                                    canCreateProducts = roleManager?.canCreateProducts ?: true,
                                )
                            }
                            InputTab.PRODUCTS -> {
                                ProductGridView(
                                    viewModel = cartViewModel,
                                    onProductTap = { product ->
                                        handleProductTap(
                                            product,
                                            cartViewModel,
                                            { selectedProduct = it },
                                            { weightProduct = it },
                                        )
                                    },
                                    onPackTap = { cartViewModel.addCreditPack(it) },
                                )
                            }
                            InputTab.MOSAIC -> {
                                MosaicConfigView(
                                    cartViewModel = cartViewModel,
                                )
                            }
                        }
                    }

                    // Bottom cart bar (iPhone only)
                    if (!cartState.isEmpty) {
                        IPhoneCartBar(
                            itemCount = cartState.itemCount,
                            total = cartState.subtotalDisplay,
                            onClick = { showIPhoneCart = true },
                        )
                    }
                }
            }
        }
    }

    // Product detail panel overlay
    selectedProduct?.let { product ->
        val discounts by cartViewModel.discountsRepository.discounts.collectAsState()
        ProductDetailPanel(
            product = product,
            isTablet = isTablet,
            discounts = discounts,
            onAddToCart = { quantity, modifiers, note, isCortesia, cortesiaReason, priceAdj, discountId ->
                cartViewModel.addProductWithModifiers(
                    product = product,
                    quantity = quantity,
                    modifiers = modifiers,
                    note = note,
                    isCortesia = isCortesia,
                    cortesiaReason = cortesiaReason,
                    priceAdjustment = priceAdj,
                    discountId = discountId,
                )
                selectedProduct = null
            },
            onDismiss = { selectedProduct = null },
        )
    }

    // Venta por peso: panel de captura de peso (báscula/manual) para productos soldByWeight.
    weightProduct?.let { product ->
        LaunchedEffect(product.id, areaOperationsState.settings?.scaleIntegration) {
            scaleCaptureViewModel.start(areaOperationsState.settings?.scaleIntegration)
        }
        WeightCapturePanel(
            product = product,
            isTablet = isTablet,
            scaleState = scaleState,
            onRetryScale = scaleCaptureViewModel::retry,
            onAdd = { weightKg ->
                cartViewModel.addProductByWeight(product, weightKg)
            },
            onDismiss = {
                scaleCaptureViewModel.stop()
                weightProduct = null
            },
        )
    }

    // Cart item detail overlay (matching iOS: side panel on tablet, sheet on phone)
    selectedCartItem?.let { item ->
        CartItemDetailPanel(
            item = item,
            isTablet = isTablet,
            onUpdateQuantity = { newQty ->
                cartViewModel.updateQuantity(item.id, newQty)
                // Refresh state
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onUpdateNote = { note ->
                cartViewModel.updateItemNote(item.id, note)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onToggleCortesia = { isCortesia, reason ->
                cartViewModel.updateItemCortesia(item.id, isCortesia, reason)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onUpdatePriceAdjustment = { priceCents ->
                cartViewModel.updateItemPriceAdjustment(item.id, priceCents)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onDelete = {
                cartViewModel.removeItem(item.id)
                selectedCartItem = null
            },
            onDismiss = { selectedCartItem = null },
        )
    }

    // iPhone full-screen cart sheet (matching iOS fullScreenCover)
    if (showIPhoneCart) {
        IPhoneCartSheet(
            cartState = cartState,
            onItemTap = { item -> selectedCartItem = item },
            onOrderTypeChange = { cartViewModel.setOrderType(it) },
            onCharge = {
                runPrimaryAction(closePhoneCart = true)
            },
            onClearCart = { showClearCartConfirm = true },
            onSaveCart = {
                if (cartViewModel.saveCurrentCart()) {
                    showSavedSnackbar = true
                }
            },
            onAddCustomAmount = {
                showIPhoneCart = false
                selectedTab = InputTab.KEYPAD
            },
            onRemoveItem = { cartViewModel.removeItem(it) },
            onApplyTaxPercent = { cartViewModel.applyOrderTaxPercent(it) },
            staffName = cartState.selectedStaffName,
            onStaffTap = {
                cartViewModel.fetchStaff()
                showStaffSelector = true
            },
            customerName = selectedCustomer?.fullName,
            customerId = selectedCustomer?.id,
            onCustomerTap = openGeneralCustomerPicker,
            referralCode = referralCodeState,
            referralUiState = referralUiState,
            customerSelectedForReferral = selectedCustomer != null,
            onReferralCodeChange = { cartViewModel.onReferralCodeChange(it) },
            onValidateReferral = { cartViewModel.validateReferralCode() },
            onClearReferral = { cartViewModel.clearReferral() },
            onForceOverrideReferral = { /* v1 placeholder */ },
            referralPlanAllowed = cartViewModel.referralPlanAllowed,
            onDismiss = { showIPhoneCart = false },
            primaryActionLabel = if (areaOperationsState.issueWorkspace) {
                "Emitir vale ${cartState.totalDisplay}"
            } else {
                null
            },
        )
    }

    if (showStaffSelector) {
        StaffSelectorSheet(
            staff = staffOptions,
            selectedStaffId = cartState.selectedStaffId,
            isLoading = isStaffLoading,
            error = staffError,
            onStaffSelected = { staff ->
                cartViewModel.selectStaff(staff.id, staff.fullName)
            },
            onDismiss = { showStaffSelector = false },
        )
    }

    // Barcode scanner overlay
    if (showBarcodeScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            BarcodeScannerView(
                onBarcodeScanned = { barcode ->
                    showBarcodeScanner = false
                    checkoutScope.launch {
                        when (val result = cartViewModel.resolveScannedBarcode(barcode)) {
                            is ScannedBarcodeResult.ProductFound ->
                                handleProductTap(
                                    result.product,
                                    cartViewModel,
                                    { selectedProduct = it },
                                    { weightProduct = it },
                                )
                            is ScannedBarcodeResult.AreaTicketsAdded ->
                                areaTicketAddedCount = result.ticketCount
                            is ScannedBarcodeResult.Unknown ->
                                unknownBarcode = result.code
                            is ScannedBarcodeResult.Error ->
                                barcodeError = result.message
                        }
                    }
                },
                onDismiss = { showBarcodeScanner = false },
            )
        }
    }

    // Create product overlay
    if (showCreateProduct) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            CreateProductView(
                productsRepository = cartViewModel.productsRepository,
                initialName = createProductInitialName,
                initialGtin = createProductInitialGtin,
                onProductCreated = { product ->
                    showCreateProduct = false
                    createProductInitialName = ""
                    createProductInitialGtin = ""
                    handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                },
                onDismiss = {
                    showCreateProduct = false
                    createProductInitialName = ""
                    createProductInitialGtin = ""
                },
            )
        }
    }

    // Unknown barcode confirmation dialog
    unknownBarcode?.let { scannedCode ->
        AvoqadoDialog(
            title = "Producto no encontrado",
            description = "No existe un producto con el código $scannedCode. ¿Quieres crear uno nuevo?",
            onDismiss = { unknownBarcode = null },
            actionButton = {
                PrimaryButton(
                    text = "Crear nuevo",
                    onClick = {
                        createProductInitialGtin = scannedCode
                        unknownBarcode = null
                        showCreateProduct = true
                    },
                    fullWidth = true,
                )
            },
        ) {}
    }

    barcodeError?.let { message ->
        AvoqadoDialog(
            title = "No se pudo agregar el vale",
            description = message,
            onDismiss = { barcodeError = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { barcodeError = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    areaTicketAddedCount?.let { count ->
        AvoqadoSuccessToast(
            message = "¡Vale agregado!",
            subtitle = "$count ${if (count == 1) "vale" else "vales"} en esta venta",
            onDismiss = { areaTicketAddedCount = null },
        )
    }

    areaOperationsState.message?.let { message ->
        AvoqadoSuccessToast(
            message = message,
            onDismiss = areaTicketOperations::dismissFeedback,
        )
    }

    areaOperationsState.checkoutBlockingError?.let { message ->
        AvoqadoDialog(
            title = "Vale por área",
            description = message,
            onDismiss = areaTicketOperations::dismissFeedback,
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = areaTicketOperations::dismissFeedback,
                    fullWidth = true,
                )
            },
        ) {}
    }

    if (areaOperationsState.error == null && areaOperationsState.pendingReprintCode != null) {
        AvoqadoDialog(
            title = "Vale pendiente de impresión",
            description = "El vale ${areaOperationsState.pendingReprintCode} ya existe y no se emitirá otro. Reimprímelo o guárdalo como PDF con el mismo código.",
            onDismiss = {
                if (!areaOperationsState.submitting && !areaOperationsState.preparingPdf) {
                    areaTicketOperations.dismissPendingReprint()
                }
            },
            actionButton = {
                PrimaryButton(
                    text = if (areaOperationsState.submitting) "Reimprimiendo…" else "Reimprimir",
                    onClick = {
                        areaTicketOperations.reprintPending {
                            cartViewModel.clearCart()
                        }
                    },
                    enabled = !areaOperationsState.submitting && !areaOperationsState.preparingPdf,
                    fullWidth = true,
                )
            },
        ) {
            PrimaryButton(
                text = if (areaOperationsState.preparingPdf) {
                    "Preparando PDF…"
                } else {
                    "Guardar PDF y continuar"
                },
                onClick = areaTicketOperations::preparePendingPdf,
                enabled = !areaOperationsState.submitting && !areaOperationsState.preparingPdf,
                fullWidth = true,
            )
        }
    }

    // Payment flow overlay (full screen, matching iOS fullScreenCover)
    if (showPaymentFlow) {
        val paymentCart = paymentCartSnapshot ?: cartState.paymentSnapshot()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            PaymentFlowScreen(
                cartState = paymentCart,
                onComplete = { completion ->
                    when {
                        completion.splitType == "BYPRODUCT" && completion.paidItemIds.isNotEmpty() -> {
                            completion.paidItemIds.forEach { paidItemId ->
                                cartViewModel.removeItem(paidItemId)
                            }
                        }
                        completion.remainingBalanceCents > 0 -> {
                            // For amount-based partial splits, carry remaining balance as pending amount.
                            cartViewModel.clearCart()
                            cartViewModel.addCustomAmount(
                                name = "Saldo pendiente",
                                amountCents = completion.remainingBalanceCents,
                            )
                            // TABLE_SERVICE (PAYING): the session's charge target
                            // becomes the remainder so a re-entry never re-seeds
                            // the original (already partially paid) total.
                            if (tableSessionActive?.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING) {
                                tablesViewModel.updateTableSessionRemaining(completion.remainingBalanceCents)
                            }
                        }
                        else -> {
                            // Full payment — grant any captured membership credits.
                            pendingPackGrant?.let { creditsViewModel.grantPacks(it.second, it.first) }
                            pendingPackGrant = null
                            cartViewModel.clearCart()
                            // TABLE_SERVICE (PAYING): the table's order was just
                            // fully paid through the normal flow — release it.
                            if (tableSessionActive?.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING) {
                                tablesViewModel.finishTableAfterPayment()
                            }
                        }
                    }
                    // Referral is real now: capture on actual payment success
                    // (a cancelled payment no longer leaves a dangling referral).
                    checkoutScope.launch { cartViewModel.captureReferralOnPayment(orderId = null) }
                    showPaymentFlow = false
                    paymentCartSnapshot = null
                    pendingSplitConfig = SplitConfig()
                    // El "Gracias" del cliente vuelve al logo del negocio (o al
                    // carrito si quedó saldo) en cuanto se cierra el pago.
                    cartViewModel.refreshCustomerDisplay()
                },
                onCancel = {
                    showPaymentFlow = false
                    paymentCartSnapshot = null
                    pendingSplitConfig = SplitConfig()
                    cartViewModel.refreshCustomerDisplay()
                },
                splitConfig = pendingSplitConfig,
            )
        }
    }

    // Split payment sheet
    if (showSplitPayment && !cartState.isEmpty) {
        SplitPaymentSheet(
            totalCents = cartState.totalCents,
            items = cartState.items,
            allowByProduct = cartState.items.none { it.locked },
            onDismiss = { showSplitPayment = false },
            onConfirm = { splitConfig ->
                showSplitPayment = false
                pendingSplitConfig = splitConfig
                paymentCartSnapshot = cartState.paymentSnapshot()
                showPaymentFlow = true
            },
        )
    }

    // Save cart success toast
    if (showSavedSnackbar) {
        AvoqadoSuccessToast(
            message = "¡Carrito guardado!",
            onDismiss = { showSavedSnackbar = false },
        )
    }

    if (showPayLaterSuccessToast) {
        AvoqadoSuccessToast(
            message = "¡Venta enviada a pagar después!",
            onDismiss = { showPayLaterSuccessToast = false },
        )
    }

    // Customers sheet (full screen overlay matching iOS)
    if (showCustomersSheet) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            if (showCreateCustomer) {
                CreateCustomerView(
                    viewModel = customersViewModel,
                    initialPhone = createCustomerSearchText.takeIf { it.all { c -> c.isDigit() || c == '+' } },
                    initialName = createCustomerSearchText.takeIf { !it.all { c -> c.isDigit() || c == '+' } },
                    onCustomerCreated = { customer ->
                        val fromPayLater = customerSelectionContext == CustomerSelectionContext.PAY_LATER
                        selectedCustomer = customer
                        showCreateCustomer = false
                        showCustomersSheet = false
                        if (fromPayLater) {
                            selectedTab = InputTab.SHORTCUTS
                            reopenPayLaterToken += 1
                        }
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onBack = { showCreateCustomer = false },
                )
            } else {
                CustomersView(
                    viewModel = customersViewModel,
                    onCustomerSelected = { customer ->
                        val fromPayLater = customerSelectionContext == CustomerSelectionContext.PAY_LATER
                        selectedCustomer = customer
                        showCustomersSheet = false
                        showCreateCustomer = false
                        if (fromPayLater) {
                            selectedTab = InputTab.SHORTCUTS
                            reopenPayLaterToken += 1
                        }
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onDismiss = {
                        showCustomersSheet = false
                        showCreateCustomer = false
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onCreateCustomer = { searchText ->
                        createCustomerSearchText = searchText
                        showCreateCustomer = true
                    },
                    canCreateCustomer = roleManager?.canManageCustomers ?: true,
                )
            }
        }
    }

    payLaterError?.let { message ->
        AvoqadoDialog(
            title = "No se pudo diferir el pago",
            description = message,
            onDismiss = { payLaterError = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { payLaterError = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    // Note dialog for keypad custom amount
    if (showClearCartConfirm) {
        AvoqadoDialog(
            title = "¿Vaciar carrito?",
            description = if (cartState.reservationId != null)
                "Este carrito tiene una clase con inscripción activa. Al vaciarlo, la inscripción se mantiene SIN cobro — cóbrala después o cancélala desde el calendario."
            else "Se quitarán todos los artículos del carrito.",
            onDismiss = { showClearCartConfirm = false },
            actionButton = {
                PrimaryButton(text = "Vaciar", onClick = {
                    cartViewModel.clearCart()
                    showClearCartConfirm = false
                })
            },
            content = {},
        )
    }

    if (showPackNoSplitAlert) {
        AvoqadoDialog(
            title = "Membresía en el carrito",
            description = "Las membresías se cobran en un solo pago. Cobra la membresía por separado o quítala del carrito para dividir el pago.",
            onDismiss = { showPackNoSplitAlert = false },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { showPackNoSplitAlert = false },
                )
            },
            content = {},
        )
    }

    if (showPackCustomerRequired) {
        AvoqadoDialog(
            title = "Asigna un cliente",
            description = "Este cobro incluye una membresía. Asigna un cliente al carrito para poder otorgarle los créditos.",
            onDismiss = { showPackCustomerRequired = false },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { showPackCustomerRequired = false },
                )
            },
            content = {},
        )
    }

    if (showNoteDialog) {
        var noteInput by remember { mutableStateOf(currentNote) }
        AvoqadoDialog(
            title = "Agregar nota",
            description = "Escribe una nota para el importe personalizado",
            onDismiss = { showNoteDialog = false },
            actionButton = {
                PrimaryButton(
                    text = "Guardar",
                    onClick = {
                        currentNote = noteInput
                        showNoteDialog = false
                    },
                    fullWidth = true,
                )
            },
        ) {
            AvoqadoPillTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                placeholder = "Nota para el importe",
            )
            if (currentNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Quitar nota",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        currentNote = ""
                        showNoteDialog = false
                    },
                )
            }
        }
    }
}

private fun CartState.paymentSnapshot(): CartState = copy(
    items = items.map { item ->
        item.copy(selectedModifiers = item.selectedModifiers.toList())
    },
)

private fun handleProductTap(
    product: Product,
    cartViewModel: CartViewModel,
    onSelectForDetail: (Product) -> Unit,
    onSelectForWeight: (Product) -> Unit,
) {
    when {
        // Venta por peso MANDA sobre los modificadores (MVP): el tap SIEMPRE abre la captura de
        // peso, nunca el panel de modificadores, aunque el producto tenga grupos.
        product.soldByWeight -> onSelectForWeight(product)
        product.hasModifiers -> onSelectForDetail(product)
        else -> cartViewModel.addProduct(product)
    }
}

// MARK: - iPhone Cart Sheet (full screen, matching iOS fullScreenCover)

@Composable
private fun IPhoneCartSheet(
    cartState: com.avoqado.pos.pos.presentation.cart.CartState,
    onItemTap: (CartItem) -> Unit,
    onCharge: () -> Unit,
    onOrderTypeChange: (String) -> Unit = {},
    onClearCart: () -> Unit,
    onSaveCart: () -> Unit,
    onAddCustomAmount: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onApplyTaxPercent: (Int?) -> Unit,
    staffName: String,
    onStaffTap: () -> Unit,
    customerName: String? = null,
    customerId: String? = null,
    onCustomerTap: () -> Unit = {},
    referralCode: String = "",
    referralUiState: com.avoqado.pos.referrals.presentation.ReferralCaptureUiState =
        com.avoqado.pos.referrals.presentation.ReferralCaptureUiState.Idle,
    customerSelectedForReferral: Boolean = customerName != null,
    onReferralCodeChange: (String) -> Unit = {},
    onValidateReferral: () -> Unit = {},
    onClearReferral: () -> Unit = {},
    onForceOverrideReferral: () -> Unit = {},
    referralPlanAllowed: Boolean = true,
    onDismiss: () -> Unit,
    primaryActionLabel: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: "Cerrar" | "Carrito" | invisible spacer (matching iOS)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cerrar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Carrito",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                // Invisible spacer to balance "Cerrar" button
                Text(
                    text = "Cerrar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Transparent,
                )
            }

            HorizontalDivider()

            // Reuse CartPanelView
            CartPanelView(
                cartState = cartState,
                onItemTap = onItemTap,
                onCharge = onCharge,
                onOrderTypeChange = onOrderTypeChange,
                onClearCart = onClearCart,
                onSaveCart = onSaveCart,
                onAddCustomAmount = onAddCustomAmount,
                onRemoveItem = onRemoveItem,
                onApplyTaxPercent = onApplyTaxPercent,
                customerName = customerName,
                customerId = customerId,
                onCustomerTap = onCustomerTap,
                staffName = staffName,
                onStaffTap = onStaffTap,
                referralCode = referralCode,
                referralUiState = referralUiState,
                customerSelectedForReferral = customerSelectedForReferral,
                onReferralCodeChange = onReferralCodeChange,
                onValidateReferral = onValidateReferral,
                onClearReferral = onClearReferral,
                onForceOverrideReferral = onForceOverrideReferral,
                referralPlanAllowed = referralPlanAllowed,
                primaryActionLabel = primaryActionLabel,
            )
        }
    }
}

// MARK: - Cart Item Detail Panel (matching iOS: side panel on tablet, sheet on phone)

@Composable
private fun CartItemDetailPanel(
    item: CartItem,
    isTablet: Boolean,
    onUpdateQuantity: (Int) -> Unit,
    onUpdateNote: (String?) -> Unit,
    onToggleCortesia: (Boolean, String?) -> Unit,
    onUpdatePriceAdjustment: (Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        // Side panel overlay from right (matching iOS)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(400.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {}),
            ) {
                CartItemDetailContent(
                    item = item,
                    onUpdateQuantity = onUpdateQuantity,
                    onUpdateNote = onUpdateNote,
                    onToggleCortesia = onToggleCortesia,
                    onUpdatePriceAdjustment = onUpdatePriceAdjustment,
                    onDelete = onDelete,
                    onDismiss = onDismiss,
                )
            }
        }
    } else {
        // Phone: full-screen overlay (matching iOS sheet)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            CartItemDetailContent(
                item = item,
                onUpdateQuantity = onUpdateQuantity,
                onUpdateNote = onUpdateNote,
                onToggleCortesia = onToggleCortesia,
                onUpdatePriceAdjustment = onUpdatePriceAdjustment,
                onDelete = onDelete,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun CartItemDetailContent(
    item: CartItem,
    onUpdateQuantity: (Int) -> Unit,
    onUpdateNote: (String?) -> Unit,
    onToggleCortesia: (Boolean, String?) -> Unit,
    onUpdatePriceAdjustment: (Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$${String.format("%.2f", item.totalPrice / 100.0)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (item.isCortesia) Color(0xFF34C759)
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                )
                // Venta por peso: subtítulo "0.435 kg × $420.00/kg" en vez del resumen de modificadores.
                item.weightSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.modifiersSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Close button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        HorizontalDivider()

        // Quantity selector — oculto en líneas por peso (D9: cada pesada es 1 línea con cantidad 1).
        if (item.weightKg == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cantidad",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    )
                    .padding(horizontal = AvoqadoTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(enabled = item.quantity > 1) {
                            onUpdateQuantity(item.quantity - 1)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Menos",
                        modifier = Modifier.size(18.dp),
                        tint = if (item.quantity > 1) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                Text(
                    text = "${item.quantity}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onUpdateQuantity(item.quantity + 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Mas",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        } // fin del bloque de cantidad (oculto para líneas por peso)

        // Note section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newNote = if (item.itemNote.isNullOrEmpty()) "" else null
                    onUpdateNote(newNote)
                }
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nota del artículo",
                    style = MaterialTheme.typography.bodyMedium,
                )
                item.itemNote?.let { note ->
                    if (note.isNotEmpty()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

        // Cortesia toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (item.isCortesia) {
                        onToggleCortesia(false, null)
                    } else {
                        onToggleCortesia(true, "Cortesia del administrador")
                    }
                }
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cortesia",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (item.isCortesia) {
                    Text(
                        text = item.cortesiaReason ?: "Activado",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF34C759),
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Delete button at bottom
        HorizontalDivider()
        TextButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = "Eliminar artículo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// MARK: - Search Bar (matching iOS: pill search + refresh + barcode buttons)

@Composable
private fun SearchBarView(
    isLoading: Boolean,
    onSearchTap: () -> Unit,
    onRefresh: () -> Unit,
    onBarcodeScan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .padding(top = AvoqadoTheme.spacing.xxl, bottom = AvoqadoTheme.spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Search field (tappable pill)
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

        // Refresh button
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Actualizar productos",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Barcode scanner button
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onBarcodeScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = "Escanear código de barras",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Tab Selector (matching iOS: underline style tabs)

@Composable
private fun TabSelectorView(
    selectedTab: InputTab,
    onTabSelected: (InputTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .padding(bottom = AvoqadoTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        InputTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clickable { onTabSelected(tab) },
            ) {
                Text(
                    text = tab.label,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}

// MARK: - iPhone Cart Bar (black bar at bottom)

@Composable
private fun IPhoneCartBar(
    itemCount: Int,
    total: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cart badge
        Text(
            text = "$itemCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.error,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Ver carrito",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = total,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
