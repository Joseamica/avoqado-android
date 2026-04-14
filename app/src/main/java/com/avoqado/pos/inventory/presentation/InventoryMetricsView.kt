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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.model.StockItem

// MARK: - Inventory Metrics View

@Composable
fun InventoryMetricsView(viewModel: InventoryViewModel) {
    val stockItems by viewModel.stockItems.collectAsState()

    if (stockItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(
                    Icons.Filled.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = "Sin datos de inventario",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Agrega productos con seguimiento de inventario para ver metricas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // Compute metrics from stockItems
    val totalTracked = stockItems.size
    val totalStockValue = stockItems.sumOf { it.onHand * 1.0 } // quantity as value proxy
    val lowStockItems = stockItems.filter { it.isLowStock }
    val outOfStockItems = stockItems.filter { it.onHand <= 0 }

    // Group by category
    val categoryValues = stockItems
        .groupBy { it.categoryName ?: "Sin categoria" }
        .map { (category, items) ->
            category to items.sumOf { it.onHand }
        }
        .sortedByDescending { it.second }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        // MARK: - Resumen section
        item {
            Text(
                text = "Resumen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                MetricCard(
                    title = "Productos rastreados",
                    value = "$totalTracked",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Existencias totales",
                    value = "${totalStockValue.toInt()}",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Bajo stock",
                    value = "${lowStockItems.size}",
                    valueColor = if (lowStockItems.isNotEmpty()) Warning else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // MARK: - Bajo stock section
        if (lowStockItems.isNotEmpty() || outOfStockItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Bajo stock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val alertItems = (outOfStockItems + lowStockItems).distinctBy { it.id }
            items(alertItems, key = { "low-${it.id}" }) { item ->
                LowStockRow(item = item)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // MARK: - Movimientos recientes (placeholder from stock data)
        item {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "Movimientos recientes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Show the last 10 items sorted by stock level (as proxy for recent movements)
        val recentMovements = stockItems
            .sortedBy { it.onHand }
            .take(10)

        if (recentMovements.isEmpty()) {
            item {
                Text(
                    text = "No hay movimientos recientes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recentMovements, key = { "mov-${it.id}" }) { item ->
                MovementRow(item = item)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // MARK: - Valor por categoria section
        item {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "Valor por categoria",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        items(categoryValues, key = { "cat-${it.first}" }) { (category, total) ->
            CategoryValueRow(category = category, totalQuantity = total)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Metric Card

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Low Stock Row

@Composable
private fun LowStockRow(item: StockItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Warning icon
        val iconBg = if (item.onHand <= 0) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        } else {
            Warning.copy(alpha = 0.15f)
        }
        val iconTint = if (item.onHand <= 0) {
            MaterialTheme.colorScheme.error
        } else {
            Warning
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (item.onHand <= 0) "Agotado" else "Stock bajo",
                style = MaterialTheme.typography.bodySmall,
                color = iconTint,
            )
        }

        Text(
            text = item.currentQuantityDisplay,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = iconTint,
        )
    }
}

// MARK: - Movement Row

@Composable
private fun MovementRow(item: StockItem) {
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
                text = item.initials,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.sku?.let { sku ->
                Text(
                    text = "SKU: $sku",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Stock level with color
        val qtyColor = when {
            item.onHand <= 0 -> MaterialTheme.colorScheme.error
            item.isLowStock -> Warning
            else -> Success
        }
        val trendIcon = if (item.onHand > 5) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
        ) {
            Icon(
                trendIcon,
                contentDescription = null,
                tint = qtyColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = item.currentQuantityDisplay,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = qtyColor,
            )
        }
    }
}

// MARK: - Category Value Row

@Composable
private fun CategoryValueRow(category: String, totalQuantity: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${totalQuantity.toInt()} uds",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
