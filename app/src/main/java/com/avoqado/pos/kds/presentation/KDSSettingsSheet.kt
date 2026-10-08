package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet

// MARK: - KDS Settings Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KDSSettingsSheet(
    settings: KDSSettings,
    /** El nombre de la estación de este tablero, para el texto de apagar. `null` si por algo no se sabe. */
    estacion: String?,
    /** `true` si `puedeApagar(estado, prendida)`. */
    puedeApagar: Boolean,
    /** `TextosDeCocina.PILOTO` cuando `estado` es `SoloApagar(LANZAMIENTO)`; si no, `null`. */
    detalleApagar: String?,
    onToggleSound: () -> Unit,
    onToggleLargeFont: () -> Unit,
    onCambiarEstacion: () -> Unit,
    onApagar: () -> Unit,
    onDismiss: () -> Unit,
) {
    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = TextosDeCocina.AJUSTES_TITULO,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
            )

            // Sound toggle
            SettingsRow(
                title = TextosDeCocina.SONIDO,
                subtitle = TextosDeCocina.SONIDO_DETALLE,
                isChecked = settings.soundEnabled,
                onToggle = onToggleSound,
            )

            HorizontalDivider()

            // Font size toggle
            SettingsRow(
                title = TextosDeCocina.LETRA_GRANDE,
                subtitle = TextosDeCocina.LETRA_GRANDE_DETALLE,
                isChecked = settings.largeFontEnabled,
                onToggle = onToggleLargeFont,
            )

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCambiarEstacion)
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TextosDeCocina.CAMBIAR_ESTACION,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (puedeApagar && estacion != null) {
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onApagar)
                        .padding(vertical = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = TextosDeCocina.apagarEstacion(estacion),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (detalleApagar != null) {
                        Text(
                            text = detalleApagar,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Settings Row

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
