package com.avoqado.pos.onlineorders.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.domain.OnlineOrder

/** Los diálogos que una tarjeta pide a la bandeja (viven fuera de la lista). */
enum class TipoDeDialogo { ACEPTAR, RECHAZAR, CANCELAR }

/** En qué sección de la bandeja vive la tarjeta: de ella dependen sus acciones. */
enum class SeccionDePedido { POR_ACEPTAR, EN_PREPARACION, LISTOS, HOY }

/** ¿El pedido ya no debe nada? «Pagado» (en línea) o saldo en cero. */
internal fun estaPagado(pedido: OnlineOrder): Boolean = pedido.paymentStatus == "PAID" || pedido.remainingCents <= 0

/** Cancelar sólo aplica a un pedido SIN dinero: nada cobrado ni abonado. */
internal fun sinDinero(pedido: OnlineOrder): Boolean = pedido.paymentStatus == "PENDING" && pedido.remainingCents >= pedido.totalCents

/** Lo que dice el renglón de dinero de la tarjeta: «Pagado» o «Cobrar al recoger $X». */
internal fun textoDeDinero(pedido: OnlineOrder): String =
    if (estaPagado(pedido)) TextosPedidosEnLinea.PAGADO else TextosPedidosEnLinea.cobrarAlRecoger(formatoPesos(pedido.remainingCents))

/**
 * Una tarjeta de pedido. Acciones por sección (spec A3):
 * - Por aceptar: Aceptar (10/15/20/30 min) y Rechazar (motivo).
 * - En preparación: Listo, Cobrar, Entregado, y Cancelar (sin dinero, con `orders:cancel-unpaid`).
 * - Listos: Cobrar, Entregado, y Cancelar (igual).
 * - Hoy: sólo lectura.
 * Sin `orders:update` no se pinta NINGÚN botón de acción. «Cobrar» sólo se muestra si queda saldo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineOrderCard(
    pedido: OnlineOrder,
    seccion: SeccionDePedido,
    puedeActualizar: Boolean,
    puedeCancelar: Boolean,
    puedeCobrar: Boolean,
    sinConexion: Boolean,
    ocupado: Boolean,
    /** Aceptar, rechazar y cancelar piden su diálogo a la bandeja: viven fuera de la lista (la fila puede salir de la ventana). */
    onPedirDialogo: (TipoDeDialogo) -> Unit,
    onListo: () -> Unit,
    onEntregado: () -> Unit,
    /** A5 lo llena: abre el cobro del pedido. En A3 es sólo el botón. */
    onCobrar: () -> Unit,
    /** Vuelve a sacar la comanda en este aparato, sin reclamar. */
    onReimprimir: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val espacio = AvoqadoTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth().testTag("pedido-${pedido.orderId}"),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(espacio.lg), verticalArrangement = Arrangement.spacedBy(espacio.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TextosPedidosEnLinea.pedidoNumero(pedido.orderNumber),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(horaDelPedido(pedido), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(TextosPedidosEnLinea.PARA_RECOGER, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val cliente = listOfNotNull(pedido.customerName?.takeIf { it.isNotBlank() }, pedido.customerPhone?.takeIf { it.isNotBlank() })
            if (cliente.isNotEmpty()) Text(cliente.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

            pedido.items.forEach { renglon ->
                Column {
                    Text("${renglon.quantity} × ${renglon.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    renglon.modifiers.forEach { m ->
                        Text(
                            "   + ${if (m.quantity > 1) "${m.quantity} × " else ""}${m.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    renglon.note?.takeIf { it.isNotBlank() }?.let {
                        Text("   “$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            pedido.note?.takeIf { it.isNotBlank() }?.let {
                Text("Nota: $it", style = MaterialTheme.typography.bodyMedium)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(textoDeDinero(pedido), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(formatoPesos(pedido.totalCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            val hayAcciones = puedeActualizar && seccion != SeccionDePedido.HOY
            if (hayAcciones) {
                Spacer(Modifier.height(espacio.xxs))
                // Sin red las acciones en línea se apagan con texto neutro; el efectivo («Cobrar») se guarda y sube solo.
                val enLinea = !sinConexion && !ocupado
                FlowRow(horizontalArrangement = Arrangement.spacedBy(espacio.sm), verticalArrangement = Arrangement.spacedBy(espacio.sm)) {
                    when (seccion) {
                        SeccionDePedido.POR_ACEPTAR -> {
                            Button(onClick = { onPedirDialogo(TipoDeDialogo.ACEPTAR) }, enabled = enLinea, modifier = Modifier.testTag("aceptar-${pedido.orderId}")) {
                                Text(TextosPedidosEnLinea.ACEPTAR)
                            }
                            OutlinedButton(onClick = { onPedirDialogo(TipoDeDialogo.RECHAZAR) }, enabled = enLinea, modifier = Modifier.testTag("rechazar-${pedido.orderId}")) {
                                Text(TextosPedidosEnLinea.RECHAZAR)
                            }
                        }
                        SeccionDePedido.EN_PREPARACION, SeccionDePedido.LISTOS -> {
                            if (seccion == SeccionDePedido.EN_PREPARACION) {
                                Button(onClick = onListo, enabled = enLinea, modifier = Modifier.testTag("listo-${pedido.orderId}")) {
                                    Text(TextosPedidosEnLinea.LISTO)
                                }
                            }
                            if (puedeCobrar && !estaPagado(pedido)) {
                                Button(onClick = onCobrar, enabled = !ocupado, modifier = Modifier.testTag("cobrar-${pedido.orderId}")) {
                                    Text(TextosPedidosEnLinea.COBRAR)
                                }
                            }
                            // La comanda sale en la impresora de ESTE aparato: no necesita red (el servidor no interviene).
                            OutlinedButton(onClick = onReimprimir, enabled = !ocupado, modifier = Modifier.testTag("reimprimir-${pedido.orderId}")) {
                                Text(TextosPedidosEnLinea.REIMPRIMIR)
                            }
                            OutlinedButton(onClick = onEntregado, enabled = enLinea, modifier = Modifier.testTag("entregado-${pedido.orderId}")) {
                                Text(TextosPedidosEnLinea.ENTREGADO)
                            }
                            if (puedeCancelar && sinDinero(pedido)) {
                                TextButton(onClick = { onPedirDialogo(TipoDeDialogo.CANCELAR) }, enabled = enLinea, modifier = Modifier.testTag("cancelar-${pedido.orderId}")) {
                                    Text(TextosPedidosEnLinea.CANCELAR, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        SeccionDePedido.HOY -> Unit
                    }
                }
                if (sinConexion && seccion != SeccionDePedido.HOY) {
                    Text(
                        TextosPedidosEnLinea.REINTENTA,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
