package com.avoqado.pos.kds.data

import android.content.Context
import com.avoqado.pos.kds.domain.KDSOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /**
     * Codex 3.6 (#2): la última lista buena del servidor para ESTA sucursal y estación. Al abrir sin internet (tablet
     * reiniciada), el tablero arranca con ella: lo que el servidor ya devolvía y la copia local ya retiró no desaparece de la
     * cocina. Una lectura buena la reescribe. Espejo de `KDSPrefs.guardarFoto` de iOS.
     */
    fun guardarFoto(venueId: String, stationId: String, comandas: List<KDSOrder>, tomadaEnMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putString(llaveDeFoto(venueId, stationId), json.encodeToString(FotoDelTablero.serializer(), FotoDelTablero(tomadaEnMillis, comandas))).apply()
    }

    /** La foto de esa sucursal y estación si tiene menos de 12 h (el horizonte del LISTO local); `null` si no hay, venció o es ilegible. */
    fun foto(venueId: String, stationId: String, ahora: Long = System.currentTimeMillis()): List<KDSOrder>? {
        val texto = prefs.getString(llaveDeFoto(venueId, stationId), null) ?: return null
        val foto = runCatching { json.decodeFromString(FotoDelTablero.serializer(), texto) }.getOrNull() ?: return null
        return foto.comandas.takeIf { ahora - foto.tomadaEnMillis in 0 until KdsTicketsLocalesStore.VIGENCIA_MS }
    }

    @Serializable
    private data class FotoDelTablero(val tomadaEnMillis: Long, val comandas: List<KDSOrder>)

    private fun llaveDeFoto(venueId: String, stationId: String) = "board_${venueId}_$stationId"

    private val json = Json { ignoreUnknownKeys = true }

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
