package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.domain.AccionDeHoja
import com.avoqado.pos.giftcards.domain.EstadoDeHoja
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaDeTarjetaDeRegalo(
    hoja: EstadoDeHoja,
    onVender: () -> Unit,
    onConsultar: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    prendiendo: Boolean = false,
    errorAlPrender: String? = null,
    onPrender: () -> Unit = {},
    onLimpiarError: () -> Unit = {},
) {
    var pidiendoPrender by remember { mutableStateOf(false) }
    // Ya prendidas, la fila se va y el diálogo con ella: «Vender tarjeta» queda activo en la misma hoja.
    if (pidiendoPrender && hoja.prender.visible) {
        DialogoDePrender(
            prender = hoja.prender,
            prendiendo = prendiendo,
            error = errorAlPrender,
            onPrender = onPrender,
            onCerrar = { pidiendoPrender = false; onLimpiarError() },
        )
    }
    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(TextosDeTarjetas.MOSAICO, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md))
        if (hoja.prender.visible) {
            AccionDeTarjeta(TextosDeTarjetas.PRENDER, hoja.prender) { pidiendoPrender = true }
            HorizontalDivider()
        }
        if (hoja.vender.visible) {
            AccionDeTarjeta(TextosDeTarjetas.VENDER, hoja.vender, onVender)
            HorizontalDivider()
        }
        if (hoja.consultar.visible) AccionDeTarjeta(TextosDeTarjetas.CONSULTAR, hoja.consultar, onConsultar)
    }
}

/** Las mismas reglas y casilla que el panel (`GiftCardsSwitch`): prender sin aceptar los términos no se puede en ninguno de los dos. */
@Composable
private fun DialogoDePrender(prender: AccionDeHoja, prendiendo: Boolean, error: String?, onPrender: () -> Unit, onCerrar: () -> Unit) {
    var acepta by remember { mutableStateOf(false) }
    val enlaces = LocalUriHandler.current
    AvoqadoDialog(
        title = TextosDeTarjetas.PRENDER_TITULO,
        description = TextosDeTarjetas.PRENDER_INTRO,
        onDismiss = { if (!prendiendo) onCerrar() },
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = TextosDeTarjetas.PRENDER_BOTON,
                onClick = onPrender,
                enabled = acepta && prender.habilitada && !prendiendo,
                isLoading = prendiendo,
                fullWidth = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
            TextosDeTarjetas.PRENDER_REGLAS.forEach { regla ->
                Text("• $regla", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !prendiendo, role = Role.Checkbox) { acepta = !acepta }) {
                Checkbox(checked = acepta, onCheckedChange = null, enabled = !prendiendo)
                Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
                Text(TextosDeTarjetas.PRENDER_ACEPTO, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                TextButton(onClick = { enlaces.openUri(URL_TERMINOS) }) { Text(TextosDeTarjetas.TERMINOS) }
                TextButton(onClick = { enlaces.openUri(URL_PRIVACIDAD) }) { Text(TextosDeTarjetas.AVISO_DE_PRIVACIDAD) }
            }
            // Sin red o con un rechazo se dice aquí, en el mismo diálogo, para que se pueda reintentar.
            (error ?: prender.motivo)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private const val URL_TERMINOS = "https://avoqado.io/terms"
private const val URL_PRIVACIDAD = "https://avoqado.io/privacy"

@Composable
private fun AccionDeTarjeta(titulo: String, accion: AccionDeHoja, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = accion.habilitada, role = Role.Button, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titulo, style = MaterialTheme.typography.bodyLarge,
                    color = if (accion.habilitada) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                accion.etiquetaDePlan?.let {
                    Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
                    TierBadge(tierLabel = it)
                }
            }
            accion.motivo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AvoqadoTheme.spacing.xxs))
            }
        }
        if (accion.habilitada) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
