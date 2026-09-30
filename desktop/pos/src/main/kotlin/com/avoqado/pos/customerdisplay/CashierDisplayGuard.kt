package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio: no hay otra pantalla a la cual mover la caja. El interruptor que llama a esto
 * (Ajustes › Pantalla del cliente › invertir) sólo aparece si `CustomerDisplayState.invertible`, que en escritorio
 * nunca se prende; si aun así llegara, lo dice en la bitácora y no mueve nada.
 */
@Singleton
class CashierDisplayGuard @Inject constructor() {
    fun enforce(activity: Activity) {
        Log.w("Escritorio", "No disponible en Windows todavía: mover la caja a otra pantalla")
    }

    fun resetAttempts() = Unit
}
