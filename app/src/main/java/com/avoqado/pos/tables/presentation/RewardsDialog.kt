package com.avoqado.pos.tables.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * El diálogo de «Recompensas» (canje de puntos de lealtad). Lo usan Mesas y, desde D39, las cuentas abiertas de Pedidos: el mismo
 * diálogo y el mismo canje. [nota] es la línea de abajo; sólo donde se puede quitar la recompensa promete que los puntos regresan.
 */
@Composable
internal fun RewardsDialog(
    loyalty: com.avoqado.pos.tables.data.CustomerLoyalty?,
    motivo: String?,
    aviso: String?,
    enLinea: Boolean,
    onDismiss: () -> Unit,
    onRedeem: (Int, Double) -> Unit,
    nota: (com.avoqado.pos.tables.data.CustomerLoyalty) -> String = {
        "Mínimo ${it.minPointsRedeem} puntos. Si quitas la recompensa de la cuenta, los puntos regresan."
    },
) {
    val maximo = loyalty?.maxRedeemablePoints ?: 0
    // Arranca en el máximo canjeable de ESTA lectura: cada lectura nueva vuelve a empezar ahí.
    var points by remember(loyalty, maximo) { mutableStateOf(maximo) }
    val step = (loyalty?.minPointsRedeem ?: 0).coerceAtLeast(1)

    AvoqadoDialog(
        title = "Recompensas",
        description = loyalty?.let { "${it.customerName ?: "Cliente"} · ${it.balance} puntos disponibles, valen ${money(it.balanceValue)}" }
            ?: motivo.orEmpty(),
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Canjear",
                onClick = { onRedeem(points, loyalty?.valorDelCanje(points) ?: 0.0) },
                // Sólo con la lectura fresca de ESTA cuenta y sin motivo para no canjear: sin red, programa apagado, pocos puntos…
                // (Codex android r2 #4, r6 #1 y #3).
                enabled = motivo == null && points <= maximo && loyalty?.puedeCanjear(points, enLinea) == true,
                fullWidth = true,
            )
        },
    ) {
            Column {
                if (aviso != null) {
                    // «La cuenta cambió: revisa los puntos.» (409 del candado de la oferta): lo de abajo ya es la oferta nueva.
                    Text(text = aviso, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                }
                if (loyalty != null && loyalty.canRedeem) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(onClick = { points = (points - step).coerceAtLeast(0) }) {
                            Icon(Icons.Default.Remove, contentDescription = "Menos puntos")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$points pts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "− ${money(loyalty.valorDelCanje(points))} en la cuenta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { points = (points + step).coerceAtMost(maximo) }) {
                            Icon(Icons.Default.Add, contentDescription = "Más puntos")
                        }
                    }
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                }
                if (loyalty != null && motivo != null) {
                    // El motivo de ahora (sin red, pocos puntos…). Sin red es un estado normal: mismo color que la nota, nunca rojo.
                    Text(
                        text = motivo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                }
                if (loyalty != null) {
                    Text(
                        text = nota(loyalty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

private fun money(v: Double): String = "$" + String.format(java.util.Locale.US, "%.2f", v)
