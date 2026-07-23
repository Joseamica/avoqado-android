package com.avoqado.pos.customerdisplay

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Ventana en la pantalla SECUNDARIA (la chica, de cara al cliente).
 *
 * 🔴 Un ComposeView dentro de un Presentation NO hereda los owners de la
 * Activity: hay que dárselos a mano (lifecycle, ViewModelStore y
 * SavedStateRegistry) o truena al primer recompose con
 * "ViewTreeLifecycleOwner not found". Por eso esta clase los implementa.
 */
class CustomerDisplayPresentation(
    context: Context,
    display: Display,
    private val state: CustomerDisplayState,
) : Presentation(context, display), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        // 🔴 Un Presentation es un Dialog y por defecto es TOUCH MODAL: reclama
        // los toques de FUERA de su ventana. En el T3 Pro eso CONGELABA la caja
        // — el estado táctil de la pantalla principal se quedaba atrapado en la
        // ventana del cliente (región táctil [-1280,-800][2560,1600]) y ningún
        // botón del cajero respondía.
        // NOT_FOCUSABLE trae NOT_TOUCH_MODAL: la ventana del cliente ya solo
        // puede recibir toques DENTRO de su pantalla, nunca robarle los suyos
        // al cajero. Además, un letrero de cara al público jamás debe quitarle
        // el foco de teclado a la caja.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@CustomerDisplayPresentation)
            setViewTreeViewModelStoreOwner(this@CustomerDisplayPresentation)
            setViewTreeSavedStateRegistryOwner(this@CustomerDisplayPresentation)
            setContent {
                // La pantalla del cliente SIEMPRE en claro: es un letrero de
                // cara al público, no sigue el tema del cajero.
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
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onStop()
    }
}
