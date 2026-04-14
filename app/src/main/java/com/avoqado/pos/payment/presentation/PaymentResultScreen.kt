package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    paymentId: String? = null,
    isSendingWhatsApp: Boolean = false,
    whatsAppResultMessage: String? = null,
    onSendWhatsApp: ((String) -> Unit)? = null,
    onClearWhatsAppResult: (() -> Unit)? = null,
    isSendingEmail: Boolean = false,
    emailResultMessage: String? = null,
    onSendEmail: ((String) -> Unit)? = null,
    onClearEmailResult: (() -> Unit)? = null,
    isPrintingReceipt: Boolean = false,
    printResultMessage: String? = null,
    onPrintReceipt: (() -> Unit)? = null,
    onClearPrintResult: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    var showWhatsAppInput by remember { mutableStateOf(false) }
    var whatsAppPhone by remember { mutableStateOf("") }
    var showEmailInput by remember { mutableStateOf(false) }
    var emailAddress by remember { mutableStateOf("") }

    // Combine result messages for display (whatsApp/email/print)
    val resultMessage = whatsAppResultMessage ?: emailResultMessage ?: printResultMessage
    val isResultSuccess = resultMessage == "Recibo enviado por WhatsApp" ||
        resultMessage == "Recibo enviado por correo" ||
        resultMessage == "Recibo impreso"

    androidx.compose.material3.Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(AvoqadoTheme.spacing.xxxl)
                .verticalScroll(rememberScrollState()),
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

            // Receipt result message (email or WhatsApp)
            if (resultMessage != null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Surface(
                    color = if (isResultSuccess) Success.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = resultMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isResultSuccess) Success else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

            // Receipt options + primary action share the same explicit width.
            // We use BoxWithConstraints (instead of widthIn) because that fully
            // bypasses any modifier-order quirks: we read the available maxWidth
            // as a Dp, clamp it, and apply Modifier.width(...) directly. The
            // resulting width is centered horizontally inside the parent Column
            // (which already has horizontalAlignment = CenterHorizontally).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxFormWidth = 400.dp
                val targetWidth = if (maxWidth > maxFormWidth) maxFormWidth else maxWidth
                Column(
                    modifier = Modifier
                        .width(targetWidth)
                        .align(Alignment.Center),
                ) {
                    ReceiptOptionRow(
                        icon = Icons.Filled.Print,
                        title = if (isPrintingReceipt) "Imprimiendo..." else "Imprimir recibo",
                        enabled = onPrintReceipt != null && !isPrintingReceipt,
                        onClick = {
                            onClearPrintResult?.invoke()
                            onClearWhatsAppResult?.invoke()
                            onClearEmailResult?.invoke()
                            onPrintReceipt?.invoke()
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

                    ReceiptOptionRow(
                        icon = Icons.Filled.Email,
                        title = "Enviar por correo",
                        onClick = {
                            if (paymentId != null && onSendEmail != null) {
                                onClearEmailResult?.invoke()
                                onClearWhatsAppResult?.invoke()
                                emailAddress = ""
                                showEmailInput = true
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

                    ReceiptOptionRow(
                        icon = Icons.Filled.Sms,
                        title = "Enviar por WhatsApp",
                        onClick = {
                            if (paymentId != null && onSendWhatsApp != null) {
                                onClearWhatsAppResult?.invoke()
                                onClearEmailResult?.invoke()
                                whatsAppPhone = ""
                                showWhatsAppInput = true
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

                    PrimaryButton(
                        text = "Venta nueva",
                        onClick = onDone,
                    )
                }
            }
        }
    }

    // WhatsApp phone input dialog
    if (showWhatsAppInput) {
        PhoneInputDialog(
            title = "Enviar recibo por WhatsApp",
            description = "Ingresa el numero de telefono del cliente",
            label = "Telefono",
            placeholder = "+52 10 digitos",
            value = whatsAppPhone,
            onValueChange = { whatsAppPhone = it.filter { c -> c.isDigit() || c == '+' } },
            isSending = isSendingWhatsApp,
            onConfirm = {
                val phone = whatsAppPhone.trim()
                if (phone.isNotEmpty()) {
                    onSendWhatsApp?.invoke(phone)
                    showWhatsAppInput = false
                }
            },
            onDismiss = { if (!isSendingWhatsApp) showWhatsAppInput = false },
            confirmEnabled = whatsAppPhone.trim().isNotEmpty() && !isSendingWhatsApp,
            dismissEnabled = !isSendingWhatsApp,
            keyboardType = KeyboardType.Phone,
        )
    }

    // Email input dialog
    if (showEmailInput) {
        PhoneInputDialog(
            title = "Enviar recibo por correo",
            description = "Ingresa el correo electronico del cliente",
            label = "Correo electronico",
            placeholder = "cliente@ejemplo.com",
            value = emailAddress,
            onValueChange = { emailAddress = it },
            isSending = isSendingEmail,
            onConfirm = {
                val email = emailAddress.trim()
                if (email.isNotEmpty()) {
                    onSendEmail?.invoke(email)
                    showEmailInput = false
                }
            },
            onDismiss = { if (!isSendingEmail) showEmailInput = false },
            confirmEnabled = emailAddress.trim().isNotEmpty() && !isSendingEmail,
            dismissEnabled = !isSendingEmail,
            keyboardType = KeyboardType.Email,
        )
    }
}

// MARK: - Reusable Input Dialog

@Composable
private fun PhoneInputDialog(
    title: String,
    description: String,
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean,
    dismissEnabled: Boolean,
    keyboardType: KeyboardType,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending,
                )
                if (isSending) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = "Enviando...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = dismissEnabled,
            ) {
                Text("Cancelar")
            }
        },
    )
}

// MARK: - Receipt Option Row (matching iOS)

@Composable
private fun ReceiptOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = contentColor,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
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
