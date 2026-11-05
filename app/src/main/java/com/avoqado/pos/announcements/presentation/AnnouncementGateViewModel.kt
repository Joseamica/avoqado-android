package com.avoqado.pos.announcements.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.announcements.data.AnnouncementsRepository
import com.avoqado.pos.announcements.data.CierresDeAnuncios
import com.avoqado.pos.announcements.data.ResultadoDelCierre
import com.avoqado.pos.announcements.data.respuestaVigente
import com.avoqado.pos.announcements.data.ventanaVisible
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decide si hay un anuncio que deba interrumpir al entrar. */
@HiltViewModel
class AnnouncementGateViewModel @Inject constructor(
    private val repository: AnnouncementsRepository,
    private val secureStorage: SecureStorage,
    private val cierres: CierresDeAnuncios,
    private val conectividad: ConnectivityMonitor,
) : ViewModel() {

    private val _ventanaPendiente = MutableStateFlow<AnnouncementsRepository.VentanaPendiente?>(null)
    val ventanaPendiente: StateFlow<AnnouncementsRepository.VentanaPendiente?> = _ventanaPendiente.asStateFlow()

    /** Quién está adentro; la pantalla cierra el «Ver más» cuando cambia. */
    val generacion: StateFlow<Long> get() = secureStorage.generacionDeSesion

    private var consulta: Job? = null

    /** Un solo envío de pendientes a la vez: el arranque y la reconexión no se pisan. */
    private val enviando = Mutex()

    init {
        // Se consulta al arrancar y cada vez que entra o sale alguien (codex r4 #4): si la app abrió sin sesión, la
        // ventana se pide al entrar, no nunca.
        viewModelScope.launch { secureStorage.generacionDeSesion.collect { consultar() } }
        // Al volver la red (red + servidor), lo que se cerró sin red se manda sin esperar a otra sesión (codex impl r3 #1).
        // La misma señal y la misma espera de estabilización que la cola de cobros.
        viewModelScope.launch {
            PaymentSyncService.alReconectar(
                combine(conectividad.isConnected, conectividad.isServerReachable) { red, servidor -> red && servidor },
            ) {
                val staffId = secureStorage.userId ?: return@alReconectar
                mandarPendientes(staffId, secureStorage.generacion)
            }
        }
    }

    fun consultar() {
        val pedida = secureStorage.generacion
        val staffId = secureStorage.userId
        consulta?.cancel()
        _ventanaPendiente.value = null   // Al cambiar de persona no queda nada de la anterior.
        consulta = viewModelScope.launch {
            val ventana = repository.fetchVentanaPendiente()
            ensureActive()   // El repositorio se traga la cancelación: una consulta reemplazada no publica nada.
            // La respuesta de una sesión que ya no está no se enseña (codex r5 #3). La persona también: `clearSession` sube
            // la generación antes de borrar a la persona.
            if (!respuestaVigente(pedida, secureStorage.generacion) || secureStorage.userId != staffId) return@launch
            val cerrados = staffId?.let(cierres::cerrados).orEmpty()
            _ventanaPendiente.value = ventana?.takeIf { ventanaVisible(it.id, cerrados) }
            if (staffId != null) mandarPendientes(staffId, pedida)
        }
    }

    /**
     * Cerrar apaga la ventana para siempre, en este y en cualquier otro aparato. Se guarda en el aparato ANTES de la red;
     * si el POST no llega, se reintenta en la siguiente consulta de esta misma persona.
     */
    fun cerrar(announcementId: String) {
        _ventanaPendiente.value = null
        val staffId = secureStorage.userId ?: return
        cierres.marcar(staffId, announcementId)
        viewModelScope.launch {
            if (repository.recordDismiss(announcementId, staffId) == ResultadoDelCierre.RESUELTO) {
                cierres.marcarSincronizado(staffId, announcementId)
            }
        }
    }

    /**
     * Uno por uno, en el orden en que se cerraron. Sólo sin red se para; un 409 u otro 4xx deja ESE pendiente y sigue
     * con el siguiente, para que uno atorado no trabe a los demás.
     */
    private suspend fun mandarPendientes(staffId: String, pedida: Long) {
        // Uno detrás de otro, nunca dos a la vez. El que espera vuelve a leer la lista: lo que el anterior ya sincronizó no
        // sale otra vez.
        enviando.withLock { mandarPendientesUnoPorUno(staffId, pedida) }
    }

    private suspend fun mandarPendientesUnoPorUno(staffId: String, pedida: Long) {
        for (id in cierres.pendientes(staffId)) {
            if (!respuestaVigente(pedida, secureStorage.generacion)) return
            when (repository.recordDismiss(id, staffId)) {
                ResultadoDelCierre.SIN_RED -> return
                ResultadoDelCierre.RESUELTO -> cierres.marcarSincronizado(staffId, id)
                ResultadoDelCierre.PENDIENTE -> Unit
            }
        }
    }
}
