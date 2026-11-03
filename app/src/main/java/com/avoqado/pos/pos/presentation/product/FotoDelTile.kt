package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

/**
 * El recuadro de la foto de una tarjeta: la foto cuando llega; sin foto, mientras carga o si falla, [respaldo] (el color y las
 * iniciales). Con [colorDelIndicador], además un indicador mientras carga (las tarjetas; la miniatura del carrito de iOS no lo
 * lleva). Lo comparten la tarjeta del producto, la de una familia y la miniatura del carrito.
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
