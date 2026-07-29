package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

enum class FullscreenHeaderNav { CLOSE, BACK }

/**
 * Fullscreen header pattern (mandatory per CLAUDE.md):
 * - Circular nav button left (X close OR ← back)
 * - Centered title
 * - Optional pill action right (text or icon)
 *
 * Use [navStyle] = [FullscreenHeaderNav.CLOSE] for entry-point screens / fullscreen modals.
 * Use [navStyle] = [FullscreenHeaderNav.BACK] within multi-step wizards (steps 2..N).
 */
@Composable
fun AvoqadoFullscreenHeader(
    title: String,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
    navStyle: FullscreenHeaderNav = FullscreenHeaderNav.CLOSE,
    primaryActionText: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionEnabled: Boolean = true,
    /**
     * Por qué la acción está apagada, en palabras.
     *
     * Un botón gris arriba a la derecha y unos campos vacíos abajo no se conectan
     * solos: quien abre la pantalla ve un control muerto y no sabe si le falta
     * llenar algo, si no tiene permiso, o si la app se rompió. Con un cliente
     * enfrente, adivinar no es una opción. Pasa el motivo y el header lo muestra
     * bajo la barra hasta que la acción se habilita.
     */
    primaryActionDisabledReason: String? = null,
    primaryActionIcon: ImageVector? = null,
    showDivider: Boolean = false,
) {
    val compact = AvoqadoTheme.adaptive.sizeClass == AvoqadoAdaptiveSizeClass.Compact ||
        AvoqadoTheme.adaptive.isAggressiveCompact
    val actionSlotWidth = if (compact) 132.dp else 148.dp
    val headerContainer = MaterialTheme.colorScheme.onSurface
    val headerContent = MaterialTheme.colorScheme.surface

    Column(modifier = modifier) {
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
                style = if (compact) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (navStyle) {
                    FullscreenHeaderNav.CLOSE -> CloseButton(onClick = onNav)
                    FullscreenHeaderNav.BACK -> BackButton(onClick = onNav)
                }
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier.width(actionSlotWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    when {
                        primaryActionText != null && onPrimaryAction != null -> {
                            Button(
                                onClick = onPrimaryAction,
                                enabled = primaryActionEnabled,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = headerContainer,
                                    contentColor = headerContent,
                                    disabledContainerColor = headerContainer.copy(alpha = 0.35f),
                                    disabledContentColor = headerContent.copy(alpha = 0.75f),
                                ),
                            ) {
                                Text(
                                    text = primaryActionText,
                                    style = if (compact) MaterialTheme.typography.titleSmall
                                        else MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        primaryActionIcon != null && onPrimaryAction != null -> {
                            CircularIconButton(
                                onClick = onPrimaryAction,
                                icon = primaryActionIcon,
                                contentDescription = "Acción",
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

        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
