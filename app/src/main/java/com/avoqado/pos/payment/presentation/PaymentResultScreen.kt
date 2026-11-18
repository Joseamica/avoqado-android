package com.avoqado.pos.payment.presentation

import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.input.key.Key
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

import com.avoqado.pos.designsystem.components.AtajoDeTeclado
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.TeclaDeAtajo
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.sinTeclasDeClic
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoWarningToast
import com.avoqado.pos.loyalty.domain.RenglonDelSello
import com.avoqado.pos.loyalty.domain.TextosDelSello
import com.avoqado.pos.designsystem.components.AvoqadoPhoneInput
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.CardChargeDecision

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
    /**
     * Lo que la cola dice HOY de este cobro. Sin esto el aviso «Se sincronizará…» se quedaba pegado
     * después de que la venta subió (30-sep-2026) y el cajero la volvía a cobrar.
     */
    sincronizacion: SincronizacionDelCobro =
        if (isQueued) SincronizacionDelCobro.PENDIENTE else SincronizacionDelCobro.NINGUNA,
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
    /**
     * D2 (founder, 28-oct): sin impresora de recibos la fila lo dice ANTES de tocarla («Sin impresora · Configurar») y al
     * tocarla ofrece imprimir en la terminal (si hay) y «Configurar impresora», que abre «Impresoras y cajón».
     */
    hayImpresoraDeRecibos: Boolean = true,
    onConfigurarImpresora: (() -> Unit)? = null,
    /** Se revisa al entrar a la pantalla: la impresora pudo agregarse o quitarse desde la venta anterior. */
    onRevisarImpresora: (() -> Unit)? = null,
    customerName: String? = null,
    customerResultMessage: String? = null,
    onClearCustomerResult: (() -> Unit)? = null,
    onAddCustomer: (() -> Unit)? = null,
    /** Sellos tardíos: qué pasó con el sello de esta venta. null = nada que decir. */
    renglonDelSello: RenglonDelSello? = null,
    /**
     * «Ofrecer recibo» (`showReceiptScreen`) de la copia del cobro. Apagado no se componen «Enviar por correo» ni «Enviar
     * por WhatsApp»; «Imprimir recibo» y «Venta nueva» no cambian, ni el ticket impreso. Igual que iOS (`PaymentSuccessView`).
     */
    offerReceipt: Boolean = true,
    onDone: () -> Unit,
    saldoPagado: com.avoqado.pos.giftcards.data.CobroConSaldo? = null,
    accionesDeRedHabilitadas: Boolean = true,
    /**
     * Venta de tarjetas de regalo ya cobrada: este recibo es sólo la constancia del pago y la tarjeta sale en el siguiente
     * paso (`onDone`). En vez de «Venta nueva», que no lo sugiere, va un botón que lo dice.
     */
    entregarTarjeta: Boolean = false,
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

    androidx.compose.runtime.LaunchedEffect(Unit) { onRevisarImpresora?.invoke() }

    androidx.compose.runtime.LaunchedEffect(printResultMessage, canPrintOnTerminal) {
        if (printResultMessage == LOCAL_PRINTER_UNAVAILABLE && canPrintOnTerminal) {
            showTerminalPrintDialog = true
        }
    }

    // POS de Windows (founder, 19-oct): Esc = «Venta nueva». Con un diálogo de recibo abierto, Esc es del diálogo.
    AtajoDeTeclado(Key.Escape) { onDone() }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!entregarTarjeta) {
                    Text(
                        text = "Venta nueva",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(onClick = onDone)
                            .padding(vertical = AvoqadoTheme.spacing.xs),
                    )
                    TeclaDeAtajo("Esc", Modifier.padding(start = AvoqadoTheme.spacing.sm))
                    }
                }
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
            if (!accionesDeRedHabilitadas) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED)

            // Cobro hecho sin red: lo que la cola dice de él AHORA, no lo que decía al cobrar.
            val avisoDeCola = when (sincronizacion) {
                SincronizacionDelCobro.NINGUNA -> null
                SincronizacionDelCobro.PENDIENTE ->
                    Triple(Icons.Filled.CloudOff, Warning, "Se sincronizará cuando haya conexión")
                SincronizacionDelCobro.SINCRONIZADA ->
                    Triple(Icons.Filled.CheckCircle, com.avoqado.pos.designsystem.theme.Success, "Venta sincronizada")
                SincronizacionDelCobro.RECHAZADA ->
                    Triple(
                        Icons.Filled.Error,
                        com.avoqado.pos.designsystem.theme.Error,
                        "No se pudo sincronizar esta venta. Revísala en Pendientes.",
                    )
            }
            if (avisoDeCola != null) {
                val (icono, color, texto) = avisoDeCola
                Surface(
                    color = color.copy(alpha = 0.15f),
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
                            icono,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
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

            saldoPagado?.let { saldo ->
                if (saldo.orderRemaining > 0) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.cobroConfirmadoFaltan(kotlin.math.round(saldo.orderRemaining * 100).toInt()))
                saldo.redeemed.forEach { tarjeta ->
                    Text("•••• ${tarjeta.last4} · ${com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SALDO_DISPONIBLE}: ${formatMoney(tarjeta.balanceAfter)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (method == PaymentMethod.CASH && methodLabel == null && changeCents > 0) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Cambio: ${formatMoney(changeCents / 100.0)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Success,
                )
            }

            if (entregarTarjeta) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                com.avoqado.pos.designsystem.components.PrimaryButton(
                    text = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.ENTREGAR_TARJETA,
                    onClick = onDone,
                    modifier = Modifier.widthIn(max = 400.dp),
                    fullWidth = true,
                    tecla = "Esc",
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

            // Renglón del sello (sellos tardíos). Va APARTE del aviso de arriba: «Imprimir» o «Enviar» lo reemplazan,
            // y el cajero tiene que seguir viendo si la venta sumó. Verde sólo si SÍ sumó; un motivo («Ya recibió su
            // sello de hoy») es información, no éxito.
            if (renglonDelSello != null) {
                Spacer(
                    modifier = Modifier.height(
                        if (resultMessage != null && isResultSuccess) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md,
                    ),
                )
                // Si SÍ sumó: la tarjetita (sellos llenos, los que faltan y el premio). Los motivos siguen en texto.
                val tarjeta = renglonDelSello.tarjeta
                if (tarjeta != null) {
                    TarjetaDeSellos(
                        tarjeta = tarjeta,
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth(),
                    )
                } else {
                    Surface(
                        color = if (renglonDelSello.positivo) Success.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = renglonDelSello.texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (renglonDelSello.positivo) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                        )
                    }
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
                    detalle = if (!hayImpresoraDeRecibos && !isPrintingReceipt) "Sin impresora · Configurar" else null,
                    enabled = !isPrintingReceipt,
                    onClick = {
                        if (!hayImpresoraDeRecibos && onConfigurarImpresora != null) {
                            showNoPrinterDialog = true
                        } else if (onPrintReceipt != null) {
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

                // «Ofrecer recibo» apagado: sin envío. Imprimir y «Venta nueva» siguen (igual que iOS).
                if (offerReceipt) {
                    ReceiptOptionRow(
                        icon = rememberVectorPainter(Icons.Filled.Email),
                        title = "Enviar por correo",
                        enabled = accionesDeRedHabilitadas && canSendReceipt && onSendEmail != null,
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
                        enabled = accionesDeRedHabilitadas && canSendReceipt && onSendWhatsApp != null,
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
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        }
    }

    // WhatsApp phone input dialog
    if (showWhatsAppInput) {
        WhatsAppReceiptDialog(
            title = "Enviar recibo por WhatsApp",
            description = if (!accionesDeRedHabilitadas) com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED else "Selecciona el país y escribe el número del cliente",
            country = whatsAppCountry,
            onCountryChange = { whatsAppCountry = it },
            digits = whatsAppPhoneDigits,
            onDigitsChange = { whatsAppPhoneDigits = it },
            isSending = isSendingWhatsApp,
            onConfirm = {
                val digits = whatsAppPhoneDigits.trim()
                if (accionesDeRedHabilitadas && digits.isNotEmpty()) {
                    onSendWhatsApp?.invoke("+${whatsAppCountry.dialCode}$digits")
                    if (cerrarDialogoDeRecibo(true)) showWhatsAppInput = false
                }
            },
            onDismiss = { if (!isSendingWhatsApp) showWhatsAppInput = false },
            confirmEnabled = accionesDeRedHabilitadas && whatsAppPhoneDigits.trim().isNotEmpty() && !isSendingWhatsApp,
            dismissEnabled = !isSendingWhatsApp,
        )
    }

    // Email input dialog
    if (showEmailInput) {
        ReceiptInputDialog(
            title = "Enviar recibo por correo",
            description = if (!accionesDeRedHabilitadas) com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED else "Ingresa el correo electronico del cliente",
            placeholder = "cliente@ejemplo.com",
            value = emailAddress,
            onValueChange = { emailAddress = it },
            isSending = isSendingEmail,
            onConfirm = {
                val email = emailAddress.trim()
                if (accionesDeRedHabilitadas && email.isNotEmpty()) {
                    onSendEmail?.invoke(email)
                    if (cerrarDialogoDeRecibo(true)) showEmailInput = false
                }
            },
            onDismiss = { if (!isSendingEmail) showEmailInput = false },
            confirmEnabled = accionesDeRedHabilitadas && emailAddress.trim().isNotEmpty() && !isSendingEmail,
            dismissEnabled = !isSendingEmail,
            keyboardType = KeyboardType.Email,
        )
    }

    // Sin red (o venta aún sin sincronizar) al agregar cliente: ÁMBAR, no rojo — es un estado, no una falla.
    val esAvisoSinRed = resultMessage == TextosDelSello.SIN_RED_AL_AGREGAR_CLIENTE ||
        resultMessage == TextosDelSello.VENTA_SIN_SINCRONIZAR
    if (resultMessage != null && esAvisoSinRed) {
        AvoqadoWarningToast(
            message = resultMessage,
            durationMs = 5_000L,
            onDismiss = { onClearCustomerResult?.invoke() },
        )
    }

    // Error feedback (printer/email/WhatsApp failures) — reusable red toast
    if (resultMessage != null && !isResultSuccess && !esAvisoSinRed) {
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
        val enTerminal = canPrintOnTerminal && onPrintOnTerminalReceipt != null
        AvoqadoDialog(
            title = "Sin impresora de recibos",
            description = if (enTerminal) {
                "Esta caja no tiene impresora de recibos. Imprímelo en la terminal o configura una impresora."
            } else {
                "Esta caja no tiene impresora de recibos. Configura una para imprimir tickets y abrir el cajón."
            },
            onDismiss = { showNoPrinterDialog = false },
            actionButton = {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    if (enTerminal) {
                        PrimaryButton(
                            text = if (isPrintingReceipt) "Imprimiendo..." else "Imprimir en la terminal",
                            onClick = {
                                showNoPrinterDialog = false
                                onClearPrintResult?.invoke()
                                onPrintOnTerminalReceipt?.invoke()
                            },
                            enabled = !isPrintingReceipt,
                            fullWidth = true,
                        )
                    }
                    if (onConfigurarImpresora != null) {
                        val configurar = {
                            showNoPrinterDialog = false
                            onConfigurarImpresora()
                        }
                        if (enTerminal) {
                            OutlinedButton(onClick = configurar, modifier = Modifier.fillMaxWidth()) { Text("Configurar impresora") }
                        } else {
                            PrimaryButton(text = "Configurar impresora", onClick = configurar, fullWidth = true)
                        }
                    } else {
                        PrimaryButton(text = "Entendido", onClick = { showNoPrinterDialog = false }, fullWidth = true)
                    }
                }
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
            description = if (!accionesDeRedHabilitadas) com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED else "No hay una impresora de recibos disponible en este dispositivo.",
            onDismiss = {
                showTerminalPrintDialog = false
                onClearPrintResult?.invoke()
            },
            actionButton = {
                PrimaryButton(
                    text = if (isPrintingReceipt) "Imprimiendo..." else "Imprimir en TPV",
                    onClick = {
                        if (!accionesDeRedHabilitadas) return@PrimaryButton
                        if (cerrarDialogoDeRecibo(true)) showTerminalPrintDialog = false
                        onClearPrintResult?.invoke()
                        onPrintOnTerminalReceipt?.invoke()
                    },
                    enabled = accionesDeRedHabilitadas && !isPrintingReceipt && onPrintOnTerminalReceipt != null,
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
    /** D2: a la derecha, en ámbar (p. ej. «Sin impresora · Configurar»). */
    detalle: String? = null,
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
        if (detalle != null) {
            Text(text = detalle, style = MaterialTheme.typography.bodySmall, color = Warning)
        }
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
    /** Sólo con el cobro ya en la terminal. Un efectivo o una orden que se registra no espera a ninguna (iOS: «Procesando…»). */
    esperandoTerminal: Boolean,
    onCancel: (() -> Unit)? = null,
    title: String? = null,
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
            text = title ?: "Procesando pago...",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        if (esperandoTerminal) {
            Text(
                text = "Esperando respuesta de la terminal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
    /**
     * Falta de red, no un fallo del cobro: se dice «Sin conexión» con el ícono de wifi, como el
     * selector de terminal. Sin red es un estado normal (regla «todo funciona sin red»), no un error.
     */
    sinRed: Boolean = false,
    /** D58: no se cobró y nadie rechazó (p. ej. se canceló en la terminal): «No se cobró» en gris, no «Error en el pago». */
    noSeCobro: Boolean = false,
    /**
     * D14 (hz:87f4b879:D14): la venta necesita a un gerente (su PIN o su sesión). Tampoco es una falla del cobro: gris, «Lo autoriza un
     * gerente». Con [retryLabel] `null` no hay nada que reintentar y la salida ([cancelLabel]) es el botón principal.
     */
    pideGerente: Boolean = false,
    /**
     * C1 (22-oct): «Revisar ese cobro» — la terminal (o la venta) la ocupa otro cobro y se puede consultar cómo quedó.
     * `null` ⇒ no se ofrece (cualquier otro error, o el servidor no dijo cuál es).
     */
    onRevisarCobro: (() -> Unit)? = null,
    retryLabel: String? = "Reintentar",
    cancelLabel: String = "Cancelar",
    titleLabel: String? = null,
    accionesDeRedHabilitadas: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!accionesDeRedHabilitadas) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED)
        Icon(
            when {
                sinRed -> Icons.Filled.WifiOff
                pideGerente -> Icons.Filled.Lock
                noSeCobro -> Icons.Filled.Info
                else -> Icons.Filled.Error
            },
            contentDescription = when {
                sinRed -> "Sin conexión"
                pideGerente -> "Lo autoriza un gerente"
                noSeCobro -> "No se cobró"
                else -> "Error"
            },
            tint = if (sinRed || pideGerente || noSeCobro) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(80.dp),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        Text(
            text = titleLabel ?: when {
                sinRed -> "Sin conexión"
                pideGerente -> "Lo autoriza un gerente"
                noSeCobro -> "No se cobró"
                else -> "Error en el pago"
            },
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        if (retryLabel != null) {
            PrimaryButton(
                text = retryLabel,
                onClick = onRetry,
                enabled = accionesDeRedHabilitadas,
                modifier = Modifier.sinTeclasDeClic(LocalEsEscritorio.current),
            )
        } else {
            // D14: sin nada que reintentar, la única salida es el botón principal (no cobra: puede responder al teclado).
            PrimaryButton(text = cancelLabel, onClick = onCancel)
        }

        // C1: la salida de «Terminal ocupada». Sólo consulta (nunca cobra): abre «Cobro sin confirmar» sobre el cobro
        // que ocupa, con «Volver a consultar» y «Ya revisé la terminal: no se cobró».
        if (onRevisarCobro != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            OutlinedButton(
                onClick = onRevisarCobro,
                // Acotado como el resto de la pantalla: en una tablet de 1920 px una píldora de lado a lado se pierde.
                modifier = Modifier.widthIn(max = 400.dp),
            ) {
                Text(CardChargeDecision.REVISAR_ESE_COBRO)
            }
        }

        if (retryLabel != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            TextButton(onClick = onCancel) {
                Text(cancelLabel)
            }
        }
    }
}

// MARK: - Cobro no confirmado (ni éxito ni fracaso)

/**
 * 🔴 F1 mínimo, tarea 6 (founder, 17-nov: «el sistema solo; lo que menos tenga que decidir el personal»; 18-nov: «que nunca trabe una
 * terminal»). La tarjeta en duda en el POS de ANDROID y de WINDOWS (mismas fuentes): el cajero o el mesero ve el importe del INTENTO
 * y una sola acción, [Seguir]. Ninguna decisión sobre el dinero: ni «No se cobró», ni «Cancelar la venta», ni cobrar otra vez. La duda
 * sigue guardada en el aparato, la consulta sigue sola mientras la pantalla está a la vista, y el gerente la revisa con su PIN en
 * «Operaciones por revisar» (`QuarantineSheet`). Los textos son los de la TPV (tarea 5).
 *
 * - [Seguir] nunca se apaga (tampoco con una consulta en vuelo) y sale UNA vez: dos toques, Esc (Windows) y Atrás son la misma
 *   salida. Salir no cancela, no cierra la cuenta y no reenvía el cobro.
 *
 * @param montoDelCobro el importe del intento guardado (importe + propina), ya formateado; `null` = no consta («Importe por
 *   confirmar» mientras se le pregunta a Avoqado por su ID). 🔴 Nunca el del carrito.
 * @param confirmando con red: la consulta automática sigue (K19a).
 * @param sinRed sin red en esta tablet: se dice con el texto de siempre, sin rojo.
 */
@Composable
fun TarjetaPorConfirmarView(
    montoDelCobro: String?,
    confirmando: Boolean,
    sinRed: Boolean,
    onSeguir: () -> Unit,
) {
    var salio by remember { mutableStateOf(false) }
    val seguir = { if (!salio) { salio = true; onSeguir() } }
    androidx.activity.compose.BackHandler { seguir() }
    AtajoDeTeclado(Key.Escape) { seguir() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (sinRed) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED)
        Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Warning, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        Text(
            text = CancelacionDeCobro.TITULO_TARJETA_POR_CONFIRMAR,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        if (montoDelCobro == null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = CancelacionDeCobro.IMPORTE_POR_CONFIRMAR,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = CancelacionDeCobro.tarjetaPorConfirmar(montoDelCobro),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )
        if (confirmando && !sinRed) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = CancelacionDeCobro.CONFIRMANDO_CON_EL_BANCO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        Box(modifier = Modifier.widthIn(max = 320.dp)) {
            PrimaryButton(text = CancelacionDeCobro.BOTON_SEGUIR, onClick = seguir, fullWidth = true, tecla = "Esc")
        }
    }
}

/**
 * El cambio de talla («Cambio», IQ) cuyo envío quedó sin confirmar. No es un cobro de terminal que declarar: sólo «Confirmar estado»
 * (pregunta, nunca cobra) y «Salir (queda pendiente)». F1 le quitó todo lo que era del cobro de la venta (declarar, cancelar).
 */
@Composable
fun PaymentUndeterminedView(
    message: String,
    isChecking: Boolean,
    onRecheck: () -> Unit,
    /** «Salir (queda pendiente)»: se sale y NO se borra nada. */
    onSalir: () -> Unit,
    recheckLabel: String? = null,
) {
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
        Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Warning, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        Text(text = "Cobro sin confirmar", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        // Acción segura: sólo pregunta, nunca cobra.
        PrimaryButton(
            text = if (isChecking) "Consultando..." else recheckLabel ?: "Volver a consultar",
            onClick = onRecheck,
            enabled = !isChecking,
            isLoading = isChecking,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        TextButton(onClick = onSalir, enabled = !isChecking) {
            Text(CancelacionDeCobro.BOTON_SALIR_PENDIENTE, textAlign = TextAlign.Center)
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

// MARK: - Cancelación del cobro (§C.4)

/**
 * «Cancelando el cobro…»: la intención ya está guardada en el aparato y va camino a la terminal.
 *
 * Lleva salida a propósito: si la terminal tarda, el cajero no se puede quedar atrapado mirando un
 * loader con fila enfrente — la cancelación sigue sola aunque él se vaya.
 */
@Composable
fun CancelandoCobroView(onSalir: () -> Unit) {
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
            text = CancelacionDeCobro.TEXTO_CANCELANDO,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        TextButton(onClick = onSalir) {
            Text(CancelacionDeCobro.BOTON_SALIR_PENDIENTE)
        }
    }
}

/**
 * 🔴 «Cancelación pendiente» — ÁMBAR, nunca rojo: no falló nada, falta que CONSTE.
 *
 * La venta se cancela sola en cuanto el desenlace llegue (la intención vive en disco y se reproduce sola), así que salir de aquí no
 * pierde nada. 🔴 F1 (tarea 6): el cajero ya no declara «no se cobró» desde aquí; si la terminal ya se soltó y no consta, la revisa el
 * gerente con su PIN en «Operaciones por revisar». [Seguir] es la salida de siempre (`salirDejandoPendiente`): nunca se apaga y sale
 * UNA vez (dos toques, Esc o Atrás).
 */
@Composable
fun CancelacionPendienteView(
    sinRed: Boolean,
    isChecking: Boolean,
    onVolverAConsultar: () -> Unit,
    onSalir: () -> Unit,
    accionesDeRedHabilitadas: Boolean = true,
    /** D6: la terminal ya se soltó y la venta sigue en duda. Sólo cambia las palabras. */
    terminalLibre: Boolean = false,
) {
    var salio by remember { mutableStateOf(false) }
    val seguir = { if (!salio) { salio = true; onSalir() } }
    androidx.activity.compose.BackHandler { seguir() }
    AtajoDeTeclado(Key.Escape) { seguir() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!accionesDeRedHabilitadas) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED)
        Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Warning, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        Text(text = CancelacionDeCobro.TITULO_PENDIENTE, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = when {
                sinRed -> CancelacionDeCobro.CUERPO_SIN_RED
                terminalLibre -> CancelacionDeCobro.CUERPO_TERMINAL_LIBRE_PISO
                else -> CancelacionDeCobro.CUERPO_PENDIENTE
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        Box(modifier = Modifier.widthIn(max = 320.dp)) {
            PrimaryButton(text = CancelacionDeCobro.BOTON_SEGUIR, onClick = seguir, fullWidth = true, tecla = "Esc")
        }
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        // Sólo pregunta, nunca cobra ni borra.
        TextButton(onClick = onVolverAConsultar, enabled = accionesDeRedHabilitadas && !isChecking) {
            Text(if (isChecking) "Consultando…" else CancelacionDeCobro.BOTON_VOLVER_A_CONSULTAR, textAlign = TextAlign.Center)
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

internal fun controlDeReciboDeTarjeta(cuenta: Boolean, conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = disponible && (!cuenta || conectado && servidor)
internal fun cerrarDialogoDeRecibo(peticionDespachada: Boolean): Boolean = peticionDespachada
