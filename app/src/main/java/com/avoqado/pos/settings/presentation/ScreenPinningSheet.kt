package com.avoqado.pos.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.core.util.findActivity
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.settings.domain.ScreenPinningManager

/**
 * Esconder las barras de Android + la salida explícita.
 *
 * El navbar del sistema se asoma solo al acercar el mouse a la orilla, y desde
 * ahí el personal puede irse a Chrome o a los ajustes del equipo. Fijar la
 * pantalla lo elimina; el botón de salir sustituye a ese navbar con algo
 * controlado y visible, en vez de dejar al cajero adivinando el gesto del
 * sistema.
 *
 * 🔴 Esto NO es el "modo kiosco" de autoservicio (que el cliente se atienda
 * solo, sin personal). Ese es otro eje y todavía no existe; el campo
 * `TpvSettings.kioskModeEnabled` que llega del servidor le pertenece a ÉL, no
 * a esta pantalla. Son cosas distintas: aquí se decide si se puede salir de la
 * app; allá, quién la opera. Un mostrador con personal también puede querer
 * esconder las barras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenPinningSheet(
    screenPinning: ScreenPinningManager,
    onDismiss: () -> Unit,
) {
    val enabled by screenPinning.enabled.collectAsState()
    val activity = LocalContext.current.findActivity()

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
                text = "Esconder barras de Android",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 🔴 `weight(1f)`: sin él el Column se queda con todo el ancho que pida su
                // texto y aplasta al Switch contra el borde. Esta explicación son dos
                // renglones — es el mismo caso que ya se vio recortado en la hoja de pantalla
                // del cliente. El texto cede y envuelve; el control nunca.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = "Fijar Avoqado en pantalla",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Oculta la barra del sistema y evita que se salgan de la app. " +
                            "Para salir se usa el botón de abajo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value -> activity?.let { screenPinning.setEnabled(it, value) } },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            Button(
                onClick = { activity?.let { screenPinning.exitToLauncher(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salir de la app")
            }
            Text(
                text = "No cierra tu sesión: al volver sigues donde estabas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
