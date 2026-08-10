package com.avoqado.pos.customerdisplay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * Es sin estado: solo pinta el singleton [CustomerDisplayState]. Si el sistema
 * la recrea, no se pierde nada.
 */
@AndroidEntryPoint
class CustomerDisplayActivity : ComponentActivity() {

    @Inject lateinit var state: CustomerDisplayState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Un letrero de cara al público JAMÁS debe quitarle el foco ni el
        // teclado a la caja, que en este modo vive en la otra pantalla. Con
        // NOT_FOCUSABLE los toques DENTRO de esta ventana sí llegan — es como
        // funcionan propina y calificación hoy en el Presentation.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // La pantalla de cara al cliente no se apaga a media venta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

    // 🔴 La señal de "de verdad se está mostrando" viene del propio ciclo de
    // vida, no de que startActivity() no haya lanzado excepción: eso solo
    // dice que el sistema aceptó la intención, no que la ventana llegó a
    // aparecer. isPresenting alimenta a quién le toca capturar propina y
    // calificación (ver CustomerDisplayState) — una señal optimista manda el
    // upsell a una pantalla que nadie ve.
    override fun onStart() {
        super.onStart()
        state.setPresenting(true)
    }

    override fun onStop() {
        state.setPresenting(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Se limpia en onDestroy, así que no retiene la Activity muerta. */
        private var instance: CustomerDisplayActivity? = null

        /**
         * 🔴 No basta con "viva y no terminándose": si el cliente le da HOME
         * (esta Activity no oculta las barras del sistema ni usa lock-task),
         * la instancia sigue existiendo pero dejó de estar en pantalla. Exigir
         * STARTED es lo que hace que el siguiente refresh() la vuelva a traer
         * al frente en vez de creerla montada para siempre.
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
