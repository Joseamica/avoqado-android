package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem

// MARK: - Stock Count Detail View

@Composable
fun StockCountDetailView(
    count: StockCount,
    viewModel: InventoryViewModel,
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    var searchText by remember { mutableStateOf("") }

    val filteredItems = if (searchText.isBlank()) count.items
    else count.items.filter {
        it.productName.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true) ||
            (it.gtin?.contains(searchText, ignoreCase = true) == true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Atrás")
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "${count.type.label} - ${count.statusDisplay}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Retomar lo que quedó a medias. Sin esto la pantalla era sólo
            // lectura y "Contar existencia" creaba OTRO conteo: en la base del
            // local de pruebas quedaron dos completos abiertos desde el 11 de
            // julio, con 51 artículos cada uno, que nadie iba a cerrar.
            if (count.status == "IN_PROGRESS") {
                TextButton(onClick = { viewModel.resumeCount(count) }) {
                    Text("Continuar conteo")
                }
            } else {
                // Hueco simétrico para que el título siga centrado.
                Box(modifier = Modifier.width(64.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Info row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = "${count.items.size} artículos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count.createdBy != null) {
                Text(
                    text = "Por: ${count.createdBy}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (count.note != null) {
                Text(
                    text = "Nota: ${count.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Search
        TextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Buscar por nombre, SKU o GTIN") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        )

        // Column headers (tablet)
        if (isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Artículo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(2f),
                )
                Text(
                    text = "SKU",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Esperado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = "Contado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = "Diferencia",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Items
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            items(filteredItems, key = { it.id.ifEmpty { it.productId } }) { item ->
                DetailItemRow(item = item, viewModel = viewModel, isTablet = isTablet)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun DetailItemRow(
    item: StockCountItem,
    viewModel: InventoryViewModel,
    isTablet: Boolean,
) {
    val diff = item.counted - item.expected
    val diffColor = when {
        // Sin contar no hay diferencia que enseñar: pintarla en rojo le dice al
        // gerente que falta mercancía cuando lo que falta es contarla.
        !item.yaSeConto -> MaterialTheme.colorScheme.onSurfaceVariant
        diff > 0 -> Success
        diff < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contadoTexto = if (item.yaSeConto) viewModel.formatQuantity(item.counted) else "—"
    val diferenciaTexto = if (item.yaSeConto) viewModel.formatQuantity(diff) else "Sin contar"

    if (isTablet) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.productName.take(2).uppercase(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.sku ?: "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = viewModel.formatQuantity(item.expected),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
            Text(
                text = contadoTexto,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
            Text(
                text = if (!item.yaSeConto) diferenciaTexto
                else if (diff > 0) "+${viewModel.formatQuantity(diff)}"
                else viewModel.formatQuantity(diff),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = diffColor,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.productName.take(2).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.productName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
                    Text(text = "${viewModel.formatQuantity(item.expected)} / $contadoTexto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = if (!item.yaSeConto) diferenciaTexto
                else if (diff > 0) "+${viewModel.formatQuantity(diff)}"
                else viewModel.formatQuantity(diff),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = diffColor,
            )
        }
    }
}
