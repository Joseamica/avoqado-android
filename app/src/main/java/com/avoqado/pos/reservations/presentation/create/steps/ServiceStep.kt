package com.avoqado.pos.reservations.presentation.create.steps

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
 * ServiceStep — product/service picker for the new reservation flow.
 *
 * Layout:
 *  - Horizontal `LazyRow` of [FilterChip]s for category filter (always shows "Todos").
 *  - [SearchPillField] for textual filter on product name.
 *  - [LazyColumn] of products: avatar (color from product, fallback initials) + name +
 *    "60 min · $price" + selected check.
 *
 * Empty states:
 *  - When no products at all: friendly empty state with a hint to add products in the dashboard.
 *  - When category/search filter yields nothing: "Sin resultados" placeholder.
 *
 * Phase 2 default: every product is treated as a 60-minute service. Per-product duration is a
 * Phase 3 backend feature.
 *
 * Selection writes `productId`, `productName`, and `durationMinutes = 60` into the draft. The
 * "Continuar" pill in the StepperHeader is gated on `draft.canContinueFromService`.
 */
@Composable
fun ServiceStep(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var selectedCategoryId: String? by remember { mutableStateOf(null) }

    // Active products filtered by selected category and current search query.
    val visibleProducts = remember(products, query, selectedCategoryId) {
        products
            .filter { it.active != false }
            .filter { selectedCategoryId == null || it.categoryId == selectedCategoryId }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    // Categories derived from the active product list.
    val categories = remember(products) {
        products
            .filter { it.active != false }
            .mapNotNull { it.category }
            .distinctBy { it.id }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
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

        when {
            products.isEmpty() -> EmptyProductsState()
            visibleProducts.isEmpty() -> NoResultsState()
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = AvoqadoTheme.spacing.xxl),
            ) {
                items(visibleProducts, key = { it.id }) { product ->
                    ProductRow(
                        product = product,
                        isSelected = draft.productId == product.id,
                        onClick = {
                            viewModel.update {
                                it.copy(
                                    productId = product.id,
                                    productName = product.name,
                                    // Default until per-product duration ships in Phase 3.
                                    durationMinutes = 60,
                                )
                            }
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 72.dp),
                    )
                }
            }
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
                text = "60 min · ${product.displayPrice}",
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
            // Reserve trailing space so rows don't shift when selection toggles.
            Spacer(modifier = Modifier.width(20.dp))
        }
    }
}

@Composable
private fun EmptyProductsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
            .fillMaxSize()
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
