package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun AvoqadoFullScreenModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryActionText: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionEnabled: Boolean = true,
    /**
     * Por qué la acción está apagada, en palabras. Mismo criterio que
     * [AvoqadoFullscreenHeader]: un botón gris arriba y campos vacíos abajo no se
     * conectan solos, y quien lo sufre está frente a un cliente.
     */
    primaryActionDisabledReason: String? = null,
    content: @Composable () -> Unit,
) {
    val compactTypography =
        AvoqadoTheme.adaptive.sizeClass == AvoqadoAdaptiveSizeClass.Compact ||
            AvoqadoTheme.adaptive.isAggressiveCompact
    val actionSlotWidth = if (compactTypography) 132.dp else 148.dp
    val headerActionContainer = MaterialTheme.colorScheme.onSurface
    val headerActionContent = MaterialTheme.colorScheme.surface

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AvoqadoTheme.spacing.md,
                            vertical = AvoqadoTheme.spacing.sm,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        style = if (compactTypography) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CloseButton(onClick = onDismiss)
                        Spacer(modifier = Modifier.weight(1f))

                        Box(
                            modifier = Modifier.width(actionSlotWidth),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (primaryActionText != null && onPrimaryAction != null) {
                                Button(
                                    onClick = onPrimaryAction,
                                    enabled = primaryActionEnabled,
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = headerActionContainer,
                                        contentColor = headerActionContent,
                                        disabledContainerColor = headerActionContainer.copy(alpha = 0.35f),
                                        disabledContentColor = headerActionContent.copy(alpha = 0.75f),
                                    ),
                                ) {
                                    Text(
                                        text = primaryActionText,
                                        style = if (compactTypography) {
                                            MaterialTheme.typography.titleSmall
                                        } else {
                                            MaterialTheme.typography.titleMedium
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (!primaryActionEnabled && primaryActionDisabledReason != null) {
                    Text(
                        text = primaryActionDisabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AvoqadoTheme.spacing.lg,
                                vertical = AvoqadoTheme.spacing.xs,
                            ),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = AvoqadoTheme.spacing.md,
                            vertical = AvoqadoTheme.spacing.sm,
                        ),
                ) {
                    content()
                }
            }
        }
    }
}
