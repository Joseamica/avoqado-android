package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.ConteoNoAplicado
import com.avoqado.pos.inventory.data.MotivoNoAplicado
import com.avoqado.pos.inventory.data.ResultadoNoAplicado

// MARK: - C12 del conector Shopify: lo que el servidor NO aplicó de un conteo
//
// Ámbar y NUNCA rojo: el conteo se cerró bien y lo demás sí movió el stock. Que una línea no se aplicara es un
// resultado normal (había un envío a Shopify en camino, o una duda por revisar), no un error. Espejo de las vistas
// de iOS (`ConteoNoAplicadoViews.swift`), mismos textos.

/**
 * La tarjeta que queda arriba de la lista de conteos al confirmar uno con líneas que no se aplicaron, hasta que el
 * cajero la cierra. La lista de productos se desplaza dentro de la tarjeta: con muchos, no empuja la lista de
 * conteos fuera de la pantalla, y ninguno queda escondido.
 */
@Composable
fun ResultadoNoAplicadoCard(
    resultado: ResultadoNoAplicado,
    onEntendido: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        color = Warning.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resultado.titulo,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (resultado.hayRestoAplicado) {
                        Text(
                            text = ConteoNoAplicado.RESTO_APLICADO,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onEntendido) { Text(ConteoNoAplicado.ENTENDIDO) }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                resultado.lineas.forEach { linea ->
                    Column {
                        Text(
                            text = linea.nombre,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = linea.motivo.texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** La insignia «No se aplicó» de una línea en el detalle del conteo, con su motivo debajo. */
@Composable
fun EtiquetaNoAplicado(motivo: MotivoNoAplicado) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Warning.copy(alpha = 0.18f),
        ) {
            Text(
                text = ConteoNoAplicado.ETIQUETA,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
            )
        }
        Text(
            text = motivo.texto,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** La franja del detalle: «N productos no se aplicaron», en el mismo ámbar que la tarjeta. */
@Composable
fun FranjaNoAplicadas(cantidad: Int) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Warning.copy(alpha = 0.12f)) {
        Text(
            text = ConteoNoAplicado.titulo(cantidad),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        )
    }
}
