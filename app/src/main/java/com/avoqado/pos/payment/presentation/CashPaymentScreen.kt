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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

/**
 * Cash payment screen with preset amount buttons (matching iOS).
 * Uses common Mexican bill denominations for smart rounding.
 */
@Composable
fun CashPaymentScreen(
    totalCents: Int,
    onCashReceived: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedAmountCents by remember { mutableIntStateOf(-1) }
    val presets = remember(totalCents) { calculateCashPresets(totalCents) }
    val changeCents = if (selectedAmountCents >= 0) selectedAmountCents - totalCents else 0
    val isValid = selectedAmountCents >= totalCents

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Pago en efectivo",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = "Total: ${formatMoney(totalCents / 100.0)}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        // Preset amounts grid (2 columns, matching iOS)
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            presets.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    row.forEach { preset ->
                        CashPresetButton(
                            label = preset.label,
                            isExact = preset.isExact,
                            isSelected = selectedAmountCents == preset.amountCents,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedAmountCents = preset.amountCents },
                        )
                    }
                    // Fill remaining space if odd number
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Change display
        if (isValid && changeCents > 0) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
            Text(
                text = "Cambio: ${formatMoney(changeCents / 100.0)}",
                style = MaterialTheme.typography.headlineMedium,
                color = Success,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

        PrimaryButton(
            text = "Confirmar pago en efectivo",
            onClick = { onCashReceived(selectedAmountCents) },
            enabled = isValid,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        TextButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

// MARK: - Cash Preset Button (matching iOS: green when selected with checkmark)

@Composable
private fun CashPresetButton(
    label: String,
    isExact: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) Success else Color.Transparent
    val borderColor = if (isSelected) Success else MaterialTheme.colorScheme.outline
    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    textAlign = TextAlign.Center,
                )
                if (isExact) {
                    Text(
                        text = "Exacto",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// MARK: - Preset Calculation (matching iOS: Mexican bill rounding)

private data class CashPreset(
    val amountCents: Int,
    val label: String,
    val isExact: Boolean = false,
)

private fun calculateCashPresets(totalCents: Int): List<CashPreset> {
    val presets = mutableListOf<CashPreset>()
    val totalPesos = totalCents / 100.0

    // Always include exact amount
    presets.add(
        CashPreset(
            amountCents = totalCents,
            label = formatMoney(totalPesos),
            isExact = true,
        ),
    )

    // Mexican bill denominations
    val bills = listOf(20, 50, 100, 200, 500, 1000)

    // Add smart rounding amounts based on total
    bills.forEach { bill ->
        val billCents = bill * 100
        if (billCents > totalCents) {
            // Round up to next bill amount
            val roundedUp = ((totalCents + billCents - 1) / billCents) * billCents
            if (!presets.any { it.amountCents == roundedUp }) {
                presets.add(
                    CashPreset(
                        amountCents = roundedUp,
                        label = formatMoney(roundedUp / 100.0),
                    ),
                )
            }
        }
    }

    // Sort by amount and take at most 6
    return presets
        .distinctBy { it.amountCents }
        .sortedBy { it.amountCents }
        .take(6)
}
