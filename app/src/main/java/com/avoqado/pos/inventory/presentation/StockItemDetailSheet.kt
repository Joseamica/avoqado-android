package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.model.StockItem

/**
 * Detalle de un artículo tocado en Inventario → Descripción general.
 * Solo lectura: muestra lo que el overview ya trae del server, sin otro fetch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockItemDetailSheet(
    item: StockItem,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(bottom = AvoqadoTheme.spacing.xxxl),
        ) {
            // Encabezado: imagen (o iniciales) + nombre + categoría
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.imageUrl != null) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp),
                        )
                    } else {
                        Text(
                            text = item.initials,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.size(AvoqadoTheme.spacing.lg))
                Column {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    item.categoryName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Existencias: las tres cifras que el overview ya conoce
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StockFigure(label = "En mano", value = item.onHand, unit = item.unit)
                StockFigure(label = "Disponible", value = item.available, unit = item.unit)
                StockFigure(label = "En pedido", value = item.onOrder, unit = item.unit)
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            DetailRow(label = "SKU", value = item.sku)
            DetailRow(label = "Código de barras (GTIN)", value = item.gtin)
            DetailRow(label = "Unidad", value = item.unit)
            DetailRow(
                label = "Seguimiento",
                value = when (item.inventoryMethod) {
                    "RECIPE" -> "Por receta (se calcula de sus ingredientes)"
                    null -> null
                    else -> "Existencia propia"
                },
            )
        }
    }
}

@Composable
private fun StockFigure(label: String, value: Double, unit: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatStockQuantity(value) + (unit?.let { " $it" } ?: ""),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun formatStockQuantity(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
