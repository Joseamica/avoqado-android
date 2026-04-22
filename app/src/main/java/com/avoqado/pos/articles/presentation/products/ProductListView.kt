package com.avoqado.pos.articles.presentation.products

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.ArticleCategory
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.PriceType
import com.avoqado.pos.articles.data.model.ProductType
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoLoadingState
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun ProductListView(viewModel: ArticlesViewModel) {
    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val lastSaveSuccess by viewModel.lastSaveSuccess.collectAsState()
    val context = LocalContext.current

    var showCreateForm by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<ArticleProduct?>(null) }
    var deletingProduct by remember { mutableStateOf<ArticleProduct?>(null) }

    // Celebratory toast on create/update/delete success.
    lastSaveSuccess?.let { message ->
        AvoqadoSuccessToast(
            message = message,
            onDismiss = { viewModel.clearLastSaveSuccess() },
        )
    }

    // Quick-add inline state
    var quickAddName by remember { mutableStateOf("") }
    var quickAddPrice by remember { mutableStateOf("") }

    val detailProduct = if (showCreateForm) null else editingProduct

    val filteredProducts = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products
        else products.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md
    val rowVerticalPadding = if (denseListMode) 9.dp else adaptive.listRowVerticalPadding
    val leadingVisualSize = if (denseListMode) (adaptive.listLeadingVisualSize - 4.dp) else adaptive.listLeadingVisualSize
    val leadingIconSize = if (denseListMode) (adaptive.listLeadingIconSize - 2.dp) else adaptive.listLeadingIconSize
    val priceColumnWidth = if (denseListMode) (adaptive.listPriceColumnWidth - 8.dp) else adaptive.listPriceColumnWidth
    val searchHeight = if (denseListMode) 40.dp else adaptive.listSearchFieldHeight

    Column(modifier = Modifier.fillMaxSize()) {
        // MARK: - Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = contentHorizontalPadding,
                    vertical = headerVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Todos los articulos",
                style = if (denseListMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(if (denseListMode) 34.dp else 36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { showCreateForm = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Crear articulo",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (denseListMode) 18.dp else 20.dp),
                )
            }
        }

        // MARK: - Search bar
        SearchPillField(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            placeholder = "Buscar articulos...",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding, vertical = AvoqadoTheme.spacing.xs),
            height = searchHeight,
        )

        // MARK: - Content
        if (isLoading && products.isEmpty()) {
            AvoqadoLoadingState(
                message = "Cargando articulos...",
                compact = denseListMode,
            )
        } else if (filteredProducts.isEmpty() && searchQuery.isNotBlank()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.xxxl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalOffer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No hay articulos",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Crea tu primer articulo para empezar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // MARK: - Quick-add inline row
                item {
                    QuickAddRow(
                        name = quickAddName,
                        price = quickAddPrice,
                        denseMode = denseListMode,
                        horizontalPadding = contentHorizontalPadding,
                        rowVerticalPadding = rowVerticalPadding,
                        leadingVisualSize = leadingVisualSize,
                        leadingIconSize = leadingIconSize,
                        priceColumnWidth = priceColumnWidth,
                        onNameChange = { quickAddName = it },
                        onPriceChange = { quickAddPrice = it },
                        onSubmit = {
                            if (quickAddName.isNotBlank()) {
                                viewModel.createProduct(
                                    name = quickAddName.trim(),
                                    type = ProductType.REGULAR,
                                    priceType = PriceType.FIXED,
                                    price = quickAddPrice.toDoubleOrNull(),
                                    taxRate = 0.16,
                                    isActive = true,
                                    trackInventory = false,
                                )
                                quickAddName = ""
                                quickAddPrice = ""
                            }
                        },
                    )
                    // Full-width divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                items(filteredProducts, key = { it.id }) { product ->
                    val category = categoryMap[product.categoryId]
                        ?: product.category
                    ProductRow(
                        product = product,
                        category = category,
                        denseMode = denseListMode,
                        horizontalPadding = contentHorizontalPadding,
                        rowVerticalPadding = rowVerticalPadding,
                        leadingVisualSize = leadingVisualSize,
                        onTap = { editingProduct = product },
                        onEdit = { editingProduct = product },
                        onDelete = { deletingProduct = product },
                        onPrintLabel = {
                            Toast.makeText(
                                context,
                                "Impresion no disponible",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
    }

    // MARK: - Delete confirmation dialog
    deletingProduct?.let { product ->
        AlertDialog(
            onDismissRequest = { deletingProduct = null },
            title = {
                Text(text = "Eliminar articulo")
            },
            text = {
                Text(text = "Esta accion no se puede deshacer")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProduct(product.id)
                    deletingProduct = null
                }) {
                    Text(
                        text = "Eliminar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProduct = null }) {
                    Text(text = "Cancelar")
                }
            },
        )
    }

    if (showCreateForm || detailProduct != null) {
        ProductDetailView(
            product = detailProduct,
            viewModel = viewModel,
            onDismiss = {
                showCreateForm = false
                editingProduct = null
            },
        )
    }
}

// MARK: - Quick-Add Inline Row

@Composable
private fun QuickAddRow(
    name: String,
    price: String,
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    rowVerticalPadding: androidx.compose.ui.unit.Dp,
    leadingVisualSize: androidx.compose.ui.unit.Dp,
    leadingIconSize: androidx.compose.ui.unit.Dp,
    priceColumnWidth: androidx.compose.ui.unit.Dp,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val nameStyle = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = horizontalPadding,
                vertical = rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
    ) {
        // Plus icon in a gray rounded square
        Box(
            modifier = Modifier
                .size(leadingVisualSize)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Agregar",
                modifier = Modifier.size(leadingIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Name text field
        Box(modifier = Modifier.weight(1f)) {
            if (name.isEmpty()) {
                Text(
                    text = "Nombre del artículo",
                    style = nameStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = nameStyle.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Price text field
        Box(modifier = Modifier.width(priceColumnWidth)) {
            if (price.isEmpty()) {
                Text(
                    text = "$0.00",
                    style = nameStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = price,
                onValueChange = onPriceChange,
                textStyle = nameStyle.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Product Row

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductRow(
    product: ArticleProduct,
    category: ArticleCategory?,
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    rowVerticalPadding: androidx.compose.ui.unit.Dp,
    leadingVisualSize: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPrintLabel: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val titleStyle = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val subtitleStyle = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = { showMenu = true },
            )
            .padding(
                horizontal = horizontalPadding,
                vertical = rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
    ) {
        // Avatar (neutral gray)
        ProductAvatar(
            product = product,
            denseMode = denseMode,
            leadingVisualSize = leadingVisualSize,
        )

        // Name + category
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (category != null) {
                Text(
                    text = category.name,
                    style = subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (product.isService && product.displayDuration != null) {
                Text(
                    text = product.displayDuration!!,
                    style = subtitleStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Price
        Box {
            Text(
                text = product.displayPrice,
                style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Editar") },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Imprimir etiqueta") },
                    onClick = {
                        showMenu = false
                        onPrintLabel()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Eliminar",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }

    // Divider (full width, no horizontal padding)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// MARK: - Product Avatar (neutral gray)

@Composable
private fun ProductAvatar(
    product: ArticleProduct,
    denseMode: Boolean,
    leadingVisualSize: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(leadingVisualSize)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = product.initials,
            style = if (denseMode) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
