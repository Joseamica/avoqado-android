package com.avoqado.pos.timeclock.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun PinPadView(
    pin: String,
    onPinChange: (String) -> Unit,
    maxLength: Int = 10,
    minLength: Int = 4,
    compact: Boolean = false,
) {
    val dotSize = if (compact) 12.dp else 14.dp
    val dotSpacing = if (compact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    val keySize = if (compact) 56.dp else 64.dp
    val rowWidth = if (compact) 0.82f else 0.7f
    val rowGap = if (compact) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // PIN dots — dynamic: show one filled dot per entered digit
        Row(
            modifier = Modifier.height(20.dp),
            horizontalArrangement = Arrangement.spacedBy(dotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pin.isEmpty()) {
                // Placeholder: show minLength empty dots to hint at minimum length
                repeat(minLength) {
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ),
                    )
                }
            } else {
                // Show one filled dot per entered digit
                repeat(pin.length) {
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (compact) AvoqadoTheme.spacing.xl else AvoqadoTheme.spacing.xxl))

        // Number pad
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "del"),
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(rowWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(modifier = Modifier.size(keySize))
                        "del" -> {
                            IconButton(
                                onClick = {
                                    if (pin.isNotEmpty()) onPinChange(pin.dropLast(1))
                                },
                                modifier = Modifier.size(keySize),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Borrar",
                                )
                            }
                        }
                        else -> {
                            Surface(
                                onClick = {
                                    if (pin.length < maxLength) onPinChange(pin + key)
                                },
                                modifier = Modifier.size(keySize),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key,
                                        style = if (compact) {
                                            MaterialTheme.typography.titleLarge
                                        } else {
                                            MaterialTheme.typography.headlineMedium
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(rowGap))
        }
    }
}
