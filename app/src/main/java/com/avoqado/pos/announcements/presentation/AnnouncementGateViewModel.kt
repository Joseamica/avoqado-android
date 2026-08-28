package com.avoqado.pos.announcements.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.announcements.data.AnnouncementsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decide si hay un anuncio que deba interrumpir al entrar. */
@HiltViewModel
class AnnouncementGateViewModel @Inject constructor(
    private val repository: AnnouncementsRepository,
) : ViewModel() {

    private val _ventanaPendiente = MutableStateFlow<AnnouncementsRepository.VentanaPendiente?>(null)
    val ventanaPendiente: StateFlow<AnnouncementsRepository.VentanaPendiente?> = _ventanaPendiente.asStateFlow()

    fun consultar() {
        viewModelScope.launch {
            _ventanaPendiente.value = repository.fetchVentanaPendiente()
        }
    }

    /** Cerrar apaga la ventana para siempre, en este y en cualquier otro aparato. */
    fun cerrar(announcementId: String) {
        viewModelScope.launch { repository.recordDismiss(announcementId) }
        _ventanaPendiente.value = null
    }
}
