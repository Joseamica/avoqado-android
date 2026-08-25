package com.avoqado.pos.kiosk.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.avoqado.pos.MainActivity

/**
 * Levanta la app sola cuando el aparato enciende, **si y sólo si** esta pantalla trabaja
 * como kiosco.
 *
 * 🔴 Por qué existe: se reinició la Sunmi D3 de la oficina y el servidor no recibió una
 * sola petición del aparato en los 84 minutos siguientes. El túnel y el servidor estaban
 * vivos (200 los dos): el silencio era de la app, que sin `BOOT_COMPLETED` Android
 * simplemente no vuelve a levantar.
 *
 * Para un POS de mostrador eso da igual — hay alguien que abre la app al empezar el turno.
 * Para un KIOSCO en la entrada no: un corte de luz de madrugada lo deja muerto hasta que
 * alguien llegue y lo despierte a mano, y quien se topa con la pantalla apagada es el
 * cliente, no el negocio.
 *
 * ## Por qué va detrás del interruptor del kiosco
 *
 * Arrancar solos en TODO aparato con la app instalada sería tomarnos una libertad que
 * nadie nos dio: un POS de mostrador que se abre solo tras cada reinicio se mete encima de
 * lo que el negocio estuviera haciendo. El kiosco es el único caso donde volver solo es lo
 * que el negocio quiere, porque no hay nadie a quien pedírselo.
 *
 * Se lee el `SharedPreferences` directo y no `KioskPrefs` por inyección: un receptor de
 * arranque corre antes de que exista el grafo de Hilt, y pedirle dependencias ahí es la
 * forma clásica de que el arranque truene justo cuando más falta hace.
 */
class KioskBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldLaunchOnBoot(intent.action, kioskEnabled(context))) {
            Log.d(TAG, "Arranque ignorado (acción=${intent.action}).")
            return
        }

        Log.i(TAG, "Arranque: el kiosco está prendido — levantando la app.")
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    // Desde un receptor no hay tarea a la que engancharse: sin NEW_TASK
                    // Android rechaza el arranque.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.onFailure { Log.e(TAG, "No se pudo levantar la app al arrancar", it) }
    }

    companion object {
        /**
         * La decisión, aparte del sistema para poder probarla: se levanta SÓLO si el aviso
         * es de arranque Y este aparato trabaja como kiosco.
         */
        fun shouldLaunchOnBoot(action: String?, kioskEnabled: Boolean): Boolean {
            val esArranque = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            return esArranque && kioskEnabled
        }

        private const val TAG = "KioskBoot"

        /** Mismas llaves que `KioskPrefs`; si allá cambian, aquí también. */
        private const val PREFS = "avoqado_kiosk"
        private const val KEY_ENABLED = "kiosk_enabled"

        private fun kioskEnabled(context: Context): Boolean =
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
            }.getOrDefault(false)
    }
}
