package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayInOutSheet(
    isPayIn: Boolean,
    onConfirm: (amountCents: Int, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    val title = if (isPayIn) "Registrar ingreso" else "Registrar egreso"
    val subtitle = if (isPayIn) {
        "Ingresa el monto y una nota opcional para el ingreso de efectivo."
    } else {
        "Ingresa el monto y una nota opcional para el retiro de efectivo."
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        amountText = newValue
                    }
                },
                label = { Text("Monto ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

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
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    val cents = (amount * 100).toInt()
                    val note = noteText.ifBlank { null }
                    onConfirm(cents, note)
                },
                enabled = amountText.isNotBlank() && (amountText.toDoubleOrNull() ?: 0.0) > 0,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = "Confirmar",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
