package com.avoqado.pos.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 🔴 F1 (tarea 6): la fila EXACTA que revisa el gerente — un cobro con tarjeta en duda de este aparato («Cobros por confirmar») o una
 * cancelación pendiente con cobro. La firma va sobre ESTA solicitud aunque la lista cambie mientras teclea su PIN.
 *
 * @param montoCents importe + propina del intento; `null` = «Importe por confirmar» (una fila antigua: se le pregunta a Avoqado).
 * @param cancelacionId si es una cancelación pendiente: el coordinador la termina al liberarse (cancela la orden con su barrera).
 */
data class CobroPorConfirmar(
    val requestId: String,
    val venueId: String?,
    val montoCents: Int?,
    val desdeMillis: Long?,
    val cancelacionId: String? = null,
)

/** Los pasos de la revisión del gerente (los de la TPV, tarea 4): PIN primero, la decisión después. */
sealed interface PasoDelGerente {
    data object Cerrado : PasoDelGerente

    /** «Gerente: escribe tu PIN». [id] = llave del teclado: uno nuevo nace vacío. [pinRechazado] = el servidor no aceptó el anterior. */
    data class PedirPin(val cobro: CobroPorConfirmar, val id: Long, val pinRechazado: Boolean = false) : PasoDelGerente

    /** «Firma del gerente», ya identificado. 🔴 El PIN vive sólo aquí, en memoria, y nunca sale en el texto del paso. */
    data class Firmar(val cobro: CobroPorConfirmar, val pin: String, val enviando: Boolean = false) : PasoDelGerente {
        override fun toString(): String = "Firmar(cobro=$cobro, enviando=$enviando)"
    }
}

/**
 * Lo que queda por confirmar, FUERA del flujo de cobro: las cancelaciones que la terminal no confirmó y (F1) los cobros con tarjeta en
 * duda de este aparato. Sin esto, la duda sólo existía dentro de la pantalla de pago: el cajero salía y nadie volvía a enterarse.
 *
 * 🔴 F1 (founder, 17-nov: «el sistema solo»): el piso no declara nada. Lo raro lo firma un GERENTE con su PIN sobre la fila exacta
 * ([revisar] → [identificar] → [firmar]); el SERVIDOR exige ese PIN (403 `SUPERVISOR_AUTHORIZATION_REQUIRED` sin él) y decide quién
 * puede. La firma es online-only y nunca se encola: sin red la fila sigue pendiente.
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

    private val _avisos = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Lo que contestó la firma de cada fila (por `requestId`) cuando NO liberó: el motivo del servidor, sin red o sesión renovada. */
    val avisos: StateFlow<Map<String, String>> = _avisos.asStateFlow()

    // MARK: - «Cobros por confirmar»

    private val _cobros = MutableStateFlow<List<CobroPorConfirmar>>(emptyList())
    /** Los cobros con tarjeta en duda de este aparato que no son una cancelación pendiente (ésas tienen su propia sección). */
    val cobrosPorConfirmar: StateFlow<List<CobroPorConfirmar>> = _cobros.asStateFlow()

    private val importesRecuperados = mutableMapOf<String, Int>()
    private val preguntandoImporte = mutableSetOf<String>()

    /**
     * Relee la lista durable. Fuera: lo que ya consta que SÍ pasó, el cobro que sigue en vuelo (todavía no es una duda) y lo que ya
     * es una cancelación pendiente. ponytail: una lectura del disco cada vez que se llama (la banda la pide cada 5 s); un flujo de
     * cambios de la lista si alguna vez pesa.
     *
     * @param recuperarImportes la hoja está abierta: a una fila sin importe se le pregunta a Avoqado por su ID (una vez a la vez).
     */
    fun refrescar(recuperarImportes: Boolean = false) {
        val cancelados = cancelacionDeCobro.pendientes.value.mapNotNullTo(HashSet()) { it.requestId }
        val enVuelo = terminalPaymentService.intentoEnVuelo()?.requestId
        val filas = terminalPaymentService.pendientesDeOtrasVentas(null)
            .filter { !it.cobrado && it.requestId != enVuelo && it.requestId !in cancelados }
            .map {
                CobroPorConfirmar(
                    requestId = it.requestId,
                    venueId = it.venueId,
                    montoCents = it.amountCents?.let { monto -> monto + (it.tipCents ?: 0) } ?: importesRecuperados[it.requestId],
                    desdeMillis = it.desdeMillis,
                )
            }
        _cobros.value = filas
        if (recuperarImportes) filas.filter { it.montoCents == null }.forEach(::recuperarImporte)
    }

    private fun recuperarImporte(cobro: CobroPorConfirmar) {
        if (!preguntandoImporte.add(cobro.requestId)) return
        viewModelScope.launch {
            try {
                val importe = terminalPaymentService.importeDelCobro(cobro.requestId, cobro.venueId) ?: return@launch
                importesRecuperados[cobro.requestId] = importe
                _cobros.update { filas -> filas.map { if (it.requestId == cobro.requestId) it.copy(montoCents = importe) else it } }
            } finally {
                preguntandoImporte -= cobro.requestId
            }
        }
    }

    /** La fila de una cancelación pendiente, para la revisión del gerente; `null` si no hubo cobro (intención sólo de la orden). */
    fun filaDe(intencion: IntencionDeCancelarCobro): CobroPorConfirmar? {
        val requestId = intencion.requestId?.takeIf { it.isNotBlank() } ?: return null
        return CobroPorConfirmar(
            requestId = requestId,
            venueId = intencion.venueId.takeIf { it.isNotBlank() },
            montoCents = intencion.montoCents,
            desdeMillis = intencion.creadaEn,
            cancelacionId = intencion.id,
        )
    }

    // MARK: - La revisión del gerente (PIN primero)

    private val _paso = MutableStateFlow<PasoDelGerente>(PasoDelGerente.Cerrado)
    val paso: StateFlow<PasoDelGerente> = _paso.asStateFlow()
    private var idDelTeclado = 0L

    /** «Revisar» en una fila: lo primero es el PIN del gerente. Con una firma en vuelo no se abre otra. */
    fun revisar(cobro: CobroPorConfirmar) {
        if ((_paso.value as? PasoDelGerente.Firmar)?.enviando == true) return
        _avisos.update { it - cobro.requestId }
        _paso.value = PasoDelGerente.PedirPin(cobro, ++idDelTeclado)
    }

    /** El gerente se identifica; sólo entonces ve la decisión. El PIN no se manda todavía ni se guarda en ningún lado. */
    fun identificar(pin: String) {
        val paso = _paso.value as? PasoDelGerente.PedirPin ?: return
        if (pin.isBlank()) return
        _paso.value = PasoDelGerente.Firmar(paso.cobro, pin)
    }

    /**
     * «Firmar»: la declaración de ESA fila con ESE PIN, por el mismo servicio de siempre (`/release`, online-only). Un segundo toque
     * no manda otra. 🔴 Corre en `viewModelScope`: si la hoja se cierra con la firma viajando, la respuesta se aplica igual.
     * - liberada ⇒ se cierra; si era una cancelación, el coordinador vuelve a preguntar y cancela la orden (aquí no se borra nada);
     * - el servidor no aceptó el PIN ⇒ vuelve al PIN de la MISMA fila, con «Ese PIN no puede firmar este cobro»;
     * - cualquier otra respuesta ⇒ se cierra y la fila dice por qué (sin red: la fila sigue pendiente).
     */
    fun firmar() {
        val paso = _paso.value as? PasoDelGerente.Firmar ?: return
        if (paso.enviando) return
        _paso.value = paso.copy(enviando = true)
        val cobro = paso.cobro
        viewModelScope.launch {
            val r = terminalPaymentService.declararNoCobrado(cobro.requestId, cobro.venueId, supervisorPin = paso.pin)
            if (r is ResultadoDeDeclaracion.Rechazada && r.code == CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE) {
                _paso.value = PasoDelGerente.PedirPin(cobro, ++idDelTeclado, pinRechazado = true)
                return@launch
            }
            _paso.value = PasoDelGerente.Cerrado
            when (r) {
                ResultadoDeDeclaracion.Liberada -> {
                    cobro.cancelacionId?.let(cancelacionDeCobro::procesarTrasDeclarar)
                    refrescar()
                }
                is ResultadoDeDeclaracion.Rechazada -> _avisos.update { it + (cobro.requestId to r.mensaje) }
                ResultadoDeDeclaracion.SinConexion -> _avisos.update { it + (cobro.requestId to CancelacionDeCobro.FIRMA_SIN_RED) }
                ResultadoDeDeclaracion.SesionRenovada ->
                    _avisos.update { it + (cobro.requestId to CancelacionDeCobro.DECLARACION_SESION_RENOVADA) }
            }
        }
    }

    /** Cancelar olvida el PIN (vivía sólo en el paso). Con una firma en vuelo no se cierra: decide la respuesta del servidor. */
    fun cerrar() {
        if ((_paso.value as? PasoDelGerente.Firmar)?.enviando == true) return
        _paso.value = PasoDelGerente.Cerrado
    }

    /** El aviso de un cobro que la terminal SÍ hizo; el resto son cancelaciones sin confirmar. */
    fun esAvisoDeCobro(intencion: IntencionDeCancelarCobro): Boolean =
        intencion.faseActual == FaseDeCancelacion.SE_COBRO
}
