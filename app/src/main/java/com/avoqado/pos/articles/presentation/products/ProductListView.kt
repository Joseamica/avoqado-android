package com.avoqado.pos.articles.presentation.products

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun ProductListView(viewModel: ArticlesViewModel) {
    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current

    var showCreateForm by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<ArticleProduct?>(null) }
    var deletingProduct by remember { mutableStateOf<ArticleProduct?>(null) }

    // Quick-add inline state
    var quickAddName by remember { mutableStateOf("") }
    var quickAddPrice by remember { mutableStateOf("") }

    // Show detail view (create or edit) instead of the list
    if (showCreateForm || editingProduct != null) {
        ProductDetailView(
            product = editingProduct,
            viewModel = viewModel,
            onDismiss = {
                showCreateForm = false
                editingProduct = null
            },
        )
        return
    }

    val filteredProducts = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products
        else products.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    Column(modifier = Modifier.fillMaxSize()) {
        // MARK: - Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Todos los articulos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
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
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // MARK: - Search bar (outlined style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                placeholder = {
                    Text(
                        text = "Buscar articulos...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { viewModel.updateSearch("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Limpiar búsqueda",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // MARK: - Content
        if (filteredProducts.isEmpty() && searchQuery.isNotBlank()) {
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
}

// MARK: - Quick-Add Inline Row

@Composable
private fun QuickAddRow(
    name: String,
    price: String,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Plus icon in a gray rounded square
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Agregar",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Name text field
        Box(modifier = Modifier.weight(1f)) {
            if (name.isEmpty()) {
                Text(
                    text = "Nombre del artículo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Price text field
        Box(modifier = Modifier.width(72.dp)) {
            if (price.isEmpty()) {
                Text(
                    text = "$0.00",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = price,
                onValueChange = onPriceChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
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
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPrintLabel: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = { showMenu = true },
            )
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Avatar (neutral gray)
        ProductAvatar(product = product)

        // Name + category
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (category != null) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (product.isService && product.displayDuration != null) {
                Text(
                    text = product.displayDuration!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Price
        Box {
            Text(
                text = product.displayPrice,
                style = MaterialTheme.typography.bodyMedium,
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
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = product.initials,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
