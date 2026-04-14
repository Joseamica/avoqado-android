package com.avoqado.pos.transactions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.model.Transaction

// MARK: - Transaction Detail Panel (matching iOS TransactionDetailView)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailPanel(
    viewModel: TransactionsViewModel,
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val transaction by viewModel.selectedTransaction.collectAsState()
    val isLoadingDetail by viewModel.isLoadingDetail.collectAsState()

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        when {
            isLoadingDetail -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            transaction != null -> {
                LoadedDetailView(
                    transaction = transaction!!,
                    showBackButton = showBackButton,
                    onBack = { viewModel.clearSelection() },
                    viewModel = viewModel,
                )
            }
            else -> {
                // Empty state (iPad right panel)
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Selecciona una transacción",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Refund bottom sheet disabled — see TODO above for risk details.
}

@Composable
private fun LoadedDetailView(
    transaction: Transaction,
    showBackButton: Boolean,
    onBack: () -> Unit,
    viewModel: TransactionsViewModel,
) {
    // Receipt dialog states
    var showReceiptMethodDialog by remember { mutableStateOf(false) }
    var showEmailInput by remember { mutableStateOf(false) }
    var showWhatsAppInput by remember { mutableStateOf(false) }
    var emailAddress by remember { mutableStateOf("") }
    var whatsAppPhone by remember { mutableStateOf("") }

    val isSendingReceipt by viewModel.isSendingReceipt.collectAsState()
    val receiptResultMessage by viewModel.receiptResultMessage.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Back button (phone only)
        if (showBackButton) {
            CircleBackButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }

        // Fixed header
        Text(
            text = "Venta de ${transaction.totalDisplay}",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (showBackButton) 0.dp else 24.dp, bottom = 12.dp),
        )

        ThinDivider()

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 56.dp),
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Action buttons (text-only, tall, matching iOS)
            // TODO [HIGH RISK - DISABLED]: "Devolver o cambiar" button hidden.
            // See TransactionsScreen.kt for the full risk breakdown. Tapping this would
            // open the same UnassociatedRefundSheet flow which currently corrupts sales
            // reports, cash closeout, settlement, and available balance because the
            // backend aggregation queries don't handle Payments with negative amounts +
            // status='REFUNDED'. Re-enable only after the 5 server bugs are fixed.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ActionButton(
                    label = "Recibo nuevo",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.clearReceiptResult()
                        showReceiptMethodDialog = true
                    },
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // MARK: - Pago Section
            PaymentSection(transaction)

            // MARK: - Artículos Section
            if (transaction.items.isNotEmpty()) {
                ItemsSection(transaction)
            }

            // MARK: - Total Section
            TotalSection(transaction)

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    // Receipt method chooser dialog
    if (showReceiptMethodDialog) {
        AlertDialog(
            onDismissRequest = { showReceiptMethodDialog = false },
            title = { Text("Enviar recibo") },
            text = {
                Text(
                    text = "Elige como enviar el recibo al cliente",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReceiptMethodDialog = false
                        emailAddress = ""
                        showEmailInput = true
                    },
                ) {
                    Text("Correo")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReceiptMethodDialog = false
                        whatsAppPhone = ""
                        showWhatsAppInput = true
                    },
                ) {
                    Text("WhatsApp")
                }
            },
        )
    }

    // Email input dialog
    if (showEmailInput) {
        ReceiptInputDialog(
            title = "Enviar recibo por correo",
            description = "Ingresa el correo electronico del cliente",
            label = "Correo electronico",
            placeholder = "cliente@ejemplo.com",
            value = emailAddress,
            onValueChange = { emailAddress = it },
            isSending = isSendingReceipt,
            resultMessage = receiptResultMessage,
            onConfirm = {
                val email = emailAddress.trim()
                if (email.isNotEmpty()) {
                    viewModel.sendReceiptEmail(transaction.id, email)
                }
            },
            onDismiss = {
                if (!isSendingReceipt) {
                    showEmailInput = false
                    viewModel.clearReceiptResult()
                }
            },
            confirmEnabled = emailAddress.trim().isNotEmpty() && !isSendingReceipt && receiptResultMessage == null,
            dismissEnabled = !isSendingReceipt,
            keyboardType = KeyboardType.Email,
        )
    }

    // WhatsApp phone input dialog
    if (showWhatsAppInput) {
        ReceiptInputDialog(
            title = "Enviar recibo por WhatsApp",
            description = "Ingresa el numero de telefono del cliente",
            label = "Telefono",
            placeholder = "+52 10 digitos",
            value = whatsAppPhone,
            onValueChange = { whatsAppPhone = it.filter { c -> c.isDigit() || c == '+' } },
            isSending = isSendingReceipt,
            resultMessage = receiptResultMessage,
            onConfirm = {
                val phone = whatsAppPhone.trim()
                if (phone.isNotEmpty()) {
                    viewModel.sendReceiptWhatsApp(transaction.id, phone)
                }
            },
            onDismiss = {
                if (!isSendingReceipt) {
                    showWhatsAppInput = false
                    viewModel.clearReceiptResult()
                }
            },
            confirmEnabled = whatsAppPhone.trim().isNotEmpty() && !isSendingReceipt && receiptResultMessage == null,
            dismissEnabled = !isSendingReceipt,
            keyboardType = KeyboardType.Phone,
        )
    }
}

// MARK: - Action Button (iOS style: text-only, tall, gray bg)

@Composable
private fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Pago Section

@Composable
private fun PaymentSection(transaction: Transaction) {
    Column(modifier = Modifier.padding(bottom = 44.dp)) {
        // Section header with date
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Pago",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            transaction.dateShortDisplay?.let {
                Text(
                    text = it,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        ThinDivider()

        // Payment method row
        val methodIcon = when (transaction.method) {
            "CASH" -> Icons.Filled.Payments
            else -> Icons.Filled.CreditCard
        }
        IconRow(
            icon = methodIcon,
            text = transaction.methodDescription,
        )
        ThinDivider()

        // Reference number
        transaction.referenceNumber?.let { ref ->
            if (ref.length >= 4) {
                val last4 = ref.takeLast(4).uppercase()
                IconRow(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    text = "Recibo #$last4",
                )
                ThinDivider()
            }
        }

        // Staff name
        transaction.staffName?.let { staff ->
            if (staff.isNotEmpty()) {
                IconRow(
                    icon = Icons.Filled.Person,
                    text = staff,
                )
                ThinDivider()
            }
        }
    }
}

// MARK: - Items Section

@Composable
private fun ItemsSection(transaction: Transaction) {
    Column(modifier = Modifier.padding(bottom = 44.dp)) {
        Text(
            text = "Artículos",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Gray sub-header
        Text(
            text = "Para comer aquí",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )

        // Items
        transaction.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Product image
                item.productImageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }

                // Name + modifiers
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.productName,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (item.modifiers.isNotEmpty()) {
                        Text(
                            text = item.modifiers.joinToString(", ") { it.name },
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Price
                Text(
                    text = item.formattedTotal,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            ThinDivider()
        }
    }
}

// MARK: - Total Section

@Composable
private fun TotalSection(transaction: Transaction) {
    Column {
        Text(
            text = "Total",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(24.dp))
        ThinDivider()

        TotalRow(label = "Subtotal", value = transaction.formattedAmount)
        ThinDivider()

        if (transaction.tipAmount > 0) {
            TotalRow(label = "Propina", value = transaction.formattedTip)
            ThinDivider()
        }

        TotalRow(label = "Total", value = transaction.totalDisplay, isBold = true)
        ThinDivider()
    }
}

// MARK: - Reusable Components

@Composable
private fun IconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String,
    isBold: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = label,
            fontSize = if (isBold) 20.sp else 18.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = if (isBold) 20.sp else 18.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(thickness = 0.5.dp)
}

// MARK: - Receipt Input Dialog

@Composable
private fun ReceiptInputDialog(
    title: String,
    description: String,
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    resultMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean,
    dismissEnabled: Boolean,
    keyboardType: KeyboardType,
) {
    val isSuccess = resultMessage == "Recibo enviado por correo" ||
        resultMessage == "Recibo enviado por WhatsApp"

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
                    enabled = !isSending && resultMessage == null,
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
                if (resultMessage != null) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(
                        text = resultMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (isSuccess) {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                ) {
                    Text("Enviar")
                }
            }
        },
        dismissButton = {
            if (!isSuccess) {
                TextButton(
                    onClick = onDismiss,
                    enabled = dismissEnabled,
                ) {
                    Text("Cancelar")
                }
            }
        },
    )
}
