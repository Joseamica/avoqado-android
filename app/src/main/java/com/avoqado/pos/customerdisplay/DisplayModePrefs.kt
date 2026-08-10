package com.avoqado.pos.customerdisplay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ¿Este mostrador está armado al revés — el cliente viendo la pantalla grande y
 * el cajero trabajando en la chica?
 *
 * Vive en SharedPreferences y NO en el cache de settings del server por una
 * razón dura: el refresh de settings borra su cache ante un 4xx
 * (`TpvSettingsRepository.kt:127-131`). Si el modo de pantallas viviera ahí, un
 * error de permisos movería la caja de pantalla a media venta. El valor local
 * es la autoridad para APLICAR; el server solo sincroniza.
 *
 * Apagado por defecto: prenderlo por nuestra cuenta movería la caja de alguien
 * sin avisar.
 */
@Singleton
class DisplayModePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("avoqado_display_mode", Context.MODE_PRIVATE)

    private val _inverted = MutableStateFlow(prefs.getBoolean(KEY_INVERTED, false))
    val inverted: StateFlow<Boolean> = _inverted.asStateFlow()

    /** true = hay un cambio hecho en este equipo que el server todavía no confirmó. */
    private val _dirty = MutableStateFlow(prefs.getBoolean(KEY_DIRTY, false))
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Cambio hecho DESDE este equipo: aplica ya y queda pendiente de empujar. */
    fun setInverted(value: Boolean) {
        prefs.edit().putBoolean(KEY_INVERTED, value).putBoolean(KEY_DIRTY, true).apply()
        _inverted.value = value
        _dirty.value = true
    }

    /**
     * Valor que llegó del server. Se ignora si hay un cambio local pendiente:
     * si no, un equipo sin internet que acaba de prender el modo lo vería
     * revertirse en el siguiente refresh.
     */
    fun adoptFromServer(value: Boolean) {
        if (_dirty.value) return
        if (_inverted.value == value) return
        prefs.edit().putBoolean(KEY_INVERTED, value).apply()
        _inverted.value = value
    }

    /** El server confirmó nuestro valor: a partir de aquí él manda. */
    fun markSynced() {
        prefs.edit().putBoolean(KEY_DIRTY, false).apply()
        _dirty.value = false
    }

    private companion object {
        const val KEY_INVERTED = "customer_display_inverted"
        const val KEY_DIRTY = "customer_display_inverted_dirty"
    }
}
