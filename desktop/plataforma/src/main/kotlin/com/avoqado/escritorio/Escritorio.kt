package com.avoqado.escritorio

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.google.inject.Injector
import com.google.inject.Module
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.lang.reflect.Proxy
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Lo que en Android da el sistema a toda la app: el contexto y el inyector. Se instala una vez en Main. */
object Escritorio {
    @Volatile private var _inyector: Injector? = null
    @Volatile private var _contexto: android.content.Context? = null

    val inyector: Injector get() = checkNotNull(_inyector) { "El inyector de escritorio no está armado (lo arma Main.kt)" }
    val contexto: android.content.Context get() = checkNotNull(_contexto) { "El contexto de escritorio no está instalado (lo instala Main.kt)" }

    fun instalar(contexto: android.content.Context, inyector: Injector) { _contexto = contexto; _inyector = inyector }

    fun instalarInyector(inyector: Injector) { _inyector = inyector }

    /** Algo sin soporte en escritorio: lo dice en la bitácora y, con ventana, en pantalla. */
    fun avisarNoDisponible(que: String) {
        val texto = "No disponible en Windows todavía: $que"
        Log.w("Escritorio", texto)
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow, texto)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> puntoDeEntrada(tipo: Class<T>): T =
        Proxy.newProxyInstance(tipo.classLoader, arrayOf(tipo)) { proxy, metodo, args ->
            when (metodo.name) {   // los métodos de Object no se inyectan (un toString en un log no debe pedir un String a Guice)
                "toString" -> "PuntoDeEntrada(${tipo.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> inyector.getInstance(llave(metodo.genericReturnType, metodo.annotations))
            }
        } as T

    /** Lo que Hilt hace por cada ViewModel: un hijo del inyector que además sabe su SavedStateHandle. */
    fun <VM : ViewModel> crearViewModel(tipo: Class<VM>, estado: SavedStateHandle): VM =
        inyector.createChildInjector(Module { it.bind(SavedStateHandle::class.java).toInstance(estado) }).getInstance(tipo)
}
