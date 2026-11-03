package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/*
 * D6 (2-nov-2026): en la tableta, con la cuenta de prueba, las tarjetas de producto salían grises y sin iniciales. Sus fotos son de
 * loremflickr, que responde 500: la foto fallaba y Android no tenía respaldo (las iniciales sólo salían SIN foto). iOS pinta el
 * color y las iniciales mientras la foto carga y si falla (`ProductTile.colorBackground`, `FamilyTile.fondo`, la miniatura del
 * carrito), y en las tarjetas un indicador mientras carga.
 */

/** Qué pasó con la foto: mientras Coil no avisa, se está CARGANDO; luego queda LISTA o FALLO. */
internal enum class EstadoDeFoto { CARGANDO, LISTA, FALLO }

/** El color y las iniciales se ven sin foto, mientras carga y si falla: sólo la foto LISTA los tapa. */
internal fun mostrarRespaldoDeFoto(conFoto: Boolean, estado: EstadoDeFoto): Boolean = !conFoto || estado != EstadoDeFoto.LISTA

/** El indicador de carga (el `ProgressView` de iOS) sólo mientras la foto viene en camino. */
internal fun mostrarCargaDeFoto(conFoto: Boolean, estado: EstadoDeFoto): Boolean = conFoto && estado == EstadoDeFoto.CARGANDO

/**
 * Las iniciales de una tarjeta: la primera letra de las dos primeras palabras, o las dos primeras letras si es una sola
 * («Agua mineral» → «AM», «Latte» → «LA»). Espejo de `Product.initials` de iOS (`CartModels.swift`), que también usan la
 * tarjeta de una familia y la miniatura del carrito. Antes la tarjeta tomaba siempre las dos primeras letras («AG»).
 */
internal fun inicialesDeTile(nombre: String): String {
    val palabras = nombre.split(" ").filter { it.isNotEmpty() }
    return if (palabras.size >= 2) {
        (primeros(palabras[0], 1) + primeros(palabras[1], 1)).uppercase()
    } else {
        primeros(nombre, 2).uppercase()
    }
}

/** Los primeros [n] caracteres sin partir un emoji en dos (Swift cuenta caracteres; Kotlin, unidades UTF-16). */
private fun primeros(texto: String, n: Int): String =
    texto.substring(0, texto.offsetByCodePoints(0, minOf(n, texto.codePointCount(0, texto.length))))

/** De qué tamaño son las iniciales de una miniatura: 3/10 de su lado menor («AM» queda holgado en cualquier tamaño). */
private const val LETRA_POR_LADO = 0.3f

/**
 * La letra de las iniciales de una [MiniaturaConIniciales], en dp: 3/10 de su lado menor, para que quepan igual en 44 dp (el
 * detalle de una venta) que en 104 dp (la pantalla del cliente). Sin un tamaño de verdad (infinito, cero o NaN: quien la usa
 * no le dio uno), `null` y se usa la letra de siempre.
 */
internal fun letraDeIniciales(anchoDp: Float, altoDp: Float): Float? {
    val lado = minOf(anchoDp, altoDp)
    return if (lado.isFinite() && lado > 0f) lado * LETRA_POR_LADO else null
}

/**
 * El recuadro de la foto de una tarjeta: la foto cuando llega; sin foto, mientras carga o si falla, [respaldo] (el color y las
 * iniciales). Con [colorDelIndicador], además un indicador mientras carga (las tarjetas; la miniatura del carrito de iOS no lo
 * lleva). Lo comparten la tarjeta del producto, la de una familia, la miniatura del carrito y [MiniaturaConIniciales].
 */
@Composable
internal fun FotoConRespaldo(
    url: String?,
    contentDescription: String?,
    colorDelIndicador: Color?,
    modifier: Modifier = Modifier,
    respaldo: @Composable BoxScope.() -> Unit,
) {
    val conFoto = !url.isNullOrEmpty()
    var estado by remember(url) { mutableStateOf(EstadoDeFoto.CARGANDO) }
    Box(modifier) {
        if (mostrarRespaldoDeFoto(conFoto, estado)) respaldo()
        if (colorDelIndicador != null && mostrarCargaDeFoto(conFoto, estado)) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(AvoqadoTheme.dimensions.iconMedium),
                color = colorDelIndicador,
                strokeWidth = 2.dp,
            )
        }
        if (conFoto) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { estado = EstadoDeFoto.LISTA },
                onError = { estado = EstadoDeFoto.FALLO },
            )
        }
    }
}

/**
 * D16 (3-nov-2026), el founder: «si no tiene fotos, que salgan las iniciales, como Square». La miniatura de un artículo fuera
 * de la cuadrícula (el detalle de una venta, las promociones, las sugerencias en la caja y en la pantalla del cliente, el
 * detalle de inventario): sin foto, mientras carga y si falla, sus iniciales sobre un fondo neutro; sólo la foto lista las tapa.
 * Sin red es justo lo que se ve: la foto que no está guardada falla y quedan las iniciales. Sin indicador de carga, como la
 * miniatura del carrito. El tamaño y el recorte los pone quien la usa en [modifier]; la letra se ajusta a ese tamaño. Es
 * decorativa: el nombre ya está escrito junto a ella. iOS tiene la misma pieza con el mismo nombre.
 */
@Composable
internal fun MiniaturaConIniciales(url: String?, nombre: String, modifier: Modifier = Modifier) {
    FotoConRespaldo(
        url = url?.takeIf { it.isNotBlank() },
        contentDescription = null,
        colorDelIndicador = null,
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val letraEnDp = letraDeIniciales(maxWidth.value, maxHeight.value)
            val estilo = MaterialTheme.typography.labelLarge
            Text(
                text = inicialesDeTile(nombre),
                // La letra sigue al recuadro y no al tamaño de letra del aparato: tiene que caber en él.
                style = if (letraEnDp == null) {
                    estilo
                } else {
                    estilo.copy(fontSize = with(LocalDensity.current) { letraEnDp.dp.toSp() }, lineHeight = TextUnit.Unspecified)
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
