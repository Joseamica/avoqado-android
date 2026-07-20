package com.avoqado.pos

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.designsystem.components.AvoqadoLaunchSplash
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.navigation.AvoqadoNavGraph
import dagger.hilt.android.AndroidEntryPoint
import com.avoqado.pos.customerdisplay.CustomerDisplayManager
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /** Pantalla de cara al cliente en POS de doble pantalla (Sunmi T3 Pro y
     *  similares). Se ata al ciclo de vida de la Activity: sin el detach la
     *  ventana de la segunda pantalla se filtra. */
    @Inject lateinit var customerDisplay: CustomerDisplayManager

    override fun onStart() {
        super.onStart()
        customerDisplay.attach(this)
    }

    override fun onStop() {
        customerDisplay.detach()
        super.onStop()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
            var showBrandSplash by rememberSaveable {
                mutableStateOf(savedInstanceState == null)
            }

            LaunchedEffect(showBrandSplash) {
                if (showBrandSplash) {
                    delay(if (animationsEnabled) 1_350L else 250L)
                    showBrandSplash = false
                }
            }

            AvoqadoTheme(windowSizeClass = windowSizeClass) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AvoqadoNavGraph(windowSizeClass = windowSizeClass)

                    AnimatedVisibility(
                        visible = showBrandSplash,
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = if (animationsEnabled) 220 else 0,
                            ),
                        ),
                    ) {
                        AvoqadoLaunchSplash()
                    }
                }
            }
        }
    }
}
