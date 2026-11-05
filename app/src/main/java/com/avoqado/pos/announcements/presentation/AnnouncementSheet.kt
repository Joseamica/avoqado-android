package com.avoqado.pos.announcements.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.announcements.data.model.AnnouncementDetail
import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.presentation.product.EstadoDeFoto

/*
 * D16 (3-nov-2026): «si no tiene fotos, que salgan las iniciales, como Square» es para los artículos; un anuncio no tiene
 * iniciales que poner en lugar de su imagen. Mientras carga, la imagen guarda su lugar (un gris tenue, como el `placeholder`
 * de iOS); si falla (sin red falla la que no está guardada, o la liga ya no existe) se esconde: un recuadro vacío parece roto.
 */

/** Si una imagen de un anuncio se pinta: con URL, mientras carga (guarda su lugar) y ya lista; si su foto falla, no. */
internal fun mostrarImagenDeAnuncio(url: String?, estado: EstadoDeFoto): Boolean =
    !url.isNullOrBlank() && estado != EstadoDeFoto.FALLO

/** Las fotos de una galería que se pintan (por posición): las que no han fallado. Sin ninguna, la galería entera se esconde. */
internal fun fotosVisiblesDeGaleria(urls: List<String>, estados: Map<Int, EstadoDeFoto>): List<Int> =
    urls.indices.filter { mostrarImagenDeAnuncio(urls[it], estados[it] ?: EstadoDeFoto.CARGANDO) }

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
                var estado by remember(url) { mutableStateOf(EstadoDeFoto.CARGANDO) }
                if (mostrarImagenDeAnuncio(url, estado)) {
                    FotoDeAnuncio(url = url, alt = null, width = null, height = null, alCambiar = { estado = it })
                }
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

        // D16: si su foto no carga se esconde con todo y su pie (un pie sin su foto no explica nada).
        is ContentBlock.Image -> {
            var estado by remember(bloque.url) { mutableStateOf(EstadoDeFoto.CARGANDO) }
            if (mostrarImagenDeAnuncio(bloque.url, estado)) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    FotoDeAnuncio(
                        url = bloque.url,
                        alt = bloque.alt,
                        width = bloque.width,
                        height = bloque.height,
                        alCambiar = { estado = it },
                    )
                    bloque.caption?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // D16: cada foto que no carga se esconde; si no carga ninguna, la galería entera, sin dejar un hueco.
        is ContentBlock.Gallery -> {
            val estados = remember(bloque) { mutableStateMapOf<Int, EstadoDeFoto>() }
            val visibles = fotosVisiblesDeGaleria(bloque.images.map { it.url }, estados)
            if (visibles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    visibles.forEach { i ->
                        key(i) {
                            val img = bloque.images[i]
                            FotoDeAnuncio(
                                url = img.url,
                                alt = img.alt,
                                width = img.width,
                                height = img.height,
                                alCambiar = { estados[i] = it },
                            )
                        }
                    }
                }
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
