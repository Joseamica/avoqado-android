package com.avoqado.pos.addons.domain

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
) {
    private val prefs = context.getSharedPreferences("avoqado_addons", Context.MODE_PRIVATE)

    private val _enabledAddonIds = MutableStateFlow(loadEnabled())
    val enabledAddonIds: StateFlow<Set<String>> = _enabledAddonIds.asStateFlow()

    private fun prefKey(): String {
        val venueId = secureStorage.venueId
        return if (venueId != null) "enabledAddons_$venueId" else "enabled_addons"
    }

    private fun loadEnabled(): Set<String> {
        val stored = prefs.getStringSet(prefKey(), null)
        return stored ?: setOf("estimates") // Pre-enable estimates by default
    }

    fun isEnabled(id: String): Boolean = _enabledAddonIds.value.contains(id)

    fun toggle(id: String) {
        val current = _enabledAddonIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        save(current)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = _enabledAddonIds.value.toMutableSet()
        if (enabled) {
            current.add(id)
        } else {
            current.remove(id)
        }
        save(current)
    }

    private fun save(ids: Set<String>) {
        prefs.edit().putStringSet(prefKey(), ids).apply()
        _enabledAddonIds.value = ids
    }

    /** Reload enabled addons for the current venue (call after venue switch). */
    fun reloadForCurrentVenue() {
        _enabledAddonIds.value = loadEnabled()
    }
}
