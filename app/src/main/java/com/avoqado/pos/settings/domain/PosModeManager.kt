package com.avoqado.pos.settings.domain

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PosModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val switchState: VenueSwitchState,
) {
    private val prefs = context.getSharedPreferences("avoqado_pos_mode", Context.MODE_PRIVATE)

    private val _currentMode = MutableStateFlow(loadMode())
    val currentMode: StateFlow<PosMode> = _currentMode.asStateFlow()

    private fun prefKey(): String {
        val venueId = secureStorage.venueId
        return if (venueId != null) "posMode_$venueId" else "pos_mode"
    }

    private fun loadMode(): PosMode {
        val stored = prefs.getString(prefKey(), null)
        if (stored != null) {
            return PosMode.entries.find { it.key == stored } ?: PosMode.RETAIL
        }
        // Migración del VenueMode legacy (Estándar/Reservas, llave global):
        // si el dispositivo vivía en "Reservas", conservarlo como modo único.
        // computeVisibleTabs igual cae a Retail si el venue no tiene reservas.
        if (secureStorage.venueMode == "reservations") return PosMode.RESERVATIONS
        return PosMode.RETAIL
    }

    fun switchMode(mode: PosMode) {
        if (mode == _currentMode.value) return
        // Loader de recarga (Square): el rebuild del NavHost hace el resto.
        switchState.pulse("Cambiando a modo ${mode.displayName}…")
        prefs.edit().putString(prefKey(), mode.key).apply()
        _currentMode.value = mode
    }

    /** Reload mode for the current venue (call after venue switch). */
    fun reloadForCurrentVenue() {
        _currentMode.value = loadMode()
    }
}
