package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.BackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Square-style payment confirmation screen:
 * - Hero amount centered (56sp bold)
 * - Flat detail rows with dividers at bottom
 * - Pill button with wave icon
 */
@Composable
fun PaymentConfirmScreen(
    subtotalCents: Int,
    tipCents: Int,
    totalCents: Int,
    rating: Int? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xl),
    ) {
        // Back button
        BackButton(onClick = onCancel)

        // Hero amount centered
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$${String.format("%.2f", totalCents / 100.0)}",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Total a cobrar",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Detail rows at bottom (flat, with dividers, max 600dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            HorizontalDivider()
            DetailRow("Subtotal", subtotalCents)
            if (tipCents > 0) {
                HorizontalDivider()
                DetailRow("Propina", tipCents)
            }
            if (rating != null) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.xl)
                        .padding(vertical = AvoqadoTheme.spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Calificación", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "★".repeat(rating) + "☆".repeat(5 - rating),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.xl)
                    .padding(vertical = AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Total", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "$${String.format("%.2f", totalCents / 100.0)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            HorizontalDivider()

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Pill button with icon (Square-style)
            Button(
                onClick = onConfirm,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    Icons.Filled.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xxs))
                Text(
                    text = "Enviar a terminal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Cancelar")
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

@Composable
private fun DetailRow(label: String, cents: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .padding(vertical = AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            "$${String.format("%.2f", cents / 100.0)}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
