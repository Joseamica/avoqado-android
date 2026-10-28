package com.avoqado.pos.kiosk.domain

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ¿Esta segunda pantalla trabaja como KIOSCO de autoservicio, o como el espejo
 * del mostrador de siempre?
 *
 * Vive en SharedPreferences y NO en el cache de settings del server, por la
 * misma razón dura que [com.avoqado.pos.customerdisplay.DisplayModePrefs]: ese
 * cache se borra ante un 4xx, y un error de permisos convertiría el kiosco en
 * espejo a media clase — con gente parada enfrente sin poder registrarse. El
 * valor local es la autoridad para APLICAR; el server sólo sincroniza.
 *
 * **Apagado por defecto.** Prenderlo por nuestra cuenta le cambiaría la pantalla
 * del cliente a todo negocio con doble pantalla, sin avisar.
 *
 * 🔴 **Llave propia.** Antes el kiosco compartía `avoqado_kiosk/kiosk_enabled` con «Esconder
 * barras» ([com.avoqado.pos.settings.domain.ScreenPinningManager], que SIGUE usándola): prender el
 * fijado en una tableta de una pantalla prendía el kiosco al reiniciar. Hoy el kiosco usa
 * [KEY_CHECKIN] y se migra UNA vez ([readOrMigrate]); la llave vieja nunca se escribe desde aquí.
 */
@Singleton
class KioskPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_CHECKIN, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_CHECKIN, value).apply()
        _enabled.value = value
    }

    /**
     * Migra la llave vieja una vez, al arrancar la app (antes de observar [enabled]).
     * [keepLegacy]: ¿este aparato SÍ es un kiosco de verdad? ANDROID = tiene segunda pantalla;
     * WINDOWS = siempre (allá la llave vieja sólo la escribió el kiosco).
     */
    fun migrateLegacyFlag(keepLegacy: Boolean) {
        _enabled.value = readOrMigrate(prefs) { keepLegacy }
    }

    companion object {
        const val PREFS = "avoqado_kiosk"
        const val KEY_CHECKIN = "checkin_kiosk_enabled"

        /** Desde hoy sólo de ScreenPinningManager («Esconder barras»): el kiosco la LEE una vez y no la escribe. */
        const val KEY_SHARED_LEGACY = "kiosk_enabled"

        /** [enabled] = lo que vale el kiosco; [persist] = si ya se puede guardar en [KEY_CHECKIN] (si no, se reevalúa el próximo arranque). */
        data class Migration(val enabled: Boolean, val persist: Boolean)

        /**
         * La decisión, aparte del disco para poder probarla. `null` = ya migrado (existe [KEY_CHECKIN]).
         *
         * Un fijado prendido en una tableta SIN segunda pantalla no prende el kiosco, pero tampoco se
         * guarda el «no» (`persist = false`): si la segunda pantalla de una D3 tarda en enumerarse en un
         * arranque, ese arranque no le apaga el kiosco para siempre.
         */
        fun legacyMigration(hasNewKey: Boolean, legacyShared: Boolean, keepLegacy: Boolean): Migration? = when {
            hasNewKey -> null
            !legacyShared -> Migration(enabled = false, persist = true)
            keepLegacy -> Migration(enabled = true, persist = true)
            else -> Migration(enabled = false, persist = false)
        }

        /** Lee [KEY_CHECKIN]; si no existe, migra desde [KEY_SHARED_LEGACY]. [keepLegacy] sólo se evalúa si la llave vieja estaba prendida. */
        fun readOrMigrate(prefs: SharedPreferences, keepLegacy: () -> Boolean): Boolean {
            if (prefs.contains(KEY_CHECKIN)) return prefs.getBoolean(KEY_CHECKIN, false)
            val legacy = prefs.getBoolean(KEY_SHARED_LEGACY, false)
            val m = legacyMigration(hasNewKey = false, legacyShared = legacy, keepLegacy = legacy && keepLegacy())
                ?: return prefs.getBoolean(KEY_CHECKIN, false)
            if (m.persist) prefs.edit().putBoolean(KEY_CHECKIN, m.enabled).apply()
            return m.enabled
        }
    }
}
