package com.avoqado.pos.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.settings.domain.PosModeManager

// MARK: - Change Mode Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeModeSheet(
    posModeManager: PosModeManager,
    onDismiss: () -> Unit,
    /** Reservas solo se ofrece si el venue tiene la feature activa (+plan). */
    reservationsAvailable: Boolean = false,
) {
    val currentMode by posModeManager.currentMode.collectAsState()
    val modes = PosMode.entries.filter { it != PosMode.RESERVATIONS || reservationsAvailable }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Modo de punto de venta",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Text(
                text = "Elige el modo de operación para este dispositivo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            modes.forEachIndexed { index, mode ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ModeRow(
                    mode = mode,
                    isSelected = mode == currentMode,
                    onClick = { posModeManager.switchMode(mode) },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            PrimaryButton(
                text = "¡Listo!",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Mode Row

@Composable
private fun ModeRow(
    mode: PosMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val icon = when (mode) {
        PosMode.RETAIL -> Icons.Filled.Storefront
        PosMode.RESTAURANT -> Icons.Filled.Restaurant
        PosMode.RESERVATIONS -> Icons.Filled.CalendarMonth
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconXLarge),
            tint = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Activo en 1 dispositivo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
