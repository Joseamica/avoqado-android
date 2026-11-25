package com.avoqado.pos.escritorio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.avoqado.escritorio.ConfiguracionDeAndroid
import com.avoqado.escritorio.calendario.CalendarioPendiente
import com.avoqado.pos.designsystem.LocalModoLigero
import com.avoqado.pos.designsystem.components.AvoqadoLaunchSplash
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.escritorio.dibujo.DibujoDeLaCaja
import com.avoqado.pos.navigation.AvoqadoNavGraph
import com.avoqado.pos.escritorio.teclado.ConTecladoEnPantalla
import com.avoqado.pos.escritorio.teclado.TecladoDeLaVentana
import com.avoqado.pos.escritorio.teclado.TecladoEnPantalla
import kotlinx.coroutines.delay

/** El dueño de ViewModels y ciclo de vida de la raíz, como la Activity en Android. */
object RaizDeEscritorio : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    private val registro = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registro
}

/**
 * Modo ligero (hz:a5fa2439:D2), en la raíz de cada ventana: la app dibuja sin la tarjeta de video, así que el código
 * compartido quita unas animaciones y aquí se apaga la onda del clic. Apagado, la onda es la misma que hereda (no una copia).
 * Una sola llamada en los dos casos: si el modo cambia con la app abierta, nada de lo de adentro se vuelve a crear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConModoLigero(ligero: Boolean = DibujoDeLaCaja.estado.value.modoLigero, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalModoLigero provides ligero,
        LocalRippleConfiguration provides if (ligero) null else LocalRippleConfiguration.current,
        content = content,
    )
}

/** Lo que MainActivity.setContent hace en Android, sin barras de sistema ni segunda pantalla. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppEscritorio(
    contenido: @Composable (WindowSizeClass) -> Unit = { AvoqadoNavGraph(windowSizeClass = it) },
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides RaizDeEscritorio,
        LocalLifecycleOwner provides RaizDeEscritorio,
    ) {
        ConfiguracionDeAndroid(maxWidth, maxHeight) {
            var splash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(1_350L); splash = false }
            AvoqadoTheme(windowSizeClass = windowSizeClass) {
                ConModoLigero { Box(Modifier.fillMaxSize()) {
                    // Teclado híbrido: sólo con un dedo sobre un campo de texto; el contenido se encoge y las teclas van en un Popup.
                    ConTecladoEnPantalla(
                        visible = TecladoDeLaVentana.visible.value,
                        generacion = TecladoDeLaVentana.generacion.value,
                        teclado = { TecladoEnPantalla(TecladoDeLaVentana::alPresionar, TecladoDeLaVentana.estado, Modifier.fillMaxWidth()) },
                    ) { contenido(windowSizeClass) }
                    CalendarioPendiente()   // android.app.DatePickerDialog de Reportes (sustituto en desktop/plataforma)
                    AvisoDeFallaDeGuardado()   // el disco no guardó (antivirus, disco lleno): se le DICE al cajero
                    // El splash se ve y dura igual en los dos modos: su indicador sí se mueve.
                    AnimatedVisibility(visible = splash, exit = fadeOut(tween(220))) {
                        CompositionLocalProvider(LocalModoLigero provides false) { AvoqadoLaunchSplash() }
                    }
                } }
            }
        }
    }
}
