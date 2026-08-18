package com.avoqado.pos.pos.presentation.upsell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
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
 * 🟠 Rediseño 2026-08-18, pedido del founder con Uber Eats de referencia. Antes
 * era una TIRA baja con las tarjetas en una fila que se desplazaba en horizontal:
 * el nombre resuelto se cortaba a media palabra ("¿Le agregamos un agua bien …")
 * y la miniatura era de 36dp. El comentario original defendía la tira porque "el
 * cobro es el acto principal y esto no puede secuestrarlo".
 *
 * Esa preocupación SIGUE VIGENTE y por eso la hoja no es un diálogo modal: crece
 * hasta 3/4 de la pantalla, pero el botón principal queda ANCLADO abajo y siempre
 * visible, y la cabecera sigue minimizando de un toque o de un arrastre. Se gana
 * el espacio para leer sin que el cobro quede detrás de una lista.
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

    // 🔴 MINIMIZABLE. Aunque la hoja sea alta, el cajero necesita poder quitarla de
    // enmedio SIN perder la sugerencia: colapsa a una sola línea y se vuelve a abrir
    // de un toque. Cerrar ("No, gracias") es otra cosa y sigue siendo explícito.
    var expanded by remember { mutableStateOf(true) }

    // Una columna en teléfono —como la referencia de Uber Eats en portrait— y dos en
    // tablet, donde la hoja mide lo suficiente para que una sola columna deje media
    // pantalla vacía. La D3 del mostrador es 1920x1080 apaisada: ahí van dos.
    val columnas = if (AvoqadoTheme.adaptive.sizeClass == AvoqadoAdaptiveSizeClass.Compact) 1 else 2

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 3/4 de la pantalla es el TOPE, no la altura fija: con una sola sugerencia
        // la hoja mide lo que mide su contenido y no deja un hueco vacío.
        val altoMaximo = maxHeight * 0.75f

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = AvoqadoTheme.spacing.xl, topEnd = AvoqadoTheme.spacing.xl),
            // 🟡 Gris nativo (`surfaceVariant`), el MISMO tono que el círculo del
            // "+": con `surface` la hoja quedaba del color exacto de la pantalla de
            // cobro y no se veía dónde empezaba. El borde superior la remata.
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = altoMaximo)
                    .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            ) {
                // 🔴 El gesto vive SÓLO en la cabecera, no en toda la hoja: las filas
                // son botones y se comerían el toque antes de que llegara aquí.
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
                        .padding(vertical = AvoqadoTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Text(
                        text = "¿Algo más?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Colapsada se dice CUÁNTAS hay, para que el cajero sepa que no
                    // desapareció nada y valga la pena volver a abrirla.
                    if (!expanded) {
                        Text(
                            text = if (cards.size == 1) "1 sugerencia" else "${cards.size} sugerencias",
                            style = MaterialTheme.typography.bodyMedium,
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

                if (expanded) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    // La lista se lleva el espacio sobrante (weight) para que el botón
                    // de abajo quede ANCLADO: con muchas sugerencias se desplaza la
                    // lista, nunca el botón. Es lo que evita que el cobro se esconda.
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        items(cards.chunked(columnas)) { fila ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                            ) {
                                fila.forEach { card ->
                                    UpsellRow(
                                        card = card,
                                        isSelected = card.ruleId in selected,
                                        onTap = { onToggle(card.ruleId) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // Rellena la celda que falta para que una fila impar no
                                // estire la última tarjeta al doble de ancho.
                                if (fila.size < columnas) {
                                    repeat(columnas - fila.size) {
                                        Box(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                }

                // 🔴 UN SOLO botón, ancho completo, como la referencia. Sin nada
                // marcado dice "No, gracias" y sigue al cobro; con algo marcado se
                // vuelve "Continuar" y muestra el monto.
                //
                // El monto NO es decorativo: es la promesa que el cajero compara
                // contra el total. Se verificó en hardware (2026-08-18) que este
                // "+$50.00" coincide exactamente con lo que termina cobrando la
                // orden. Si algún día se quita, se pierde esa garantía visible.
                //
                // Color invertido por tema — mismo patrón que `AvoqadoFullscreenHeader`:
                // `onSurface` es blanco en oscuro y negro en claro.
                Button(
                    onClick = if (selected.isEmpty()) onSkip else onConfirm,
                    modifier = Modifier.fillMaxWidth().height(AvoqadoTheme.adaptive.circularIconButtonSize + AvoqadoTheme.spacing.sm),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = if (selected.isEmpty()) "No, gracias" else "Continuar (+${money(deltaCents)})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Una fila de sugerencia, al estilo de la hoja de acompañamientos de Uber Eats:
 * miniatura grande, el texto con aire, y un botón circular a la derecha que pasa de
 * "+" a la cantidad cuando ya se agregó.
 *
 * 🔴 El nombre YA NO se acota a 150dp. Ese tope existía porque la fila vivía dentro
 * de un `horizontalScroll` con constraints infinitas y el ellipsis nunca disparaba;
 * aquí la celda tiene ancho real, así que el nombre se lee completo en dos líneas.
 */
@Composable
private fun UpsellRow(
    card: UpsellCard,
    isSelected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onTap),
        shape = RoundedCornerShape(AvoqadoTheme.spacing.md),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
        colors = CardDefaults.cardColors(
            // Sin tinte de color: seleccionado se dice con el borde y con el
            // círculo relleno, no pintando la tarjeta. Un tinte aquí competiría
            // con el gris de la hoja y se vería sucio.
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            if (!card.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.spacing.sm))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                // 🔴 El NOMBRE DEL PRODUCTO manda, y `card.name` ya trae el
                // modificador resuelto dentro ("Agua Mineral 1L (Grande)"), así que
                // no hace falta una línea aparte para los modificadores.
                //
                // Antes esto era `card.headline ?: card.name`: el gancho REEMPLAZABA
                // al nombre, y el cajero veía "¿Le agregamos un agua bien fría?" sin
                // saber NUNCA qué producto era ni de qué tamaño. El gancho es para
                // convencer; el nombre es para saber qué se está vendiendo. Van los
                // dos, en ese orden de peso.
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                card.headline?.takeIf { it.isNotBlank() && it != card.name }?.let { gancho ->
                    Text(
                        text = gancho,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = money(card.displayPriceCents),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    card.badge?.let {
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Accent,
                        )
                    }
                }
            }

            // El círculo de Uber Eats: "+" para agregar, la cantidad cuando ya está.
            // Comparte el toque con toda la fila a propósito — el objetivo del cajero
            // es agregar rápido, no apuntarle a un blanco de 44dp con fila esperando.
            Box(
                modifier = Modifier
                    .size(AvoqadoTheme.adaptive.circularIconButtonSize)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onTap),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Text(
                        text = "1",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.surface,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Agregar ${card.name}",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun money(cents: Int): String = "$" + String.format("%.2f", cents / 100.0)
