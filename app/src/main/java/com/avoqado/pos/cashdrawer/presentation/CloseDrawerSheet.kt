package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Success
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseDrawerSheet(
    expectedAmountCents: Int,
    onConfirm: (actualAmountCents: Int, note: String?) -> Unit,
    onDismiss: () -> Unit,
    onClosed: ((actualAmountCents: Int) -> Unit)? = null,
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var closed by remember { mutableStateOf(false) }

    val actualCents = ((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
    val differenceCents = actualCents - expectedAmountCents

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Cerrar caja",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Text(
                text = "Cuenta el efectivo real en caja e ingresa el monto.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Expected amount display
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Monto esperado:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = formatCurrency(expectedAmountCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        amountText = newValue
                    }
                },
                label = { Text("Conteo real ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            // Over/short indicator
            if (amountText.isNotBlank() && amountText.toDoubleOrNull() != null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                val (label, color) = when {
                    differenceCents > 0 -> "Sobrante: +${formatCurrency(differenceCents)}" to Success
                    differenceCents < 0 -> "Faltante: -${formatCurrency(abs(differenceCents))}" to Error
                    else -> "Cuadre exacto" to Success
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Nota (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            Button(
                onClick = {
                    val note = noteText.ifBlank { null }
                    onConfirm(actualCents, note)
                    onClosed?.invoke(actualCents)
                },
                enabled = amountText.isNotBlank() && amountText.toDoubleOrNull() != null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = "Cerrar caja",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
