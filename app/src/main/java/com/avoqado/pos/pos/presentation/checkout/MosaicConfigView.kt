package com.avoqado.pos.pos.presentation.checkout

import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartViewModel

// MARK: - Preferences Keys

private const val MOSAIC_PREFS_NAME = "avoqado_mosaic_prefs"
private const val MOSAIC_PRODUCT_IDS_KEY = "mosaic_product_ids"

// MARK: - Mosaic Preferences Helper

object MosaicPreferences {
    fun getSavedProductIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(MOSAIC_PREFS_NAME, Context.MODE_PRIVATE)
        val idsString = prefs.getString(MOSAIC_PRODUCT_IDS_KEY, null)
        return idsString?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun saveProductIds(context: Context, ids: List<String>) {
        val prefs = context.getSharedPreferences(MOSAIC_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(MOSAIC_PRODUCT_IDS_KEY, ids.joinToString(",")).apply()
    }
}

// MARK: - Mosaic Config View

@Composable
fun MosaicConfigView(
    cartViewModel: CartViewModel,
) {
    val allProducts by cartViewModel.products.collectAsState()
    val context = LocalContext.current

    // Load saved state
    val savedIds = remember { MosaicPreferences.getSavedProductIds(context) }

    // Local mutable state: selected product IDs (in order)
    var selectedIds by remember {
        mutableStateOf(savedIds.filter { id -> allProducts.any { it.id == id } })
    }
    var saved by remember { mutableStateOf(false) }

    // Build the display list: selected products first (in order), then unselected (alphabetical)
    val selectedProducts = selectedIds.mapNotNull { id -> allProducts.find { it.id == id } }
    val unselectedProducts = allProducts
        .filter { it.id !in selectedIds }
        .sortedBy { it.name }
    val displayList = selectedProducts + unselectedProducts

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
        ) {
            Text(
                text = "Configurar mosaico",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            Text(
                text = "Selecciona los productos que apareceran en la cuadricula rapida",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Text(
                    text = "${selectedIds.size} productos seleccionados",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Product checklist
        if (displayList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No hay productos disponibles",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(displayList, key = { it.id }) { product ->
                    val isChecked = product.id in selectedIds

                    MosaicProductRow(
                        product = product,
                        isChecked = isChecked,
                        onToggle = {
                            selectedIds = if (isChecked) {
                                selectedIds - product.id
                            } else {
                                selectedIds + product.id
                            }
                            saved = false
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        // Save button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            PrimaryButton(
                text = if (saved) "Guardado" else "Guardar",
                onClick = {
                    MosaicPreferences.saveProductIds(context, selectedIds)
                    saved = true
                },
                enabled = !saved,
            )
        }
    }
}

// MARK: - Product Row with Checkbox

@Composable
private fun MosaicProductRow(
    product: Product,
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox icon
        Icon(
            imageVector = if (isChecked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (isChecked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Product initials avatar
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = product.name.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Product name + price
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = product.displayPrice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Drag handle hint (visual only)
        if (isChecked) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
            )
        }
    }
}
