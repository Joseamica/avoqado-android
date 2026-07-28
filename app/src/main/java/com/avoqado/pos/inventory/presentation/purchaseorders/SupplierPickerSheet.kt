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
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.model.Supplier

// MARK: - Supplier Picker Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierPickerSheet(
    suppliers: List<Supplier>,
    onSupplierSelected: (Supplier) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filteredSuppliers by remember(suppliers) {
        derivedStateOf {
            val q = query.trim()
            if (q.isEmpty()) {
                suppliers
            } else {
                suppliers.filter { supplier ->
                    supplier.name.contains(q, ignoreCase = true) ||
                        (supplier.contactName?.contains(q, ignoreCase = true) == true)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            // MARK: - Title
            Text(
                text = "Seleccionar proveedor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - Search field
            SearchPillField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Buscar proveedor",
                modifier = Modifier.fillMaxWidth(),
            )

            // MARK: - Results list / empty states
            when {
                suppliers.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AvoqadoTheme.spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No hay proveedores registrados. Agrégalos desde el dashboard web.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                filteredSuppliers.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AvoqadoTheme.spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Sin resultados",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(filteredSuppliers, key = { it.id }) { supplier ->
                            SupplierPickerRow(
                                supplier = supplier,
                                onClick = {
                                    onSupplierSelected(supplier)
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
}

// MARK: - Row

@Composable
private fun SupplierPickerRow(
    supplier: Supplier,
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
            text = supplier.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        supplier.contactName?.let { contact ->
            Text(
                text = contact,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        supplier.phone?.let { phone ->
            Text(
                text = phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
