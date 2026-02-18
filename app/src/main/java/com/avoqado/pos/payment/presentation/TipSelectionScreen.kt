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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

// Keep the old function signature for backward compat (used in CollectingTip state)
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
                .padding(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            // Header: "Propina" + close button (matching iOS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Propina",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
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

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // 3 percentage buttons in a Row (matching iOS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                tipSuggestions.forEach { percent ->
                    val isSelected = selectedTipPercent == percent && !showCustomInput
                    val tipAmount = (amountCents * percent / 100.0).toInt()

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clickable {
                                selectedTipPercent = percent
                                showCustomInput = false
                                customTipCents = 0
                            },
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                        color = if (isSelected) Success else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "$${String.format("%.2f", tipAmount / 100.0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // "Otra cantidad" button or custom input (matching iOS)
            if (showCustomInput) {
                // Custom input with % <-> $ toggle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                        )
                        .padding(AvoqadoTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    // Display row: prefix + value + mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        Text(
                            text = if (isPercentageMode) "%" else "$",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = if (customTipCents == 0) {
                                if (isPercentageMode) "0" else "0.00"
                            } else {
                                if (isPercentageMode) "$customTipCents"
                                else String.format("%.2f", customTipCents / 100.0)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (customTipCents == 0) MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )

                        // Mode toggle button
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
                                .padding(
                                    horizontal = AvoqadoTheme.spacing.sm,
                                    vertical = AvoqadoTheme.spacing.xxs,
                                ),
                        ) {
                            Text(
                                text = if (isPercentageMode) "% \u2192 $" else "$ \u2192 %",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // In-sheet keypad
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
                                        .height(48.dp)
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
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            } else {
                // "Otra cantidad" outlined button (matching iOS)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                        )
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .clickable {
                            showCustomInput = true
                            selectedTipPercent = -1
                        }
                        .padding(vertical = AvoqadoTheme.spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Otra cantidad",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // "Continuar (total)" button
            PrimaryButton(
                text = "Continuar (${String.format("$%.2f", (amountCents + tipCents) / 100.0)})",
                onClick = { onTipSelected(tipCents) },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasSelection,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            // "Sin propina" underlined link (matching iOS)
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

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
