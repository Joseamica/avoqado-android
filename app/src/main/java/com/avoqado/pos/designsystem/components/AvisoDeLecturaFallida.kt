package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/** Título y texto de una pantalla que no pudo leer del servidor. */
data class TextosDeLecturaFallida(val titulo: String, val texto: String)

/**
 * Lo que enseña una pantalla de datos del servidor (Ventas, Informes) cuando no tiene nada que enseñar porque la
 * lectura falló (D18, 3-nov-2026). Sin red es un estado normal: nube tachada y en gris, nunca en rojo. «Reintentar»
 * llama a la misma recarga del gesto de jalar: no hay otro camino de carga.
 *
 * No se desplaza solo: quien lo pone decide (dentro de un `LazyColumn` ya hay scroll; fuera, el gesto de jalar
 * necesita un hijo desplazable).
 */
@Composable
fun AvisoDeLecturaFallida(
    fallo: FalloDeLectura,
    textos: TextosDeLecturaFallida,
    onReintentar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (fallo == FalloDeLectura.SIN_RED) Icons.Outlined.CloudOff else Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = textos.titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = textos.texto,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
        PrimaryButton(text = "Reintentar", onClick = onReintentar)
    }
}
