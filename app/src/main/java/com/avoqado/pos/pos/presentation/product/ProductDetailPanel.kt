package com.avoqado.pos.pos.presentation.product

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SelectedModifier

/**
 * Product detail panel matching iOS.
 * - Tablet: side panel overlay sliding from right (400dp width)
 * - Phone: ModalBottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailPanel(
    product: Product,
    isTablet: Boolean = true,
    discounts: List<Discount> = emptyList(),
    onAddToCart: (
        quantity: Int,
        modifiers: List<SelectedModifier>,
        note: String?,
        isCortesia: Boolean,
        cortesiaReason: String?,
        priceAdjustment: Int?,
        discountId: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        // iPad-style: side panel overlay from right
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
                    // 🔴 Sin esto el contenido se mete DEBAJO de la barra de
                    // estado: en la D3 el precio del producto chocaba con el
                    // reloj y el primer modificador salía cortado.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .shadow(8.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {}), // prevent click-through
            ) {
                ProductDetailContent(
                    product = product,
                    discounts = discounts,
                    onAddToCart = onAddToCart,
                    onDismiss = onDismiss,
                )
            }
        }
    } else {
        // iPhone-style: bottom sheet
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            ProductDetailContent(
                product = product,
                discounts = discounts,
                onAddToCart = onAddToCart,
                onDismiss = onDismiss,
            )
        }
    }
}

// MARK: - Sub-view navigation state

private enum class DetailSubView {
    MAIN, DISCOUNTS, PRICE_ADJUST, CORTESIA, NOTE,
}

// MARK: - Main content (shared between side panel and bottom sheet)

@Composable
private fun ProductDetailContent(
    product: Product,
    discounts: List<Discount>,
    onAddToCart: (Int, List<SelectedModifier>, String?, Boolean, String?, Int?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val addToCartTapThrottleMs = 800L
    val selectedModifiers = remember { mutableStateListOf<SelectedModifier>() }
    var quantity by remember { mutableIntStateOf(1) }
    var lastAddToCartTapAt by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var priceAdjustment by remember { mutableStateOf<Int?>(null) }
    var isCortesia by remember { mutableStateOf(false) }
    var cortesiaReason by remember { mutableStateOf<String?>(null) }
    var selectedDiscountId by remember { mutableStateOf<String?>(null) }
    var currentSubView by remember { mutableStateOf(DetailSubView.MAIN) }

    // Calculate display price
    val modifiersCents = selectedModifiers.sumOf { it.priceInCents }
    val baseCents = priceAdjustment ?: product.priceInCents
    val unitTotal = baseCents + modifiersCents
    val displayPrice = if (isCortesia) {
        "$0.00"
    } else {
        "$${String.format("%.2f", unitTotal * quantity / 100.0)}"
    }

    // Validate: can add to cart? (required modifier groups must be satisfied)
    val missingRequiredGroups = product.sortedModifierGroups.filter { group ->
        group.required && selectedModifiers.count { it.groupId == group.id } < (group.minSelections ?: 1)
    }.map { it.name }
    val canAddToCart = missingRequiredGroups.isEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        // Animated content switching between main view and sub-views
        AnimatedContent(
            targetState = currentSubView,
            label = "subview",
        ) { subView ->
            when (subView) {
                DetailSubView.MAIN -> {
                    MainDetailView(
                        product = product,
                        selectedModifiers = selectedModifiers,
                        quantity = quantity,
                        note = note,
                        priceAdjustment = priceAdjustment,
                        isCortesia = isCortesia,
                        cortesiaReason = cortesiaReason,
                        selectedDiscountId = selectedDiscountId,
                        displayPrice = displayPrice,
                        canAddToCart = canAddToCart,
                        missingRequiredGroups = missingRequiredGroups,
                        onDismiss = onDismiss,
                        onQuantityChange = { quantity = it.coerceAtLeast(1) },
                        onModifierToggled = { modifier, isSelected, group ->
                            if (isSelected) {
                                if (!group.multipleSelect) {
                                    selectedModifiers.removeAll { it.groupId == group.id }
                                }
                                selectedModifiers.add(
                                    SelectedModifier(
                                        groupId = group.id,
                                        groupName = group.name,
                                        modifierId = modifier.id,
                                        modifierName = modifier.name,
                                        priceInCents = modifier.priceInCents,
                                    ),
                                )
                            } else {
                                selectedModifiers.removeAll {
                                    it.groupId == group.id && it.modifierId == modifier.id
                                }
                            }
                        },
                        onNavigate = { currentSubView = it },
                        onAddToCart = {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastAddToCartTapAt < addToCartTapThrottleMs) {
                                return@MainDetailView
                            }
                            lastAddToCartTapAt = now
                            onAddToCart(
                                quantity,
                                selectedModifiers.toList(),
                                note,
                                isCortesia,
                                cortesiaReason,
                                priceAdjustment,
                                selectedDiscountId,
                            )
                        },
                    )
                }
                DetailSubView.NOTE -> {
                    NoteSubView(
                        currentNote = note ?: "",
                        onSave = { newNote ->
                            note = newNote.ifBlank { null }
                            currentSubView = DetailSubView.MAIN
                        },
                        onClear = {
                            note = null
                            currentSubView = DetailSubView.MAIN
                        },
                        onBack = { currentSubView = DetailSubView.MAIN },
                    )
                }
                DetailSubView.CORTESIA -> {
                    CortesiaSubView(
                        currentReason = cortesiaReason,
                        onApply = { reason ->
                            isCortesia = true
                            cortesiaReason = reason
                            currentSubView = DetailSubView.MAIN
                        },
                        onClear = {
                            isCortesia = false
                            cortesiaReason = null
                            currentSubView = DetailSubView.MAIN
                        },
                        onBack = { currentSubView = DetailSubView.MAIN },
                    )
                }
                DetailSubView.PRICE_ADJUST -> {
                    PriceAdjustSubView(
                        originalPriceCents = product.priceInCents,
                        currentAdjustment = priceAdjustment,
                        onConfirm = { newPrice ->
                            priceAdjustment = newPrice
                            currentSubView = DetailSubView.MAIN
                        },
                        onClear = {
                            priceAdjustment = null
                            currentSubView = DetailSubView.MAIN
                        },
                        onBack = { currentSubView = DetailSubView.MAIN },
                    )
                }
                DetailSubView.DISCOUNTS -> {
                    DiscountsSubView(
                        discounts = discounts.filter {
                            it.appliesTo(
                                productId = (product.id),
                                categoryId = product.categoryId,
                            )
                        },
                        selectedDiscountId = selectedDiscountId,
                        onSelect = { discountId ->
                            selectedDiscountId = if (selectedDiscountId == discountId) null else discountId
                        },
                        onBack = { currentSubView = DetailSubView.MAIN },
                    )
                }
            }
        }
    }
}

// MARK: - Main Detail View

@Composable
private fun MainDetailView(
    product: Product,
    selectedModifiers: List<SelectedModifier>,
    quantity: Int,
    note: String?,
    priceAdjustment: Int?,
    isCortesia: Boolean,
    cortesiaReason: String?,
    selectedDiscountId: String?,
    displayPrice: String,
    canAddToCart: Boolean,
    /** Nombres de los grupos obligatorios que faltan — para decirlo, no callarlo. */
    missingRequiredGroups: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onQuantityChange: (Int) -> Unit,
    onModifierToggled: (com.avoqado.pos.pos.data.model.Modifier, Boolean, com.avoqado.pos.pos.data.model.ProductModifierGroup) -> Unit,
    onNavigate: (DetailSubView) -> Unit,
    onAddToCart: () -> Unit,
) {
    var showMissingRequired by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showMissingRequired && missingRequiredGroups.isNotEmpty()) {
            Text(
                text = "Falta elegir: ${missingRequiredGroups.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
            )
        }
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 100.dp), // Clear bottom bar
        ) {
            // Header: close button + price + name + category
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.xl),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Display price (large)
                    Text(
                        text = displayPrice,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                        ),
                        color = if (isCortesia) {
                            Color(0xFF34C759)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))

                    // Product name
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Category name
                    product.category?.name?.let { categoryName ->
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Close button (circle with gray bg)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider()

            // Modifier groups
            product.sortedModifierGroups.forEach { group ->
                ModifierGroupSection(
                    group = group,
                    selectedModifiers = selectedModifiers,
                    onModifierToggled = { modifier, isSelected ->
                        onModifierToggled(modifier, isSelected, group)
                    },
                )
            }

            // SKU (if available)
            if (!product.sku.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = "SKU",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = product.sku,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Inventory (if tracked)
            if (product.trackInventory == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = "Inventario",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val qty = product.availableQuantity
                    Text(
                        text = if (qty != null) "Disponible: $qty" else "Sin límite",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (qty != null && qty <= 0) {
                            Color(0xFFFF3B30)
                        } else {
                            Color(0xFF34C759)
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm))

            // Toggle option rows
            ToggleOptionRow(
                icon = Icons.Filled.LocalOffer,
                title = "Descuentos",
                subtitle = if (selectedDiscountId != null) "1 descuento aplicado" else null,
                onClick = { onNavigate(DetailSubView.DISCOUNTS) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            ToggleOptionRow(
                icon = Icons.Filled.AttachMoney,
                title = "Ajuste de precio",
                subtitle = priceAdjustment?.let {
                    "$${String.format("%.2f", it / 100.0)}"
                },
                onClick = { onNavigate(DetailSubView.PRICE_ADJUST) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            ToggleOptionRow(
                icon = Icons.Filled.CardGiftcard,
                title = "Cortesía",
                subtitle = if (isCortesia) (cortesiaReason ?: "Activado") else null,
                onClick = { onNavigate(DetailSubView.CORTESIA) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

            ToggleOptionRow(
                icon = Icons.AutoMirrored.Filled.NoteAdd,
                title = "Nota del artículo",
                subtitle = note,
                onClick = { onNavigate(DetailSubView.NOTE) },
            )
        }

        // Sticky bottom bar: quantity selector + add to cart
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    horizontal = AvoqadoTheme.spacing.xl,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            // Quantity selector
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    )
                    .padding(horizontal = AvoqadoTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onQuantityChange(quantity - 1) },
                    enabled = quantity > 1,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Menos",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "$quantity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(
                    onClick = { onQuantityChange(quantity + 1) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Más",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Add to cart button — bloqueado NO significa mudo: si faltan
            // modificadores obligatorios, el toque lo dice. El "Requerido 0/1"
            // vive arriba del panel, lejos del botón que el mesero acaba de
            // tocar, así que sin esto parecía que la app no respondía.
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(
                    text = "Agregar al carrito",
                    onClick = onAddToCart,
                    enabled = canAddToCart,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!canAddToCart) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showMissingRequired = true },
                    )
                }
            }
        }
    }
}

// MARK: - Toggle Option Row

@Composable
private fun ToggleOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.lg))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - Sub-view Header

@Composable
private fun SubViewHeader(
    title: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleBackButton(onClick = onBack)

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
    HorizontalDivider()
}

// MARK: - Note Sub-View

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NoteSubView(
    currentNote: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(currentNote) }
    val maxChars = 200
    val quickNotes = listOf(
        "Sin hielo", "Extra caliente", "Sin azúcar",
        "Para llevar", "Sin cebolla", "Extra picante",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SubViewHeader(
            title = "Nota del artículo",
            onBack = onBack,
            actionLabel = if (text.isNotEmpty()) "Eliminar" else null,
            onAction = onClear,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            Text(
                text = "Agrega una nota para este artículo. Se mostrará en el ticket y en la cocina.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= maxChars) text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("Escribe una nota...") },
            )

            Text(
                text = "${text.length}/$maxChars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = AvoqadoTheme.spacing.xxs),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Quick note chips
            Text(
                text = "Notas rápidas",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                quickNotes.forEach { chip ->
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                            )
                            .clickable {
                                text = if (text.isBlank()) chip
                                else "$text, $chip"
                            }
                            .padding(
                                horizontal = AvoqadoTheme.spacing.md,
                                vertical = AvoqadoTheme.spacing.sm,
                            ),
                    )
                }
            }
        }

        // Save button
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            PrimaryButton(
                text = "Guardar nota",
                onClick = { onSave(text.trim()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Cortesia Sub-View

private val cortesiaReasons = listOf(
    "Error de entrada",
    "El cliente cambió de parecer",
    "Reclamo del cliente",
    "Amigos y familia",
    "Descuento de empleado",
    "Especial del administrador",
)

@Composable
private fun CortesiaSubView(
    currentReason: String?,
    onApply: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedReason by remember { mutableStateOf(currentReason) }

    Column(modifier = Modifier.fillMaxSize()) {
        SubViewHeader(
            title = "Cortesía",
            onBack = onBack,
            actionLabel = if (currentReason != null) "Eliminar" else null,
            onAction = onClear,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            Text(
                text = "Selecciona un motivo para aplicar la cortesía. El artículo tendrá un precio de \$0.00.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            cortesiaReasons.forEach { reason ->
                val isSelected = selectedReason == reason
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedReason = reason }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Radio circle
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                CircleShape,
                            )
                            .then(
                                if (!isSelected) {
                                    Modifier.background(
                                        Color.Transparent,
                                        CircleShape,
                                    )
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 10.dp else 22.dp)
                                .background(
                                    if (isSelected) Color.White else Color.Transparent,
                                    CircleShape,
                                )
                                .then(
                                    if (!isSelected) {
                                        Modifier.background(
                                            Color.Transparent,
                                            CircleShape,
                                        )
                                    } else Modifier,
                                ),
                        )
                        if (!isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color.Transparent, CircleShape)
                                    .then(
                                        Modifier.background(
                                            Color.Transparent,
                                            CircleShape,
                                        ),
                                    ),
                            ) {
                                // outline circle
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape,
                                        )
                                        .padding(2.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Apply button
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            PrimaryButton(
                text = "Aplicar cortesía",
                onClick = { selectedReason?.let { onApply(it) } },
                enabled = selectedReason != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Price Adjustment Sub-View

@Composable
private fun PriceAdjustSubView(
    originalPriceCents: Int,
    currentAdjustment: Int?,
    onConfirm: (Int) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var priceCents by remember { mutableIntStateOf(currentAdjustment ?: 0) }

    Column(modifier = Modifier.fillMaxSize()) {
        SubViewHeader(
            title = "Ajuste de precio",
            onBack = onBack,
            actionLabel = if (currentAdjustment != null) "Eliminar" else null,
            onAction = onClear,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AvoqadoTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Original price (strikethrough)
            Text(
                text = "Precio original",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$${String.format("%.2f", originalPriceCents / 100.0)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // New price display
            Text(
                text = "$${String.format("%.2f", priceCents / 100.0)}",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Numeric keypad (3x4 grid)
            val buttons = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("00", "0", "⌫"),
            )

            buttons.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    row.forEach { label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    when (label) {
                                        "⌫" -> priceCents /= 10
                                        "00" -> {
                                            val newVal = priceCents.toLong() * 100
                                            if (newVal <= 9999999) priceCents = newVal.toInt()
                                        }
                                        else -> {
                                            val digit = label.toInt()
                                            val newVal = priceCents.toLong() * 10 + digit
                                            if (newVal <= 9999999) priceCents = newVal.toInt()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(1.dp))
            }
        }

        // Confirm button
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            PrimaryButton(
                text = "Confirmar precio",
                onClick = { onConfirm(priceCents) },
                enabled = priceCents > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Discounts Sub-View

@Composable
private fun DiscountsSubView(
    discounts: List<Discount>,
    selectedDiscountId: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SubViewHeader(
            title = "Descuentos",
            onBack = onBack,
        )

        if (discounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No hay descuentos disponibles para este artículo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AvoqadoTheme.spacing.xl),
            ) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                discounts.forEach { discount ->
                    val isSelected = selectedDiscountId == discount.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(discount.id) }
                            .padding(vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Checkbox (tap to select, tap again to deselect — single-select)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                                )
                                .then(
                                    if (!isSelected) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Color.Transparent,
                                            RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                                        ),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                        Text(text = "🏷️", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = discount.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = discount.displayValue,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
