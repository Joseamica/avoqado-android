package com.avoqado.pos.announcements.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.announcements.data.AnnouncementsRepository
import com.avoqado.pos.announcements.data.respuestaVigente
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private val secureStorage: SecureStorage,
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

    /**
     * La carga se descartó porque ya entró otra persona: el diálogo se cierra solo en vez de quedarse girando (un
     * detalle nulo sin fallo pinta el cargador).
     */
    private val _descartado = MutableStateFlow(false)
    val descartado: StateFlow<Boolean> = _descartado.asStateFlow()

    private var carga: Job? = null

    /**
     * La misma regla que la ventana (codex r6 #1): el detalle que llega cuando ya entró otra persona no se enseña ni
     * cuenta como abierto, y una carga nueva reemplaza a la anterior.
     */
    fun abrir(announcementId: String) {
        val pedida = secureStorage.generacion
        val staffId = secureStorage.userId
        carga?.cancel()
        _descartado.value = false
        carga = viewModelScope.launch {
            _cargando.value = true
            _fallo.value = false
            val cargado = repository.fetchDetail(announcementId)
            ensureActive()
            if (!respuestaVigente(pedida, secureStorage.generacion) || secureStorage.userId != staffId) {
                _detalle.value = null
                _cargando.value = false
                _descartado.value = true
                return@launch
            }
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
        _descartado.value = false
    }
}
