package com.avoqado.pos.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.customerdisplay.CustomerDisplayPrefs
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Ajustes de la pantalla de cara al cliente (POS de doble pantalla).
 *
 * La decisión que resuelve: tener segunda pantalla NO significa que el cliente
 * la alcance. Si el negocio no confirma que sí, propina y calificación se
 * siguen capturando del lado del cajero — nunca dejamos el cobro esperando un
 * toque que nadie va a dar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDisplaySheet(
    prefs: CustomerDisplayPrefs,
    displayState: CustomerDisplayState,
    onDismiss: () -> Unit,
) {
    val detected by displayState.isPresenting.collectAsState()
    val captureEnabled by prefs.customerCaptureEnabled.collectAsState()

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
                text = "Pantalla del cliente",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (detected) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (detected) {
                        "Segunda pantalla detectada. El cliente ve su carrito y el total."
                    } else {
                        "No se detecta una segunda pantalla en este equipo."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AvoqadoTheme.spacing.sm),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.padding(end = AvoqadoTheme.spacing.md)) {
                    Text(
                        text = "El cliente elige propina y calificación",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Actívalo solo si el cliente alcanza la segunda pantalla. " +
                            "El cajero verá una espera mientras el cliente decide.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = captureEnabled,
                    enabled = detected,
                    onCheckedChange = { prefs.setCustomerCaptureEnabled(it) },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
