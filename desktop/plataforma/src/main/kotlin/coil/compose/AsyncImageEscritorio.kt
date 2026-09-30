package coil.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import com.avoqado.escritorio.CacheDeImagenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

// Tope propio: con WiFi sin internet las descargas colgadas no deben agotar el pool de IO (Room, cola del cobro).
@OptIn(ExperimentalCoroutinesApi::class)
private val descargas = Dispatchers.IO.limitedParallelism(4)

/** Lo mínimo del painter de Coil que la app nombra (sólo el tipo del parámetro onError). */
object AsyncImagePainter {
    sealed interface State {
        class Error(val mensaje: String?) : State
    }
}

/** Sustituto de Coil: la URL sale de `model`, con caché en memoria; vacío mientras carga o si falla (y avisa por onError). */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    val url = model as? String
    var imagen by remember(url) { mutableStateOf(url?.let { CacheDeImagenes.enMemoria(it) }) }
    val alFallar = rememberUpdatedState(onError)
    LaunchedEffect(url) {
        if (url != null && imagen == null) {
            imagen = withContext(descargas) { CacheDeImagenes.cargar(url) }
            if (imagen == null) alFallar.value?.invoke(AsyncImagePainter.State.Error("No se pudo cargar la imagen"))
        }
    }
    val bitmap = imagen
    if (bitmap != null) {
        Image(bitmap, contentDescription, modifier, alignment, contentScale, alpha, colorFilter)
    } else {
        Box(modifier)
    }
}
