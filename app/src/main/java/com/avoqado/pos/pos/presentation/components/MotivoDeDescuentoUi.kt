package com.avoqado.pos.pos.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.domain.MotivoElegido
import com.avoqado.pos.pos.domain.MotivosDeDescuento

/*
 * Encargo f998c8 (founder «A», 15-nov, como Toast): elegir el motivo de un descuento. Lo usan el panel del producto (como subvista),
 * la hoja de descuentos de la cuenta, Mesas y la última puerta antes de cobrar (como ventana). Funciona sin red: la lista llega con los
 * ajustes guardados del aparato.
 */

/** La lista de motivos para elegir uno, más el comentario opcional. [onCambio] avisa cada vez que cambia lo elegido. */
@Composable
fun SelectorDeMotivo(
    motivos: List<String>,
    elegido: String?,
    comentario: String,
    onElegir: (String) -> Unit,
    onComentario: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
        motivos.forEach { motivo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onElegir(motivo) }
                    .testTag("motivo-$motivo"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = elegido == motivo, onClick = { onElegir(motivo) })
                Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
                Text(motivo, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.padding(top = AvoqadoTheme.spacing.xs))
        OutlinedTextField(
            value = comentario,
            onValueChange = { onComentario(it.take(MotivosDeDescuento.MAX_COMENTARIO)) },
            label = { Text("Comentario (opcional)") },
            modifier = Modifier.fillMaxWidth().testTag("motivo-comentario"),
            maxLines = 3,
        )
    }
}

/**
 * Ventana para elegir el motivo. [detalle] dice a qué se le pone (uno o varios descuentos). Confirmar sólo se puede con un motivo
 * elegido; cancelar no pone el descuento (o, en la puerta del cobro, no cobra).
 */
@Composable
fun DialogoMotivoDeDescuento(
    motivos: List<String>,
    titulo: String,
    detalle: List<String>,
    textoConfirmar: String,
    onConfirmar: (MotivoElegido) -> Unit,
    onCancelar: () -> Unit,
) {
    var elegido by remember { mutableStateOf<String?>(null) }
    var comentario by remember { mutableStateOf("") }
    AvoqadoDialog(
        title = titulo,
        onDismiss = onCancelar,
        description = "El negocio pide un motivo para cada descuento.",
        actionButton = {
            Column(Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = textoConfirmar,
                    onClick = { elegido?.let { onConfirmar(MotivoElegido.de(it, comentario)) } },
                    enabled = elegido != null,
                    fullWidth = true,
                )
                TextButton(onClick = onCancelar, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (detalle.isNotEmpty()) {
                detalle.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.padding(top = AvoqadoTheme.spacing.sm))
            }
            SelectorDeMotivo(
                motivos = motivos,
                elegido = elegido,
                comentario = comentario,
                onElegir = { elegido = it },
                onComentario = { comentario = it },
            )
        }
    }
}
