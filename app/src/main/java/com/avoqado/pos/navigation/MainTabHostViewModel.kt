package com.avoqado.pos.navigation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MainTabHostViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val planManager: PlanManager,
) : ViewModel() {

    private val _mode = MutableStateFlow(VenueMode.fromStorage(secureStorage.venueMode))
    val mode: StateFlow<VenueMode> = _mode.asStateFlow()

    private val _reservationsEnabled = MutableStateFlow(effectiveReservationsEnabled())
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
        val enabled = effectiveReservationsEnabled()
        _mode.value = mode
        _reservationsEnabled.value = enabled
        _tabs.value = computeTabs(mode, enabled)
    }

    /**
     * Local toggle ANDed with the plan gate: a stale local toggle can never
     * expose the Calendar tab on a plan without RESERVATIONS. Fail-open when
     * the plan is unknown (old server / not fetched).
     */
    private fun effectiveReservationsEnabled(): Boolean =
        secureStorage.reservationsEnabled && planManager.hasFeature("RESERVATIONS")

    private fun computeTabs(mode: VenueMode, reservationsEnabled: Boolean): List<MainTab> = when {
        reservationsEnabled && mode == VenueMode.RESERVATIONS ->
            listOf(
                MainTab.CALENDAR,
                MainTab.CHECKOUT,
                MainTab.INVENTORY,
                MainTab.TRANSACTIONS,
                MainTab.NOTIFICATIONS,
                MainTab.MORE,
            )
        else ->
            listOf(MainTab.CHECKOUT, MainTab.INVENTORY, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE)
    }
}
