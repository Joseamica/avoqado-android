package com.avoqado.pos.announcements.presentation

import android.content.Context
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogWindowProvider
import com.avoqado.pos.designsystem.components.ImmersiveWindow

// 🔴 Archivo SÓLO de Android (ComponentDialog, AbstractComposeView): el POS de Windows lo excluye en
// desktop/pos/excluidos.txt y compila su reemplazo, desktop/pos/src/main/kotlin/com/avoqado/pos/announcements/presentation/VisorDeFoto.kt.
// Si cambias este archivo, revisa el reemplazo y corre `./gradlew -p desktop :pos:actualizarHuellas`.

/**
 * La foto a pantalla completa sobre negro, con botón de cerrar. Sin red usa la caché de Coil o se queda en negro.
 *
 * 🔴 NO es un `Dialog` de Compose: su `DialogLayout` mide el contenido con `Configuration.screenHeightDp` (la pantalla
 * MENOS las barras del sistema) y en cada layout fija la ventana a esa medida con `window.setLayout(ancho, alto)`. En la
 * D3 eso daba 1920×954 centrada (`[0,63][1920,1017]`) y por arriba y abajo asomaba la app. Ningún flag de ventana lo
 * evita (probado en la ronda 3: `fitInsetsTypes = 0`, `NO_LIMITS`, `MATCH_PARENT` llegan y el alto se vuelve a pisar).
 *
 * Es una ventana propia de pantalla completa (tema no flotante: `MATCH_PARENT` y sin huecos), con el mismo patrón que
 * `CustomerDisplayPresentation`: un `ComposeView` dentro de un diálogo de plataforma. Se agrega DESPUÉS de la ventana
 * del anuncio, así que queda encima. Atrás la cierra (cancelar el diálogo). Hereda el tema por [rememberCompositionContext].
 */
@Composable
fun VisorDeFoto(url: String, alt: String?, onCerrar: () -> Unit) {
    val context = LocalContext.current
    val padre = rememberCompositionContext()
    val alCerrar by rememberUpdatedState(onCerrar)
    DisposableEffect(context, url) {
        val dialogo = ComponentDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialogo.setOnCancelListener { alCerrar() }   // Atrás
        dialogo.setContentView(
            VistaDelVisor(dialogo.context, dialogo.window!!) { ContenidoDelVisor(url, alt) { alCerrar() } }.apply {
                setParentCompositionContext(padre)
            },
        )
        dialogo.show()
        onDispose { dialogo.dismiss() }
    }
}

/**
 * La vista del visor es la que da su ventana a [ImmersiveWindow] (que la busca en `LocalView.current.parent`), igual
 * que el `DialogLayout` de un `Dialog` de Compose. Medido en la D3: con un contenedor aparte no la encontraba y el dock
 * de Sunmi se quedaba abajo.
 */
private class VistaDelVisor(
    context: Context,
    override val window: Window,
    private val contenido: @Composable () -> Unit,
) : AbstractComposeView(context), DialogWindowProvider {
    @Composable
    override fun Content() = contenido()
}

