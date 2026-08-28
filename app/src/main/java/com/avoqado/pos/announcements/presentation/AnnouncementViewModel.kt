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

/**
 * Carga el detalle de un anuncio y registra que se abrió.
 * Espejo de `AnnouncementDetailViewModel` en iOS.
 */
@HiltViewModel
class AnnouncementViewModel @Inject constructor(
    private val repository: AnnouncementsRepository,
) : ViewModel() {

    private val _detalle = MutableStateFlow<AnnouncementsRepository.Detalle?>(null)
    val detalle: StateFlow<AnnouncementsRepository.Detalle?> = _detalle.asStateFlow()

    private val _cargando = MutableStateFlow(false)
    val cargando: StateFlow<Boolean> = _cargando.asStateFlow()

    /**
     * 🔴 Sin esto la pantalla NO puede decir que falló: un detalle nulo se ve idéntico a
     * uno cargando, así que un error dejaba el cargador girando para siempre. Pasó de
     * verdad en la tablet.
     */
    private val _fallo = MutableStateFlow(false)
    val fallo: StateFlow<Boolean> = _fallo.asStateFlow()

    fun abrir(announcementId: String) {
        viewModelScope.launch {
            _cargando.value = true
            _fallo.value = false
            val cargado = repository.fetchDetail(announcementId)
            _detalle.value = cargado
            _fallo.value = cargado == null
            _cargando.value = false
            // La medición no puede retrasar lo que ve la persona: va después de pintar.
            repository.recordOpen(announcementId)
        }
    }

    fun registrarAccion(announcementId: String) {
        viewModelScope.launch { repository.recordCta(announcementId) }
    }

    fun limpiar() {
        _detalle.value = null
        _fallo.value = false
    }
}
