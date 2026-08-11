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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.customerdisplay.CustomerDisplayPrefs
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
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
    displayModePrefs: DisplayModePrefs,
    ventaEnCurso: Boolean,
    onInvertedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val detected by displayState.isPresenting.collectAsState()
    val captureEnabled by prefs.customerCaptureEnabled.collectAsState()
    val invertible by displayState.invertible.collectAsState()
    val invertUnsupported by displayState.invertUnsupported.collectAsState()
    val inverted by displayModePrefs.inverted.collectAsState()
    var confirmando by remember { mutableStateOf(false) }

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
                // 🔴 `weight(1f)`, no sólo padding: sin él el Column se queda con todo el ancho
                // que pida su texto y APLASTA al Switch — se vio recortado contra el borde en
                // un T3 Pro. El texto es el que debe ceder y envolver, nunca el control.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = AvoqadoTheme.spacing.md),
                ) {
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

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 🔴 `weight(1f)`, no sólo padding: sin él el Column se queda con todo el ancho
                // que pida su texto y APLASTA al Switch — se vio recortado contra el borde en
                // un T3 Pro. El texto es el que debe ceder y envolver, nunca el control.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = "Invertir pantallas",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        // Apagado se VE y se EXPLICA: nunca desaparece en silencio.
                        // 🔴 `!invertible` cubre DOS motivos distintos —"no hay
                        // segunda pantalla" y "la hay pero no es táctil"—, y
                        // dárselos a ambos con el mismo texto es mentirle a
                        // cualquiera con un teléfono o una tablet normal. El "hay
                        // o no hay" se lee de `detected`, el MISMO dato que la
                        // línea de arriba de esta hoja, para que las dos frases
                        // no puedan contradecirse entre sí.
                        text = when {
                            invertUnsupported ->
                                "Este equipo no permitió mover la caja a la otra pantalla."
                            !invertible && !detected ->
                                "Este equipo no tiene una segunda pantalla; no hay nada que invertir."
                            !invertible ->
                                // 🔴 NO decir "no es táctil": es falso y manda a
                                // buscar el problema donde no está. Medido en un
                                // T3 Pro (2026-08-10): el panel del cliente SÍ trae
                                // digitalizador (SUNMI NP511, TOUCH_MT), pero Android
                                // no lo asocia a esa pantalla —`displayId` vacío— y
                                // sus toques aterrizan en la pantalla del cajero, con
                                // las coordenadas del panel grande.
                                //
                                // 🔴 Desde el puente táctil (CustomerTouchBridge) el
                                // CLIENTE sí puede tocar su pantalla: los reenviamos
                                // nosotros. Lo que sigue sin poder hacerse es poner
                                // ahí al CAJERO —app completa, campos de texto,
                                // teclado— porque el puente no da foco de entrada.
                                // Decir "los toques no llegan" volvería a ser falso.
                                "El cliente sí puede tocar su pantalla (Avoqado le " +
                                    "reenvía los toques), pero el cajero no podría " +
                                    "escribir ahí: Android no le da teclado a esa pantalla."
                            ventaEnCurso ->
                                "Termina la venta en curso para poder cambiar de pantalla."
                            else ->
                                "El cliente ve la pantalla grande y el cajero trabaja en la chica."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = inverted,
                    enabled = invertible && !ventaEnCurso,
                    onCheckedChange = { confirmando = true },
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }

    if (confirmando) {
        AvoqadoDialog(
            title = if (inverted) "¿Volver a la pantalla normal?" else "¿Invertir las pantallas?",
            description = "La caja se va a reiniciar en la otra pantalla. " +
                "Tarda unos segundos y no se pierde nada.",
            onDismiss = { confirmando = false },
            actionButton = {
                PrimaryButton(
                    text = "Continuar",
                    onClick = {
                        confirmando = false
                        onInvertedChange(!inverted)
                    },
                    fullWidth = true,
                )
            },
        ) {}
    }
}
