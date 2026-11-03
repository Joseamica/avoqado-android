package com.avoqado.pos.onlineorders.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.domain.PedidoPorCobrar

/**
 * Rappi 2b: un pedido de «Rappi · por cobrar». Lo que el repartidor trae en efectivo, por partes, y [Cobrar] (sólo efectivo).
 * Sin red también se cobra: el saldo sale de la lista guardada y el cobro se guarda antes de tocar la red.
 */
@Composable
fun PedidoPorCobrarCard(
    pedido: PedidoPorCobrar,
    puedeCobrar: Boolean,
    ocupado: Boolean,
    onCobrar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val espacio = AvoqadoTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth().testTag("por-cobrar-${pedido.orderId}"),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(espacio.lg), verticalArrangement = Arrangement.spacedBy(espacio.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TextosPedidosEnLinea.notaDeReparto(pedido.thirdPartyLabel, pedido.orderNumber),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    horaDelNegocio(pedido.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pedido.customerName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(desgloseDe(pedido), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TextosPedidosEnLinea.totalACobrar(formatoPesos(pedido.remainingBalanceCents)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (puedeCobrar) {
                    Button(onClick = onCobrar, enabled = !ocupado, modifier = Modifier.testTag("cobrar-reparto-${pedido.orderId}")) {
                        Text(TextosPedidosEnLinea.COBRAR)
                    }
                }
            }
        }
    }
}
