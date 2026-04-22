package com.avoqado.pos.pos.presentation.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Square-style numeric keypad for amount entry.
 * Shows: large amount display, "+ Nota" button, and 4x3 keypad grid.
 */
@Composable
fun NumericKeypadView(
    amountCents: Int,
    onAmountChange: (Int) -> Unit,
    onAddToCart: () -> Unit,
    onNoteTap: () -> Unit = {},
    noteText: String = "",
    useCompactSizing: Boolean = false,
) {
    val formattedAmount = String.format("$%.2f", amountCents / 100.0)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val isCompact = useCompactSizing || screenHeight < 700

    val amountFontSize = if (isCompact) 36.sp else 60.sp
    val keypadFontSize = if (isCompact) 24.sp else 36.sp
    val topPadding = if (isCompact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.lg
    val notePadding = if (isCompact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.lg

    Column(modifier = Modifier.fillMaxSize()) {
        // Amount display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = formattedAmount,
                fontSize = amountFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = topPadding),
            )

            Spacer(modifier = Modifier.height(if (isCompact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.lg))

            // "+ Nota" button (shows current note if set)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .border(
                        width = 1.dp,
                        color = if (noteText.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    )
                    .clickable(onClick = onNoteTap)
                    .padding(vertical = notePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (noteText.isNotEmpty()) noteText else "+ Nota",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (noteText.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

        // Quick amount buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            val quickAmounts = listOf(50, 100, 200, 500)
            quickAmounts.forEach { amount ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(50),
                        )
                        .clickable { onAmountChange(amount * 100) }
                        .padding(vertical = if (isCompact) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$$amount",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

        // Keypad grid - fills remaining space
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "+"),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.sm)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                )
                .background(MaterialTheme.colorScheme.outlineVariant),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    row.forEach { key ->
                        KeypadButton(
                            label = key,
                            modifier = Modifier.weight(1f),
                            isAction = key == "+",
                            fontSize = keypadFontSize,
                            onClick = {
                                when (key) {
                                    "C" -> onAmountChange(0)
                                    "+" -> {
                                        if (amountCents > 0) onAddToCart()
                                    }
                                    else -> {
                                        val digit = key.toIntOrNull() ?: return@KeypadButton
                                        if (amountCents < 100_000_000) {
                                            onAmountChange(amountCents * 10 + digit)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    modifier: Modifier = Modifier,
    isAction: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 36.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (isAction) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            color = if (isAction) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
