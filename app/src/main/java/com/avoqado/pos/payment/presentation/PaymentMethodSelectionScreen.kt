package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentItem
import com.avoqado.pos.payment.data.model.PaymentMethod
import kotlin.math.ceil

/**
 * Combined payment method selection screen (matching iOS).
 * Shows big amount at center, bottom rows for Efectivo (with presets) and Cobrar con terminal.
 */
@Composable
fun PaymentMethodSelectionScreen(
    paymentContext: PaymentContext,
    onMethodSelected: (PaymentMethod) -> Unit,
    onCashPresetSelected: ((Int) -> Unit)? = null,
    onCashCustomSelected: ((Int) -> Unit)? = null,
    onCancel: () -> Unit,
    terminalsUnavailable: Boolean = false,
    onRetryTerminals: () -> Unit = {},
    /** Mesas (Square): link "Dividir importe" arriba — null lo oculta (retail). */
    onSplitImporte: (() -> Unit)? = null,
    /** Cobro que NO pasó por Avoqado (terminal ajena, transferencia). */
    onManualMethodSelected: ((com.avoqado.pos.payment.domain.ManualPaymentChoice) -> Unit)? = null,
    /** Tipos de pago del catálogo del negocio, ya cacheados (vacío = sólo los fijos). */
    tenderTypes: List<com.avoqado.pos.payment.domain.TenderTypeOption> = emptyList(),
) {
    val amountCents = paymentContext.totalCents
    val suggestions = remember(amountCents) { calculateCashSuggestions(amountCents) }
    var showCustomCashSheet by remember { mutableStateOf(false) }
    var showItemsSheet by remember { mutableStateOf(false) }
    var showManualMethodSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Close button (top-right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSplitImporte != null) {
                Text(
                    text = "Dividir importe",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onSplitImporte),
                )
            } else {
                Spacer(modifier = Modifier.size(1.dp))
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Big amount centered
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = formatMoney(amountCents / 100.0),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "Envía el cobro a una terminal para procesar el pago",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

        PaymentBreakdownCard(
            paymentContext = paymentContext,
            onViewProducts = if (paymentContext.items.isNotEmpty()) {
                { showItemsSheet = true }
            } else {
                null
            },
        )

        Spacer(modifier = Modifier.weight(1f))

        // Bottom payment options (centered, max width like iOS)
        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(bottom = AvoqadoTheme.spacing.xxxl)
                .align(Alignment.CenterHorizontally),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Efectivo row: label + suggested amounts + Personalizado
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Efectivo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    suggestions.forEach { amountSuggestion ->
                        Text(
                            text = formatCompactAmount(amountSuggestion),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                                .clickable {
                                    if (onCashPresetSelected != null) {
                                        onCashPresetSelected(amountSuggestion)
                                    } else {
                                        onMethodSelected(PaymentMethod.CASH)
                                    }
                                }
                                .padding(
                                    horizontal = AvoqadoTheme.spacing.sm,
                                    vertical = AvoqadoTheme.spacing.xs,
                                ),
                        )
                    }

                    Text(
                        text = "Personalizado",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                            .clickable {
                                if (onCashCustomSelected != null) {
                                    showCustomCashSheet = true
                                } else {
                                    onMethodSelected(PaymentMethod.CASH)
                                }
                            }
                            .padding(
                                horizontal = AvoqadoTheme.spacing.sm,
                                vertical = AvoqadoTheme.spacing.xs,
                            ),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Cobrar con terminal row — disabled up-front when no terminals are
            // online (don't let the operator walk through tip+rating toward a
            // send that can't happen).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (terminalsUnavailable) Modifier
                        else Modifier.clickable { onMethodSelected(PaymentMethod.CARD) },
                    )
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cobrar con terminal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (terminalsUnavailable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (terminalsUnavailable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "No hay terminales conectadas · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Reintentar",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onRetryTerminals() },
                            )
                        }
                    }
                }


                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // "Ya me pagó de otra forma": el dinero NO pasó por Avoqado.
            // Antes el mesero sólo podía marcarlo como efectivo, y el corte
            // terminaba pidiendo dinero que nunca entró al cajón.
            if (onManualMethodSelected != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManualMethodSheet = true }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ya pagó de otra forma",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Tarjeta de otra terminal, transferencia…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (showManualMethodSheet && onManualMethodSelected != null) {
        ManualMethodSheet(
            amountCents = amountCents,
            tenderTypes = tenderTypes,
            onSelect = { method ->
                showManualMethodSheet = false
                onManualMethodSelected(method)
            },
            onDismiss = { showManualMethodSheet = false },
        )
    }

    // Custom cash amount bottom sheet
    if (showCustomCashSheet) {
        CustomCashSheet(
            totalCents = amountCents,
            onConfirm = { amount ->
                showCustomCashSheet = false
                onCashCustomSelected?.invoke(amount)
            },
            onDismiss = { showCustomCashSheet = false },
        )
    }

    if (showItemsSheet) {
        PaymentItemsSheet(
            paymentContext = paymentContext,
            onDismiss = { showItemsSheet = false },
        )
    }
}

// MARK: - Custom Cash Bottom Sheet (matching iOS "Monto recibido")

/**
 * Registrar un cobro que NO pasó por Avoqado. Sin teclado ni cambio: el monto
 * es exactamente el total, así que un toque cierra la venta.
 *
 * NO pregunta débito o crédito a propósito — el mesero no siempre lo sabe y un
 * dato inventado es peor que uno genérico.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualMethodSheet(
    amountCents: Int,
    tenderTypes: List<com.avoqado.pos.payment.domain.TenderTypeOption>,
    onSelect: (com.avoqado.pos.payment.domain.ManualPaymentChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Text(
                text = "¿Cómo pagó el cliente?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Se registra ${formatAmount(amountCents)} como cobrado. No entra al corte de efectivo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
            )
            // Los tipos que el negocio dio de alta van PRIMERO: son los que su gente
            // usa de verdad ("Uber Eats", "Terminal BBVA"). Las 3 opciones genéricas
            // se quedan abajo para quien no configuró ninguna.
            tenderTypes
                .sortedWith(compareBy({ it.posSection != "PRIMARY" }, { it.displayOrder }, { it.name }))
                .forEach { tender ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(com.avoqado.pos.payment.domain.ManualPaymentChoice.Tender(tender)) }
                            .padding(AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tender.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (tender.captureTip) "Tipo de pago del negocio" else "Tipo de pago del negocio · sin propina",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

            com.avoqado.pos.payment.domain.ManualPaymentMethod.entries.forEach { method ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(method)) }
                        .padding(AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = method.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = method.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomCashSheet(
    totalCents: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inputCents by remember { mutableIntStateOf(0) }
    val isValid = inputCents >= totalCents
    val changeCents = if (isValid) inputCents - totalCents else 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxl),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Monto recibido",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Amount display
            Text(
                text = formatMoney(inputCents / 100.0),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = if (inputCents == 0) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))

            // Minimum / Change hint
            if (isValid && changeCents > 0) {
                Text(
                    text = "Cambio: ${formatMoney(changeCents / 100.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Success,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                Text(
                    text = "Mínimo ${formatMoney(totalCents / 100.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Keypad with decimal
            val buttons = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(".", "0", "\u232B"),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                    )
                    .background(MaterialTheme.colorScheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                buttons.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        row.forEach { label ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        when (label) {
                                            "\u232B" -> inputCents /= 10
                                            "." -> { /* decimal handled by cents input */ }
                                            else -> {
                                                val digit = label.toIntOrNull() ?: return@clickable
                                                if (inputCents < 10_000_000) {
                                                    inputCents = inputCents * 10 + digit
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Confirmar button
            androidx.compose.material3.Button(
                onClick = { onConfirm(inputCents) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = isValid,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    text = "Confirmar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PaymentBreakdownCard(
    paymentContext: PaymentContext,
    onViewProducts: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.xl))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
            ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Text(
                text = "Desglose",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SummaryRow("Subtotal", paymentContext.subtotalCents)

            if (paymentContext.discountCents > 0) {
                SummaryRow(
                    "Descuento",
                    -paymentContext.discountCents,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (paymentContext.taxCents > 0) {
                SummaryRow("Impuestos", paymentContext.taxCents)
            }

            if (paymentContext.tipCents > 0) {
                SummaryRow(
                    "Propina",
                    paymentContext.tipCents,
                    valueColor = Success,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryRow(
                "Total",
                paymentContext.totalCents,
                labelStyle = MaterialTheme.typography.titleMedium,
                valueStyle = MaterialTheme.typography.titleMedium,
            )
        }

        if (onViewProducts != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onViewProducts)
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.widthIn(min = AvoqadoTheme.spacing.sm))
                Text(
                    text = "Ver productos (${paymentContext.items.sumOf { it.quantity }})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    cents: Int,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatSignedAmount(cents),
            style = valueStyle,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentItemsSheet(
    paymentContext: PaymentContext,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AvoqadoTheme.spacing.xl),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs)) {
                    Text(
                        text = "Productos",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (paymentContext.splitType) {
                            "CUSTOMAMOUNT", "EQUALPARTS" -> "Pago parcial: los productos se muestran como referencia."
                            else -> "Detalle de lo que se va a cobrar."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.xs,
                ),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                items(paymentContext.items) { item ->
                    PaymentItemCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun PaymentItemCard(item: PaymentItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )
            .padding(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${item.quantity} x ${formatAmount(item.unitPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatAmount(item.lineTotal),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (item.modifiers.isNotEmpty()) {
            Text(
                text = item.modifiers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!item.note.isNullOrBlank()) {
            Text(
                text = "Nota: ${item.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (item.isCortesia) {
            Text(
                text = "Cortesía",
                style = MaterialTheme.typography.labelLarge,
                color = Success,
                textDecoration = TextDecoration.LineThrough,
            )
        }
    }
}

private fun formatAmount(cents: Int): String {
    return formatMoney(cents / 100.0)
}

private fun formatSignedAmount(cents: Int): String {
    val prefix = if (cents < 0) "-" else ""
    return prefix + formatAmount(kotlin.math.abs(cents))
}

// MARK: - Cash Suggestions (matching iOS algorithm)

/**
 * Calculate 2 suggested cash amounts based on the total:
 * 1. Round up by range: <$100 → nearest $5, <$500 → nearest $50, else → nearest $100
 * 2. Find next common bill multiples: $20, $50, $100, $200, $500, $1000
 */
private fun calculateCashSuggestions(totalCents: Int): List<Int> {
    val suggestions = mutableListOf<Int>()
    val totalPesos = totalCents / 100.0

    // Stage 1: Round-up by amount range
    val rounded = when {
        totalPesos < 100 -> ceil(totalPesos / 5.0) * 5.0
        totalPesos < 500 -> ceil(totalPesos / 50.0) * 50.0
        else -> ceil(totalPesos / 100.0) * 100.0
    }
    val roundedCents = (rounded * 100).toInt()
    if (roundedCents > totalCents) {
        suggestions.add(roundedCents)
    }

    // Stage 2: Find bill multiples
    val commonBills = listOf(20, 50, 100, 200, 500, 1000)
    for (bill in commonBills) {
        val multiplier = ceil(totalPesos / bill.toDouble())
        val billAmount = (multiplier * bill * 100).toInt()
        if (billAmount >= totalCents && !suggestions.contains(billAmount)) {
            suggestions.add(billAmount)
        }
        if (suggestions.size >= 2) break
    }

    return suggestions.distinct().sorted().take(2)
}

/** Format amount compactly: $250 (no decimals) or $250.50 (with decimals) */
private fun formatCompactAmount(cents: Int): String {
    val pesos = cents / 100.0
    return if (cents % 100 == 0) {
        "$${(cents / 100)}"
    } else {
        formatMoney(pesos)
    }
}
