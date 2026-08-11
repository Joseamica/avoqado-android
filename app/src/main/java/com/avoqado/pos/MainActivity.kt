package com.avoqado.pos

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    /** Modo kiosco: sin él el navbar del sistema se asoma al pasar el mouse. */
    @Inject lateinit var kiosk: com.avoqado.pos.settings.domain.KioskManager

    /** Impresora integrada de los POS Sunmi: se liga al arrancar para que la
     *  primera impresión no tenga que esperar el bind (asíncrono). En equipos
     *  sin ella el bind no conecta y no pasa nada. */
    @Inject lateinit var innerPrinter: com.avoqado.pos.printing.data.SunmiInnerPrinter

    /** Deja la caja en la pantalla que le toca cuando el mostrador está invertido. */
    @Inject lateinit var cashierGuard: com.avoqado.pos.customerdisplay.CashierDisplayGuard

    override fun onStart() {
        super.onStart()
        innerPrinter.bind()
        customerDisplay.attach(this)
    }

    override fun onResume() {
        super.onResume()
        // Reengancha tras un reinicio del equipo o del proceso: si el negocio
        // dejó el kiosco encendido, debe seguir encendido al volver.
        kiosk.applyOnResume(this)
        // Volver al frente es el otro momento en que el escenario pudo haber
        // cambiado por debajo (alguien desenchufó y volvió a enchufar la
        // pantalla, o el cliente se fue a HOME desde su letrero). resync()
        // recoloca la caja y remonta el letrero, y es no-op cuando ya está todo
        // en su sitio — en modo normal no mueve nada. Ver
        // CustomerDisplayManager.resync.
        customerDisplay.resync()
    }

    override fun onStop() {
        // 🔴 Se pasa `this` porque el desmontaje tiene que ser consciente de la
        // instancia: cuando Android RECREA esta Activity (justo lo que hace
        // CashierDisplayGuard al mover la caja de pantalla), el onStart() de la
        // instancia NUEVA corre ANTES de este onStop() de la VIEJA. Un detach a
        // ciegas mataría aquí la pantalla del cliente que la nueva acaba de
        // montar. Ver CustomerDisplayManager.shouldTearDownOnDetach.
        customerDisplay.detach(this)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    /**
     * 🔴 Puente táctil. En los POS cuya pantalla de cliente es virtual (Sunmi T3
     * Pro y su panel `SUNMI NP511`), Android **no asocia el digitalizador de esa
     * pantalla a esa pantalla**: sus toques aterrizan AQUÍ, en la ventana de la
     * caja, con las coordenadas del panel grande. O sea que hoy, en producción,
     * un cliente tocando su pantalla está apretando cosas en la caja.
     *
     * Se identifica por el DISPOSITIVO que generó el evento (no por dónde cayó),
     * se saca del camino del cajero y —si hay ventana de cliente montada— se
     * reenvía traducido. Ver `CustomerDisplayManager.handleCustomerPanelTouch`.
     *
     * En un equipo sin ese defecto contesta `false` de inmediato y todo sigue
     * igual que siempre.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (customerDisplay.handleCustomerPanelTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si el mostrador está invertido, la caja va en la otra pantalla. Se
        // llama ANTES de pintar, pero NO se hace finish(): el sistema mueve la
        // tarea y recrea la Activity, y si por lo que sea no se mueve, la caja
        // sigue siendo usable donde está.
        cashierGuard.enforce(this)
        enableEdgeToEdge()
        hideNavigationBar()
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

    /**
     * Pantalla completa de POS: se ocultan AMBAS barras del sistema.
     *
     * Antes sólo se escondía la de navegación, así que arriba quedaba la barra
     * de estado con el reloj y los iconos. En un POS eso no aporta nada y sí
     * estorba: en la D3 se comía los toques de media pantalla —"Agregar
     * cliente" era 100% inalcanzable y el 60% de las pestañas del cheque no
     * respondía— porque los paneles nacen en y=0 (ver 691e18f).
     *
     * Sigue siendo transient: un swipe desde el borde las trae de vuelta unos
     * segundos, así que el operador nunca queda encerrado sin salida.
     */
    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
