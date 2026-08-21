package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderStatus

// MARK: - Color Constants

private val PreparingAmber = Color(0xFFFFA000)
private val ReadyGreen = Color(0xFF4CAF50)
private val LateRedBorder = Color(0xFFFF3B30)
private const val LATE_THRESHOLD_MS = 10 * 60 * 1000L

// MARK: - KDS Order Card

@Composable
fun KDSOrderCard(
    order: KDSOrder,
    elapsedText: String,
    isLargeFont: Boolean,
    onAdvanceStatus: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sólo se usan cuando `order.needsAcceptance` — ver el bloque de acciones abajo. */
    onAcceptDelivery: () -> Unit = {},
    onDenyDelivery: () -> Unit = {},
) {
    val isLate = (System.currentTimeMillis() - order.createdAt) > LATE_THRESHOLD_MS
            && order.status != KDSOrderStatus.READY
            && order.status != KDSOrderStatus.COMPLETED

    val borderColor = when {
        isLate -> LateRedBorder
        else -> when (order.status) {
            KDSOrderStatus.NEW -> MaterialTheme.colorScheme.surfaceContainerHigh
            KDSOrderStatus.PREPARING -> PreparingAmber
            KDSOrderStatus.READY -> ReadyGreen
            KDSOrderStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    }

    val cardBorderColor = if (isLate) LateRedBorder else Color.Transparent

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = if (isLate) {
            androidx.compose.foundation.BorderStroke(2.dp, LateRedBorder)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left color bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = AvoqadoTheme.cornerRadius.lg, bottomStart = AvoqadoTheme.cornerRadius.lg))
                    .background(borderColor),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.md),
            ) {
                // Header: Order # + elapsed time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#${order.orderNumber}",
                        style = if (isLargeFont) MaterialTheme.typography.headlineSmall
                                else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = elapsedText,
                        style = if (isLargeFont) MaterialTheme.typography.bodyLarge
                                else MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (isLate) LateRedBorder
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))

                // Order type badge
                OrderTypeBadge(orderType = order.orderType)

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                // Items list
                order.items.forEach { item ->
                    KDSItemRow(item = item, isLargeFont = isLargeFont)
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

                // 🔴 Pedido de delivery que NADIE ha aceptado todavía (canal en modo MANUAL).
                // El proveedor da ~11.5 minutos y después lo cancela solo: el cliente se queda
                // sin comida y el rechazo cuenta contra la tasa que Uber exige para no
                // suspender la integración. Por eso esto REEMPLAZA al botón de preparar —
                // ponerse a cocinar antes de aceptar es cocinar algo que quizá ya se canceló.
                if (order.needsAcceptance) {
                    Text(
                        text = "Falta aceptarlo en la app de delivery",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = LateRedBorder,
                        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.xs),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // "No puedo" va PRIMERO y en tamaño completo, no escondido: cuando se
                        // acabó un ingrediente hay que poder decirlo rápido, y esconderlo
                        // empuja a aceptar un pedido que no se va a poder entregar.
                        OutlinedButton(
                            onClick = onDenyDelivery,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        ) {
                            Text("No puedo", style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = onAcceptDelivery,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        ) {
                            Text("Aceptar", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    return@Column
                }

                // Action button
                val (buttonText, buttonColor) = when (order.status) {
                    KDSOrderStatus.NEW -> "Preparar" to MaterialTheme.colorScheme.primary
                    KDSOrderStatus.PREPARING -> "Listo" to PreparingAmber
                    KDSOrderStatus.READY -> "Completar" to ReadyGreen
                    KDSOrderStatus.COMPLETED -> "Completado" to MaterialTheme.colorScheme.surfaceContainerHigh
                }

                Button(
                    onClick = onAdvanceStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = if (order.status == KDSOrderStatus.NEW) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White
                        },
                    ),
                    enabled = order.status != KDSOrderStatus.COMPLETED,
                ) {
                    Text(
                        text = buttonText,
                        style = if (isLargeFont) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// MARK: - Order Type Badge

@Composable
private fun OrderTypeBadge(orderType: String) {
    val backgroundColor = when (orderType.lowercase()) {
        "para llevar" -> Color(0xFFFFF3E0)
        "delivery" -> Color(0xFFE3F2FD)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (orderType.lowercase()) {
        "para llevar" -> Color(0xFFE65100)
        "delivery" -> Color(0xFF1565C0)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
    ) {
        Text(
            text = orderType,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}

// MARK: - Item Row

@Composable
private fun KDSItemRow(
    item: com.avoqado.pos.kds.domain.KDSOrderItem,
    isLargeFont: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${item.quantity}x ${item.productName}",
                style = if (isLargeFont) MaterialTheme.typography.bodyLarge
                        else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // Modifiers
        if (item.modifiers.isNotEmpty()) {
            item.modifiers.forEach { mod ->
                Text(
                    text = "  - $mod",
                    style = if (isLargeFont) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Notes
        if (!item.notes.isNullOrBlank()) {
            Text(
                text = "  Nota: ${item.notes}",
                style = if (isLargeFont) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFFFA000),
            )
        }
    }
}
