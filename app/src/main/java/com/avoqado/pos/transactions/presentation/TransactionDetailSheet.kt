package com.avoqado.pos.transactions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.AvoqadoPhoneInput
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.designsystem.components.PrimaryButton
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionRefund

// MARK: - Responsive metrics
//
// The detail panel renders in two contexts: phone fullscreen (~360-400dp) and
// tablet split (~500-700dp). Hardcoded 28sp/18sp/64dp values looked enormous
// in both. Metrics scale based on the panel's actual width.

private data class DetailMetrics(
    val sectionTitleSize: TextUnit,
    val bodySize: TextUnit,
    val smallSize: TextUnit,
    val totalBoldSize: TextUnit,
    val actionButtonHeight: Dp,
    val actionButtonTextSize: TextUnit,
    val horizontalPadding: Dp,
    val iconSize: Dp,
    val iconRowVerticalPadding: Dp,
    val sectionBottomSpacing: Dp,
    val sectionTopSpacing: Dp,
)

private val CompactMetrics = DetailMetrics(
    sectionTitleSize = 18.sp,
    bodySize = 14.sp,
    smallSize = 12.sp,
    totalBoldSize = 16.sp,
    actionButtonHeight = 44.dp,
    actionButtonTextSize = 14.sp,
    horizontalPadding = 16.dp,
    iconSize = 20.dp,
    iconRowVerticalPadding = 10.dp,
    sectionBottomSpacing = 24.dp,
    sectionTopSpacing = 24.dp,
)

private val RegularMetrics = DetailMetrics(
    sectionTitleSize = 20.sp,
    bodySize = 15.sp,
    smallSize = 13.sp,
    totalBoldSize = 17.sp,
    actionButtonHeight = 48.dp,
    actionButtonTextSize = 15.sp,
    horizontalPadding = 24.dp,
    iconSize = 22.dp,
    iconRowVerticalPadding = 12.dp,
    sectionBottomSpacing = 28.dp,
    sectionTopSpacing = 28.dp,
)

private val SpaciousMetrics = DetailMetrics(
    sectionTitleSize = 24.sp,
    bodySize = 17.sp,
    smallSize = 15.sp,
    totalBoldSize = 19.sp,
    actionButtonHeight = 56.dp,
    actionButtonTextSize = 16.sp,
    horizontalPadding = 32.dp,
    iconSize = 26.dp,
    iconRowVerticalPadding = 16.dp,
    sectionBottomSpacing = 36.dp,
    sectionTopSpacing = 36.dp,
)

private val LocalDetailMetrics = compositionLocalOf { RegularMetrics }

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

    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        val metrics = when {
            maxWidth < 500.dp -> CompactMetrics
            maxWidth < 900.dp -> RegularMetrics
            else -> SpaciousMetrics
        }
        CompositionLocalProvider(LocalDetailMetrics provides metrics) {
            when {
                isLoadingDetail -> {
                    AvoqadoBrandLoader(
                        modifier = Modifier.align(Alignment.Center),
                        size = 72.dp,
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
    var showRefundSheet by remember { mutableStateOf(false) }
    var showEmailInput by remember { mutableStateOf(false) }
    var showWhatsAppInput by remember { mutableStateOf(false) }
    var emailAddress by remember { mutableStateOf("") }
    var whatsAppPhone by remember { mutableStateOf("") }
    var whatsAppCountry by remember { mutableStateOf<Country>(Countries.byIso("MX")) }
    var successToastChannel by remember { mutableStateOf<String?>(null) }

    val isSendingReceipt by viewModel.isSendingReceipt.collectAsState()
    val receiptResultMessage by viewModel.receiptResultMessage.collectAsState()
    val metrics = LocalDetailMetrics.current

    // Success → dismiss input dialog + show celebratory toast.
    androidx.compose.runtime.LaunchedEffect(receiptResultMessage) {
        val msg = receiptResultMessage ?: return@LaunchedEffect
        val channel = when (msg) {
            "Recibo enviado por correo" -> "correo"
            "Recibo enviado por WhatsApp" -> "WhatsApp"
            else -> null
        }
        if (channel != null) {
            showEmailInput = false
            showWhatsAppInput = false
            successToastChannel = channel
            viewModel.clearReceiptResult()
        }
    }

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
            fontSize = metrics.bodySize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (showBackButton) 0.dp else 20.dp, bottom = 10.dp),
        )

        // Cortesía badge: $0 payment with items that have value = fully comped order
        if (transaction.amount == 0.0 && transaction.items.any { it.amount > 0 }) {
            Text(
                text = "• Cortesía •",
                fontSize = metrics.smallSize,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF10B981),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
        }

        ThinDivider()

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.horizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(metrics.sectionTopSpacing))

            // Action buttons (text-only, tall, matching iOS)
            // TODO [HIGH RISK - DISABLED]: "Devolver o cambiar" button hidden.
            // See TransactionsScreen.kt for the full risk breakdown. Tapping this would
            // open the same UnassociatedRefundSheet flow which currently corrupts sales
            // reports, cash closeout, settlement, and available balance because the
            // backend aggregation queries don't handle Payments with negative amounts +
            // status='REFUNDED'. Re-enable only after the 5 server bugs are fixed.
            val isPrintingReceipt by viewModel.isPrintingReceipt.collectAsState()
            val printReceiptResult by viewModel.printReceiptResult.collectAsState()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Direct print — visible action, Square-style (not hidden in a dialog).
                ActionButton(
                    label = if (isPrintingReceipt) "Imprimiendo…" else "Imprimir",
                    modifier = Modifier.weight(1f),
                    enabled = !isPrintingReceipt,
                    onClick = { viewModel.printTransactionReceipt(transaction) },
                )
                ActionButton(
                    label = "Recibo nuevo",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.clearReceiptResult()
                        showReceiptMethodDialog = true
                    },
                )
                if (viewModel.roleManager.canIssueRefund) {
                    ActionButton(
                        label = "Emitir reembolso",
                        modifier = Modifier.weight(1f),
                        enabled = transaction.remainingRefundable > 0.001,
                        onClick = { showRefundSheet = true },
                    )
                }
            }

            // Print status
            if (isPrintingReceipt || printReceiptResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isPrintingReceipt) "Imprimiendo recibo…" else printReceiptResult.orEmpty(),
                    fontSize = metrics.smallSize,
                    color = when {
                        isPrintingReceipt -> MaterialTheme.colorScheme.onSurfaceVariant
                        printReceiptResult == "Recibo impreso" -> Color(0xFF10B981)
                        else -> MaterialTheme.colorScheme.error
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(metrics.sectionTopSpacing))

            if (transaction.refunds.isNotEmpty()) {
                RefundHistorySection(
                    refunds = transaction.refunds,
                    remainingRefundable = transaction.remainingRefundableDisplay,
                )
                Spacer(modifier = Modifier.height(metrics.sectionTopSpacing))
            }

            // MARK: - Pago Section
            PaymentSection(transaction)

            // MARK: - Artículos Section
            if (transaction.items.isNotEmpty()) {
                ItemsSection(transaction)
            }

            // MARK: - Total Section
            TotalSection(transaction)

            Spacer(modifier = Modifier.height(metrics.sectionTopSpacing))
        }
    }

    // Receipt method chooser dialog
    if (showReceiptMethodDialog) {
        AvoqadoDialog(
            title = "Enviar recibo",
            description = "Elige como enviar el recibo al cliente",
            onDismiss = { showReceiptMethodDialog = false },
        ) {
            PrimaryButton(
                text = "Correo",
                onClick = {
                    showReceiptMethodDialog = false
                    emailAddress = ""
                    showEmailInput = true
                },
                fullWidth = true,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            PrimaryButton(
                text = "WhatsApp",
                onClick = {
                    showReceiptMethodDialog = false
                    whatsAppPhone = ""
                    showWhatsAppInput = true
                },
                fullWidth = true,
            )
        }
    }

    // Email input dialog
    if (showEmailInput) {
        ReceiptInputDialog(
            title = "Enviar recibo por correo",
            description = "Ingresa el correo electrónico del cliente",
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

    // WhatsApp phone input dialog (with country selector)
    if (showWhatsAppInput) {
        PhoneReceiptDialog(
            country = whatsAppCountry,
            onCountryChange = { whatsAppCountry = it },
            digits = whatsAppPhone,
            onDigitsChange = { whatsAppPhone = it },
            isSending = isSendingReceipt,
            resultMessage = receiptResultMessage,
            onConfirm = {
                val digits = whatsAppPhone.trim()
                if (digits.isNotEmpty()) {
                    viewModel.sendReceiptWhatsApp(transaction.id, "+${whatsAppCountry.dialCode}$digits")
                }
            },
            onDismiss = {
                if (!isSendingReceipt) {
                    showWhatsAppInput = false
                    viewModel.clearReceiptResult()
                }
            },
            dismissEnabled = !isSendingReceipt,
        )
    }

    // Refund sheet (Square-style: items or amount)
    if (showRefundSheet) {
        IssueRefundSheet(
            transaction = transaction,
            maxRefundable = transaction.remainingRefundable,
            refundRepository = viewModel.refundRepository,
            cashDrawerRepository = viewModel.cashDrawerRepository,
            onDismiss = { showRefundSheet = false },
            onRefunded = {
                showRefundSheet = false
                viewModel.refresh()
                viewModel.selectTransaction(transaction.id)
            },
        )
    }

    // Success celebration toast (auto-dismisses after ~1.6s)
    successToastChannel?.let { channel ->
        AvoqadoSuccessToast(
            message = "¡Recibo enviado!",
            subtitle = "Enviado por $channel",
            onDismiss = { successToastChannel = null },
        )
    }
}

// MARK: - Action Button (iOS style: text-only, tall, gray bg)

@Composable
private fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val metrics = LocalDetailMetrics.current
    Box(
        modifier = modifier
            .height(metrics.actionButtonHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = metrics.actionButtonTextSize,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefundHistorySection(
    refunds: List<TransactionRefund>,
    remainingRefundable: String,
) {
    val metrics = LocalDetailMetrics.current
    Column(modifier = Modifier.padding(bottom = metrics.sectionBottomSpacing)) {
        Text(
            text = "Reembolsos",
            fontSize = metrics.sectionTitleSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Disponible para reembolsar: $remainingRefundable",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        refunds.forEach { refund ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = refund.reasonDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = refund.formattedAmount,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                refund.createdAt?.let {
                    Text(
                        text = it.replace("T", " ").take(16),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

// MARK: - Pago Section

@Composable
private fun PaymentSection(transaction: Transaction) {
    val metrics = LocalDetailMetrics.current
    Column(modifier = Modifier.padding(bottom = metrics.sectionBottomSpacing)) {
        // Section header with date
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Pago",
                fontSize = metrics.sectionTitleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            transaction.dateShortDisplay?.let {
                Text(
                    text = it,
                    fontSize = metrics.smallSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
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
    val metrics = LocalDetailMetrics.current
    // Courtesy heuristic: $0 payment with items that had value = fully comped.
    val isCourtesyTransaction = transaction.amount == 0.0 && transaction.items.any { it.amount > 0 }
    Column(modifier = Modifier.padding(bottom = metrics.sectionBottomSpacing)) {
        Text(
            text = "Artículos",
            fontSize = metrics.sectionTitleSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Items
        transaction.items.forEach { item ->
            val isItemComped = isCourtesyTransaction && item.amount > 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Product image
                item.productImageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }

                // Name + modifiers
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.productName,
                            fontSize = metrics.bodySize,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isItemComped) {
                            Text(
                                text = "Cortesía",
                                fontSize = metrics.smallSize,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF10B981),
                                modifier = Modifier
                                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (item.modifiers.isNotEmpty()) {
                        Text(
                            text = item.modifiers.joinToString(", ") { it.name },
                            fontSize = metrics.smallSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Price
                if (isItemComped) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = item.formattedTotal,
                            fontSize = metrics.smallSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        Text(
                            text = "$0.00",
                            fontSize = metrics.bodySize,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF10B981),
                        )
                    }
                } else {
                    Text(
                        text = item.formattedTotal,
                        fontSize = metrics.bodySize,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            ThinDivider()
        }
    }
}

// MARK: - Total Section

@Composable
private fun TotalSection(transaction: Transaction) {
    val metrics = LocalDetailMetrics.current
    Column {
        Text(
            text = "Total",
            fontSize = metrics.sectionTitleSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(20.dp))
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
    val metrics = LocalDetailMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = metrics.iconRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(metrics.iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            fontSize = metrics.bodySize,
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
    val metrics = LocalDetailMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontSize = if (isBold) metrics.totalBoldSize else metrics.bodySize,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = if (isBold) metrics.totalBoldSize else metrics.bodySize,
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

    AvoqadoDialog(
        title = title,
        description = description,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissEnabled,
        actionButton = {
            PrimaryButton(
                text = when {
                    isSuccess -> "Cerrar"
                    isSending -> "Enviando..."
                    else -> "Enviar"
                },
                onClick = if (isSuccess) onDismiss else onConfirm,
                enabled = isSuccess || confirmEnabled,
                isLoading = isSending,
                fullWidth = true,
            )
        },
    ) {
        AvoqadoPillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = !isSending && resultMessage == null,
            keyboardType = keyboardType,
        )

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
}

@Composable
private fun PhoneReceiptDialog(
    country: Country,
    onCountryChange: (Country) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    isSending: Boolean,
    resultMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean,
) {
    val isSuccess = resultMessage == "Recibo enviado por WhatsApp"
    val confirmEnabled = digits.trim().isNotEmpty() && !isSending && resultMessage == null

    AvoqadoDialog(
        title = "Enviar recibo por WhatsApp",
        description = "Selecciona el país y escribe el número",
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissEnabled,
        actionButton = {
            PrimaryButton(
                text = when {
                    isSuccess -> "Cerrar"
                    isSending -> "Enviando..."
                    else -> "Enviar"
                },
                onClick = if (isSuccess) onDismiss else onConfirm,
                enabled = isSuccess || confirmEnabled,
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
            enabled = !isSending && resultMessage == null,
            placeholder = "Número",
        )

        if (resultMessage != null && !isSuccess) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = resultMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
