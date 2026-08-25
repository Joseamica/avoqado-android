package com.avoqado.pos.kiosk.domain

import android.content.Context
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
 */
@Singleton
class KioskPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("avoqado_kiosk", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val KEY_ENABLED = "kiosk_enabled"
    }
}
