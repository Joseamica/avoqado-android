package com.avoqado.pos.customers.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.ResultadoDeAjuste
import com.avoqado.pos.customers.data.SellosRepository
import com.avoqado.pos.customers.data.TarjetaDeSellos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Una apertura del diálogo «Ajustar sellos»: su identidad, su cliente y la tarjeta que el usuario vio al abrir. */
data class AjusteAbierto(val id: Long, val customerId: String, val foto: TarjetaDeSellos)

sealed interface EstadoDeSellos {
    data object Cargando : EstadoDeSellos
    data object Oculto : EstadoDeSellos
    data object SinRed : EstadoDeSellos
    data class Listo(val tarjeta: TarjetaDeSellos, val puedeAjustar: Boolean) : EstadoDeSellos
}

@HiltViewModel
class CustomerStampsViewModel @Inject constructor(
    private val repo: SellosRepository,
    private val roles: RoleManager,
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoDeSellos>(EstadoDeSellos.Cargando)
    val estado: StateFlow<EstadoDeSellos> = _estado.asStateFlow()

    /** Último desenlace de un ajuste (la pantalla lo pinta y lo acusa con [limpiarResultado]). */
    private val _resultado = MutableStateFlow<ResultadoDeAjuste?>(null)
    val resultado: StateFlow<ResultadoDeAjuste?> = _resultado.asStateFlow()

    fun limpiarResultado() { _resultado.value = null }

    private val _guardando = MutableStateFlow(false)
    /**
     * La apertura vigente tiene su POST en vuelo: Guardar queda deshabilitado (un segundo toque recibiría un falso
     * STAMPS_CHANGED). Es de la apertura: abrir otra lo limpia y el `finally` de una anterior no lo toca.
     */
    val guardando: StateFlow<Boolean> = _guardando.asStateFlow()

    private var clienteActual: String? = null
    private var jobDeCarga: Job? = null

    fun cargar(customerId: String) {
        if (clienteActual != customerId) {
            clienteActual = customerId
            _estado.value = EstadoDeSellos.Cargando
            _resultado.value = null
            _ajusteAbierto.value = null // una apertura es de UN cliente
        }
        jobDeCarga?.cancel()
        jobDeCarga = viewModelScope.launch { recargar(customerId) }
    }

    private suspend fun recargar(customerId: String) {
        val r = repo.obtener(customerId)
        if (customerId != clienteActual) return // respuesta tardía de otro cliente
        _estado.value = r.fold(
            onSuccess = { t -> if (t == null) EstadoDeSellos.Oculto else EstadoDeSellos.Listo(t, roles.canAdjustLoyalty) },
            onFailure = { if (it is IOException) EstadoDeSellos.SinRed else EstadoDeSellos.Oculto },
        )
    }

    private val _ajusteAbierto = MutableStateFlow<AjusteAbierto?>(null)
    /**
     * 🔴 La apertura vigente del diálogo (null = cerrado). Es la ÚNICA fuente del cliente y de la foto que se mandan: una
     * recarga, cambiar de cliente o la respuesta de una apertura anterior no la cambian.
     */
    val ajusteAbierto: StateFlow<AjusteAbierto?> = _ajusteAbierto.asStateFlow()
    private var ultimaApertura = 0L
    /** La apertura dueña del «guardando»: un `finally` de otra no lo libera. */
    private var guardandoDe: Long? = null

    /** Captura la tarjeta de la lectura vigente con una identidad nueva. Sin lectura `Listo`, no abre. */
    fun abrirAjuste() {
        val cliente = clienteActual ?: return
        val e = _estado.value as? EstadoDeSellos.Listo ?: return
        _resultado.value = null // abrir es un acto nuevo del usuario: el aviso anterior ya cumplió
        guardandoDe = null
        _guardando.value = false
        _ajusteAbierto.value = AjusteAbierto(++ultimaApertura, cliente, e.tarjeta)
    }

    fun cerrarAjuste() { _ajusteAbierto.value = null }

    /** Manda [total] para la foto de la apertura vigente. Su desenlace sólo toca esa apertura. */
    fun guardarAjuste(total: Int, motivo: String) {
        val a = _ajusteAbierto.value ?: return
        if (a.customerId != clienteActual) return // la apertura es de otro cliente
        if (_guardando.value) return
        guardandoDe = a.id
        _guardando.value = true
        _resultado.value = null // W4: reintentar es un acto nuevo; el aviso anterior (p. ej. «Sin conexión») ya cumplió
        viewModelScope.launch {
            try {
                val r = repo.ajustar(a.customerId, total, motivo, a.foto)
                val terminal = r is ResultadoDeAjuste.Hecho || r is ResultadoDeAjuste.Cambio || r is ResultadoDeAjuste.Apagado
                if (_ajusteAbierto.value?.id == a.id) {
                    _resultado.value = r
                    // Decidido: Hecho, y también un 409 propio, cierran el diálogo; reabrir es un acto nuevo.
                    if (terminal) _ajusteAbierto.value = null
                }
                // Leer sí puede, aunque la apertura ya no sea la vigente: es la lectura del cliente actual, no el borrador.
                if (terminal && a.customerId == clienteActual) recargar(a.customerId)
            } finally {
                if (guardandoDe == a.id) {
                    guardandoDe = null
                    _guardando.value = false
                }
            }
        }
    }
}
