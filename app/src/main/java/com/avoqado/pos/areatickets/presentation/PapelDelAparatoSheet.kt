package com.avoqado.pos.areatickets.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.areatickets.domain.PapelDelAparato
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.printing.presentation.esteAparato

/** «¿Qué hará este aparato?» (en Windows, «esta computadora»). */
internal fun tituloDeLaHoja(esEscritorio: Boolean): String = "¿Qué hará ${esteAparato(esEscritorio)}?"

/** El aviso de éxito al guardar. */
internal fun textoAlGuardar(papel: PapelDelAparato, esEscritorio: Boolean): String {
    val aparato = esteAparato(esEscritorio).replaceFirstChar { it.uppercase() }
    val hace = when (papel) {
        is PapelDelAparato.EmiteVales -> "emite vales de ${papel.areaNombre}"
        else -> "cobra"
    }
    return "¡Listo! $aparato ahora $hace"
}

/**
 * D3: la hoja del papel del aparato, la misma la primera vez y desde «Más». Sólo ofrece «Cobra (caja)» y «Emite vales de
 * {área}» (un aparato que emite siempre emite). «Guardar» se apaga y dice por qué; «Ahora no» sólo la primera vez.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PapelDelAparatoSheet(
    estado: HojaDelPapel,
    hayRed: Boolean,
    onElegir: (PapelDelAparato) -> Unit,
    onGuardar: () -> Unit,
    onAhoraNo: (() -> Unit)?,
    onCerrar: () -> Unit,
) {
    val esEscritorio = LocalEsEscritorio.current
    val motivo = estado.motivo(hayRed)
    val guardando by rememberUpdatedState(estado.guardando)
    AvoqadoModalBottomSheet(
        onDismissRequest = onCerrar,
        // Codex r8 #2: mientras guarda no se esconde (arrastrar o tocar fuera); si no, un rechazo quedaría en una hoja oculta.
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !guardando },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = tituloDeLaHoja(esEscritorio),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Hasta 200 áreas: la lista se desplaza y «Guardar» sigue a la vista.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                OpcionDelPapel(
                    titulo = "Cobra (caja)",
                    detalle = "Aquí se pagan los vales",
                    elegida = estado.seleccion == PapelDelAparato.Cobra,
                    onClick = { onElegir(PapelDelAparato.Cobra) },
                )
                estado.areas.forEach { area ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OpcionDelPapel(
                        titulo = "Emite vales de ${area.name}",
                        detalle = "Toma el pedido e imprime el vale",
                        elegida = (estado.seleccion as? PapelDelAparato.EmiteVales)?.areaId == area.id,
                        onClick = { onElegir(PapelDelAparato.EmiteVales(area.id, area.name)) },
                    )
                }
            }

            if (estado.areasDeMas > 0) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Hay ${estado.areasDeMas} áreas más: elígela desde el panel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val aviso = estado.error ?: motivo
            if (aviso != null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = aviso,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (estado.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
            PrimaryButton(
                text = "Guardar",
                onClick = onGuardar,
                modifier = Modifier.fillMaxWidth(),
                enabled = motivo == null && estado.seleccion != PapelDelAparato.SinElegir,
                isLoading = estado.guardando,
            )
            if (onAhoraNo != null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                TextButton(
                    onClick = onAhoraNo,
                    enabled = !estado.guardando,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ahora no") }
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
        }
    }
}

@Composable
private fun OpcionDelPapel(titulo: String, detalle: String, elegida: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detalle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(
            selected = elegida,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
    }
}
