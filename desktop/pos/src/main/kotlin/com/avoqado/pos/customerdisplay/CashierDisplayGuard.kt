package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio: aquí no se usa. En Android este guard relanza la Activity de la caja en otra pantalla; en
 * escritorio la ventana de la caja la mueve el propio `CustomerDisplayManager` (modo invertido con dos monitores), que no
 * llama a esto. Se conserva la clase porque el código de la app la inyecta.
 */
@Singleton
class CashierDisplayGuard @Inject constructor() {
    fun enforce(activity: Activity) {
        Log.w("Escritorio", "No disponible en Windows todavía: mover la caja a otra pantalla")
    }

    fun resetAttempts() = Unit
}
