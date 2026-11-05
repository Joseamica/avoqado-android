package com.avoqado.pos.announcements.presentation

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.pos.presentation.product.EstadoDeFoto

/*
 * Fotos de un anuncio: completas (nunca recortadas), con la ventana angosta y un visor a pantalla completa.
 * Las medidas se CALCULAN y se aplican exactas con `size`/`width` (codex r1 #9: encadenar `aspectRatio` y topes
 * de Compose anulaba los topes según el orden de los modificadores).
 */

/** Ancho de la ventana del anuncio: 92 % de la pantalla, sin pasar de 680 dp. */
internal fun anchoDeVentana(pantallaDp: Float): Float = minOf(pantallaDp * 0.92f, 680f)

/** Alto máximo de una foto: 480 dp en pantalla ancha (>= 600 dp); en angosta, el 60 % del alto. */
internal fun topeDeAlto(pantallaAnchoDp: Float, pantallaAltoDp: Float): Float =
    if (pantallaAnchoDp >= 600f) 480f else pantallaAltoDp * 0.6f

/** (ancho, alto) en dp que ocupa la foto sin recortarse, o null si faltan sus medidas (anuncios viejos). */
internal fun medidaDeFoto(anchoDisponibleDp: Float, width: Int?, height: Int?, topeDp: Float): Pair<Float, Float>? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val alto = minOf(anchoDisponibleDp * height / width, topeDp)
    return (alto * width / height) to alto
}

/** Una foto del anuncio, completa. Al tocarla abre el visor. `alCambiar` avisa si cargó o falló (D16: la que falla se esconde). */
@Composable
internal fun FotoDeAnuncio(
    url: String,
    alt: String?,
    width: Int?,
    height: Int?,
    modifier: Modifier = Modifier,
    alCambiar: (EstadoDeFoto) -> Unit = {},
) {
    val config = LocalConfiguration.current
    val tope = topeDeAlto(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
    var visor by remember(url) { mutableStateOf(false) }
    var estado by remember(url) { mutableStateOf(EstadoDeFoto.CARGANDO) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { visor = true },
        contentAlignment = Alignment.Center,
    ) {
        val medida = medidaDeFoto(maxWidth.value, width, height, tope)
        val alCargar = { e: EstadoDeFoto -> estado = e; alCambiar(e) }
        AsyncImage(
            model = url,
            contentDescription = alt,
            contentScale = ContentScale.Fit,
            modifier = if (medida != null) {
                Modifier.size(medida.first.dp, medida.second.dp)
            } else {
                // Sin medidas no hay aspectRatio antes: aquí heightIn sí manda.
                Modifier.fillMaxWidth().heightIn(max = tope.dp)
            },
            onSuccess = { alCargar(EstadoDeFoto.LISTA) },
            onError = { alCargar(EstadoDeFoto.FALLO) },
        )
    }
    if (visor && estado != EstadoDeFoto.FALLO) VisorDeFoto(url, alt) { visor = false }
}

/** La foto a pantalla completa sobre negro, con botón de cerrar. Sin red usa la caché de Coil o se queda en negro. */
@Composable
fun VisorDeFoto(url: String, alt: String?, onCerrar: () -> Unit) {
    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImmersiveWindow()   // primero: antes del primer dibujo, para que no asomen las barras
        VentanaSinHuecos()  // y que la ventana mida la pantalla entera, no la pantalla menos las barras
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = url,
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onCerrar, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }
    }
}

/**
 * 🔴 La ventana de un `Dialog` es «flotante» y, desde Android 11, su marco se encoge por las barras del sistema
 * (`fitInsetsTypes = systemBars()` por defecto en ventanas flotantes; las de pantalla normal lo traen en 0). Ocultar las
 * barras con [ImmersiveWindow] no basta: en la D3 (Android 14) el visor medía `[0,63][1920,1017]` y por los huecos de
 * arriba y abajo se veía la app («Agregar cliente» y la barra «Cobrar · Inventario…»). Es el mismo límite que Mesas
 * anotó en `ImmersiveDialogWindow` (probó `setLayout(MATCH_PARENT)` y `FLAG_LAYOUT_NO_LIMITS`, pero no `fitInsetsTypes`).
 *
 * Aquí la ventana deja de ajustarse a cualquier hueco (`fitInsetsTypes = 0`, lados = 0, también bajo el recorte de la
 * cámara) y mide la pantalla completa. Mismo patrón que [ImmersiveWindow]: se toca en la composición, antes del primer
 * dibujo. Sólo para el visor: las hojas de abajo se anclan al borde y no deben meterse bajo la barra de navegación.
 */
@Composable
private fun VentanaSinHuecos() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    remember(window) {
        window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.attributes = w.attributes.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        window
    }
}
