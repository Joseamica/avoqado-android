package com.avoqado.pos.inventory.presentation.purchaseorders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.model.StockItem

// MARK: - Product Picker Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductPickerSheet(
    products: List<StockItem>,
    onProductSelected: (StockItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filteredProducts by remember(products) {
        derivedStateOf {
            val q = query.trim()
            if (q.isEmpty()) {
                products
            } else {
                products.filter { stock ->
                    stock.name.contains(q, ignoreCase = true) ||
                        (stock.sku?.contains(q, ignoreCase = true) == true)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            // MARK: - Title
            Text(
                text = "Seleccionar artículo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - Search field
            SearchPillField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Buscar por nombre o SKU",
                modifier = Modifier.fillMaxWidth(),
            )

            // MARK: - Results list
            if (filteredProducts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No hay artículos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(filteredProducts, key = { it.id }) { stock ->
                        ProductPickerRow(
                            stock = stock,
                            onClick = {
                                onProductSelected(stock)
                                onDismiss()
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// MARK: - Row

@Composable
private fun ProductPickerRow(
    stock: StockItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.md,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
    ) {
        Text(
            text = stock.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        stock.sku?.let { sku ->
            Text(
                text = "SKU: $sku",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
