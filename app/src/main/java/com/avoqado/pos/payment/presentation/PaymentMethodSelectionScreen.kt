package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.payment.data.model.PaymentMethod
import kotlin.math.ceil

/**
 * Combined payment method selection screen (matching iOS).
 * Shows big amount at center, bottom rows for Efectivo (with presets) and Cobrar con terminal.
 */
@Composable
fun PaymentMethodSelectionScreen(
    amountCents: Int,
    onMethodSelected: (PaymentMethod) -> Unit,
    onCashPresetSelected: ((Int) -> Unit)? = null,
    onCashCustomSelected: ((Int) -> Unit)? = null,
    onCancel: () -> Unit,
) {
    val suggestions = remember(amountCents) { calculateCashSuggestions(amountCents) }
    var showCustomCashSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Close button (top-right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.End,
        ) {
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
            text = "$${String.format("%.2f", amountCents / 100.0)}",
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

            // Cobrar con terminal row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMethodSelected(PaymentMethod.CARD) }
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cobrar con terminal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.weight(1f))

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
}

// MARK: - Custom Cash Bottom Sheet (matching iOS "Monto recibido")

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
                text = "$${String.format("%.2f", inputCents / 100.0)}",
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
                    text = "Cambio: $${String.format("%.2f", changeCents / 100.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Success,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                Text(
                    text = "Mínimo $${String.format("%.2f", totalCents / 100.0)}",
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
        "$${String.format("%.2f", pesos)}"
    }
}
