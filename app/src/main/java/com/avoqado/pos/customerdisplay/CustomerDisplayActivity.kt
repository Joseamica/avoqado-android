package com.avoqado.pos.customerdisplay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * La pantalla del cliente cuando el mostrador está invertido (el cliente ve la
 * pantalla grande, que es la PRINCIPAL del equipo).
 *
 * 🔴 Por qué una Activity y no el `Presentation` de siempre: Android prohíbe las
 * ventanas `TYPE_PRESENTATION` en la pantalla por defecto — solo las acepta en
 * pantallas que califican como *public presentation display*. El `Presentation`
 * sigue siendo el camino en modo normal (y es el ÚNICO que funciona en equipos
 * cuya pantalla de cliente es virtual, como el T3 Pro).
 *
 * No guarda NADA del negocio: solo pinta el singleton [CustomerDisplayState], y
 * quién va en qué pantalla lo decide y lo recuerda [CustomerDisplayManager]. Por
 * eso una recreación del sistema (rotación, densidad, resize) es inofensiva: la
 * instancia nueva le vuelve a preguntar al manager y obtiene la misma respuesta.
 * Lo único que sí es de la instancia es [reportedPresence], y es puro ciclo de
 * vida — ver su KDoc.
 */
@AndroidEntryPoint
class CustomerDisplayActivity : ComponentActivity() {

    @Inject lateinit var state: CustomerDisplayState

    // Para preguntarle al manager, en cada onStart(), si SIGUE queriendo una
    // ventana de cliente aquí (ver CustomerDisplayManager.desiredCustomerDisplayId).
    // La decisión es del manager; esta Activity solo pregunta.
    @Inject lateinit var manager: CustomerDisplayManager

    /**
     * ¿Fue ESTA instancia la que anunció presencia en [CustomerDisplayState]?
     *
     * 🔴 [CustomerDisplayState] es un singleton COMPARTIDO por todas las
     * ventanas de cliente, así que un `setPresenting(false)` de una apaga la
     * señal de cualquier otra. Cuando el guard de [onStart] nos cierra, Android
     * dispara nuestro `onStop()` igual más adelante (porque `onStart()` ya
     * corrió): sin esta guarda, ese `onStop()` tardío apagaría un `true`
     * legítimo puesto mientras tanto por OTRA ventana — la `Presentation` del
     * modo normal, o una instancia relanzada en la pantalla correcta. Solo
     * apagamos lo que nosotros prendimos.
     */
    private var reportedPresence = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Un letrero de cara al público JAMÁS debe quitarle el foco ni el
        // teclado a la caja, que en este modo vive en la otra pantalla. Con
        // NOT_FOCUSABLE los toques DENTRO de esta ventana sí llegan — es como
        // funcionan propina y calificación hoy en el Presentation.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // La pantalla de cara al cliente no se apaga a media venta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        instance = this
        setContent {
            // Siempre en claro: es un letrero público, no sigue el tema del cajero.
            AvoqadoTheme(darkTheme = false) {
                CustomerDisplayScreen(
                    state = state,
                    onRating = { state.onRatingPicked?.invoke(it) },
                    onTip = { state.onTipPicked?.invoke(it) },
                    onWhatsApp = { state.onWhatsAppSubmit?.invoke(it) },
                    onEmail = { state.onEmailSubmit?.invoke(it) },
                )
            }
        }
    }

    // 🔴 `startActivity()` hacia otra pantalla es ASÍNCRONO: entre que el
    // manager lo dispara y que esta Activity llega a existir puede haber
    // corrido otro refresh() que decidió que YA NO quiere un letrero aquí — el
    // caso que importa es el guard anti-bucle, cuando la caja resultó estar
    // todavía en esta misma pantalla. En esa ventana `instance` (companion)
    // seguía siendo null, así que el `finishIfShowing()` del manager no tuvo
    // nada que cancelar y este lanzamiento en vuelo llegó de todos modos. Por
    // eso NO asumimos que existir signifique seguir vigentes: le preguntamos al
    // manager, que es quien tiene la decisión — nosotros solo preguntamos. Si
    // dice que no, nos cerramos ANTES de que alguien (cliente o cajero, si esto
    // tapó su pantalla) llegue a vernos.
    //
    // La pregunta se contesta en CADA onStart(), no solo el primero, porque el
    // manager deja el deseo PUESTO mientras siga queriendo esta ventana (ver
    // CustomerDisplayManager.desiredCustomerDisplayId). Así un `onStop →
    // onRestart → onStart` por causa ajena (bloqueo/desbloqueo, gestión de
    // energía del fabricante, un overlay del sistema) y una recreación por
    // cambio de configuración encuentran el deseo intacto y siguen vivas: lo
    // único que se cierra solo es el lanzamiento que el manager ya no quiere.
    override fun onStart() {
        super.onStart()
        if (!manager.wantsCustomerDisplayOn(currentDisplayId())) {
            finish()
            return
        }
        // 🔴 Esta ventana tiene FLAG_NOT_FOCUSABLE (ver onCreate), así que
        // NUNCA dispara onWindowFocusChanged(hasFocus = true) — el sistema
        // excluye a las ventanas no enfocables de recibir foco de entrada, y
        // ese es el hook que MainActivity usa para reaplicar el ocultamiento
        // de las barras. onStart() sí corre aquí: es un callback de
        // VISIBILIDAD del ciclo de vida (no de foco de ventana), y ya se
        // repite en cada onStop→onRestart→onStart — bloqueo/desbloqueo,
        // gestión de energía del fabricante, overlays del sistema (ver el
        // KDoc de la clase) — así que cubre los mismos casos que en
        // MainActivity cubría el focus-changed.
        hideSystemBars()
        // La señal de "de verdad se está mostrando" viene del propio ciclo de
        // vida, no de que startActivity() no haya lanzado excepción: eso solo
        // dice que el sistema aceptó la intención, no que la ventana llegó a
        // aparecer. isPresenting alimenta a quién le toca capturar propina y
        // calificación (ver CustomerDisplayState) — una señal optimista manda
        // el upsell a una pantalla que nadie ve.
        reportedPresence = true
        state.setPresenting(true)
        // Este es el momento en que la ventana del cliente ya está de verdad en
        // pantalla — y por tanto el momento en que la caja se quedó sin foco de
        // teclado (fue la última pantalla en activarse). El manager decide si hay
        // que devolvérselo y lo hace UNA sola vez por montaje; llamar aquí en cada
        // onStart() es inofensivo. Ver CustomerDisplayManager.onCustomerDisplayPresented.
        manager.onCustomerDisplayPresented()
    }

    override fun onStop() {
        // Solo si ESTA instancia llegó a anunciar presencia: si el guard de
        // arriba nos cerró, jamás tocamos isPresenting — de lo contrario este
        // onStop() (que Android dispara igual, porque onStart() ya corrió)
        // podría apagar un `true` legítimo que ya puso otra ventana
        // (Presentation o una relanzada) después de que a nosotros nos
        // cancelaron. Ver el KDoc de reportedPresence.
        if (reportedPresence) {
            reportedPresence = false
            state.setPresenting(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Oculta AMBAS barras del sistema en la ventana de cara al cliente: la de
     * estado arriba (reloj, wifi, bluetooth) y, en Sunmi, el dock de abajo con
     * apps lanzables (Chrome, Ajustes, Sunmi OS) + atrás/home/recientes.
     *
     * Sin esto, un cliente frente a la caja tiene Chrome y los Ajustes del
     * equipo a un toque — y si le da HOME, el letrero desaparece y nada lo
     * vuelve a montar (la Activity queda viva pero fuera de pantalla, ver
     * [isShowingOn]).
     *
     * 🔴 Por qué no [com.avoqado.pos.designsystem.components.ImmersiveWindow]:
     * ese helper busca `LocalView.current.parent as? DialogWindowProvider` —
     * resuelve la ventana de un `Dialog`/`ModalBottomSheet`, NO la ventana
     * propia de una `Activity`. El contenido de `setContent {}` aquí cuelga
     * directo del decor de esta Activity (no hay `DialogWindowProvider` en la
     * cadena de padres), así que ese cast siempre da null y el helper no
     * haría nada — se replica en su lugar el mismo patrón que usa
     * `MainActivity.hideNavigationBar()`.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** Se limpia en onDestroy, así que no retiene la Activity muerta. */
        private var instance: CustomerDisplayActivity? = null

        /**
         * 🔴 No basta con "viva y no terminándose": las barras se ocultan
         * (ver [hideSystemBars]) pero son TRANSITORIAS — un swipe desde el
         * borde las trae de vuelta unos segundos — y esta Activity no usa
         * lock-task, así que el cliente igual puede llegar a HOME desde ahí.
         * Si lo hace, la instancia sigue existiendo pero dejó de estar en
         * pantalla. Exigir STARTED es lo que hace que el siguiente refresh()
         * la vuelva a traer al frente en vez de creerla montada para siempre.
         */
        fun isShowingOn(displayId: Int): Boolean =
            instance?.let {
                !it.isFinishing &&
                    it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    it.currentDisplayId() == displayId
            } == true

        fun finishIfShowing() {
            instance?.finish()
            instance = null
        }
    }
}
