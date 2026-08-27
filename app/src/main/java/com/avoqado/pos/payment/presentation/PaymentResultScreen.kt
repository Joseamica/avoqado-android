package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.avoqado.pos.R
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoPhoneInput
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.payment.data.model.PaymentMethod

@Composable
fun PaymentResultScreen(
    totalCents: Int,
    method: PaymentMethod,
    /**
     * Etiqueta real cuando el cobro se registró a mano (terminal ajena,
     * transferencia). Sin esto la pantalla decía "Efectivo" en un cobro con
     * tarjeta — la misma mentira que descuadra el corte.
     */
    methodLabel: String? = null,
    changeCents: Int = 0,
    isQueued: Boolean = false,
    paymentId: String? = null,
    canSendReceipt: Boolean = paymentId != null,
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
    canPrintOnTerminal: Boolean = false,
    onPrintOnTerminalReceipt: (() -> Unit)? = null,
    onClearPrintResult: (() -> Unit)? = null,
    customerName: String? = null,
    customerResultMessage: String? = null,
    onClearCustomerResult: (() -> Unit)? = null,
    onAddCustomer: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    var showWhatsAppInput by remember { mutableStateOf(false) }
    var whatsAppPhoneDigits by remember { mutableStateOf("") }
    var whatsAppCountry by remember { mutableStateOf<Country>(Countries.byIso("MX")) }
    var showEmailInput by remember { mutableStateOf(false) }
    var emailAddress by remember { mutableStateOf("") }
    var showNoPrinterDialog by remember { mutableStateOf(false) }
    var showTerminalPrintDialog by remember { mutableStateOf(false) }

    // Combine result messages for display (customer/whatsApp/email/print)
    val visiblePrintResult = printResultMessage?.takeUnless { it == LOCAL_PRINTER_UNAVAILABLE }
    val resultMessage = customerResultMessage ?: whatsAppResultMessage ?: emailResultMessage ?: visiblePrintResult
    val isResultSuccess = resultMessage == "Recibo enviado por WhatsApp" ||
        resultMessage == "Recibo enviado por correo" ||
        resultMessage == "Recibo impreso" ||
        resultMessage == "Recibo impreso en TPV" ||
        resultMessage?.startsWith("Cliente agregado") == true

    androidx.compose.runtime.LaunchedEffect(printResultMessage, canPrintOnTerminal) {
        if (printResultMessage == LOCAL_PRINTER_UNAVAILABLE && canPrintOnTerminal) {
            showTerminalPrintDialog = true
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.xl,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Venta nueva",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable(onClick = onDone)
                        .padding(vertical = AvoqadoTheme.spacing.xs),
                )
                if (onAddCustomer != null) {
                    // Con cliente ya puesto esto es una ETIQUETA, no un botón:
                    // subrayarlo e invitar al toque hacía pensar que faltaba
                    // algo. Mismo criterio que iOS (PaymentResultViews.swift).
                    val hasCustomer = customerName != null
                    Text(
                        text = customerName ?: "Agregar cliente",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (hasCustomer) null else TextDecoration.Underline,
                        modifier = Modifier
                            .then(
                                if (hasCustomer) Modifier else Modifier.clickable(onClick = onAddCustomer),
                            )
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(vertical = AvoqadoTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                            text = "Se sincronizará cuando haya conexión",
                            style = MaterialTheme.typography.bodySmall,
                            color = Warning,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            Text(
                text = formatMoney(totalCents / 100.0),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = methodLabel ?: method.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (method == PaymentMethod.CASH && methodLabel == null && changeCents > 0) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Cambio: ${formatMoney(changeCents / 100.0)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Success,
                )
            }

            // Success receipt-send confirmation (errors are shown via AvoqadoErrorToast below)
            if (resultMessage != null && isResultSuccess) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Surface(
                    color = Success.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = resultMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Success,
                        modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
            ) {
                ReceiptOptionRow(
                    icon = rememberVectorPainter(Icons.Filled.Print),
                    title = if (isPrintingReceipt) "Imprimiendo..." else "Imprimir recibo",
                    enabled = !isPrintingReceipt,
                    onClick = {
                        if (onPrintReceipt != null) {
                            onClearCustomerResult?.invoke()
                            onClearPrintResult?.invoke()
                            onClearWhatsAppResult?.invoke()
                            onClearEmailResult?.invoke()
                            onPrintReceipt.invoke()
                        } else {
                            showNoPrinterDialog = true
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

                ReceiptOptionRow(
                    icon = rememberVectorPainter(Icons.Filled.Email),
                    title = "Enviar por correo",
                    enabled = canSendReceipt && onSendEmail != null,
                    onClick = {
                        onClearCustomerResult?.invoke()
                        onClearEmailResult?.invoke()
                        onClearWhatsAppResult?.invoke()
                        emailAddress = ""
                        showEmailInput = true
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

                ReceiptOptionRow(
                    icon = painterResource(R.drawable.ic_whatsapp),
                    title = "Enviar por WhatsApp",
                    enabled = canSendReceipt && onSendWhatsApp != null,
                    onClick = {
                        onClearCustomerResult?.invoke()
                        onClearWhatsAppResult?.invoke()
                        onClearEmailResult?.invoke()
                        whatsAppPhoneDigits = ""
                        showWhatsAppInput = true
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        }
    }

    // WhatsApp phone input dialog
    if (showWhatsAppInput) {
        WhatsAppReceiptDialog(
            title = "Enviar recibo por WhatsApp",
            description = "Selecciona el país y escribe el número del cliente",
            country = whatsAppCountry,
            onCountryChange = { whatsAppCountry = it },
            digits = whatsAppPhoneDigits,
            onDigitsChange = { whatsAppPhoneDigits = it },
            isSending = isSendingWhatsApp,
            onConfirm = {
                val digits = whatsAppPhoneDigits.trim()
                if (digits.isNotEmpty()) {
                    onSendWhatsApp?.invoke("+${whatsAppCountry.dialCode}$digits")
                    showWhatsAppInput = false
                }
            },
            onDismiss = { if (!isSendingWhatsApp) showWhatsAppInput = false },
            confirmEnabled = whatsAppPhoneDigits.trim().isNotEmpty() && !isSendingWhatsApp,
            dismissEnabled = !isSendingWhatsApp,
        )
    }

    // Email input dialog
    if (showEmailInput) {
        ReceiptInputDialog(
            title = "Enviar recibo por correo",
            description = "Ingresa el correo electronico del cliente",
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

    // Error feedback (printer/email/WhatsApp failures) — reusable red toast
    if (resultMessage != null && !isResultSuccess) {
        AvoqadoErrorToast(
            message = resultMessage,
            onDismiss = {
                onClearCustomerResult?.invoke()
                onClearPrintResult?.invoke()
                onClearEmailResult?.invoke()
                onClearWhatsAppResult?.invoke()
            },
        )
    }

    // No printer configured dialog
    if (showNoPrinterDialog) {
        AvoqadoDialog(
            title = "Sin impresora configurada",
            description = "No hay una impresora de recibos configurada. Ve a Configuración > Impresoras para conectar una.",
            onDismiss = { showNoPrinterDialog = false },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { showNoPrinterDialog = false },
                    fullWidth = true,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Print,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    if (showTerminalPrintDialog) {
        AvoqadoDialog(
            title = "Sin impresora local",
            description = "No hay una impresora de recibos disponible en este dispositivo.",
            onDismiss = {
                showTerminalPrintDialog = false
                onClearPrintResult?.invoke()
            },
            actionButton = {
                PrimaryButton(
                    text = if (isPrintingReceipt) "Imprimiendo..." else "Imprimir en TPV",
                    onClick = {
                        showTerminalPrintDialog = false
                        onClearPrintResult?.invoke()
                        onPrintOnTerminalReceipt?.invoke()
                    },
                    enabled = !isPrintingReceipt && onPrintOnTerminalReceipt != null,
                    fullWidth = true,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Print,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// MARK: - Reusable Input Dialogs

@Composable
private fun ReceiptInputDialog(
    title: String,
    description: String,
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
    AvoqadoDialog(
        title = title,
        description = description,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissEnabled,
        actionButton = {
            PrimaryButton(
                text = if (isSending) "Enviando..." else "Enviar",
                onClick = onConfirm,
                enabled = confirmEnabled,
                isLoading = isSending,
                fullWidth = true,
            )
        },
    ) {
        AvoqadoPillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = !isSending,
            keyboardType = keyboardType,
        )
    }
}

@Composable
private fun WhatsAppReceiptDialog(
    title: String,
    description: String,
    country: Country,
    onCountryChange: (Country) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    isSending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean,
    dismissEnabled: Boolean,
) {
    AvoqadoDialog(
        title = title,
        description = description,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissEnabled,
        actionButton = {
            PrimaryButton(
                text = if (isSending) "Enviando..." else "Enviar",
                onClick = onConfirm,
                enabled = confirmEnabled,
                isLoading = isSending,
                fullWidth = true,
            )
        },
    ) {
        AvoqadoPhoneInput(
            country = country,
            onCountryChange = onCountryChange,
            digits = digits,
            onDigitsChange = onDigitsChange,
            enabled = !isSending,
            placeholder = "Número",
        )
    }
}

// MARK: - Receipt Option Row (matching iOS)

@Composable
private fun ReceiptOptionRow(
    icon: Painter,
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
            painter = icon,
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
        AvoqadoBrandLoader(size = 96.dp)

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

// MARK: - Cobro no confirmado (ni éxito ni fracaso)

/**
 * 🔴 El tercer desenlace. Se muestra cuando NO se pudo determinar si la tarjeta se cobró:
 * fallo de transporte, plazo de espera vencido o server inalcanzable justo donde la terminal
 * ya pudo haber cobrado.
 *
 * Reglas de esta pantalla:
 *  - NO dice "Error": no consta que haya fallado (y decirlo produjo un doble cobro real).
 *  - La acción destacada es la SEGURA: volver a consultar, que jamás cobra.
 *  - "Cobrar de nuevo" existe —el negocio no se puede quedar parado— pero SÓLO tras una
 *    advertencia explícita del riesgo, confirmada por el cajero.
 */
@Composable
fun PaymentUndeterminedView(
    message: String,
    isChecking: Boolean,
    onRecheck: () -> Unit,
    onChargeAgain: () -> Unit,
    onCancel: () -> Unit,
    /** El cobro sin confirmar quedó de otra venta: confirmarlo no paga la actual. */
    fromPreviousSale: Boolean = false,
) {
    var showChargeAgainWarning by remember { mutableStateOf(false) }
    var showLeaveWarning by remember { mutableStateOf(false) }

    // 🔴 Sin esto, un "atrás" desmonta la pantalla y toda la ceremonia de la advertencia
    // desaparece en silencio. La llave vive en disco, así que salir NO pierde el cobro —
    // pero el cajero tiene que enterarse de que sigue sin confirmarse.
    androidx.activity.compose.BackHandler(enabled = !isChecking) {
        showLeaveWarning = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.HelpOutline,
            contentDescription = null,
            tint = Warning,
            modifier = Modifier.size(80.dp),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = if (fromPreviousSale) "Cobro anterior sin confirmar" else "Cobro sin confirmar",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        // Acción segura y destacada: sólo pregunta, nunca cobra.
        PrimaryButton(
            text = if (isChecking) "Consultando..." else "Volver a consultar",
            onClick = onRecheck,
            enabled = !isChecking,
            isLoading = isChecking,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        TextButton(
            onClick = { showChargeAgainWarning = true },
            enabled = !isChecking,
        ) {
            Text(
                text = if (fromPreviousSale) "Cobrar esta venta de todos modos" else "Cobrar de nuevo",
                color = if (isChecking) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        TextButton(onClick = onCancel, enabled = !isChecking) {
            Text("Cancelar")
        }
    }

    // La advertencia NO es decorativa: es lo único entre el cajero y un segundo cargo.
    if (showChargeAgainWarning) {
        AvoqadoDialog(
            title = if (fromPreviousSale) "¿Cobrar esta venta?" else "¿Cobrar de nuevo?",
            description = if (fromPreviousSale) {
                "El cobro anterior sigue sin confirmarse y dejará de avisarte. Si aquel cobro " +
                    "sí pasó y no lo verificaste, el cliente podría quedar cobrado dos veces. " +
                    "Revisa la terminal antes de continuar."
            } else {
                "Puede que la tarjeta YA se haya cobrado. Revisa la terminal " +
                    "y el estado del cobro antes de continuar: si el cobro anterior sí pasó, " +
                    "esto le cobrará al cliente por segunda vez."
            },
            onDismiss = { showChargeAgainWarning = false },
            actionButton = {
                PrimaryButton(
                    text = if (fromPreviousSale) "Sí, continuar" else "Sí, cobrar de nuevo",
                    onClick = {
                        showChargeAgainWarning = false
                        onChargeAgain()
                    },
                    fullWidth = true,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    // Salir está permitido (bloquear la caja sería peor), pero nunca en silencio: se dice
    // que el cobro sigue sin confirmarse y que va a volver a aparecer.
    if (showLeaveWarning) {
        AvoqadoDialog(
            title = "El cobro sigue sin confirmarse",
            description = "Si sales ahora, este cobro queda pendiente y te lo volveremos a " +
                "mostrar la próxima vez que cobres. Revisa la terminal para saber si pasó.",
            onDismiss = { showLeaveWarning = false },
            actionButton = {
                PrimaryButton(
                    text = "Volver a consultar",
                    onClick = {
                        showLeaveWarning = false
                        onRecheck()
                    },
                    fullWidth = true,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Filled.HelpOutline,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(24.dp),
            )
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
        AvoqadoBrandLoader(size = 72.dp)

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = "Preparando pago...",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
