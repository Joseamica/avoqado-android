package com.avoqado.pos.pos.presentation.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.GUARDANDO_LA_CUENTA
import com.avoqado.pos.pos.presentation.cart.USAR_PUNTOS
import com.avoqado.pos.pos.presentation.cart.explicacionDelAtajo

/**
 * D39 · atajo «Usar puntos» (promesa v2, `docs/audit/2026-11-25-defectos-lealtad-referidos/d39/promesa-atajo.md`). Lee el saldo del
 * cliente elegido, dice por qué no se puede o qué va a pasar, y con *Continuar* manda la venta a «Pagar después»; la caja abre esa cuenta
 * en Pedidos con «Recompensas». Sin «Cambiar cliente» (Codex r1 #4): el cliente es el de la venta.
 */
@Composable
fun UsarPuntosModal(cartViewModel: CartViewModel, error: String?, onDismiss: () -> Unit) {
    val cartState by cartViewModel.cartState.collectAsState()
    val cliente by cartViewModel.selectedCustomer.collectAsState()
    val lectura by cartViewModel.lecturaDelAtajo.collectAsState()
    val enCurso by cartViewModel.pagarDespuesEnCurso.collectAsState()
    LaunchedEffect(cliente?.id) { cartViewModel.leerPuntosParaElAtajo() }
    // Se recalcula con lo de arriba (venta, cliente y lectura): la razón de este momento.
    val motivo = remember(cartState, cliente, lectura) { cartViewModel.motivoSinUsarPuntos() }
    val mandando = enCurso?.deUsarPuntos == true
    AvoqadoFullScreenModal(
        title = USAR_PUNTOS,
        // Mientras se manda, no se cierra (como «Pagar después», R2-4).
        onDismiss = { if (!mandando) onDismiss() },
        primaryActionText = when {
            mandando -> GUARDANDO_LA_CUENTA
            motivo == null -> "Continuar"
            else -> null
        },
        onPrimaryAction = if (motivo == null || mandando) ({ cartViewModel.usarPuntos() }) else null,
        primaryActionEnabled = !mandando,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AvoqadoTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Loyalty, contentDescription = null, modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(text = USAR_PUNTOS, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = cliente?.name ?: "Sin cliente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = enCurso?.let { com.avoqado.pos.core.util.formatMoneyFromCents(it.totalCents) } ?: cartState.totalDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            lectura?.lealtad?.takeIf { lectura?.customerId == cliente?.id }?.let {
                Text(
                    text = "${it.balance} puntos disponibles, valen ${com.avoqado.pos.core.util.formatMoneyFromCents(Math.round(it.balanceValue * 100).toInt())}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            }
            // Sin red, sin cliente o sin puntos es un estado normal: el mismo color de las notas, nunca rojo.
            Text(
                text = error ?: motivo ?: explicacionDelAtajo(cliente?.name ?: "el cliente"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xl),
            )
        }
    }
}
