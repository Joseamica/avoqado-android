package com.avoqado.pos.pos.presentation.checkout

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.customers.data.model.Customer
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
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartPanelView
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.product.CreateProductView
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductGridView
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.presentation.search.SearchOverlayView
import kotlinx.coroutines.launch

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
    val cartState by cartViewModel.cartState.collectAsState()
    val isLoading by cartViewModel.isLoading.collectAsState()
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var selectedTab by remember { mutableStateOf(InputTab.KEYPAD) }
    var showSearch by remember { mutableStateOf(false) }
    var amountCents by remember { mutableIntStateOf(0) }
    var showPaymentFlow by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var currentNote by remember { mutableStateOf("") }
    var showIPhoneCart by remember { mutableStateOf(false) }
    var selectedCartItem by remember { mutableStateOf<CartItem?>(null) }
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var showCustomersSheet by remember { mutableStateOf(false) }
    var showCreateCustomer by remember { mutableStateOf(false) }
    var createCustomerSearchText by remember { mutableStateOf("") }
    var showSavedSnackbar by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showCreateProduct by remember { mutableStateOf(false) }
    var createProductInitialName by remember { mutableStateOf("") }
    var createProductInitialGtin by remember { mutableStateOf("") }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var showSplitPayment by remember { mutableStateOf(false) }
    var pendingSplitConfig by remember { mutableStateOf(SplitConfig()) }
    var customerSelectionContext by remember { mutableStateOf(CustomerSelectionContext.GENERAL) }
    var isSubmittingPayLater by remember { mutableStateOf(false) }
    var payLaterError by remember { mutableStateOf<String?>(null) }
    var showPayLaterSuccessToast by remember { mutableStateOf(false) }
    var reopenPayLaterToken by remember { mutableIntStateOf(0) }
    val checkoutScope = rememberCoroutineScope()
    val customersViewModel: CustomersViewModel = hiltViewModel()

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
                    payLaterError = error.message ?: "No se pudo registrar la venta como pagar despues"
                },
            )
        }
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
                            handleProductTap(product, cartViewModel, { selectedProduct = it })
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
                                        handleProductTap(product, cartViewModel, { selectedProduct = it })
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
                                        )
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
                    onCharge = {
                        pendingSplitConfig = SplitConfig()
                        showPaymentFlow = true
                    },
                    onClearCart = { cartViewModel.clearCart() },
                    onSaveCart = {
                        if (cartViewModel.saveCurrentCart()) {
                            showSavedSnackbar = true
                        }
                    },
                    onAddCustomAmount = { selectedTab = InputTab.KEYPAD },
                    onRemoveItem = { cartViewModel.removeItem(it) },
                    onApplyTaxPercent = { cartViewModel.applyOrderTaxPercent(it) },
                    customerName = selectedCustomer?.fullName,
                    onCustomerTap = openGeneralCustomerPicker,
                    onSplitPayment = { showSplitPayment = true },
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
                            handleProductTap(product, cartViewModel, { selectedProduct = it })
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
                                        handleProductTap(product, cartViewModel, { selectedProduct = it })
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
                                        )
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
            onAddToCart = { quantity, modifiers, note, isCortesia, cortesiaReason, priceAdj ->
                cartViewModel.addProductWithModifiers(
                    product = product,
                    quantity = quantity,
                    modifiers = modifiers,
                    note = note,
                    isCortesia = isCortesia,
                    cortesiaReason = cortesiaReason,
                    priceAdjustment = priceAdj,
                )
                selectedProduct = null
            },
            onDismiss = { selectedProduct = null },
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
            onCharge = {
                showIPhoneCart = false
                pendingSplitConfig = SplitConfig()
                showPaymentFlow = true
            },
            onClearCart = { cartViewModel.clearCart() },
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
            onDismiss = { showIPhoneCart = false },
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
                    val products = cartViewModel.products.value
                    val matched = products.find { product ->
                        product.sku == barcode || product.barcode == barcode || product.gtin == barcode
                    }
                    if (matched != null) {
                        handleProductTap(matched, cartViewModel, { selectedProduct = it })
                    } else {
                        unknownBarcode = barcode
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
            CreateProductView(
                productsRepository = cartViewModel.productsRepository,
                initialName = createProductInitialName,
                initialGtin = createProductInitialGtin,
                onProductCreated = { product ->
                    showCreateProduct = false
                    createProductInitialName = ""
                    createProductInitialGtin = ""
                    handleProductTap(product, cartViewModel, { selectedProduct = it })
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

    // Payment flow overlay (full screen, matching iOS fullScreenCover)
    if (showPaymentFlow) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            PaymentFlowScreen(
                cartState = cartState,
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
                        }
                        else -> {
                            cartViewModel.clearCart()
                        }
                    }
                    showPaymentFlow = false
                    pendingSplitConfig = SplitConfig()
                },
                onCancel = {
                    showPaymentFlow = false
                    pendingSplitConfig = SplitConfig()
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
            onDismiss = { showSplitPayment = false },
            onConfirm = { splitConfig ->
                showSplitPayment = false
                pendingSplitConfig = splitConfig
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

private fun handleProductTap(
    product: Product,
    cartViewModel: CartViewModel,
    onSelectForDetail: (Product) -> Unit,
) {
    if (product.hasModifiers) {
        onSelectForDetail(product)
    } else {
        cartViewModel.addProduct(product)
    }
}

// MARK: - iPhone Cart Sheet (full screen, matching iOS fullScreenCover)

@Composable
private fun IPhoneCartSheet(
    cartState: com.avoqado.pos.pos.presentation.cart.CartState,
    onItemTap: (CartItem) -> Unit,
    onCharge: () -> Unit,
    onClearCart: () -> Unit,
    onSaveCart: () -> Unit,
    onAddCustomAmount: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onApplyTaxPercent: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
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
                onClearCart = onClearCart,
                onSaveCart = onSaveCart,
                onAddCustomAmount = onAddCustomAmount,
                onRemoveItem = onRemoveItem,
                onApplyTaxPercent = onApplyTaxPercent,
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
                .background(MaterialTheme.colorScheme.surface),
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

        // Quantity selector
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
                    text = "Nota del articulo",
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
                text = "Eliminar articulo",
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
                contentDescription = "Escanear codigo de barras",
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
