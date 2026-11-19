package com.avoqado.pos.onlineorders.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ¿La ventana de la app se ve? Lo usan la bandeja de «Pedidos en línea» y, desde D662, el vigilante global para decidir si mandan la
 * cabecera de latido (`x-online-orders-tray: 1`, que mantiene abierta la tienda).
 *
 * - ANDROID: siempre `true`. La bandeja ya sólo corre con la Activity en `STARTED` (`repeatOnLifecycle`), así que no hace falta más.
 * - WINDOWS: el ciclo de vida de `RaizDeEscritorio` se queda en `RESUMED` aunque la ventana se minimice, así que el escritorio
 *   publica aquí `!WindowState.isMinimized` desde `Main.kt`. Minimizada ⇒ ni la bandeja ni el vigilante laten (Codex plan r2 #5);
 *   el vigilante global sigue sonando.
 */
object VentanaALaVista {
    private val _aLaVista = MutableStateFlow(true)
    val aLaVista: StateFlow<Boolean> = _aLaVista.asStateFlow()

    fun publicar(aLaVista: Boolean) { _aLaVista.value = aLaVista }
}
