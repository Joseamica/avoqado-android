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

    /**
     * true desde el PRIMER `onStart()` que confirmó con el manager que esta
     * instancia sigue siendo bienvenida. NO se resetea en `onStop()`: Android
     * garantiza un ciclo `onStop() → onRestart() → onStart()` sobre la MISMA
     * instancia cada vez que su ventana deja de ser visible y vuelve a serlo
     * (bloqueo/desbloqueo del equipo, gestión de energía del fabricante, un
     * overlay del sistema) — sin Intent nuevo, sin pasar por `onCreate()`, y
     * sin que el manager se entere. Si el guard de `onStart()` volviera a
     * correr en esos ciclos, comprobaría contra un token que el manager YA
     * limpió al confirmar el primero (ver `onCustomerActivityStarted`) y se
     * autocerraría de una instancia que sigue siendo válida. El guard es una
     * comprobación de "¿me confirman ESTE lanzamiento?", no de "¿sigo
     * queriendo esta ventana viva?" — por eso corre UNA sola vez por
     * instancia; los `onStart()` siguientes son vaivén normal de visibilidad.
     */
    private var confirmed = false

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
    // manager lo dispara y que esta Activity llega a su PRIMER onStart()
    // puede haber corrido otro refresh() que decidió que YA NO nos quiere
    // aquí — el caso que importa es el guard anti-bucle, cuando la caja
    // resultó estar todavía en esta misma pantalla. En esa ventana `instance`
    // (companion) seguía siendo null, así que `finishIfShowing()` no tuvo
    // nada que cancelar y este lanzamiento en vuelo llegó de todos modos. Por
    // eso NO asumimos que llegar aquí significa seguir vigentes: le
    // preguntamos al manager, que es quien tiene la decisión — nosotros solo
    // preguntamos. Si dice que no, nos cerramos ANTES de que alguien (cliente
    // o cajero, si esto tapó su pantalla) llegue a vernos.
    //
    // Pero SOLO la primera vez (`!confirmed`): los `onStart()` siguientes de
    // una instancia ya confirmada son el vaivén normal de visibilidad —ver el
    // porqué en el KDoc de `confirmed`— y NO deben volver a consultar el
    // token, porque el manager ya lo limpió al confirmar y un re-chequeo la
    // autocerraría por error. Cuando el manager de verdad deja de querer esta
    // ventana, es ÉL quien la cierra (`finishCustomerActivity()`); este
    // guard existe solo para el lanzamiento en vuelo que ya nadie quiere.
    override fun onStart() {
        super.onStart()
        if (!confirmed) {
            val displayId = currentDisplayId()
            if (!manager.wantsCustomerDisplayOn(displayId)) {
                finish()
                return
            }
            manager.onCustomerActivityStarted(displayId)
            confirmed = true
        }
        // La señal de "de verdad se está mostrando" viene del propio ciclo de
        // vida, no de que startActivity() no haya lanzado excepción: eso solo
        // dice que el sistema aceptó la intención, no que la ventana llegó a
        // aparecer. isPresenting alimenta a quién le toca capturar propina y
        // calificación (ver CustomerDisplayState) — una señal optimista manda
        // el upsell a una pantalla que nadie ve.
        state.setPresenting(true)
    }

    override fun onStop() {
        // Solo si ESTA instancia de verdad llegó a confirmar el lanzamiento:
        // si el guard de arriba nos cerró en el primer onStart(), jamás
        // tocamos isPresenting — de lo contrario este onStop() (que Android
        // dispara igual, porque onStart() ya corrió) podría apagar un `true`
        // legítimo que ya puso otra ventana (Presentation o una relanzada)
        // después de que a nosotros nos cancelaron.
        if (confirmed) state.setPresenting(false)
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
