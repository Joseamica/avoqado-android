package com.avoqado.pos.payment.presentation

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.payment.data.model.PaymentMethod

@Composable
fun PaymentResultScreen(
    totalCents: Int,
    method: PaymentMethod,
    changeCents: Int = 0,
    isQueued: Boolean = false,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Offline queued banner
        if (isQueued) {
            Surface(
                color = Warning.copy(alpha = 0.15f),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AvoqadoTheme.spacing.lg),
            ) {
                Row(
                    modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Se sincronizara cuando haya conexion",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning,
                    )
                }
            }
        }

        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Exito",
            tint = Success,
            modifier = Modifier.size(80.dp),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Pago exitoso",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "$${String.format("%.2f", totalCents / 100.0)}",
            style = MaterialTheme.typography.displayMedium,
        )

        Text(
            text = method.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (method == PaymentMethod.CASH && changeCents > 0) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Cambio: $${String.format("%.2f", changeCents / 100.0)}",
                style = MaterialTheme.typography.titleLarge,
                color = Success,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        // Receipt options (matching iOS: 4 rows with icons)
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            ReceiptOptionRow(
                icon = Icons.Filled.Print,
                title = "Imprimir recibo",
                onClick = { /* TODO: printer picker */ },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

            ReceiptOptionRow(
                icon = Icons.Filled.Email,
                title = "Enviar por correo",
                onClick = { /* TODO: email input */ },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

            ReceiptOptionRow(
                icon = Icons.Filled.Sms,
                title = "Enviar por SMS",
                onClick = { /* TODO: SMS input */ },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        PrimaryButton(
            text = "Venta nueva",
            onClick = onDone,
        )
    }
}

// MARK: - Receipt Option Row (matching iOS)

@Composable
private fun ReceiptOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Payment Processing View

@Composable
fun PaymentProcessingView(
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Procesando pago...",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "Esperando respuesta de la terminal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (onCancel != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

            TextButton(onClick = onCancel) {
                Text(
                    text = "Cancelar",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// MARK: - Payment Error View

@Composable
fun PaymentErrorView(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(80.dp),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Error en el pago",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        PrimaryButton(
            text = "Reintentar",
            onClick = onRetry,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        TextButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

// MARK: - Payment Loading View

@Composable
fun PaymentLoadingView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Preparando pago...",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
