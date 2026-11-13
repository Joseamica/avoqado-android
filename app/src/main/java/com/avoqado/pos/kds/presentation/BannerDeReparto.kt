package com.avoqado.pos.kds.presentation

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
import com.avoqado.pos.kds.data.ComandaImpresa
import com.avoqado.pos.kds.data.VigilanteDeReparto

/** Texto de la franja: el pedido más reciente, cuántos van y si alguno no salió completo en papel. PURA. */
internal fun textoDeLaFranjaDeReparto(avisos: List<ComandaImpresa>, proveedor: String): String {
    val ultimo = avisos.maxByOrNull { it.createdAt } ?: return ""
    val base = if (avisos.size == 1) {
        "Nuevo pedido de $proveedor #${ultimo.orderNumber}"
    } else {
        "Nuevo pedido de $proveedor #${ultimo.orderNumber} (${avisos.size} nuevos)"
    }
    return if (avisos.any { !it.salioEnPapel }) "$base · no salió en papel, revisa la impresora" else base
}

/**
 * Banda global «Nuevo pedido de Rappi #folio» con «Entendido» (y «Reimprimir» si salió sólo una parte). Vive junto a
 * `BannerDePedidoEnLinea`, en el tope de las dos disposiciones del NavGraph: se ve en CUALQUIER pantalla, también en
 * Cobrar. Color de acento, nunca el rojo de error: un pedido nuevo es una buena noticia.
 */
@Composable
fun BannerDeReparto(vigilante: VigilanteDeReparto, modifier: Modifier = Modifier) {
    val avisos by vigilante.avisos.collectAsState()
    val proveedor by vigilante.proveedor.collectAsState()
    AnimatedVisibility(
        visible = avisos.isNotEmpty(),
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
                text = textoDeLaFranjaDeReparto(avisos, proveedor),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            avisos.firstOrNull { it.porReimprimir.isNotEmpty() }?.let { aviso ->
                TextButton(onClick = { vigilante.reimprimir(aviso.kdsId) }) { Text("Reimprimir") }
            }
            TextButton(onClick = { vigilante.entendido() }) { Text("Entendido") }
        }
    }
}
