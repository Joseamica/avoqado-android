package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.customers.data.ResultadoDeAjuste
import com.avoqado.pos.customers.data.TarjetaDeSellos
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

private const val TEXTO_SIN_RED = "Sin conexión: los sellos se ajustan con internet"

/** Tarjeta de sellos del cliente + «Ajustar sellos» (sólo con `loyalty:adjust`). Online-only: nada se encola. */
@Composable
fun CustomerStampsSection(
    customerId: String,
    viewModel: CustomerStampsViewModel = hiltViewModel(key = "sellos-del-cliente-$customerId"),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val resultado by viewModel.resultado.collectAsStateWithLifecycle()
    // 🔴 La apertura (cliente + foto de la tarjeta) es del ViewModel: la pantalla no guarda ni pasa una tarjeta.
    val abierto by viewModel.ajusteAbierto.collectAsStateWithLifecycle()
    var toast by remember(customerId) { mutableStateOf(false) }
    val guardando by viewModel.guardando.collectAsStateWithLifecycle()

    LaunchedEffect(customerId) { viewModel.cargar(customerId) }
    // Salir de la ficha cierra la apertura (A → B → A no la resucita). `viewModel` es el de ESTE cliente.
    DisposableEffect(customerId) { onDispose { viewModel.cerrarAjuste() } }
    // Hecho, Cambio y Apagado ya cerraron la apertura en el ViewModel; aquí sólo el aviso de éxito.
    LaunchedEffect(resultado) {
        if (resultado is ResultadoDeAjuste.Hecho) { toast = true; viewModel.limpiarResultado() }
    }

    when (val e = estado) {
        EstadoDeSellos.Cargando, EstadoDeSellos.Oculto -> Unit
        EstadoDeSellos.SinRed -> Column(Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.lg)) {
            Titulo()
            Text(TEXTO_SIN_RED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is EstadoDeSellos.Listo -> Column(Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.lg)) {
            Titulo()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(AvoqadoTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${e.tarjeta.stampsEarned} de ${e.tarjeta.stampsRequired} sellos",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (e.tarjeta.rewardLabel.isNotBlank()) {
                            Text(e.tarjeta.rewardLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (e.puedeAjustar) TextButton(onClick = viewModel::abrirAjuste) { Text("Ajustar sellos") }
                }
            }
        }
    }

    // F7: el aviso del 409 va FUERA de la lectura. Si la recarga deja la sección en Oculto o SinRed, sigue a la vista hasta
    // la siguiente acción del usuario.
    (resultado as? ResultadoDeAjuste.Cambio)?.let {
        Text(
            "Los sellos de este cliente cambiaron mientras tanto: ahora tiene ${it.stampsEarned}. Revisa y vuelve a intentar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
        )
    }
    if (resultado == ResultadoDeAjuste.Apagado) {
        Text(
            "El programa de sellos está apagado",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
        )
    }

    abierto?.takeIf { it.customerId == customerId }?.let { a ->
        // Un borrador nuevo por apertura: reabrir no hereda el texto de la anterior.
        key(a.id) {
            AjustarSellosDialog(
                tarjeta = a.foto,
                resultado = resultado,
                guardando = guardando,
                onEditar = viewModel::limpiarResultado,
                onDismiss = { viewModel.cerrarAjuste(); viewModel.limpiarResultado() },
                onGuardar = viewModel::guardarAjuste,
            )
        }
    }

    if (toast) AvoqadoSuccessToast(message = "¡Sellos ajustados!", onDismiss = { toast = false })
}

@Composable
private fun Titulo() {
    Text(
        "Tarjeta de sellos",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
    )
}

@Composable
private fun AjustarSellosDialog(
    tarjeta: TarjetaDeSellos,
    resultado: ResultadoDeAjuste?,
    guardando: Boolean,
    onEditar: () -> Unit,
    onDismiss: () -> Unit,
    onGuardar: (Int, String) -> Unit,
) {
    var texto by remember { mutableStateOf(tarjeta.stampsEarned.toString()) }
    var motivo by remember { mutableStateOf("") }
    val total = texto.toIntOrNull()
    val validoTotal = total != null && total in 0..tarjeta.stampsRequired && total != tarjeta.stampsEarned
    val validoMotivo = motivo.trim().length in 5..500
    val sinRed = resultado == ResultadoDeAjuste.SinRed

    AvoqadoDialog(
        title = "Ajustar sellos",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Guardar",
                // W4: sin red Guardar sigue vivo; al volver la red se reintenta sin tener que editar.
                enabled = validoTotal && validoMotivo && !guardando,
                fullWidth = true,
                onClick = { onGuardar(total!!, motivo.trim()) },
            )
        },
    ) {
        Column {
            Text("¿Cuántos sellos tiene?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            AvoqadoPillTextField(
                value = texto,
                onValueChange = { texto = it.filter(Char::isDigit).take(4); onEditar() },
                placeholder = "0 a ${tarjeta.stampsRequired}",
                keyboardType = KeyboardType.Number,
            )
            if (validoTotal) {
                Text(
                    "Pasa de ${tarjeta.stampsEarned} a $total sellos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AvoqadoTheme.spacing.xs),
                )
                if (total == tarjeta.stampsRequired) {
                    Text(
                        "Completa la tarjeta: gana «${tarjeta.rewardLabel}» y empieza una nueva en 0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text("Motivo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            AvoqadoPillTextField(value = motivo, onValueChange = { motivo = it.take(500); onEditar() }, placeholder = "Ej. Migración de sellos")
            if (sinRed) {
                Text(TEXTO_SIN_RED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm))
            }
            (resultado as? ResultadoDeAjuste.Error)?.let {
                Text(it.mensaje, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm))
            }
        }
    }
}
