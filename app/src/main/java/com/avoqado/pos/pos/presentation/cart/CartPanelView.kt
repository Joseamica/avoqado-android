package com.avoqado.pos.pos.presentation.cart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.DiscountText
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.referrals.presentation.ReferralCaptureSection
import com.avoqado.pos.referrals.presentation.ReferralCaptureUiState

@Composable
fun CartPanelView(
    cartState: CartState,
    onItemTap: (CartItem) -> Unit = {},
    onCharge: () -> Unit,
    onClearCart: () -> Unit,
    onSaveCart: () -> Unit = {},
    onAddCustomAmount: () -> Unit = {},
    onRemoveItem: (String) -> Unit = {},
    onApplyTaxPercent: (Int?) -> Unit = {},
    customerName: String? = null,
    customerId: String? = null,
    onCustomerTap: () -> Unit = {},
    staffName: String = cartState.selectedStaffName,
    onStaffTap: () -> Unit = {},
    onSplitPayment: () -> Unit = {},
    /** Cumplimiento de la venta (header de la sección, antes fijo "En tienda"). */
    onOrderTypeChange: (String) -> Unit = {},
    // Referral capture (Plan 5B) — optional, the cart still works without it.
    referralCode: String = "",
    referralUiState: ReferralCaptureUiState = ReferralCaptureUiState.Idle,
    customerSelectedForReferral: Boolean = customerName != null,
    onReferralCodeChange: (String) -> Unit = {},
    onValidateReferral: () -> Unit = {},
    onClearReferral: () -> Unit = {},
    onForceOverrideReferral: () -> Unit = {},
    // Plan gate (REFERRAL_PROGRAM, Pro). Default true = fail-open.
    referralPlanAllowed: Boolean = true,
) {
    val useDenseTabletLayout = AvoqadoTheme.adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact
    val sectionOuterPadding = if (useDenseTabletLayout) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val sectionInnerPadding = if (useDenseTabletLayout) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    var showCartOptions by remember { mutableStateOf(false) }
    var showSectionMenu by remember { mutableStateOf(false) }
    var showTaxDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Customer Header (matching iOS: "Agregar cliente" + ... menu)
            CustomerHeader(
                itemCount = cartState.itemCount,
                hasItems = !cartState.isEmpty,
                customerName = customerName,
                onCustomerTap = onCustomerTap,
                staffName = staffName,
                onStaffTap = onStaffTap,
                useDenseTabletLayout = useDenseTabletLayout,
                onMenuTap = { showCartOptions = true },
            )

            HorizontalDivider()

            // Membresías: redeem a prepaid credit at checkout (only shows if any)
            if (customerId != null) {
                CartCreditsCard(customerId = customerId)
            }

            // Cart Content
            if (cartState.isEmpty) {
                // Empty state with cart icon (matching iOS)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.ShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(
                        text = "El carrito esta vacio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Scrollable cart items
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // "En tienda" section with border (matching iOS)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(sectionOuterPadding)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                            )
                            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg)),
                    ) {
                        // Section header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = sectionInnerPadding,
                                    vertical = sectionInnerPadding,
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Cumplimiento (Square): el título de la sección ES el
                            // selector — tap para cambiar En tienda/Llevar/Entrega/Pickup.
                            var showFulfillmentMenu by remember { mutableStateOf(false) }
                            val fulfillmentLabel = when (cartState.orderType) {
                                "TAKEOUT" -> "Para llevar"
                                "DELIVERY" -> "Entrega"
                                "PICKUP" -> "Pickup"
                                else -> "En tienda"
                            }
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { showFulfillmentMenu = true },
                                ) {
                                    Text(
                                        text = fulfillmentLabel,
                                        style = if (useDenseTabletLayout) {
                                            MaterialTheme.typography.bodyMedium
                                        } else {
                                            MaterialTheme.typography.titleSmall
                                        },
                                        fontWeight = if (useDenseTabletLayout) FontWeight.Medium else FontWeight.SemiBold,
                                    )
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "Cambiar forma de entrega",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showFulfillmentMenu,
                                    onDismissRequest = { showFulfillmentMenu = false },
                                ) {
                                    listOf(
                                        "DINE_IN" to "En tienda",
                                        "TAKEOUT" to "Para llevar",
                                        "DELIVERY" to "Entrega",
                                        "PICKUP" to "Pickup",
                                    ).forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                showFulfillmentMenu = false
                                                onOrderTypeChange(code)
                                            },
                                        )
                                    }
                                }
                            }
                            Box {
                                Icon(
                                    Icons.Filled.MoreHoriz,
                                    contentDescription = "Opciones de seccion",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { showSectionMenu = true },
                                )
                                DropdownMenu(
                                    expanded = showSectionMenu,
                                    onDismissRequest = { showSectionMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Agregar impuesto") },
                                        onClick = {
                                            showSectionMenu = false
                                            showTaxDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Dividir cuenta") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Filled.CallSplit,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = {
                                            showSectionMenu = false
                                            onSplitPayment()
                                        },
                                    )
                                }
                            }
                        }

                        // Cart items
                        cartState.items.forEach { item ->
                            key(item.id) {
                                CartItemRow(
                                    item = item,
                                    onClick = { onItemTap(item) },
                                    onDelete = { onRemoveItem(item.id) },
                                    useDenseTabletLayout = useDenseTabletLayout,
                                )
                            }
                        }

                        // "Agregar impuesto" link
                        Text(
                            text = if (cartState.orderTaxPercent != null) {
                                "Impuesto (${cartState.orderTaxPercent}%)"
                            } else {
                                "Agregar impuesto"
                            },
                            style = if (useDenseTabletLayout) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clickable { showTaxDialog = true }
                                .padding(
                                    horizontal = sectionInnerPadding,
                                    vertical = sectionInnerPadding,
                                ),
                        )
                    }

                    // Discount rows (matching iOS: per-discount display)
                    if (cartState.discountCents > 0 && cartState.orderDiscount != null) {
                        DiscountItemRow(
                            name = cartState.orderDiscount.name,
                            displayValue = cartState.orderDiscount.displayValue,
                            amount = "-${cartState.discountDisplay}",
                            useDenseTabletLayout = useDenseTabletLayout,
                        )
                    }

                    if (cartState.orderTaxPercent != null) {
                        SummaryAmountRow(
                            name = "IVA (${cartState.orderTaxPercent}%)",
                            amount = cartState.taxDisplay,
                            useDenseTabletLayout = useDenseTabletLayout,
                        )
                    }

                    // Referral capture (Plan 5B) — placed after the discount/tax
                    // rows so it sits visually between the line items and the
                    // total / Cobrar action.
                    ReferralCaptureSection(
                        code = referralCode,
                        uiState = referralUiState,
                        customerSelected = customerSelectedForReferral,
                        onCodeChange = onReferralCodeChange,
                        onValidate = onValidateReferral,
                        onClear = onClearReferral,
                        onForceOverride = onForceOverrideReferral,
                        planAllowsReferrals = referralPlanAllowed,
                        modifier = Modifier.padding(
                            horizontal = AvoqadoTheme.spacing.xl,
                            vertical = AvoqadoTheme.spacing.md,
                        ),
                    )
                }

                // Bottom action buttons
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.xl)
                        .padding(vertical = AvoqadoTheme.spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    // "Guardar carrito" button (outlined)
                    OutlinedButton(
                        onClick = onSaveCart,
                        modifier = Modifier
                            .weight(1f)
                            .height(AvoqadoTheme.dimensions.buttonLarge),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "Guardar carrito",
                            style = if (useDenseTabletLayout) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                        )
                    }

                    // "Cobrar" button (filled)
                    PrimaryButton(
                        text = "Cobrar ${cartState.totalDisplay}",
                        onClick = onCharge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Tax dialog
        if (showTaxDialog) {
            var taxPercent by remember {
                mutableStateOf((cartState.orderTaxPercent ?: 16).toString())
            }
            var taxError by remember { mutableStateOf<String?>(null) }
            AvoqadoDialog(
                title = "Agregar impuesto",
                description = "Selecciona o ingresa el porcentaje de impuesto",
                onDismiss = { showTaxDialog = false },
                actionButton = {
                    PrimaryButton(
                        text = "Aplicar",
                        onClick = {
                            val parsed = taxPercent.toIntOrNull()
                            if (parsed == null || parsed !in 0..100) {
                                taxError = "Ingresa un porcentaje válido (0-100)"
                                return@PrimaryButton
                            }
                            onApplyTaxPercent(parsed.takeIf { it > 0 })
                            showTaxDialog = false
                        },
                        fullWidth = true,
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                        listOf("8", "16").forEach { pct ->
                            val isSelected = taxPercent == pct
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable {
                                        taxPercent = pct
                                        taxError = null
                                    }
                                    .padding(
                                        horizontal = AvoqadoTheme.spacing.lg,
                                        vertical = AvoqadoTheme.spacing.md,
                                    ),
                            ) {
                                Text(
                                    text = "$pct%",
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    AvoqadoPillTextField(
                        value = taxPercent,
                        onValueChange = { input ->
                            taxPercent = input.filter { it.isDigit() }.take(3)
                            taxError = null
                        },
                        placeholder = "Porcentaje (%)",
                    )

                    taxError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Cart options overlay
        if (showCartOptions) {
            CartOptionsSheet(
                hasItems = !cartState.isEmpty,
                onDismiss = { showCartOptions = false },
                onClearCart = {
                    onClearCart()
                    showCartOptions = false
                },
                onAddCustomAmount = {
                    onAddCustomAmount()
                    showCartOptions = false
                },
            )
        }
    }
}

// MARK: - Customer Header

@Composable
private fun CustomerHeader(
    itemCount: Int,
    hasItems: Boolean,
    customerName: String? = null,
    onCustomerTap: () -> Unit,
    staffName: String,
    onStaffTap: () -> Unit,
    useDenseTabletLayout: Boolean = false,
    onMenuTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = if (useDenseTabletLayout) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // "Agregar cliente" underlined link or customer name (matching iOS)
            Text(
                text = customerName ?: "Agregar cliente",
                style = if (useDenseTabletLayout) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (customerName == null) TextDecoration.Underline else null,
                modifier = Modifier.clickable(onClick = onCustomerTap),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                if (hasItems) {
                    Text(
                        text = "$itemCount articulo${if (itemCount == 1) "" else "s"}",
                        style = if (useDenseTabletLayout) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onStaffTap)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Vendiendo: $staffName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Three dots menu button (matching iOS: circle with gray bg)
        Box(
            modifier = Modifier
                .size(if (useDenseTabletLayout) 40.dp else 44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onMenuTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "Mas opciones",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Cart Item Row (matching iOS: 60dp thumbnail, image, indicators, strikethrough)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartItemRow(
    item: CartItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    useDenseTabletLayout: Boolean = false,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val progress = dismissState.progress.coerceIn(0f, 1f)
            val bgAlpha = (progress * 1.4f).coerceIn(0.25f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE53935).copy(alpha = bgAlpha))
                    .padding(horizontal = AvoqadoTheme.spacing.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Eliminar",
                    tint = Color.White,
                )
            }
        },
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = if (useDenseTabletLayout) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Product thumbnail (60dp matching iOS, with image or color bg + initials)
        val bgColor = item.colorHex?.let { hex ->
            try {
                Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
            } catch (_: Exception) {
                MaterialTheme.colorScheme.surfaceVariant
            }
        } ?: MaterialTheme.colorScheme.surfaceVariant

        val initials = item.name.split(" ")
            .take(2)
            .joinToString("") { it.take(1) }
            .uppercase()

        Box(
            modifier = Modifier
                .size(if (useDenseTabletLayout) 52.dp else 60.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md)),
        ) {
            if (!item.imageUrl.isNullOrEmpty()) {
                // Load product image with Coil
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // Color background with initials
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (item.colorHex != null) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Cortesia overlay on thumbnail (matching iOS: green semi-transparent)
            if (item.isCortesia) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF34C759).copy(alpha = 0.3f)),
                )
            }
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Item info
        Column(modifier = Modifier.weight(1f)) {
            // Name + badges row (matching iOS: name, cortesia badge, note icon, price adj icon)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
            ) {
                Text(
                    text = item.name,
                    style = if (useDenseTabletLayout) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isCortesia) {
                    Text(
                        text = "Cortesia",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(
                                Color(0xFF34C759),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                // Note indicator icon (matching iOS)
                if (!item.itemNote.isNullOrEmpty()) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Tiene nota",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Price adjustment indicator icon (matching iOS)
                if (item.priceAdjustment != null) {
                    Icon(
                        Icons.Filled.AttachMoney,
                        contentDescription = "Precio ajustado",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFFF9500),
                    )
                }
            }

            // Venta por peso: "0.435 kg × $420.00/kg" bajo el nombre.
            item.weightSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Modifiers summary
            item.modifiersSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Item note preview (matching iOS: warning color)
            item.itemNote?.let { note ->
                if (note.isNotEmpty()) {
                    Text(
                        text = "Nota: $note",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9500),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Quantity (matching iOS: "x N" only if > 1)
        if (item.quantity > 1) {
            Text(
                text = "\u00D7 ${item.quantity}",
                style = if (useDenseTabletLayout) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.sm),
            )
        }

        // Price column (matching iOS: strikethrough if cortesia, modifiers price)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$${String.format("%.2f", item.totalPrice / 100.0)}",
                style = if (useDenseTabletLayout) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (item.isCortesia) Color(0xFF34C759)
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.isCortesia) TextDecoration.LineThrough else null,
            )
            // Modifiers price breakdown (matching iOS: "+$X.XX")
            if (item.modifiersPrice > 0) {
                Text(
                    text = "+$${String.format("%.2f", item.modifiersPrice / 100.0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

// MARK: - Discount Item Row (matching iOS: icon box + info + amount)

@Composable
private fun DiscountItemRow(
    name: String,
    displayValue: String,
    amount: String,
    useDenseTabletLayout: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = if (useDenseTabletLayout) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Icon box (matching iOS: gray rounded rect with tag icon)
        Box(
            modifier = Modifier
                .size(if (useDenseTabletLayout) 44.dp else 48.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocalOffer,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (useDenseTabletLayout) 18.dp else 20.dp),
            )
        }

        // Discount info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$name ($displayValue)",
                style = if (useDenseTabletLayout) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Discount amount
        Text(
            text = amount,
            style = if (useDenseTabletLayout) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = DiscountText,
        )
    }
}

@Composable
private fun SummaryAmountRow(
    name: String,
    amount: String,
    useDenseTabletLayout: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = if (useDenseTabletLayout) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = if (useDenseTabletLayout) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amount,
            style = if (useDenseTabletLayout) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Cart Options Sheet (matching iOS: bottom sheet sliding up)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartOptionsSheet(
    hasItems: Boolean,
    onDismiss: () -> Unit,
    onClearCart: () -> Unit,
    onAddCustomAmount: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CartOptionButton(
                icon = Icons.Filled.Delete,
                title = "Vaciar carrito",
                enabled = hasItems,
                onClick = onClearCart,
            )

            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            CartOptionButton(
                icon = Icons.Filled.CardGiftcard,
                title = "Vender o verificar una tarjeta de regalo",
                enabled = false,
                onClick = { },
            )

            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            CartOptionButton(
                icon = Icons.Filled.Campaign,
                title = "Canjear recompensa",
                onClick = { onDismiss() },
            )

            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            CartOptionButton(
                icon = Icons.Filled.AttachMoney,
                title = "Agregar importe personalizado",
                onClick = onAddCustomAmount,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Cart Option Button (matching iOS: icon + title row)

@Composable
private fun CartOptionButton(
    icon: ImageVector,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
