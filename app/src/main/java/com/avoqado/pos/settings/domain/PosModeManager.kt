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
) {
    private val prefs = context.getSharedPreferences("avoqado_pos_mode", Context.MODE_PRIVATE)

    private val _currentMode = MutableStateFlow(loadMode())
    val currentMode: StateFlow<PosMode> = _currentMode.asStateFlow()

    private fun prefKey(): String {
        val venueId = secureStorage.venueId
        return if (venueId != null) "posMode_$venueId" else "pos_mode"
    }

    private fun loadMode(): PosMode {
        val key = prefs.getString(prefKey(), PosMode.RETAIL.key)
        return PosMode.entries.find { it.key == key } ?: PosMode.RETAIL
    }

    fun switchMode(mode: PosMode) {
        prefs.edit().putString(prefKey(), mode.key).apply()
        _currentMode.value = mode
    }

    /** Reload mode for the current venue (call after venue switch). */
    fun reloadForCurrentVenue() {
        _currentMode.value = loadMode()
    }
}
