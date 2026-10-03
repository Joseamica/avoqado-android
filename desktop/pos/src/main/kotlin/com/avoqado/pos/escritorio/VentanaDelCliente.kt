package com.avoqado.pos.escritorio

import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.customerdisplay.CajaDeEscritorio
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.customerdisplay.FabricaDeLetreros
import com.avoqado.pos.customerdisplay.LetreroDelCliente
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.escritorio.tactil.PuenteTactilWindows
import com.avoqado.pos.kiosk.domain.KioskState
import com.avoqado.pos.kiosk.presentation.KioskScreen
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.Frame
import java.awt.Rectangle
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/*
 * La pantalla del cliente en el segundo monitor: lo que en Android es `CustomerDisplayPresentation`. La decisión de cuándo y
 * dónde es de `CustomerDisplayManager` (reemplazo de escritorio); aquí sólo la ventana.
 */

/** La ventana de la caja, vista por el manager. */
internal class CajaDeLaVentana(
    private val ventana: ComposeWindow,
    /** false sólo con `-Davoqado.monitores=dividir`: la «pantalla» es media pantalla y maximizar la llenaría entera. */
    private val maximizar: Boolean = true,
) : CajaDeEscritorio {
    override fun limites(): Rectangle? = ventana.takeIf { it.isShowing }?.bounds

    /** Windows maximiza en el monitor donde está la ventana: primero se lleva ahí en tamaño normal, luego se maximiza. */
    override fun moverA(monitor: Rectangle) {
        ventana.extendedState = Frame.NORMAL
        if (!maximizar) {
            ventana.bounds = Rectangle(monitor)
            return
        }
        ventana.bounds = Rectangle(
            monitor.x + MARGEN, monitor.y + MARGEN,
            (monitor.width - 2 * MARGEN).coerceAtLeast(MINIMO), (monitor.height - 2 * MARGEN).coerceAtLeast(MINIMO),
        )
        ventana.extendedState = Frame.MAXIMIZED_BOTH
    }

    private companion object {
        const val MARGEN = 40
        const val MINIMO = 400
    }
}

/** El nombre AWT de la ventana del cliente: el puente táctil de la caja lo usa para no confundir sus clics con los del cajero. */
internal const val NOMBRE_VENTANA_DEL_CLIENTE = "avoqado-pantalla-del-cliente"

/**
 * Abre el letrero del cliente en UNA ventana que se reutiliza: quitarlo la esconde y volver a montarlo la mueve y la muestra.
 * Así el puente táctil (que se queda con referencias para siempre) se instala una sola vez por sesión, y no una por cada
 * reconexión o inversión. Sólo si el contenido truena se descarta y la siguiente vez se arma otra.
 */
internal class FabricaDeVentanasDelCliente(
    private val estado: CustomerDisplayState,
    private val kiosco: KioskState,
) : FabricaDeLetreros {
    private var ventana: ComposeWindow? = null
    private var dueño: DueñoDelLetrero? = null
    private var rota = false
    private var vigente: LetreroDelCliente? = null
    private var alTronarActual: () -> Unit = {}

    override fun abrir(limites: Rectangle, alTronar: () -> Unit): LetreroDelCliente {
        if (rota) liberar()
        alTronarActual = alTronar
        val v = ventana ?: crear().also { ventana = it }
        v.bounds = Rectangle(limites)
        v.isVisible = true
        v.isAlwaysOnTop = true   // otra ventana (Explorador, Chrome) no lo tapa mientras el cobro espera al cliente
        val letrero = object : LetreroDelCliente {
            override fun cerrar() {
                if (vigente === this) { vigente = null; v.isVisible = false }
            }

            override fun asegurarVisible() {
                if (vigente !== this) return
                if (v.extendedState and Frame.ICONIFIED != 0) v.extendedState = Frame.NORMAL
                if (!v.isVisible) v.isVisible = true
            }
        }
        vigente = letrero
        return letrero
    }

    override fun liberar() {
        vigente = null
        dueño?.cerrar(); dueño = null
        ventana?.let { runCatching { it.dispose() } }; ventana = null
        rota = false
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun crear(): ComposeWindow {
        val v = ComposeWindow()
        try {
            v.name = NOMBRE_VENTANA_DEL_CLIENTE
            v.title = "Avoqado POS — Cliente"
            v.isUndecorated = true
            v.isResizable = false   // sin bordes, Compose deja arrastrarlos y el cliente encogería su propia pantalla
            // Sin botón en la barra de tareas: el cajero no tiene nada que hacer con esta ventana.
            runCatching { v.type = java.awt.Window.Type.UTILITY }
            // 🔴 Nunca le quita el foco ni el teclado a la caja (en Android: FLAG_NOT_FOCUSABLE de la Presentation).
            v.focusableWindowState = false
            v.isAutoRequestFocus = false
            v.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            // 🔴 Degradar, nunca bloquear: si el letrero truena se quita ÉL; la caja sigue (sin esto Compose cerraría la app).
            v.exceptionHandler = WindowExceptionHandler { e ->
                Log.e("PantallaCliente", "La pantalla del cliente tronó", e)
                rota = true
                SwingUtilities.invokeLater(alTronarActual)
            }
            val d = DueñoDelLetrero().also { dueño = it }
            v.setContent { ContenidoDelCliente(estado, kiosco, d) { instalarToques(v) } }
            v.addNotify()   // el HWND existe desde aquí: se marca ANTES de mostrarla
            sinActivarse(v)
            return v
        } catch (t: Throwable) {
            runCatching { v.dispose() }
            dueño?.cerrar(); dueño = null
            throw t
        }
    }

    /** El dedo como DEDO también en el letrero (kiosco, propina), sin alimentar el teclado en pantalla de la caja. */
    private fun instalarToques(ventana: ComposeWindow) {
        val resultado = runCatching { PuenteTactilWindows.instalar(ventana, esLaCaja = false) }
            .getOrElse { "omitido: ${it.javaClass.simpleName}: ${it.message}" }
        Log.i("PantallaCliente", "Puente táctil del cliente: $resultado")
    }

    /**
     * 🔴 En Windows, tocar o hacer clic en una ventana la ACTIVA aunque Java la tenga por no enfocable: la caja perdería el
     * teclado a media venta. WS_EX_NOACTIVATE le dice a Windows que esta ventana nunca se activa. Si Windows no lo acepta, no
     * hay letrero (lanza): mejor sin pantalla del cliente que con una caja que pierde el teclado.
     */
    private fun sinActivarse(ventana: ComposeWindow) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val hwnd = HWND(Native.getComponentPointer(ventana))
        val estilo = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, estilo or WS_EX_NOACTIVATE)
        check(User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE) and WS_EX_NOACTIVATE != 0) {
            "Windows no aceptó WS_EX_NOACTIVATE en la ventana del cliente"
        }
    }

    private companion object {
        const val WS_EX_NOACTIVATE = 0x08000000
    }
}

/** Dueño de ViewModels y ciclo de vida del letrero (en Android lo es la Presentation misma). */
private class DueñoDelLetrero : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    private val registro = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registro

    fun cerrar() {
        registro.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * La MISMA bifurcación que `CustomerDisplayPresentation` (Android): kiosco o letrero del cliente, siempre en claro. Si
 * Android cambia aquélla, la huella de excluidos.txt avisa.
 */
@Composable
private fun ContenidoDelCliente(
    estado: CustomerDisplayState,
    kiosco: KioskState,
    dueño: DueñoDelLetrero,
    alPrimerCuadro: () -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // 🔴 La Configuration va SÓLO a esta ventana. `ConfiguracionDeAndroid` además la copia al contexto GLOBAL de la app, y
    // el tamaño del monitor del cliente no puede cambiarle el diseño a la caja.
    val densidad = LocalDensity.current.density
    val configuracion = remember(maxWidth, maxHeight, densidad) { configuracionDeAndroid(maxWidth, maxHeight, densidad) }
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides dueño,
        LocalLifecycleOwner provides dueño,
        LocalConfiguration provides configuracion,
    ) {
        AvoqadoTheme(darkTheme = false) {
            val kioskOn by kiosco.enabled.collectAsState()
            if (kioskOn) {
                val venueName by estado.venueName.collectAsState()
                KioskScreen(state = kiosco, venueName = venueName)
                return@AvoqadoTheme
            }
            CustomerDisplayScreen(
                state = estado,
                onRating = { estado.onRatingPicked?.invoke(it) },
                onTip = { estado.onTipPicked?.invoke(it) },
                onWhatsApp = { estado.onWhatsAppSubmit?.invoke(it) },
                onEmail = { estado.onEmailSubmit?.invoke(it) },
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }   // el primer cuadro se compone…
        withFrameNanos { }   // …y ya se dibujó (el lienzo nativo ya existe)
        alPrimerCuadro()
    }
}
