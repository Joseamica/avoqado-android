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

    // Para preguntarle al manager, en onStart(), si TODAVÍA nos quiere aquí
    // (ver el porqué en CustomerDisplayManager.desiredCustomerDisplayId). La
    // decisión es del manager; esta Activity solo pregunta.
    @Inject lateinit var manager: CustomerDisplayManager

    /** true solo si onStart() de verdad confirmó la presentación (ver onStop). */
    private var reallyPresenting = false

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

    // 🔴 `startActivity()` hacia otra pantalla es ASÍNCRONO: entre que el
    // manager lo dispara y que esta Activity llega a onStart() puede haber
    // corrido otro refresh() que decidió que YA NO nos quiere aquí — el caso
    // que importa es el guard anti-bucle, cuando la caja resultó estar
    // todavía en esta misma pantalla. En esa ventana `instance` (companion)
    // seguía siendo null, así que `finishIfShowing()` no tuvo nada que
    // cancelar y este lanzamiento en vuelo llegó de todos modos. Por eso NO
    // asumimos que llegar aquí significa seguir vigentes: le preguntamos al
    // manager, que es quien tiene la decisión — nosotros solo preguntamos.
    // Si dice que no, nos cerramos ANTES de que alguien (cliente o cajero,
    // si esto tapó su pantalla) llegue a vernos.
    override fun onStart() {
        super.onStart()
        val displayId = currentDisplayId()
        if (!manager.wantsCustomerDisplayOn(displayId)) {
            finish()
            return
        }
        manager.onCustomerActivityStarted(displayId)
        // La señal de "de verdad se está mostrando" viene del propio ciclo de
        // vida, no de que startActivity() no haya lanzado excepción: eso solo
        // dice que el sistema aceptó la intención, no que la ventana llegó a
        // aparecer. isPresenting alimenta a quién le toca capturar propina y
        // calificación (ver CustomerDisplayState) — una señal optimista manda
        // el upsell a una pantalla que nadie ve.
        reallyPresenting = true
        state.setPresenting(true)
    }

    override fun onStop() {
        // Solo si ESTA instancia de verdad llegó a confirmar la presentación:
        // si onStart() nos cerró por el guard de arriba, jamás tocamos
        // isPresenting — de lo contrario este onStop() tardío podría apagar
        // un `true` legítimo que ya puso otra ventana (Presentation o una
        // relanzada) después de que a nosotros nos cancelaron.
        if (reallyPresenting) {
            reallyPresenting = false
            state.setPresenting(false)
        }
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
