package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
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
) {
    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(TextosDeTarjetas.MOSAICO, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md))
        if (hoja.vender.visible) {
            AccionDeTarjeta(TextosDeTarjetas.VENDER, hoja.vender, onVender)
            HorizontalDivider()
        }
        if (hoja.consultar.visible) AccionDeTarjeta(TextosDeTarjetas.CONSULTAR, hoja.consultar, onConsultar)
    }
}

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
