package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

@Composable
fun TipSelectionScreen(
    amountCents: Int,
    tipSuggestions: List<Int>,
    onTipSelected: (Int) -> Unit,
    onSkip: () -> Unit,
) {
    TipSelectionSheet(
        amountCents = amountCents,
        tipSuggestions = tipSuggestions,
        onTipSelected = onTipSelected,
        onSkip = onSkip,
        onDismiss = onSkip,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipSelectionSheet(
    amountCents: Int,
    tipSuggestions: List<Int>,
    onTipSelected: (Int) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTipPercent by remember { mutableIntStateOf(-1) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customTipCents by remember { mutableIntStateOf(0) }
    var isPercentageMode by remember { mutableStateOf(true) }

    val tipCents = when {
        showCustomInput && customTipCents > 0 -> {
            if (isPercentageMode) {
                (amountCents * customTipCents / 100.0).toInt()
            } else {
                customTipCents
            }
        }
        selectedTipPercent > 0 -> (amountCents * selectedTipPercent / 100.0).toInt()
        else -> 0
    }

    val hasSelection = selectedTipPercent > 0 || (showCustomInput && customTipCents > 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xl),
        ) {
            // Header: "Propina" + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Propina",
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

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // 3 percentage buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                tipSuggestions.forEach { percent ->
                    val isSelected = selectedTipPercent == percent && !showCustomInput
                    val tipAmount = (amountCents * percent / 100.0).toInt()

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                            .background(
                                if (isSelected) Success
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable {
                                selectedTipPercent = percent
                                showCustomInput = false
                                customTipCents = 0
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "$${String.format("%.2f", tipAmount / 100.0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) Color.White.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // "Otra cantidad" or custom input
            if (showCustomInput) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                        )
                        .padding(AvoqadoTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                ) {
                    // Display row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        Text(
                            text = if (isPercentageMode) "%" else "$",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (customTipCents == 0) {
                                if (isPercentageMode) "0" else "0.00"
                            } else {
                                if (isPercentageMode) "$customTipCents"
                                else String.format("%.2f", customTipCents / 100.0)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = if (customTipCents == 0) MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                                )
                                .clickable {
                                    isPercentageMode = !isPercentageMode
                                    customTipCents = 0
                                }
                                .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
                        ) {
                            Text(
                                text = if (isPercentageMode) "% \u2192 $" else "$ \u2192 %",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // Compact keypad
                    val buttons = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("00", "0", "\u232B"),
                    )

                    buttons.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            row.forEach { label ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            when (label) {
                                                "\u232B" -> customTipCents /= 10
                                                "00" -> {
                                                    val maxVal = if (isPercentageMode) 999 else 9999999
                                                    val newVal = customTipCents.toLong() * 100
                                                    if (newVal <= maxVal) customTipCents = newVal.toInt()
                                                }
                                                else -> {
                                                    val maxVal = if (isPercentageMode) 999 else 9999999
                                                    val digit = label.toInt()
                                                    val newVal = customTipCents.toLong() * 10 + digit
                                                    if (newVal <= maxVal) customTipCents = newVal.toInt()
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            } else {
                // "Otra cantidad" button with pencil icon (matching iOS)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                        )
                        .clickable {
                            showCustomInput = true
                            selectedTipPercent = -1
                        }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Otra cantidad",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // "Continuar (total)" button — gray like iOS
            Button(
                onClick = { onTipSelected(tipCents) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = hasSelection,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    text = "Continuar (${String.format("$%.2f", (amountCents + tipCents) / 100.0)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))

            // "Sin propina" underlined link
            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "Sin propina",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}
