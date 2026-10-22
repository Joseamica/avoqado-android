package com.avoqado.pos.onlineorders.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea

/** Texto del banner: el folio del pedido más reciente por aceptar. PURA. */
internal fun textoDelBanner(folios: List<String>): String = when (folios.size) {
    0 -> ""
    1 -> "Nuevo pedido en línea #${folios.first()}"
    else -> "Nuevo pedido en línea #${folios.last()} (${folios.size} por aceptar)"
}

/**
 * Banda global «Nuevo pedido en línea #folio» con «Ver» (abre la bandeja vía [VigilanteDePedidosEnLinea.pedirAbrirBandeja]) y
 * «Silenciar». Vive en el tope de las dos disposiciones del NavGraph, junto a los demás avisos, así se ve en CUALQUIER pantalla.
 * Color de acento, nunca el rojo de error: un pedido nuevo es una buena noticia.
 */
@Composable
fun BannerDePedidoEnLinea(vigilante: VigilanteDePedidosEnLinea, modifier: Modifier = Modifier) {
    val pendientes by vigilante.pendientes.collectAsState()
    AnimatedVisibility(
        visible = pendientes.isNotEmpty(),
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = textoDelBanner(pendientes.map { it.orderNumber }),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vigilante.silenciarHastaNuevo() }) { Text("Silenciar") }
            TextButton(onClick = { vigilante.pedirAbrirBandeja() }) { Text("Ver") }
        }
    }
}
