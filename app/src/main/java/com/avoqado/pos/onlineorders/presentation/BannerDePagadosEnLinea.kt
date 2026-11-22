package com.avoqado.pos.onlineorders.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.avoqado.pos.onlineorders.data.tarjetasQueAvisan

/**
 * D661 (Ruling 19): la seña visible del timbre de los pagados con tarjeta en línea. El vigilante suena en CUALQUIER pantalla mientras
 * haya uno por abrir; esta banda dice por qué y su toque lleva a «Pedidos en línea», donde la sección «Pagados en línea» va arriba.
 * Sale con la MISMA regla que el sonido ([tarjetasQueAvisan]): sólo con una lectura en vivo y sin los que otra caja está cobrando.
 * Va debajo de [BannerDePedidoEnLinea] (el de «Mi equipo», de D662, que no se toca); las dos pueden verse a la vez. Color de acento,
 * nunca el rojo de error.
 */
@Composable
fun BannerDePagadosEnLinea(vigilante: VigilanteDePedidosEnLinea, modifier: Modifier = Modifier) {
    val tarjetas by vigilante.tarjetasPorAbrir.collectAsState()
    val enVivo by vigilante.tarjetasEnVivo.collectAsState()
    val porAbrir = tarjetasQueAvisan(tarjetas, enVivo)
    AnimatedVisibility(
        visible = porAbrir.isNotEmpty(),
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { vigilante.pedirAbrirBandeja() }
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = TextosPedidosEnLinea.avisoDePagadosEnLinea(porAbrir.size),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vigilante.pedirAbrirBandeja() }) { Text(TextosPedidosEnLinea.VER) }
        }
    }
}
