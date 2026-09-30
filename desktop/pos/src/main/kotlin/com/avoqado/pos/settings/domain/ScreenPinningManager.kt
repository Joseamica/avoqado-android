package com.avoqado.pos.settings.domain

import android.app.Activity
import com.avoqado.escritorio.Escritorio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio: «fijar Avoqado en pantalla» es el lock task de Android, que no existe en la PC.
 * `enabled` se queda en false (Ajustes dice la verdad: apagado) y tocar el interruptor o «Salir de la app» lo
 * dice en pantalla en vez de fingir que fijó o soltó algo.
 */
@Singleton
class ScreenPinningManager @Inject constructor() {
    val enabled: StateFlow<Boolean> = MutableStateFlow(false)

    fun setEnabled(activity: Activity, value: Boolean) {
        Escritorio.avisarNoDisponible("fijar la pantalla")
    }

    fun exitToLauncher(activity: Activity) {
        Escritorio.avisarNoDisponible("salir de la app desde Ajustes (minimiza o cierra la ventana)")
    }
}
