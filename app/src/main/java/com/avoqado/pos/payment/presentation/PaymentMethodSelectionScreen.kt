package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Money
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.data.model.PaymentMethod

@Composable
fun PaymentMethodSelectionScreen(
    amountCents: Int,
    onMethodSelected: (PaymentMethod) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$${String.format("%.2f", amountCents / 100.0)}",
            style = MaterialTheme.typography.displayLarge,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "Selecciona método de pago",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            PaymentMethodCard(
                icon = Icons.Filled.CreditCard,
                label = "Tarjeta",
                modifier = Modifier.weight(1f),
                onClick = { onMethodSelected(PaymentMethod.CARD) },
            )
            PaymentMethodCard(
                icon = Icons.Filled.Money,
                label = "Efectivo",
                modifier = Modifier.weight(1f),
                onClick = { onMethodSelected(PaymentMethod.CASH) },
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        TextButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun PaymentMethodCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconXLarge),
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
