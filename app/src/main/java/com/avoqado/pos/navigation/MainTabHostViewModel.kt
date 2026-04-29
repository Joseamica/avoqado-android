package com.avoqado.pos.navigation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MainTabHostViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _mode = MutableStateFlow(VenueMode.fromStorage(secureStorage.venueMode))
    val mode: StateFlow<VenueMode> = _mode.asStateFlow()

    private val _reservationsEnabled = MutableStateFlow(secureStorage.reservationsEnabled)
    val reservationsEnabled: StateFlow<Boolean> = _reservationsEnabled.asStateFlow()

    private val _tabs = MutableStateFlow(computeTabs(_mode.value, _reservationsEnabled.value))
    val tabs: StateFlow<List<MainTab>> = _tabs.asStateFlow()

    fun setMode(newMode: VenueMode) {
        secureStorage.venueMode = newMode.storageValue
        _mode.value = newMode
        _tabs.value = computeTabs(newMode, _reservationsEnabled.value)
    }

    fun refreshFromStorage() {
        val mode = VenueMode.fromStorage(secureStorage.venueMode)
        val enabled = secureStorage.reservationsEnabled
        _mode.value = mode
        _reservationsEnabled.value = enabled
        _tabs.value = computeTabs(mode, enabled)
    }

    private fun computeTabs(mode: VenueMode, reservationsEnabled: Boolean): List<MainTab> = when {
        reservationsEnabled && mode == VenueMode.RESERVATIONS ->
            listOf(MainTab.CALENDAR, MainTab.CHECKOUT, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE)
        else ->
            listOf(MainTab.CHECKOUT, MainTab.INVENTORY, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE)
    }
}
