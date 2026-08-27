package com.avoqado.pos.estimates.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.estimates.data.model.Estimate
import com.avoqado.pos.estimates.data.model.EstimateStatus

// MARK: - Estimate Detail View

@Composable
fun EstimateDetailView(
    estimate: Estimate,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.xl),
    ) {
        // Header: number + status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Presupuesto ${estimate.displayNumber}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = estimate.displayDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(status = estimate.estimateStatus)
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Customer section
        if (estimate.customerName != null || estimate.customerEmail != null || estimate.customerPhone != null) {
            DetailSectionCard(title = "Cliente") {
                if (estimate.customerName != null) {
                    DetailInfoRow("Nombre", estimate.customerName)
                }
                if (estimate.customerEmail != null) {
                    DetailInfoRow("Correo", estimate.customerEmail)
                }
                if (estimate.customerPhone != null) {
                    DetailInfoRow("Teléfono", estimate.customerPhone)
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        }

        // Items section
        DetailSectionCard(title = "Artículos") {
            if (estimate.items.isNullOrEmpty()) {
                Text(
                    text = "Sin artículos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                )
            } else {
                estimate.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AvoqadoTheme.spacing.lg,
                                vertical = AvoqadoTheme.spacing.md,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.productName ?: "Artículo",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${item.quantity} x ${item.displayUnitPrice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = item.displayTotal,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Totals section
        DetailSectionCard(title = "Totales") {
            Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                TotalRow("Subtotal", formatMoney(estimate.subtotal))
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                TotalRow("Impuestos", formatMoney(estimate.taxAmount))
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                TotalRow("Total", estimate.displayTotal, bold = true)
            }
        }

        // Notes
        if (!estimate.notes.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            DetailSectionCard(title = "Notas") {
                Text(
                    text = estimate.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                )
            }
        }
    }
}

// MARK: - Status Pill (larger variant for detail)

@Composable
private fun StatusPill(status: EstimateStatus) {
    val (bgColor, textColor) = when (status) {
        EstimateStatus.DRAFT -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        EstimateStatus.SENT -> Color(0xFF2196F3).copy(alpha = 0.12f) to Color(0xFF1565C0)
        EstimateStatus.ACCEPTED -> Color(0xFF4CAF50).copy(alpha = 0.12f) to Color(0xFF2E7D32)
        EstimateStatus.REJECTED -> Color(0xFFF44336).copy(alpha = 0.12f) to Color(0xFFC62828)
        EstimateStatus.EXPIRED -> Color(0xFFFF9800).copy(alpha = 0.12f) to Color(0xFFE65100)
        EstimateStatus.CONVERTED -> Color(0xFF9C27B0).copy(alpha = 0.12f) to Color(0xFF6A1B9A)
    }

    Text(
        text = status.label,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(50))
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
    )
}

// MARK: - Detail Section Card

@Composable
private fun DetailSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        content()
    }
}

// MARK: - Detail Info Row

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Total Row

@Composable
private fun TotalRow(
    label: String,
    value: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
