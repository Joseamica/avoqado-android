package com.avoqado.pos.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.OrigenDeLaDeclaracion
import com.avoqado.pos.payment.data.model.PeticionDePinDeGerente
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lo que queda por cancelar, FUERA del flujo de cobro.
 *
 * Sin esto, una cancelación que no se pudo confirmar sólo existía dentro de la pantalla de pago: el
 * cajero salía, la venta seguía abierta y nadie volvía a enterarse. Aquí se ve (banner) y se puede
 * empujar a mano (hoja de pendientes).
 */
@HiltViewModel
class CancelacionesPendientesViewModel @Inject constructor(
    private val cancelacionDeCobro: CancelacionDeCobroCoordinator,
    private val terminalPaymentService: TerminalPaymentService,
) : ViewModel() {

    val pendientes: StateFlow<List<IntencionDeCancelarCobro>> = cancelacionDeCobro.pendientes

    val enCurso: StateFlow<Set<String>> = cancelacionDeCobro.enCurso

    /** «Volver a consultar»: sólo pregunta — jamás cobra ni borra por su cuenta. */
    fun volverAConsultar(id: String) = cancelacionDeCobro.procesarAhora(id)

    private val _declarando = MutableStateFlow<Set<String>>(emptySet())
    /** D19: las filas con una declaración en vuelo (el botón se apaga; un doble toque no manda dos). */
    val declarando: StateFlow<Set<String>> = _declarando.asStateFlow()

    private val _avisos = MutableStateFlow<Map<String, String>>(emptyMap())
    /** D19: lo que contestó el servidor a la declaración de cada fila, tal cual. */
    val avisos: StateFlow<Map<String, String>> = _avisos.asStateFlow()

    /**
     * D19 (4-nov): «No se cobró» desde «Por revisar», con la terminal ya suelta. Es la MISMA declaración del cobro
     * ([TerminalPaymentService.declararNoCobrado], online-only, con el nombre de quien la hace). Si el servidor libera, sólo se vuelve a
     * consultar: el coordinador ve NOT_CHARGED y cancela la orden con su barrera de siempre. Aquí nunca se borra nada.
     * El PIN de gerente no se pide aquí: si el servidor lo exige, su mensaje lo dice y se declara desde la pantalla del cobro o con un gerente.
     */
    fun declarar(intencion: IntencionDeCancelarCobro) {
        val requestId = intencion.requestId ?: return
        val id = intencion.id
        if (id in _declarando.value) return
        _declarando.update { it + id }
        _avisos.update { it - id }
        viewModelScope.launch {
            try {
                when (val r = terminalPaymentService.declararNoCobrado(requestId, intencion.venueId.takeIf { it.isNotBlank() })) {
                    ResultadoDeDeclaracion.Liberada -> cancelacionDeCobro.procesarTrasDeclarar(id)
                    is ResultadoDeDeclaracion.Rechazada ->
                        // Codex r2 #2: el mesero sin `payments:reconcile-uncharged` tiene aquí mismo el teclado del gerente, como en el cobro.
                        if (r.code == CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE) {
                            esperaDelPin = EsperaDelPin(intencion, r.mensaje)
                            _pidiendoPin.value = PeticionDePinDeGerente(++idDelTeclado, OrigenDeLaDeclaracion.CANCELACION_PENDIENTE)
                        } else _avisos.update { it + (id to r.mensaje) }
                    ResultadoDeDeclaracion.SinConexion -> _avisos.update { it + (id to CancelacionDeCobro.DECLARACION_SIN_RED) }
                    ResultadoDeDeclaracion.SesionRenovada -> _avisos.update { it + (id to CancelacionDeCobro.DECLARACION_SESION_RENOVADA) }
                }
            } finally {
                _declarando.update { it - id }
            }
        }
    }

    private class EsperaDelPin(val intencion: IntencionDeCancelarCobro, val rechazo: String) {
        /** Un solo envío por espera: un segundo «Autorizar» (o cerrar y reabrir) espera al primero, no manda otro. */
        var envio: kotlinx.coroutines.Deferred<OverrideResult>? = null
    }
    private var esperaDelPin: EsperaDelPin? = null
    private var idDelTeclado = 0L
    private val _pidiendoPin = MutableStateFlow<PeticionDePinDeGerente?>(null)
    /** D19: el teclado del PIN de gerente abierto (el mismo `TecladoDelPinDeGerente` del cobro). */
    val pidiendoPin: StateFlow<PeticionDePinDeGerente?> = _pidiendoPin.asStateFlow()

    /**
     * El PIN viaja en el cuerpo de la MISMA declaración y no se guarda. PIN equivocado ⇒ el teclado se queda; demasiados intentos ⇒ lo
     * dice; cualquier otra respuesta cierra el teclado y se ve en la fila. 🔴 Codex r3 #2: el envío Y el trato de su respuesta corren en
     * `viewModelScope`: si el teclado se cierra con la declaración viajando, la respuesta se aplica igual (el servidor pudo liberar).
     */
    suspend fun enviarPin(pin: String): OverrideResult {
        val espera = esperaDelPin ?: return TECLADO_CERRADO
        espera.envio?.takeIf { it.isActive }?.let { return it.await() }
        val requestId = espera.intencion.requestId ?: return TECLADO_CERRADO
        val id = espera.intencion.id
        val envio = viewModelScope.async {
            val r = terminalPaymentService.declararNoCobrado(requestId, espera.intencion.venueId.takeIf { it.isNotBlank() }, supervisorPin = pin)
            if (r is ResultadoDeDeclaracion.Rechazada) when (r.code) {
                CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE -> return@async OverrideResult.Insufficient
                CancelacionDeCobro.CODIGO_DEMASIADOS_INTENTOS -> return@async OverrideResult.TooManyAttempts
            }
            if (esperaDelPin === espera) { esperaDelPin = null; _pidiendoPin.value = null }
            when (r) {
                ResultadoDeDeclaracion.Liberada -> cancelacionDeCobro.procesarTrasDeclarar(id)
                is ResultadoDeDeclaracion.Rechazada -> _avisos.update { it + (id to r.mensaje) }
                ResultadoDeDeclaracion.SinConexion -> _avisos.update { it + (id to CancelacionDeCobro.DECLARACION_SIN_RED) }
                ResultadoDeDeclaracion.SesionRenovada -> _avisos.update { it + (id to CancelacionDeCobro.DECLARACION_SESION_RENOVADA) }
            }
            TECLADO_CERRADO
        }
        espera.envio = envio
        return envio.await()
    }

    /** Cerrar el teclado sin PIN: se ve el motivo del servidor y el botón vuelve a estar disponible. Con un PIN viajando no hace nada. */
    fun cerrarPin() {
        val espera = esperaDelPin ?: return
        if (espera.envio?.isActive == true) return
        esperaDelPin = null
        _pidiendoPin.value = null
        _avisos.update { it + (espera.intencion.id to espera.rechazo) }
    }

    /** El aviso de un cobro que la terminal SÍ hizo; el resto son cancelaciones sin confirmar. */
    fun esAvisoDeCobro(intencion: IntencionDeCancelarCobro): Boolean =
        intencion.faseActual == FaseDeCancelacion.SE_COBRO

    private companion object {
        /** Lo que cierra el teclado sin mensaje propio: la respuesta ya se ve en la fila. Mismo truco que el cobro. */
        val TECLADO_CERRADO = OverrideResult.Granted(token = "", authorizedByName = "")
    }
}
