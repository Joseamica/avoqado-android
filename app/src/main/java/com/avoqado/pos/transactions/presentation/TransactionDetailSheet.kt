package com.avoqado.pos.transactions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.model.Transaction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            // Title (matching iOS: "Venta de $X.XX")
            Text(
                text = "Venta de ${transaction.totalDisplay}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Action buttons (matching iOS: "Devolver o cambiar" + "Recibo nuevo")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                ActionButton(
                    icon = Icons.Filled.SwapHoriz,
                    label = "Devolver o cambiar",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO: refund flow */ },
                )
                ActionButton(
                    icon = Icons.Filled.Receipt,
                    label = "Recibo nuevo",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO: new receipt sheet */ },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // MARK: - Pago Section (matching iOS)
            SectionHeader("Pago")

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Payment method row with icon
            val paymentIcon = if (transaction.paymentMethod == "CASH") {
                Icons.Filled.MonetizationOn
            } else {
                Icons.Filled.CreditCard
            }
            InfoRow(
                icon = paymentIcon,
                label = transaction.paymentMethodDisplay,
            )

            ThinDivider()

            // Date display
            transaction.createdAt?.let {
                transaction.timeDisplay?.let { time ->
                    InfoRow(
                        icon = null,
                        label = "${transaction.dateGroup}, $time",
                    )
                    ThinDivider()
                }
            }

            // Staff name
            transaction.staffName?.let { staff ->
                InfoRow(
                    icon = Icons.Filled.Person,
                    label = staff,
                )
                ThinDivider()
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // MARK: - Articulos Section (matching iOS)
            SectionHeader("Articulos")

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Gray sub-header (matching iOS: "Para comer aqui")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
            ) {
                Text(
                    text = "En tienda",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Items
            transaction.items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Quantity badge
                    Text(
                        text = "${item.quantity}\u00D7",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$${String.format("%.2f", item.total / 100.0)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ThinDivider()
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // MARK: - Total Section (matching iOS)
            SectionHeader("Total")

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Subtotal
            DetailRow("Subtotal", transaction.subtotal)
            ThinDivider()

            // Discount
            if (transaction.discount > 0) {
                DetailRow("Descuento", -transaction.discount)
                ThinDivider()
            }

            // Tip
            if (transaction.tip > 0) {
                DetailRow("Propina", transaction.tip)
                ThinDivider()
            }

            // Total (bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = transaction.totalDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Action Button (matching iOS: tall bordered button with icon)

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

// MARK: - Section Header

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

// MARK: - Info Row (icon + label)

@Composable
private fun InfoRow(
    icon: ImageVector?,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// MARK: - Detail Row

@Composable
private fun DetailRow(label: String, cents: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$${String.format("%.2f", cents / 100.0)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// MARK: - Thin divider (matching iOS: 0.5pt separator)

@Composable
private fun ThinDivider() {
    HorizontalDivider(thickness = 0.5.dp)
}
