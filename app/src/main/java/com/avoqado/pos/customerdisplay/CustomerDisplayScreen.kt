package com.avoqado.pos.customerdisplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Pantalla de cara al CLIENTE. Pensada para la pantalla CHICA del POS de
 * mostrador: tipografía grande, jerarquía de una sola idea por vista y áreas
 * de toque generosas (el cliente la usa de pie, sin lentes puestos a veces).
 */
@Composable
fun CustomerDisplayScreen(
    state: CustomerDisplayState,
    onRating: (Int) -> Unit,
    onTip: (Int) -> Unit,
) {
    val content by state.content.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val c = content) {
            is CustomerContent.Idle -> IdleBranding()
            is CustomerContent.Cart -> CartMirror(c)
            is CustomerContent.Rating -> RatingPrompt(c, onRating)
            is CustomerContent.Tip -> TipPrompt(c, onTip)
            is CustomerContent.Total -> TotalOnly(c)
            is CustomerContent.Charging -> ChargingPrompt(c)
            is CustomerContent.Done -> DonePrompt(c)
        }
    }
}

// MARK: - Sin venta: la marca

@Composable
private fun IdleBranding() {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Avoqado",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = "Bienvenido",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Carrito en vivo

@Composable
private fun CartMirror(cart: CustomerContent.Cart) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Tu compra",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.lg,
            ),
        )
        HorizontalDivider()

        // El último artículo agregado importa más que el primero: la lista
        // crece hacia abajo y el cliente sigue lo que acaba de pasar.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            items(cart.items, key = { it.id }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${item.quantity}×",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = money(item.totalPrice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Totales: lo que el cliente revisa antes de pagar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(AvoqadoTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            if (cart.discountCents > 0) {
                TotalRow("Subtotal", money(cart.subtotalCents))
                TotalRow("Descuento", "−${money(cart.discountCents)}")
            }
            if (cart.taxCents > 0) TotalRow("Impuestos", money(cart.taxCents))
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = money(cart.totalCents),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Estrellas

@Composable
private fun RatingPrompt(c: CustomerContent.Rating, onRating: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¿Cómo estuvo tu experiencia?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
            (1..5).forEach { star ->
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "$star estrellas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(64.dp)
                        .clickable { onRating(star) },
                )
            }
        }
    }
}

// MARK: - Propina

@Composable
private fun TipPrompt(c: CustomerContent.Tip, onTip: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¿Deseas dejar propina?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = money(c.amountCents),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
            c.suggestions.forEach { percent ->
                val tipCents = c.amountCents * percent / 100
                Card(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .clickable { onTip(tipCents) },
                ) {
                    Column(
                        modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$percent%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(money(tipCents), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = "Sin propina",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onTip(0) }
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
        )
    }
}

// MARK: - Le toca al cajero: solo el total, nada tocable

@Composable
private fun TotalOnly(c: CustomerContent.Total) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Total",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = money(c.totalCents),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// MARK: - Cobrando

@Composable
private fun ChargingPrompt(c: CustomerContent.Charging) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = money(c.totalCents),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = c.message,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Gracias + recibo

@Composable
private fun DonePrompt(c: CustomerContent.Done) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¡Gracias por tu compra!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = money(c.totalCents),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        // El QR del recibo digital se dibuja solo cuando el server dio una URL;
        // sin ella la pantalla NO miente con un código que no lleva a nada.
        c.receiptUrl?.let { url ->
            Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
            QrCode(content = url, size = 220.dp)
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "Escanea para tu recibo",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun money(cents: Int): String = "$%,.2f".format(cents / 100.0)
