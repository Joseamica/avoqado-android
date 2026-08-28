package com.avoqado.pos.announcements.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.announcements.data.model.AnnouncementDetail
import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * El anuncio abierto, con su contenido ampliado.
 *
 * Sirve para las dos cosas, igual que en el dashboard: el detalle que se abre al tocar el
 * aviso en el buzón, y la ventana que interrumpe una vez (`showAsModal`).
 *
 * Espejo de `AnnouncementDetailView.swift` en iOS: mismos textos, mismo orden, misma
 * semántica. Si cambias uno, cambia el otro en el mismo trabajo.
 */
@Composable
fun AnnouncementSheet(
    anuncio: AnnouncementDetail,
    bloques: List<ContentBlock>,
    onCerrar: () -> Unit,
    onAccion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = "Novedades de Avoqado",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = anuncio.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = anuncio.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            anuncio.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            bloques.forEach { bloque -> BloqueDeContenido(bloque, onAccion) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (anuncio.actionLabel != null && anuncio.actionUrl != null) {
                TextButton(onClick = onCerrar) { Text("Cerrar") }
                Button(onClick = { onAccion(anuncio.actionUrl) }) { Text(anuncio.actionLabel) }
            } else {
                Button(onClick = onCerrar) { Text("Entendido") }
            }
        }
    }
}

@Composable
private fun BloqueDeContenido(bloque: ContentBlock, onAccion: (String) -> Unit) {
    val spacing = AvoqadoTheme.spacing
    when (bloque) {
        is ContentBlock.Heading -> Text(
            text = bloque.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is ContentBlock.Paragraph -> Text(
            text = bloque.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is ContentBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            bloque.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ContentBlock.Image -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            AsyncImage(
                model = bloque.url,
                contentDescription = bloque.alt,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            bloque.caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        is ContentBlock.Gallery -> Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            bloque.images.forEach { img ->
                AsyncImage(
                    model = img.url,
                    contentDescription = img.alt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }

        is ContentBlock.Specs -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            bloque.rows.forEach { (etiqueta, valor) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(etiqueta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(valor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        is ContentBlock.Callout -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(spacing.md),
        ) {
            Text(
                text = bloque.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ContentBlock.ActionButton -> Button(onClick = { onAccion(bloque.url) }) {
            Text(bloque.label)
        }

        ContentBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
