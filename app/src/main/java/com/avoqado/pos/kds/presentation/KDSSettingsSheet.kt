package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - KDS Settings Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KDSSettingsSheet(
    settings: KDSSettings,
    onToggleSound: () -> Unit,
    onToggleAutoBump: () -> Unit,
    onToggleLargeFont: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
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
                text = "Configuracion de cocina",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
            )

            // Sound toggle
            SettingsRow(
                title = "Sonido de notificacion",
                subtitle = "Reproducir sonido al recibir un nuevo pedido",
                isChecked = settings.soundEnabled,
                onToggle = onToggleSound,
            )

            HorizontalDivider()

            // Auto-bump toggle
            SettingsRow(
                title = "Auto-completar",
                subtitle = "Completar pedidos listos automaticamente despues de 2 minutos",
                isChecked = settings.autoBumpEnabled,
                onToggle = onToggleAutoBump,
            )

            HorizontalDivider()

            // Font size toggle
            SettingsRow(
                title = "Fuente grande",
                subtitle = "Aumentar el tamano del texto para mejor legibilidad",
                isChecked = settings.largeFontEnabled,
                onToggle = onToggleLargeFont,
            )

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
