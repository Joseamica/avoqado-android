package com.avoqado.pos.settings.domain

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Esconder las barras de Android: fija la app en pantalla (lock task) para que
 * el navbar del sistema deje de aparecer y el personal no pueda salirse a
 * Chrome o a los ajustes desde una terminal de cobro.
 *
 * 🔴 NO confundir con el "modo kiosco" de autoservicio. Son DOS ejes distintos
 * y hasta 2026-08-18 compartían el nombre, lo cual ya causaba confusión real:
 *
 *   · ESTA clase  → ¿se puede salir de la app? (el candado)
 *   · Autoservicio → ¿quién opera, el personal o el cliente? (todavía no existe;
 *     le pertenece el campo `TpvSettings.kioskModeEnabled` que manda el servidor)
 *
 * Se componen: el autoservicio SIEMPRE necesita el candado, pero el candado no
 * implica autoservicio — un mostrador atendido también puede querer las barras
 * escondidas. Por eso son dos interruptores, no uno.
 *
 * La salida NO se elimina, se hace EXPLÍCITA: un botón nuestro en Ajustes. Un
 * equipo del que no se puede salir sin saber el gesto secreto del sistema es
 * una llamada a soporte esperando a ocurrir.
 *
 * Ajuste POR EQUIPO (SharedPreferences), no por venue: describe cómo está
 * montada esta caja. Apagado por defecto — encenderlo solo no debe pasar en el
 * equipo de alguien que no lo pidió.
 *
 * OJO: sin device owner (provisionar con el MDM de Sunmi) Android muestra su
 * aviso de "pantalla fijada" al entrar. Funciona igual; solo es menos elegante.
 */
@Singleton
class ScreenPinningManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tag = "🔒ScreenPin"

    // 🔴 El archivo de preferencias y su llave conservan el nombre viejo a
    // PROPÓSITO: son almacenamiento ya escrito en los equipos de la calle.
    // Renombrarlos leería un archivo vacío y apagaría el fijado en toda terminal
    // que hoy lo tiene encendido — un cambio de nombre no puede desconfigurar
    // aparatos en producción.
    private val prefs = context.getSharedPreferences("avoqado_kiosk", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(activity: Activity, value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (value) start(activity) else stop(activity)
    }

    /** Llamar al volver a primer plano: reengancha el fijado tras un reinicio. */
    fun applyOnResume(activity: Activity) {
        if (_enabled.value) start(activity)
    }

    private fun isLocked(activity: Activity): Boolean {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun start(activity: Activity) {
        if (isLocked(activity)) return
        // Falla suave: en un equipo que no lo permite, el POS sigue funcionando
        // exactamente igual — solo con las barras a la vista.
        runCatching { activity.startLockTask() }
            .onFailure { Log.w(tag, "No se pudo fijar la pantalla: ${it.message}") }
    }

    private fun stop(activity: Activity) {
        if (!isLocked(activity)) return
        runCatching { activity.stopLockTask() }
            .onFailure { Log.w(tag, "No se pudo soltar la pantalla: ${it.message}") }
    }

    /**
     * "Salir": suelta el fijado y manda la app al fondo. NO cierra la sesión ni
     * mata el proceso — el cajero vuelve y sigue donde estaba.
     */
    fun exitToLauncher(activity: Activity) {
        stop(activity)
        activity.moveTaskToBack(true)
    }

    private companion object {
        const val KEY_ENABLED = "kiosk_enabled"
    }
}
