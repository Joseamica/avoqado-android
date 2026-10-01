package com.avoqado.pos.escritorio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import com.avoqado.pos.designsystem.components.AvoqadoLaunchSplash
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
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

/** Lo que MainActivity.setContent hace en Android, sin barras de sistema ni segunda pantalla. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppEscritorio() = BoxWithConstraints(Modifier.fillMaxSize()) {
    val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides RaizDeEscritorio,
        LocalLifecycleOwner provides RaizDeEscritorio,
    ) {
        ConfiguracionDeAndroid(maxWidth, maxHeight) {
            var splash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(1_350L); splash = false }
            AvoqadoTheme(windowSizeClass = windowSizeClass) {
                Box(Modifier.fillMaxSize()) {
                    // Teclado híbrido: sólo con un dedo sobre un campo de texto; el contenido se encoge y las teclas van en un Popup.
                    ConTecladoEnPantalla(
                        visible = TecladoDeLaVentana.visible.value,
                        generacion = TecladoDeLaVentana.generacion.value,
                        teclado = { TecladoEnPantalla(TecladoDeLaVentana::alPresionar, TecladoDeLaVentana.estado, Modifier.fillMaxWidth()) },
                    ) { AvoqadoNavGraph(windowSizeClass = windowSizeClass) }
                    AnimatedVisibility(visible = splash, exit = fadeOut(tween(220))) { AvoqadoLaunchSplash() }
                }
            }
        }
    }
}
