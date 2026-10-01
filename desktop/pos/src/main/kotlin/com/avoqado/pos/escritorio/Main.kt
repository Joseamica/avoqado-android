package com.avoqado.pos.escritorio

import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.CandadoDeInstancia
import com.avoqado.escritorio.CarpetaDeDatos
import com.avoqado.escritorio.Urls
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.escritorio.tactil.EscenaDeVentana
import com.avoqado.pos.escritorio.tactil.PuenteTactilWindows
import com.avoqado.pos.escritorio.teclado.TecladoDeLaVentana
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import kotlin.system.exitProcess

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Lo que se escapa de appScope/viewModelScope: sin consola (javaw) la pila sólo sobrevive en la bitácora.
    Thread.setDefaultUncaughtExceptionHandler { _, e -> Log.e("SinCapturar", "Excepción no atrapada", e) }
    // Sin carpeta o sin bitácora, el aviso no apunta a unos logs que no existen.
    val carpeta = protegido(carpeta = null) { CarpetaDeDatos.resolver().also(Bitacora::iniciar) }
    protegido(carpeta) {
        // Esta URL la lee la app por su cuenta (KDS/Waste/Inventory con DEBUG) y no pasa por Urls.api: mismo candado.
        System.getProperty("avoqado.test.baseUrl")?.let { url ->
            runCatching { Urls.validar(url) }.onFailure { salir(4, it.message ?: "avoqado.test.baseUrl no es válida", JOptionPane.ERROR_MESSAGE) }
        }
        if (!CandadoDeInstancia.tomar(carpeta)) {
            salir(3, "Avoqado POS ya está abierto en esta computadora.", JOptionPane.INFORMATION_MESSAGE)
        }
        runCatching { BuildConfig.BASE_URL }.onFailure { salir(2, mensajeDeArranqueFallido(it, carpeta), JOptionPane.ERROR_MESSAGE) }
        // Soporte muestra la versión con PackageInfo, y el sustituto la lee de aquí.
        if (System.getProperty("avoqado.version") == null) System.setProperty("avoqado.version", BuildConfig.VERSION_NAME)
        // Preferencias ilegibles o base de otra versión: se niega A PROPÓSITO a arrancar vacía encima de los cobros pendientes.
        Arranque.abrir(carpeta)
    }

    // La primera composición también lee preferencias (AppState → SyncOutbox, ComandasPendientesStore): si truena ahí o
    // más tarde, un aviso en español («se cerró por un error»), no el diálogo «Error» en inglés de Compose que pierde la
    // pila bajo javaw.
    // CMP llama onException desde render, mouse, teclado y corrutinas, también con el aviso modal abierto: se avisa UNA vez.
    val yaAvise = AtomicBoolean(false)
    val alTronar = object : WindowExceptionHandlerFactory {
        override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler = object : WindowExceptionHandler {
            override fun onException(throwable: Throwable) {
                Log.e("Ventana", "La ventana tronó", throwable)
                if (!yaAvise.compareAndSet(false, true)) return
                salir(4, mensajeDeArranqueFallido(throwable, carpeta, alAbrir = false), JOptionPane.ERROR_MESSAGE)
            }
        }
    }
    // Lo que truena en application{} ANTES de que exista la ventana (p. ej. HeadlessException en getGlobalDensity,
    // medido en Windows el 30-sep) no pasa por alTronar: sin esto escapa de main con exit 1 y, bajo javaw, sin aviso.
    try {
        application {
            CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides alTronar) {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "Avoqado POS",
                    state = rememberWindowState(placement = WindowPlacement.Maximized, size = Arranque.TAMANO_INICIAL),
                ) {
                    LaunchedEffect(Unit) {
                        withFrameNanos { }   // el primer cuadro se compone…
                        withFrameNanos { }   // …y ya se dibujó (el lienzo nativo ya existe)
                        // El dedo como DEDO en Windows (Compose 1.7.3 no lo trae): si no se puede, sigue como mouse.
                        val toque = runCatching {
                            PuenteTactilWindows.instalar(window) { motivo ->
                                runCatching { Diagnostico.anotar(carpeta, "Toque con el dedo: apagado: $motivo") }
                            }
                        }.getOrElse { "omitido: ${it.javaClass.simpleName}: ${it.message}" }
                        Log.i("Toque", "Puente táctil: $toque")
                        // Teclado en pantalla para el dedo; si no se puede instalar, la app sigue con el teclado físico.
                        runCatching { TecladoDeLaVentana.instalar(EscenaDeVentana.de(window).getOrThrow(), window) }
                            .onSuccess { Log.i("Teclado", "Teclado híbrido instalado") }
                            .onFailure { Log.w("Teclado", "Teclado híbrido omitido: ${it.javaClass.simpleName}: ${it.message}") }
                        runCatching { Diagnostico.escribir(carpeta, window.renderApi.name, Diagnostico.arranqueMs(), toque) }
                            .onFailure { Log.w("Arranque", "No se pudo escribir el diagnóstico", it) }
                    }
                    DisposableEffect(window) { onDispose { TecladoDeLaVentana.desinstalar() } }
                    AppEscritorio()
                }
            }
        }
    } catch (e: Throwable) {
        Log.e("Ventana", "La ventana no llegó a abrir", e)
        salir(4, mensajeDeArranqueFallido(e, carpeta, alAbrir = true), JOptionPane.ERROR_MESSAGE)
    }
}

/** Todo lo que puede tronar antes de la ventana: se registra con la pila y se DICE, en vez de un exit 1 mudo. */
private inline fun <T> protegido(carpeta: Path?, bloque: () -> T): T =
    try {
        bloque()
    } catch (t: Throwable) {
        Log.e("Arranque", "No se pudo abrir Avoqado POS", t)
        salir(4, mensajeDeArranqueFallido(t, carpeta), JOptionPane.ERROR_MESSAGE)
    }

/** En Windows se abre con javaw.exe, sin consola: el aviso queda en la bitácora y, con pantalla, en un diálogo. */
private fun salir(codigo: Int, texto: String, tipo: Int): Nothing {
    Log.w("Arranque", texto)
    if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null, texto, "Avoqado POS", tipo)
    exitProcess(codigo)
}
