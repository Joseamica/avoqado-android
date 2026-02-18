package com.avoqado.pos.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoreMenuViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _venueName = MutableStateFlow(secureStorage.venueName ?: "Sin establecimiento")
    val venueName: StateFlow<String> = _venueName.asStateFlow()

    private val _venueRole = MutableStateFlow(secureStorage.userRole)
    val venueRole: StateFlow<String?> = _venueRole.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    val currentVenueId: String?
        get() = secureStorage.venueId

    val venuesList: List<StoredVenue>
        get() = secureStorage.venuesList

    val hasMultipleVenues: Boolean
        get() = secureStorage.venuesList.size > 1

    fun switchVenue(venue: StoredVenue) {
        if (venue.id == secureStorage.venueId) return

        _isSwitching.value = true
        viewModelScope.launch {
            try {
                authRepository.switchVenue(venue)

                _venueName.value = venue.name
                _venueRole.value = venue.role

                Log.d("🔄", "✅ Venue switch complete: ${venue.name}")
            } catch (e: Exception) {
                Log.e("🔄", "❌ Venue switch error: ${e.message}")
            } finally {
                _isSwitching.value = false
            }
        }
    }
}
