package com.avoqado.pos.reservations.presentation.create.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel

/**
 * ServiceSection — embedded service picker for the create-reservation flow.
 *
 * Lives inside a [ModalBottomSheet] (PickerSheet) — owns its own scroll via
 * LazyColumn so venues with hundreds of services stay performant. Only bookable
 * product types (`SERVICE`, `APPOINTMENTS_SERVICE`, `CLASS`) surface here.
 */
@Composable
fun ServiceSection(
    viewModel: CreateReservationViewModel,
    onServicePicked: (() -> Unit)? = null,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var selectedCategoryId: String? by remember { mutableStateOf(null) }

    val bookableTypes = remember { setOf("SERVICE", "APPOINTMENTS_SERVICE", "CLASS") }

    val visibleProducts = remember(products, query, selectedCategoryId, bookableTypes) {
        products
            .filter { it.active != false }
            .filter { it.type in bookableTypes }
            .filter { selectedCategoryId == null || it.categoryId == selectedCategoryId }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    val categories = remember(products, bookableTypes) {
        products
            .filter { it.active != false }
            .filter { it.type in bookableTypes }
            .mapNotNull { it.category }
            .distinctBy { it.id }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
            item("categories") {
                LazyRow(
                    contentPadding = PaddingValues(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    item("all") {
                        FilterChip(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null },
                            label = { Text("Todos") },
                        )
                    }
                    items(categories, key = { it.id }) { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = {
                                selectedCategoryId = if (selectedCategoryId == category.id) {
                                    null
                                } else {
                                    category.id
                                }
                            },
                            label = { Text(category.name) },
                        )
                    }
                }
            }
        }

        item("search") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchPillField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Buscar servicio",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        when {
            products.isEmpty() -> item("empty-products") { EmptyProductsState() }
            visibleProducts.isEmpty() -> item("no-results") { NoResultsState() }
            else -> items(visibleProducts, key = { it.id }) { product ->
                ProductRow(
                    product = product,
                    isSelected = draft.productId == product.id,
                    onClick = {
                        viewModel.selectProduct(product)
                        onServicePicked?.invoke()
                    },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 72.dp),
                )
            }
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
}

@Composable
private fun ProductRow(
    product: Product,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val avatarColor = parseHexColor(product.color)
        ?: MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            )
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = product.name.take(2).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "${product.duration ?: 60} min · ${product.displayPrice}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Seleccionado",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }
    }
}

@Composable
private fun EmptyProductsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Inventory2,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = "Aún no tienes productos configurados",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
        Text(
            text = "Agrega productos en el dashboard para usarlos como servicios",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResultsState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AvoqadoTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Sin resultados",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(cleanHex))
    }.getOrNull()
}
