package com.avoqado.pos.giftcards.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.EstadoDeAjustes
import com.avoqado.pos.giftcards.data.GiftCardsApi
import com.avoqado.pos.giftcards.data.GiftCardsRepository
import com.avoqado.pos.giftcards.data.Respuesta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.domain.estadoDeLaHoja
import com.avoqado.pos.giftcards.domain.motivoAlPrender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TarjetasDeRegaloViewModel @Inject constructor(
    private val repo: GiftCardsRepository,
    private val roles: RoleManager,
    private val planes: PlanManager,
    private val red: ConnectivityMonitor,
    private val almacen: SecureStorage,
) : ViewModel() {
    private var venueObservado = almacen.venueId
    private var generacion = 0L
    private fun cache(venue: String?): EstadoDeAjustes = venue?.let { almacen.leerAjustesDeTarjetas(it) }?.let {
        if (it == "null") EstadoDeAjustes.NoDisponible
        else runCatching { EstadoDeAjustes.Disponible(GiftCardsApi.parsearAjustes(it)) }.getOrNull()
    } ?: EstadoDeAjustes.Desconocido

    // El Flow global del repositorio no identifica el negocio de su última foto.
    private val ajustesDeLaSucursal = MutableStateFlow(cache(venueObservado))
    private val ventaVacia = MutableStateFlow(true)
    private fun calcular(
        ajustes: EstadoDeAjustes = ajustesDeLaSucursal.value,
        online: Boolean = red.isConnected.value && red.isServerReachable.value,
        vacia: Boolean = ventaVacia.value,
    ) = estadoDeLaHoja(
        if (venueObservado != almacen.venueId) EstadoDeAjustes.Desconocido
        else when (val actuales = repo.cambiosDeAjustes.value[venueObservado]) {
            EstadoDeAjustes.NoDisponible, EstadoDeAjustes.Desconocido, EstadoDeAjustes.SinPermiso -> actuales
            else -> ajustes
        },
        online, roles.canReadGiftCards, roles.canSellGiftCards, vacia, planes.requiredTierLabel("GIFT_CARDS"), roles.canEnableGiftCards,
    )
    private val estado = MutableStateFlow(calcular())
    val hoja = estado.asStateFlow()
    val venueActual: String? get() = almacen.venueId

    private val _prendiendo = MutableStateFlow(false)
    val prendiendo = _prendiendo.asStateFlow()
    private val _errorAlPrender = MutableStateFlow<String?>(null)
    val errorAlPrender = _errorAlPrender.asStateFlow()
    private val _prendidas = MutableStateFlow(false)
    val prendidas = _prendidas.asStateFlow()

    fun limpiarErrorAlPrender() { _errorAlPrender.value = null }
    fun avisoDePrendidasVisto() { _prendidas.value = false }

    /** Prender desde el POS (founder, 3-nov-2026). Lo decide el servidor; aquí sólo se evita mandar dos y se pinta lo que respondió. */
    fun prender() {
        if (_prendiendo.value || !calcular().prender.habilitada) return
        val venue = almacen.venueId ?: return
        generacion++ // Una apertura que venía en camino no reemplaza lo que responda esto.
        _prendiendo.value = true
        _errorAlPrender.value = null
        viewModelScope.launch {
            val resultado = repo.prender(venue)
            _prendiendo.value = false
            if (venue != almacen.venueId || venue != venueObservado) return@launch
            when (resultado) {
                is Respuesta.Ok -> {
                    ajustesDeLaSucursal.value = EstadoDeAjustes.Disponible(resultado.valor)
                    estado.value = calcular()
                    _prendidas.value = true
                }
                is Respuesta.Rechazo -> _errorAlPrender.value = motivoAlPrender(resultado.fallo)
                Respuesta.SinRespuesta -> _errorAlPrender.value = TextosDeTarjetas.PRENDER_SIN_RESPUESTA
            }
        }
    }

    fun ajustesParaConsultar(venueId: String): com.avoqado.pos.giftcards.data.AjustesDeTarjetas? {
        if (venueId != almacen.venueId || venueId != venueObservado || !calcular().consultar.habilitada) return null
        return (cache(venueId) as? EstadoDeAjustes.Disponible)?.ajustes
    }

    fun ajustesParaVender(venueId: String): com.avoqado.pos.giftcards.data.AjustesDeTarjetas? {
        if (venueId != almacen.venueId || venueId != venueObservado || !calcular().vender.habilitada) return null
        return (cache(venueId) as? EstadoDeAjustes.Disponible)?.ajustes
    }

    init {
        viewModelScope.launch {
            combine(ajustesDeLaSucursal, red.isConnected, red.isServerReachable, ventaVacia, repo.cambiosDeAjustes) { ajustes, conectado, servidor, vacia, _ ->
                calcular(ajustes, conectado && servidor, vacia)
            }.collect { estado.value = it }
        }
    }

    fun alAbrir(ventaVacia: Boolean) {
        generacion++
        val venue = almacen.venueId
        if (venue != venueObservado) {
            venueObservado = venue
            ajustesDeLaSucursal.value = cache(venue)
        }
        this.ventaVacia.value = ventaVacia
        estado.value = calcular()
        if (!red.isConnected.value || !red.isServerReachable.value) return
        if (venue == null) return
        val apertura = generacion
        viewModelScope.launch {
            val resultado = repo.refrescarAjustes(venue)
            // También descarta la respuesta antigua si se salió de A y luego se volvió a A.
            if (venue == almacen.venueId && venue == venueObservado && apertura == generacion) {
                ajustesDeLaSucursal.value = repo.cambiosDeAjustes.value[venue] ?: resultado
                estado.value = calcular()
            }
        }
    }
}
