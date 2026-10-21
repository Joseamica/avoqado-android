package com.avoqado.pos

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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

    /** Esconder las barras de Android: sin esto el navbar del sistema se asoma
     *  al pasar el mouse. No es el autoservicio — ver [ScreenPinningManager]. */
    @Inject lateinit var screenPinning: com.avoqado.pos.settings.domain.ScreenPinningManager

    /** Impresora integrada de los POS Sunmi: se liga al arrancar para que la
     *  primera impresión no tenga que esperar el bind (asíncrono). En equipos
     *  sin ella el bind no conecta y no pasa nada. */
    @Inject lateinit var innerPrinter: com.avoqado.pos.printing.data.SunmiInnerPrinter

    /** Deja la caja en la pantalla que le toca cuando el mostrador está invertido. */
    @Inject lateinit var cashierGuard: com.avoqado.pos.customerdisplay.CashierDisplayGuard

    /** Lector de pistola (USB/Bluetooth): ante Android es un teclado. Ver [com.avoqado.pos.pos.data.LectorHid]. */
    @Inject lateinit var lectorHid: com.avoqado.pos.pos.data.LectorHidBus

    override fun onStart() {
        super.onStart()
        innerPrinter.bind()
        customerDisplay.attach(this)
        AvisoDelLectorIntegrado.escuchar(applicationContext, lectorHid)
    }

    override fun onResume() {
        super.onResume()
        // Reengancha tras un reinicio del equipo o del proceso: si el negocio
        // dejó las barras escondidas, deben seguir escondidas al volver.
        screenPinning.applyOnResume(this)
        // Volver al frente es el otro momento en que el escenario pudo haber
        // cambiado por debajo (alguien desenchufó y volvió a enchufar la
        // pantalla, o el cliente se fue a HOME desde su letrero). resync()
        // recoloca la caja y remonta el letrero, y es no-op cuando ya está todo
        // en su sitio — en modo normal no mueve nada. Ver
        // CustomerDisplayManager.resync.
        customerDisplay.resync()

        // 🔴 Repintado forzado cuando la caja NO está en la pantalla principal.
        //
        // Con las pantallas invertidas, la caja se muda a la pantalla del cliente y ahí
        // aterriza con la ventana creada, visible y del tamaño correcto —pero SIN pintar.
        // Se ve un rectángulo NEGRO, y sigue negro hasta que alguien la toca: el primer
        // evento de entrada dispara la invalidación que el cambio de pantalla no disparó.
        // Medido en la Sunmi D3, mirando `dumpsys window`: `mHasSurface=true`,
        // `isReadyForDisplay()=true`, `mViewVisibility=0` y aun así negro. El negro es el
        // fondo del compositor debajo de una ventana TRANSLUCENT que no dibujó.
        //
        // Un mostrador que arranca en negro y sólo revive si alguien adivina que hay que
        // tocarlo no es aceptable, así que se pide el repintado a mano.
        //
        // Sólo fuera de la pantalla principal: en modo normal esto no pasa y no hay por
        // qué pagar un relayout de más en cada vuelta al frente.
        if (window?.decorView?.display?.displayId != android.view.Display.DEFAULT_DISPLAY) {
            window?.decorView?.post {
                window?.decorView?.requestLayout()
                window?.decorView?.invalidate()
            }
        }
    }

    override fun onStop() {
        // 🔴 Se pasa `this` porque el desmontaje tiene que ser consciente de la
        // instancia: cuando Android RECREA esta Activity (justo lo que hace
        // CashierDisplayGuard al mover la caja de pantalla), el onStart() de la
        // instancia NUEVA corre ANTES de este onStop() de la VIEJA. Un detach a
        // ciegas mataría aquí la pantalla del cliente que la nueva acaba de
        // montar. Ver CustomerDisplayManager.shouldTearDownOnDetach.
        customerDisplay.detach(this)
        AvisoDelLectorIntegrado.soltar(applicationContext)
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

    /**
     * 🔴 Puente de teclado, hermano del táctil de arriba. Un lector de pistola escribe el
     * código tecla por tecla y cierra con Enter, y ésta es la única puerta por la que pasan
     * TODAS las teclas antes de llegar a un campo. Si el bus reconoce una ráfaga de lector,
     * la tecla se corta aquí para que no ensucie lo que el cajero tenía con foco; si no,
     * sigue su camino normal. Sin lector conectado nunca toma nada.
     */
    // dispatchKeyEvent es un callback público de Activity. Lint hereda el @RestrictTo de
    // androidx.core.app.ComponentActivity a través de FragmentActivity; la excepción queda
    // sólo aquí. Conservar super mantiene el despacho compatible de las teclas no consumidas.
    // https://developer.android.com/reference/android/app/Activity#dispatchKeyEvent(android.view.KeyEvent)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (lectorHid.procesar(event)) return true
        return super.dispatchKeyEvent(event)
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
                // 🔴 `imePadding` GLOBAL de la ventana principal. La app corre
                // edge-to-edge, y con eso el `adjustResize` del manifiesto deja
                // de encoger la ventana: sin esto, el teclado tapa lo que haya
                // abajo y NINGUNA pantalla se entera. Costó un defecto real —
                // el boton "Guardar nota" del articulo quedaba enterrado bajo
                // el teclado en la Sunmi D3, asi que se escribia la nota y no
                // habia forma de guardarla.
                //
                // Va aqui y no pantalla por pantalla para que la que se
                // escriba mañana nazca arreglada. No cambia NADA sin teclado
                // abierto: el inset vale 0 y el padding es 0.
                //
                // Consume el inset (InsetsPaddingModifier es ModifierLocalProvider),
                // asi que un `imePadding()` interno —login, checador— aplica 0 y
                // no se suma dos veces.
                //
                // ⚠️ NO cubre las hojas ni los dialogos: cada uno abre su PROPIA
                // ventana. Las hojas se arreglan en `ImmersiveWindow`; los
                // dialogos ya suben solos (Compose les pone ADJUST_RESIZE).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
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

/**
 * UN solo receptor del aviso del lector integrado de Sunmi para toda la app
 * ([com.avoqado.pos.pos.data.LectorIntegradoSunmi]).
 *
 * 🔴 No uno por Activity: al mudar la caja de pantalla (CashierDisplayGuard) se enciman dos
 * instancias —la nueva arranca antes de que pare la vieja, y con dos pantallas las dos pueden
 * estar al frente— y con dos receptores un escaneo entraba dos veces al carrito (Codex r1 #2).
 * Se escucha mientras haya al menos una caja arrancada; sin ninguna, un escaneo hecho en otra
 * app no llega a la venta. Sólo se toca desde onStart/onStop (hilo principal).
 */
private object AvisoDelLectorIntegrado {
    private var cajasArrancadas = 0
    private var bus: com.avoqado.pos.pos.data.LectorHidBus? = null
    private val receptor = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            bus?.recibirDelLectorIntegrado(intent?.getStringExtra(com.avoqado.pos.pos.data.LectorIntegradoSunmi.EXTRA_CODIGO))
        }
    }

    fun escuchar(app: android.content.Context, lector: com.avoqado.pos.pos.data.LectorHidBus) {
        bus = lector
        if (cajasArrancadas++ == 0) {
            // EXPORTED: el aviso viene de otra app (com.sunmi.scanner).
            androidx.core.content.ContextCompat.registerReceiver(
                app,
                receptor,
                android.content.IntentFilter(com.avoqado.pos.pos.data.LectorIntegradoSunmi.ACCION),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    fun soltar(app: android.content.Context) {
        if (cajasArrancadas > 0 && --cajasArrancadas == 0) app.unregisterReceiver(receptor)
    }
}
