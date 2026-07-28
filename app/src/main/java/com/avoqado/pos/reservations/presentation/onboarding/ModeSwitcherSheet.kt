package com.avoqado.pos.reservations.presentation.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.domain.VenueMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitcherSheet(
    currentMode: VenueMode,
    onModeSelected: (VenueMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(Modifier.padding(20.dp)) {
            Text(
                "Cambiar de modo",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            VenueMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onModeSelected(mode)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = {
                            onModeSelected(mode)
                            onDismiss()
                        },
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(mode.displayLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (mode) {
                                VenueMode.STANDARD -> "Cobrar, transacciones e inventario"
                                VenueMode.RESERVATIONS -> "Calendario, citas y clases"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
