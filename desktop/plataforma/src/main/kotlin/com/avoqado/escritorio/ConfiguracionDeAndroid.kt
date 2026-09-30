package com.avoqado.escritorio

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Traduce el tamaño de la ventana a la Configuration de Android que leen las pantallas y el DeviceHeadersInterceptor. */
@Composable
fun ConfiguracionDeAndroid(ancho: Dp, alto: Dp, content: @Composable () -> Unit) {
    val densidad = LocalDensity.current.density
    val configuracion = remember(ancho, alto, densidad) {
        configuracionDeAndroid(ancho, alto, densidad).also { Escritorio.contexto.resources.configuration.copiarDe(it) }
    }
    CompositionLocalProvider(LocalConfiguration provides configuracion) { content() }
}

/** La Configuration de una ventana de ancho×alto. También la usa el arranque para sembrarla antes de la primera petición. */
fun configuracionDeAndroid(ancho: Dp, alto: Dp, densidad: Float): Configuration = Configuration().apply {
    screenWidthDp = ancho.value.toInt()
    screenHeightDp = alto.value.toInt()
    smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
    densityDpi = (densidad * 160).toInt()
    orientation = if (screenWidthDp >= screenHeightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
}
