package com.avoqado.pos.pos.presentation.product.variantes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.precioConUnidad
import com.avoqado.pos.pos.presentation.checkout.TileSize
import com.avoqado.pos.pos.presentation.product.ChipDeAgotado
import com.avoqado.pos.pos.presentation.product.InsigniaDeTile
import com.avoqado.pos.pos.presentation.product.coloresDeTile

/**
 * Q4 con la regla del tile de un producto suelto (`Product.displayPrice`): el mínimo, «desde» si los precios difieren y «/kg» sólo
 * si TODAS las variantes se venden por peso.
 */
val Familia.textoDePrecio: String
    get() {
        val precio = precioDeFamilia(this)
        val texto = precioConUnidad(precio.minimo, porPeso = variantes.all { it.soldByWeight })
        return if (precio.todosIguales) texto else TextosVariantes.desde(texto)
    }

/**
 * El botón del PADRE en la cuadrícula (spec 2026-10-14 §11): tocarlo abre la hoja de variantes; nunca agrega nada por sí solo.
 * Mismo tamaño y anatomía que `ProductTile` (respeta `tileSize`) para que la cuadrícula no cambie de ritmo. Espejo de
 * `FamilyTile.swift`. Su insignia es propia: la vieja de `ProductTile` (la de `Product.variants`) no se toca.
 */
@Composable
fun TileDeFamilia(familia: Familia, tileSize: TileSize, onTap: () -> Unit) {
    val precio = familia.textoDePrecio
    val forma = RoundedCornerShape(AvoqadoTheme.cornerRadius.md)
    // Sin foto, el color de su primera variante: la MISMA regla que el tile de un producto suelto (iOS igual).
    val (fondo, colorDeIniciales) = coloresDeTile(familia.variantes.first().color)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = forma)   // la misma sombra que ProductTile
            .semantics { contentDescription = TextosVariantes.accesibleFamilia(familia.nombre, familia.variantes.size, precio) },
        shape = forma,
        color = MaterialTheme.colorScheme.surface,
        onClick = onTap,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(tileSize.productImageAspect)
                    .background(fondo),
            ) {
                if (!familia.imagenUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = familia.imagenUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = familia.nombre.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = colorDeIniciales,
                        modifier = Modifier.align(Alignment.BottomStart).padding(AvoqadoTheme.spacing.sm),
                    )
                }
                // Las mismas piezas que ProductTile, para no mezclar insignias claras y oscuras en la cuadrícula.
                InsigniaDeTile(TextosVariantes.variantes(familia.variantes.size), Modifier.align(Alignment.TopStart))
                // Q5: sólo si TODAS están agotadas. Avisa y nunca bloquea: la hoja deja agregar.
                if (agotada(familia)) ChipDeAgotado(TextosVariantes.AGOTADO, Modifier.align(Alignment.TopEnd))
            }
            Column(modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = familia.nombre,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Text(text = precio, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
