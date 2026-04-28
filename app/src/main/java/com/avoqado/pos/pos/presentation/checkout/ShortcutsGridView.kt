package com.avoqado.pos.pos.presentation.checkout

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.ActionGreen
import com.avoqado.pos.designsystem.theme.ActionOrange
import com.avoqado.pos.designsystem.theme.ActionPurple
import com.avoqado.pos.designsystem.theme.ActionRed
import com.avoqado.pos.designsystem.theme.ActionTeal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import androidx.compose.ui.platform.LocalContext
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel

// MARK: - Shortcut Action Colors (Square-style muted tones)

private object ActionColors {
    val giftCard = ActionPurple
    val discounts = Color(0xFFE61F6B)   // Pink/Magenta
    val coupons = ActionOrange
    val payLater = ActionTeal
    val voidItems = ActionRed
    val cortesia = ActionGreen
    val createItem = Color(0xFF991A33)
    val savedCarts = ActionOrange
}

// MARK: - Shortcut Data Model

private data class ShortcutItem(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val screen: ShortcutsScreen,
    val enabled: Boolean = true,
    val badge: String? = null,
)

private enum class ShortcutsScreen {
    MAIN,
    DISCOUNTS,
    COUPONS,
    VOID_ITEMS,
    CORTESIA,
    PAY_LATER,
    SAVED_CARTS,
    CREATE_ITEM,
}

// MARK: - Main Shortcuts Grid View

@Composable
fun ShortcutsGridView(
    cartViewModel: CartViewModel,
    discountsRepository: DiscountsRepository,
    onCustomerSearch: () -> Unit = {},
    reopenPayLaterToken: Int = 0,
    selectedPayLaterCustomerName: String? = null,
    onConfirmPayLater: () -> Unit = {},
    isConfirmingPayLater: Boolean = false,
    onCreateItem: () -> Unit = {},
    onProductTap: (Product) -> Unit = {},
    canCreateProducts: Boolean = true,
) {
    val cartState by cartViewModel.cartState.collectAsState()
    val allProducts by cartViewModel.products.collectAsState()
    var currentScreen by remember { mutableStateOf(ShortcutsScreen.MAIN) }
    var showPayLaterModal by remember { mutableStateOf(false) }

    LaunchedEffect(reopenPayLaterToken) {
        if (reopenPayLaterToken > 0) {
            showPayLaterModal = true
        }
    }

    when (currentScreen) {
        ShortcutsScreen.MAIN -> {
            ShortcutsMainGrid(
                cartState = cartState,
                allProducts = allProducts,
                onNavigate = { screen ->
                    if (screen == ShortcutsScreen.PAY_LATER) {
                        showPayLaterModal = true
                    } else {
                        currentScreen = screen
                    }
                },
                onProductTap = onProductTap,
                canCreateProducts = canCreateProducts,
            )
        }
        ShortcutsScreen.DISCOUNTS -> {
            DiscountsSubView(
                cartViewModel = cartViewModel,
                discountsRepository = discountsRepository,
                onBack = { currentScreen = ShortcutsScreen.MAIN },
            )
        }
        ShortcutsScreen.COUPONS -> {
            CouponsSubView(
                cartViewModel = cartViewModel,
                discountsRepository = discountsRepository,
                onBack = { currentScreen = ShortcutsScreen.MAIN },
            )
        }
        ShortcutsScreen.VOID_ITEMS -> {
            VoidItemsSubView(
                cartViewModel = cartViewModel,
                onBack = { currentScreen = ShortcutsScreen.MAIN },
            )
        }
        ShortcutsScreen.CORTESIA -> {
            CortesiaSubView(
                cartViewModel = cartViewModel,
                onBack = { currentScreen = ShortcutsScreen.MAIN },
            )
        }
        ShortcutsScreen.PAY_LATER -> {
            currentScreen = ShortcutsScreen.MAIN
        }
        ShortcutsScreen.SAVED_CARTS -> {
            SavedCartsSubView(
                cartViewModel = cartViewModel,
                onBack = { currentScreen = ShortcutsScreen.MAIN },
            )
        }
        ShortcutsScreen.CREATE_ITEM -> {
            CreateItemSubView(
                onBack = { currentScreen = ShortcutsScreen.MAIN },
                onCreateNew = onCreateItem,
            )
        }
    }

    if (showPayLaterModal) {
        PayLaterFullScreenModal(
            cartState = cartState,
            selectedCustomerName = selectedPayLaterCustomerName,
            isConfirmingPayLater = isConfirmingPayLater,
            onDismiss = {
                showPayLaterModal = false
            },
            onCustomerSearch = {
                showPayLaterModal = false
                onCustomerSearch()
            },
            onConfirmPayLater = {
                onConfirmPayLater()
                showPayLaterModal = false
            },
        )
    }
}

@Composable
private fun ShortcutsMainGrid(
    cartState: CartState,
    allProducts: List<Product>,
    onNavigate: (ShortcutsScreen) -> Unit,
    onProductTap: (Product) -> Unit = {},
    canCreateProducts: Boolean = true,
) {
    val hasItems = !cartState.isEmpty
    val context = LocalContext.current

    // Load mosaic products from saved preferences
    val savedIds = remember { MosaicPreferences.getSavedProductIds(context) }
    val mosaicProducts = remember(savedIds, allProducts) {
        savedIds.mapNotNull { id -> allProducts.find { it.id == id } }
    }

    val shortcuts = listOfNotNull(
        ShortcutItem(
            id = "giftcard",
            name = "Gift Card",
            icon = Icons.Filled.CardGiftcard,
            color = ActionColors.giftCard,
            screen = ShortcutsScreen.MAIN,
            enabled = false,
            badge = "Pronto",
        ),
        if (canCreateProducts) ShortcutItem(
            id = "createitem",
            name = "Crear articulo",
            icon = Icons.Filled.LocalOffer,
            color = ActionColors.createItem,
            screen = ShortcutsScreen.CREATE_ITEM,
        ) else null,
        ShortcutItem(
            id = "discounts",
            name = "Descuentos",
            icon = Icons.Filled.Percent,
            color = ActionColors.discounts,
            screen = ShortcutsScreen.DISCOUNTS,
        ),
        ShortcutItem(
            id = "coupons",
            name = "Cupones",
            icon = Icons.Outlined.ConfirmationNumber,
            color = ActionColors.coupons,
            screen = ShortcutsScreen.COUPONS,
        ),
        ShortcutItem(
            id = "voiditems",
            name = "Anular articulos",
            icon = Icons.Filled.Delete,
            color = ActionColors.voidItems,
            screen = ShortcutsScreen.VOID_ITEMS,
            enabled = hasItems,
        ),
        ShortcutItem(
            id = "cortesia",
            name = "Cortesia",
            icon = Icons.Filled.Favorite,
            color = ActionColors.cortesia,
            screen = ShortcutsScreen.CORTESIA,
            enabled = hasItems,
        ),
        ShortcutItem(
            id = "paylater",
            name = "Pagar despues",
            icon = Icons.Filled.Schedule,
            color = ActionColors.payLater,
            screen = ShortcutsScreen.PAY_LATER,
            enabled = hasItems,
        ),
        ShortcutItem(
            id = "savedcarts",
            name = "Carritos guardados",
            icon = Icons.Filled.ShoppingCart,
            color = ActionColors.savedCarts,
            screen = ShortcutsScreen.SAVED_CARTS,
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        ) {
            Text(
                text = "Shortcuts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        // Combined grid: mosaic products + action shortcuts
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Mosaic products section
            if (mosaicProducts.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text(
                        text = "Favoritos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.xs),
                    )
                }

                items(mosaicProducts, key = { "mosaic_${it.id}" }) { product ->
                    MosaicProductTile(
                        product = product,
                        onClick = { onProductTap(product) },
                    )
                }

                item(span = { GridItemSpan(2) }) {
                    Text(
                        text = "Acciones",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            top = AvoqadoTheme.spacing.md,
                            bottom = AvoqadoTheme.spacing.xs,
                        ),
                    )
                }
            }

            // Action shortcuts
            items(shortcuts, key = { it.id }) { shortcut ->
                ShortcutTile(
                    shortcut = shortcut,
                    onClick = {
                        if (shortcut.enabled) {
                            onNavigate(shortcut.screen)
                        }
                    },
                )
            }
        }
    }
}

// MARK: - Shortcut Tile (matching iOS: colored bg, icon top-left, name bottom-left, badge top-right)

@Composable
private fun ShortcutTile(
    shortcut: ShortcutItem,
    onClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tileWidth = maxWidth
        // Responsive height: ~40% of tile width, clamped so very narrow/very wide
        // split-view columns still look proportional (small iPad ≈88dp, large ≈124dp).
        val tileHeight = (tileWidth.value * 0.4f).dp.coerceIn(80.dp, 130.dp)
        val isCompact = tileHeight < 100.dp
        val innerPad = if (isCompact) 10.dp else 14.dp
        val iconSize = if (isCompact) 18.dp else 22.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileHeight)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(shortcut.color)
                .then(
                    if (!shortcut.enabled) {
                        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    } else {
                        Modifier
                    },
                )
                .clickable(enabled = shortcut.enabled, onClick = onClick),
        ) {
            // Icon at top-left
            Icon(
                imageVector = shortcut.icon,
                contentDescription = null,
                tint = if (shortcut.enabled) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(iconSize)
                    .align(Alignment.TopStart)
                    .padding(start = innerPad, top = innerPad),
            )

            // Name at bottom-left
            Text(
                text = shortcut.name,
                style = if (isCompact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (shortcut.enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = innerPad, bottom = innerPad, end = innerPad),
            )

            // Badge at top-right
            shortcut.badge?.let { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(innerPad - 4.dp)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

// MARK: - Mosaic Product Tile (quick-add product button)

@Composable
private fun MosaicProductTile(
    product: Product,
    onClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tileWidth = maxWidth
        val tileHeight = (tileWidth.value * 0.4f).dp.coerceIn(80.dp, 130.dp)
        val isCompact = tileHeight < 100.dp
        val innerPad = if (isCompact) 10.dp else 14.dp
        val chipSize = if (isCompact) 22.dp else 28.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileHeight)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
        ) {
            // Initials at top-left
            Box(
                modifier = Modifier
                    .padding(start = innerPad, top = innerPad)
                    .size(chipSize)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .align(Alignment.TopStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = product.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Price at top-right
            Text(
                text = product.displayPrice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = innerPad, end = innerPad),
            )

            // Name at bottom-left
            Text(
                text = product.name,
                style = if (isCompact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = innerPad, bottom = innerPad, end = innerPad),
            )
        }
    }
}

// MARK: - Breadcrumb Header (matching iOS: back button + Shortcuts > Title)

@Composable
private fun BreadcrumbHeader(
    title: String,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            // Back button
            CircleBackButton(onClick = onBack)

            // Breadcrumb: Shortcuts > Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Shortcuts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()
    }
}

// MARK: - Discounts Sub View

@Composable
private fun DiscountsSubView(
    cartViewModel: CartViewModel,
    discountsRepository: DiscountsRepository,
    onBack: () -> Unit,
) {
    val discounts by discountsRepository.discounts.collectAsState()
    val cartState by cartViewModel.cartState.collectAsState()
    val orderDiscounts = discounts.filter { it.discountScope == com.avoqado.pos.pos.data.model.DiscountScope.ORDER }
    var showManualDiscount by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Descuentos", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            // Applied discount info
            cartState.orderDiscount?.let { discount ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Success.copy(alpha = 0.1f), RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .padding(AvoqadoTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "${discount.name} aplicado",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "-${discount.displayValue}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Success,
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { cartViewModel.applyOrderDiscount(null) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Quitar descuento",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Manual discount button
            OutlinedButton(
                onClick = { showManualDiscount = !showManualDiscount },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Text("Descuento manual")
            }

            // Manual discount entry
            if (showManualDiscount) {
                ManualDiscountEntry(
                    onApply = { name, value, isPercentage ->
                        val discount = Discount(
                            id = "manual_${System.currentTimeMillis()}",
                            name = name.ifBlank { "Descuento manual" },
                            value = value,
                            type = if (isPercentage) "PERCENTAGE" else "FIXED",
                            scope = "ORDER",
                        )
                        cartViewModel.applyOrderDiscount(discount)
                        showManualDiscount = false
                    },
                )
            }

            // Predefined discounts
            if (orderDiscounts.isNotEmpty()) {
                Text(
                    text = "Descuentos predefinidos",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
                )

                orderDiscounts.forEach { discount ->
                    DiscountCard(
                        discount = discount,
                        isApplied = cartState.orderDiscount?.id == discount.id,
                        onClick = {
                            if (cartState.orderDiscount?.id == discount.id) {
                                cartViewModel.applyOrderDiscount(null)
                            } else {
                                cartViewModel.applyOrderDiscount(discount)
                            }
                        },
                    )
                }
            }

            if (orderDiscounts.isEmpty() && !showManualDiscount) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No hay descuentos predefinidos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualDiscountEntry(
    onApply: (name: String, value: Double, isPercentage: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var isPercentage by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre (opcional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            // Percentage toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(
                        if (isPercentage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { isPercentage = true }
                    .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            ) {
                Text(
                    text = "%",
                    color = if (isPercentage) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Fixed toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(
                        if (!isPercentage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { isPercentage = false }
                    .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            ) {
                Text(
                    text = "$",
                    color = if (!isPercentage) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(if (isPercentage) "Porcentaje" else "Monto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        PrimaryButton(
            text = "Aplicar descuento",
            onClick = {
                val numValue = value.toDoubleOrNull() ?: return@PrimaryButton
                if (numValue > 0) {
                    onApply(name, numValue, isPercentage)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiscountCard(
    discount: Discount,
    isApplied: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(
                if (isApplied) Success.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (isApplied) 1.dp else 0.dp,
                color = if (isApplied) Success else Color.Transparent,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = discount.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when (discount.discountScope) {
                    com.avoqado.pos.pos.data.model.DiscountScope.ORDER -> "Aplica a toda la orden"
                    com.avoqado.pos.pos.data.model.DiscountScope.ITEM -> "Aplica a articulos especificos"
                    com.avoqado.pos.pos.data.model.DiscountScope.CATEGORY -> "Aplica a categorias"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = discount.displayValue,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isApplied) Success else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Coupons Sub View (with API validation, matching iOS)

@Composable
private fun CouponsSubView(
    cartViewModel: CartViewModel,
    discountsRepository: DiscountsRepository,
    onBack: () -> Unit,
) {
    var couponCode by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var validatedCoupon by remember { mutableStateOf<com.avoqado.pos.pos.data.model.CouponCode?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Cupones", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Ingresa el codigo del cupon",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = couponCode,
                onValueChange = {
                    couponCode = it.uppercase()
                    validatedCoupon = null
                    errorMessage = null
                },
                label = { Text("Codigo de cupon") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            PrimaryButton(
                text = if (isValidating) "Validando..." else "Validar cupon",
                onClick = {
                    coroutineScope.launch {
                        isValidating = true
                        errorMessage = null
                        validatedCoupon = null
                        when (val result = discountsRepository.validateCoupon(couponCode)) {
                            is DiscountsRepository.CouponResult.Valid -> {
                                validatedCoupon = result.coupon
                            }
                            is DiscountsRepository.CouponResult.Invalid -> {
                                errorMessage = result.reason
                            }
                            is DiscountsRepository.CouponResult.Error -> {
                                errorMessage = result.message
                            }
                        }
                        isValidating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = couponCode.isNotBlank() && !isValidating,
            )

            // Error message
            errorMessage?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ActionRed.copy(alpha = 0.1f), RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .padding(AvoqadoTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = ActionRed,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ActionRed,
                    )
                }
            }

            // Validated coupon card
            validatedCoupon?.let { coupon ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Success.copy(alpha = 0.1f), RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .border(1.dp, Success, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .padding(AvoqadoTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = coupon.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = coupon.displayValue,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Success,
                        )
                    }

                    coupon.estimatedSavings?.let { savings ->
                        Text(
                            text = "Ahorro estimado: $${String.format("%.2f", savings)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Success,
                        )
                    }

                    PrimaryButton(
                        text = "Aplicar cupon",
                        onClick = {
                            cartViewModel.applyOrderDiscount(coupon.toDiscount())
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// MARK: - Void Items Sub View

@Composable
private fun VoidItemsSubView(
    cartViewModel: CartViewModel,
    onBack: () -> Unit,
) {
    val cartState by cartViewModel.cartState.collectAsState()
    var selectedItemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var voidReason by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Anular articulos", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Text(
                text = "Selecciona los articulos a anular",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            cartState.items.forEach { item ->
                val isSelected = selectedItemIds.contains(item.id)
                VoidItemRow(
                    item = item,
                    isSelected = isSelected,
                    onClick = {
                        selectedItemIds = if (isSelected) {
                            selectedItemIds - item.id
                        } else {
                            selectedItemIds + item.id
                        }
                    },
                )
            }

            if (selectedItemIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

                OutlinedTextField(
                    value = voidReason,
                    onValueChange = { voidReason = it },
                    label = { Text("Razon de anulacion (requerido)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        if (selectedItemIds.isNotEmpty()) {
            HorizontalDivider()
            Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                PrimaryButton(
                    text = "Anular ${selectedItemIds.size} articulo${if (selectedItemIds.size == 1) "" else "s"}",
                    onClick = {
                        if (voidReason.isNotBlank()) {
                            selectedItemIds.forEach { id ->
                                cartViewModel.removeItem(id)
                            }
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = voidReason.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun VoidItemRow(
    item: CartItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(
                if (isSelected) ActionRed.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) ActionRed else Color.Transparent,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
            )
            if (item.quantity > 1) {
                Text(
                    text = "x${item.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "$${String.format("%.2f", item.totalPrice / 100.0)}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) ActionRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Cortesia Sub View (whole-order 100% discount, matching iOS)

@Composable
private fun CortesiaSubView(
    cartViewModel: CartViewModel,
    onBack: () -> Unit,
) {
    val cartState by cartViewModel.cartState.collectAsState()
    var cortesiaReason by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Cortesia", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

            // Big heart icon
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(60.dp),
                tint = ActionGreen,
            )

            Text(
                text = "Aplicar cortesia",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Se aplicara un descuento del 100% al pedido actual",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Current subtotal card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                    )
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Subtotal actual",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = cartState.subtotalDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Reason field (optional)
            OutlinedTextField(
                value = cortesiaReason,
                onValueChange = { cortesiaReason = it },
                label = { Text("Razon de cortesia (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // Apply button (green)
            PrimaryButton(
                text = "Aplicar cortesia",
                onClick = { showConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Confirmation dialog
    if (showConfirmation) {
        AvoqadoDialog(
            title = "Confirmar cortesía",
            description = "Se aplicará un descuento del 100% (${cartState.subtotalDisplay}) al pedido.",
            onDismiss = { showConfirmation = false },
            actionButton = {
                PrimaryButton(
                    text = "Confirmar",
                    onClick = {
                        showConfirmation = false
                        val discount = Discount(
                            id = "cortesia_${System.currentTimeMillis()}",
                            name = "Cortesia",
                            value = 100.0,
                            type = "PERCENTAGE",
                            scope = "ORDER",
                        )
                        cartViewModel.applyOrderDiscount(discount)
                        onBack()
                    },
                    fullWidth = true,
                )
            },
        ) {
            Text(
                text = "Si no era tu intención, cierra con la X.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Pay Later Fullscreen Modal

@Composable
private fun PayLaterFullScreenModal(
    cartState: CartState,
    onDismiss: () -> Unit,
    onCustomerSearch: () -> Unit,
    selectedCustomerName: String?,
    onConfirmPayLater: () -> Unit,
    isConfirmingPayLater: Boolean,
) {
    AvoqadoFullScreenModal(
        title = "Pagar despues",
        onDismiss = onDismiss,
        primaryActionText = if (selectedCustomerName.isNullOrBlank()) null else {
            if (isConfirmingPayLater) "Continuando..." else "Continuar"
        },
        onPrimaryAction = if (selectedCustomerName.isNullOrBlank()) null else onConfirmPayLater,
        primaryActionEnabled = !isConfirmingPayLater,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.size(60.dp),
                tint = ActionTeal,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Pagar despues",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "Vincula este pedido a un cliente para que pueda pagarlo mas tarde",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xl),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Deferred total card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                    )
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Total a diferir",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = cartState.totalDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            if (selectedCustomerName.isNullOrBlank()) {
                PrimaryButton(
                    text = "Seleccionar cliente",
                    onClick = onCustomerSearch,
                )
            } else {
                Text(
                    text = "Cliente seleccionado",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                Text(
                    text = selectedCustomerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                OutlinedButton(
                    onClick = onCustomerSearch,
                    enabled = !isConfirmingPayLater,
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Cambiar cliente")
                }
            }
        }
    }
}

// MARK: - Create Item Sub View (matching iOS: choose option sheet)

@Composable
private fun CreateItemSubView(
    onBack: () -> Unit,
    onCreateNew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Crear articulo", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = "Elegir opcion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
            )

            // Option 1: Create new item
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateNew)
                    .padding(vertical = AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Crear articulo nuevo",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // Option 2: Add variant (Coming soon)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Agregar variante a un articulo existente",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Proximamente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

// MARK: - Saved Carts Sub View

@Composable
private fun SavedCartsSubView(
    cartViewModel: CartViewModel,
    onBack: () -> Unit,
) {
    val savedCarts by cartViewModel.savedCarts.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Carritos guardados", onBack = onBack)

        if (savedCarts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.ShoppingCart,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                Text(
                    text = "No hay carritos guardados",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AvoqadoTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                savedCarts.forEach { cart ->
                    SavedCartCard(
                        cart = cart,
                        onRestore = {
                            cartViewModel.restoreSavedCart(cart)
                            onBack()
                        },
                        onDelete = {
                            cartViewModel.deleteSavedCart(cart.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedCartCard(
    cart: SavedCart,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cart.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${cart.items.size} articulo${if (cart.items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        cart.items.take(3).forEach { item ->
            Text(
                text = "${item.name} x${item.quantity}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cart.items.size > 3) {
            Text(
                text = "+${cart.items.size - 3} mas...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            PrimaryButton(
                text = "Restaurar",
                onClick = onRestore,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(AvoqadoTheme.dimensions.buttonLarge),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "Eliminar",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
