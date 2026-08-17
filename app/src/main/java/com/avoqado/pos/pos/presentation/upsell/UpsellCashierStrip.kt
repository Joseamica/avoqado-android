package com.avoqado.pos.pos.presentation.upsell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avoqado.pos.pos.data.model.UpsellCard

private val Accent = Color(0xFF7ADD2C)

/**
 * Upsell "¿Algo más?" — la superficie del CAJERO.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md
 *
 * 🔴 Existe porque la mayoría de los mostradores NO tienen segunda pantalla, y
 * los que la tienen a veces la traen mirando a la pared. Si el upsell viviera
 * sólo en la pantalla del cliente, la función no existiría para casi nadie.
 *
 * Es una TIRA, no un diálogo a pantalla completa: el cobro es el acto principal
 * y esto no puede secuestrarlo. "Cobrar" queda siempre visible y a un toque.
 */
@Composable
fun UpsellCashierStrip(
    cards: List<UpsellCard>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deltaCents = cards.filter { it.ruleId in selected }.sumOf { it.displayPriceCents }

    // 🔴 MINIMIZABLE. Aunque el cajón sea bajo, el cajero necesita poder quitarlo de
    // enmedio SIN perder la sugerencia: colapsa a una sola línea y se vuelve a abrir
    // de un toque. Cerrar ("No, gracias") es otra cosa y sigue siendo explícito.
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 🔴 El gesto vive SÓLO en la cabecera, no en toda la tarjeta.
            //
            // Antes estaba en el Card entero y las tarjetas —que son botones y una
            // fila con desplazamiento horizontal— se comían el toque antes de que
            // llegara: por eso arrastrar no hacía nada. Aquí no compite con nadie.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 12f) expanded = false
                            if (dragAmount < -12f) expanded = true
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "¿Algo más?",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Colapsado se dice CUÁNTAS hay, para que el cajero sepa que no
                // desapareció nada y valga la pena volver a abrirlo.
                if (!expanded) {
                    Text(
                        text = if (cards.size == 1) "1 sugerencia" else "${cards.size} sugerencias",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (expanded) "Minimizar" else "Mostrar sugerencias",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                // Las tarjetas van EN LA MISMA FILA que el título y se desplazan:
                // así el cajón mide ~1/3 de lo que medía y nunca tapa el carrito,
                // aunque lleguen las 3 tarjetas en un teléfono angosto.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cards.forEach { card ->
                        UpsellChip(
                            card = card,
                            isSelected = card.ruleId in selected,
                            onTap = { onToggle(card.ruleId) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "No, gracias" con el mismo peso visual que agregar: el camino de
                // NO vender no puede ser más difícil que el de vender.
                TextButton(onClick = onSkip) {
                    Text("No, gracias", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Button(
                    onClick = if (selected.isEmpty()) onSkip else onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        // Sin nada marcado el botón NO se apaga: se convierte en
                        // "Cobrar". Un botón deshabilitado en la ruta del cobro es
                        // como el cajero se queda mirando la pantalla.
                        text = if (selected.isEmpty()) "Cobrar" else "Agregar y cobrar (+${money(deltaCents)})",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpsellChip(
    card: UpsellCard,
    isSelected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Compacta y horizontal: miniatura al lado del texto, no una foto encima. Así el
    // cajón cabe en una franja y el cajero sigue viendo el carrito que está cobrando.
    Card(
        modifier = modifier.clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) BorderStroke(2.dp, Accent) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!card.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                )
            }
            Column {
                Text(
                    text = card.headline ?: card.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 🟡 Cuando hay `headline`, ese Text de arriba lo muestra EN VEZ
                // de `card.name` — y `card.name` es donde vive el modificador
                // resuelto ("Agua Mineral 1L (Grande)"). Sin esta línea, un
                // headline escondería POR QUÉ el precio no es el de lista.
                if (card.modifiers.isNotEmpty()) {
                    Text(
                        text = card.modifiers.joinToString(", ") { it.name },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = money(card.displayPriceCents),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    card.badge?.let {
                        Box(modifier = Modifier.width(6.dp))
                        Text(text = it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                }
            }
        }
    }
}

private fun money(cents: Int): String = "$" + String.format("%.2f", cents / 100.0)
