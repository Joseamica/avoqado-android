package com.avoqado.pos.kds.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que ESTA tablet recuerda de su pantalla de cocina (spec 2026-09-27 §4 «Tablet» y §7): qué estación atiende —por
 * NEGOCIO, porque la misma tablet puede entrar a dos sucursales— y el sonido y la letra grande. Ajuste del APARATO
 * (patrón `CustomerDisplayPrefs`), no del negocio: el servidor no se entera. Espejo de `KDSPrefs` de iOS.
 */
@Singleton
class KdsPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("avoqado_kds", Context.MODE_PRIVATE)

    fun estacion(venueId: String): String? = prefs.getString("station_$venueId", null)

    fun guardarEstacion(venueId: String, stationId: String) {
        prefs.edit().putString("station_$venueId", stationId).apply()
    }

    var sonido: Boolean
        get() = prefs.getBoolean(SONIDO, true)
        set(valor) = prefs.edit().putBoolean(SONIDO, valor).apply()

    var letraGrande: Boolean
        get() = prefs.getBoolean(LETRA_GRANDE, false)
        set(valor) = prefs.edit().putBoolean(LETRA_GRANDE, valor).apply()

    private companion object {
        const val SONIDO = "sound"
        const val LETRA_GRANDE = "large_font"
    }
}
