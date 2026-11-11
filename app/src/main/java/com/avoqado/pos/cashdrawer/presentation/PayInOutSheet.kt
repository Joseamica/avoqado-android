package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayInOutSheet(
    isPayIn: Boolean,
    onConfirm: (amountCents: Int, note: String?) -> Unit,
    onDismiss: () -> Unit,
    /** Retiro: se está registrando. El botón queda ocupado y la hoja no se cierra hasta tener la respuesta. */
    ocupado: Boolean = false,
    /** Retiro: por qué no se registró. Va dentro de la hoja, con lo escrito intacto. */
    error: String? = null,
    /** Retiro: al confirmar, un gerente teclea su PIN. */
    conPin: Boolean = false,
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    val ocupadoAhora by rememberUpdatedState(ocupado)

    val title = if (isPayIn) "Registrar ingreso" else "Registrar egreso"
    val subtitle = when {
        isPayIn -> "Ingresa el monto y una nota opcional para el ingreso de efectivo."
        conPin -> "Ingresa el monto y el concepto. Al confirmar, un gerente lo autoriza con su PIN."
        else -> "Ingresa el monto y el concepto del retiro de efectivo."
    }
    val montoValido = amountText.isNotBlank() && (amountText.toDoubleOrNull() ?: 0.0) > 0

    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        // Mientras se registra no se esconde (arrastrar o tocar fuera): un rechazo quedaría en una hoja oculta.
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !ocupadoAhora },
        ),
        // En Windows la X baja la hoja sin preguntar: mientras se registra, sin X.
        conXPropia = ocupado,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding()
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
                readOnly = ocupado,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                // En el retiro el concepto es obligatorio: sale en el comprobante que firman los dos.
                label = { Text(if (isPayIn) "Nota (opcional)" else "Concepto") },
                singleLine = true,
                readOnly = ocupado,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            error?.let {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    val cents = (amount * 100).roundToInt() // P2 Codex: toInt() truncaba centavos
                    val note = noteText.ifBlank { null }
                    onConfirm(cents, note)
                },
                enabled = montoValido && (isPayIn || noteText.isNotBlank()) && !ocupado,
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
                    text = if (ocupado) "Registrando…" else "Confirmar",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
