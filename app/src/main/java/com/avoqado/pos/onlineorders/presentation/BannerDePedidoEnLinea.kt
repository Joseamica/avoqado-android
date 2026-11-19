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

/**
 * Texto del banner: el folio del pedido más reciente por aceptar. D662: con la pausa sola, la pausa va PRIMERO, también con pedidos
 * por aceptar (Codex r2 #7: un tercer pedido no puede tapar que la tienda ya no recibe). PURA.
 */
internal fun textoDelBanner(folios: List<String>, pausadaSola: Boolean = false): String {
    val pedidos = when (folios.size) {
        0 -> ""
        1 -> "Nuevo pedido en línea #${folios.first()}"
        else -> "Nuevo pedido en línea #${folios.last()} (${folios.size} por aceptar)"
    }
    if (!pausadaSola) return pedidos
    return if (folios.isEmpty()) TextosPedidosEnLinea.PAUSADA_SOLA_BANNER else "${TextosPedidosEnLinea.PAUSADA_SOLA_BANNER} · #${folios.last()} por aceptar"
}

/**
 * Banda global «Nuevo pedido en línea #folio» con «Ver» (abre la bandeja vía [VigilanteDePedidosEnLinea.pedirAbrirBandeja]) ; la
 * pantalla de A3 observa `bandejaSolicitada`. Vive en el tope de las dos disposiciones del NavGraph, junto a los demás avisos, así se ve en CUALQUIER pantalla.
 * Color de acento, nunca el rojo de error: un pedido nuevo es una buena noticia.
 */
@Composable
fun BannerDePedidoEnLinea(vigilante: VigilanteDePedidosEnLinea, modifier: Modifier = Modifier) {
    val pendientes by vigilante.pendientes.collectAsState()
    val pausadaSola by vigilante.pausadaSola.collectAsState()
    AnimatedVisibility(
        visible = pendientes.isNotEmpty() || pausadaSola,
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
                text = textoDelBanner(pendientes.map { it.orderNumber }, pausadaSola),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vigilante.pedirAbrirBandeja() }) { Text("Ver") }
        }
    }
}
