package com.avoqado.pos.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.core.data.network.OverrideResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
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

/**
 * La revisión del gerente, en UN paso (Codex código r1 #10, igual que la TPV): el teclado del PIN ya dice qué se firma y su botón es
 * [Firmar]. Así nadie llega a una pantalla de decisión con un PIN que todavía no validó el SERVIDOR.
 */
sealed interface PasoDelGerente {
    data object Cerrado : PasoDelGerente

    /** El teclado de ESTA fila. [id] = llave del teclado: uno nuevo nace vacío. El PIN nunca vive aquí. */
    data class Firmar(val cobro: CobroPorConfirmar, val id: Long) : PasoDelGerente
}

/**
 * Lo que queda por confirmar, FUERA del flujo de cobro: las cancelaciones que la terminal no confirmó y (F1) los cobros con tarjeta en
 * duda de este aparato. Sin esto, la duda sólo existía dentro de la pantalla de pago: el cajero salía y nadie volvía a enterarse.
 *
 * 🔴 F1 (founder, 17-nov: «el sistema solo»): el piso no declara nada. Lo raro lo firma un GERENTE con su PIN sobre la fila exacta
 * ([revisar] → [firmar], en un paso); el SERVIDOR exige ese PIN (403 `SUPERVISOR_AUTHORIZATION_REQUIRED` sin él) y decide quién
 * puede. La firma es online-only y nunca se encola: sin red la fila sigue pendiente.
 */
@HiltViewModel
class CancelacionesPendientesViewModel @Inject constructor(
    private val cancelacionDeCobro: CancelacionDeCobroCoordinator,
    private val terminalPaymentService: TerminalPaymentService,
    /** F1: la sucursal de la sesión. «Cobros por confirmar» sólo enseña los suyos. */
    private val secureStorage: com.avoqado.pos.core.data.local.SecureStorage,
) : ViewModel() {

    private val _paso = MutableStateFlow<PasoDelGerente>(PasoDelGerente.Cerrado)
    val paso: StateFlow<PasoDelGerente> = _paso.asStateFlow()
    private var idDelTeclado = 0L
    /** La firma en vuelo. Mientras exista, otro [Firmar] espera a ésta (nunca manda otra) y el teclado no se cierra. */
    private var envio: kotlinx.coroutines.Deferred<OverrideResult>? = null

    private val _pendientes = MutableStateFlow(deLaSucursal(cancelacionDeCobro.pendientes.value))
    /**
     * Las cancelaciones pendientes de la sucursal ACTIVA (Codex android r1 #8): un gerente de A no ve ni firma las de B desde la sesión
     * de A. Una vieja sin sucursal se ve (no se puede saber de cuál es). Se recalcula con cada cambio del coordinador y en [refrescar].
     */
    val pendientes: StateFlow<List<IntencionDeCancelarCobro>> = _pendientes.asStateFlow()

    private fun deLaSucursal(todas: List<IntencionDeCancelarCobro>): List<IntencionDeCancelarCobro> {
        val sucursal = secureStorage.venueId
        return todas.filter { it.venueId.isBlank() || it.venueId == sucursal }
    }

    init {
        viewModelScope.launch {
            cancelacionDeCobro.pendientes.collect {
                _pendientes.value = deLaSucursal(it)
                revisarQueSigueVigente()
            }
        }
    }

    val enCurso: StateFlow<Set<String>> = cancelacionDeCobro.enCurso

    /** «Volver a consultar»: sólo pregunta — jamás cobra ni borra por su cuenta. */
    fun volverAConsultar(id: String) = cancelacionDeCobro.procesarAhora(id)

    private val _avisos = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Lo que contestó la firma de cada fila (por `requestId`) cuando NO liberó: el motivo del servidor, sin red o sesión renovada. */
    val avisos: StateFlow<Map<String, String>> = _avisos.asStateFlow()

    private val _resultado = MutableStateFlow<String?>(null)
    /** Codex android r1 #7: la fila que revisaba el gerente se resolvió sola mientras tecleaba; cómo quedó (se cierra su teclado). */
    val resultado: StateFlow<String?> = _resultado.asStateFlow()
    fun consumirResultado() { _resultado.value = null }

    // MARK: - «Cobros por confirmar»

    private val _cobros = MutableStateFlow<List<CobroPorConfirmar>>(emptyList())
    /** Los cobros con tarjeta en duda de este aparato que no son una cancelación pendiente (ésas tienen su propia sección). */
    val cobrosPorConfirmar: StateFlow<List<CobroPorConfirmar>> = _cobros.asStateFlow()

    private val _importes = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Los importes que se recuperaron por ID (filas antiguas sin importe); la hoja lo observa para repintar. */
    val importesRecuperados: StateFlow<Map<String, Int>> = _importes.asStateFlow()
    private val preguntandoImporte = mutableSetOf<String>()

    /**
     * Relee la lista durable. Fuera: lo que ya consta que SÍ pasó, el cobro que sigue en vuelo (todavía no es una duda) y lo que ya
     * es una cancelación pendiente. La lista ya es sólo de la sucursal activa (`pendientesDeOtrasVentas`, Codex android r1 #8).
     * ponytail: una lectura del disco cada vez que se llama (la banda la pide cada 5 s); un flujo de cambios de la lista si alguna
     * vez pesa.
     *
     * @param recuperarImportes la hoja está abierta: a una fila (también de cancelación, #11) sin importe se le pregunta a Avoqado
     *   por su ID, una a la vez.
     */
    fun refrescar(recuperarImportes: Boolean = false) {
        _pendientes.value = deLaSucursal(cancelacionDeCobro.pendientes.value)
        val cancelados = cancelacionDeCobro.pendientes.value.mapNotNullTo(HashSet()) { it.requestId }
        val enVuelo = terminalPaymentService.intentoEnVuelo()?.requestId
        val filas = terminalPaymentService.pendientesDeOtrasVentas(null)
            .filter { !it.cobrado && it.requestId != enVuelo && it.requestId !in cancelados }
            .map {
                CobroPorConfirmar(
                    requestId = it.requestId,
                    venueId = it.venueId,
                    montoCents = it.amountCents?.let { monto -> monto + (it.tipCents ?: 0) } ?: _importes.value[it.requestId],
                    desdeMillis = it.desdeMillis,
                )
            }
        _cobros.value = filas
        revisarQueSigueVigente()
        if (recuperarImportes) {
            (filas + _pendientes.value.mapNotNull(::filaDe)).filter { it.montoCents == null }.forEach(::recuperarImporte)
        }
    }

    private fun recuperarImporte(cobro: CobroPorConfirmar) {
        if (!preguntandoImporte.add(cobro.requestId)) return
        viewModelScope.launch {
            try {
                val importe = terminalPaymentService.importeDelCobro(cobro.requestId, cobro.venueId) ?: return@launch
                _importes.update { it + (cobro.requestId to importe) }
                _cobros.update { filas -> filas.map { if (it.requestId == cobro.requestId) it.copy(montoCents = importe) else it } }
            } finally {
                preguntandoImporte -= cobro.requestId
            }
        }
    }

    /**
     * La fila de una cancelación pendiente, para la revisión del gerente; `null` si no hubo cobro (intención sólo de la orden). El
     * importe es el de SU solicitud (contexto durable) y, si no lo guardó, el de la intención o el recuperado por ID (Codex android
     * r1 #11); si no consta, `null` («Importe por confirmar»).
     */
    fun filaDe(intencion: IntencionDeCancelarCobro): CobroPorConfirmar? {
        val requestId = intencion.requestId?.takeIf { it.isNotBlank() } ?: return null
        val contexto = terminalPaymentService.contextoDe(requestId)
        return CobroPorConfirmar(
            requestId = requestId,
            venueId = intencion.venueId.takeIf { it.isNotBlank() },
            montoCents = contexto?.amountCents?.let { it + (contexto.tipCents ?: 0) } ?: intencion.montoCents ?: _importes.value[requestId],
            desdeMillis = intencion.creadaEn,
            cancelacionId = intencion.id,
        )
    }

    /** ¿La fila sigue en duda? Una cancelación, mientras su intención siga viva; un cobro, mientras siga en la lista durable. */
    private fun sigueVigente(cobro: CobroPorConfirmar): Boolean =
        if (cobro.cancelacionId != null) cancelacionDeCobro.pendientes.value.any { it.id == cobro.cancelacionId }
        else _cobros.value.any { it.requestId == cobro.requestId }

    /**
     * 🔴 Codex android r1 #7 (la prueba retirada de C4): si la fila que revisa el gerente se resolvió sola (la terminal confirmó, la
     * cancelación cerró, otro aparato la firmó), su teclado se cierra y se dice cómo quedó; un PIN tardío ya no manda nada. Con una
     * firma en vuelo no se toca: decide su respuesta.
     */
    private fun revisarQueSigueVigente() {
        val abierto = _paso.value as? PasoDelGerente.Firmar ?: return
        if (envio?.isActive == true || sigueVigente(abierto.cobro)) return
        _paso.value = PasoDelGerente.Cerrado
        _resultado.value = comoQuedo(abierto.cobro)
    }

    private fun comoQuedo(cobro: CobroPorConfirmar): String {
        val siPaso = if (cobro.cancelacionId != null) {
            cancelacionDeCobro.desenlaceConocido(cobro.requestId) is com.avoqado.pos.payment.domain.DesenlaceDeCancelacion.SeCobro
        } else {
            terminalPaymentService.yaCobrado(cobro.requestId)
        }
        return if (siPaso) CancelacionDeCobro.SE_COBRO_AL_FINAL else CancelacionDeCobro.YA_SE_RESOLVIO
    }

    // MARK: - La revisión del gerente (un paso: el teclado dice qué se firma y [Firmar] lo manda)


    /** «Revisar» en una fila: el teclado de ESA fila. Con una firma en vuelo no se abre otra. */
    fun revisar(cobro: CobroPorConfirmar) {
        if (envio?.isActive == true) return
        _avisos.update { it - cobro.requestId }
        _paso.value = PasoDelGerente.Firmar(cobro, ++idDelTeclado)
    }

    /**
     * [Firmar]: la declaración de ESA fila con ESE PIN, por el mismo servicio de siempre (`/release`, online-only, nunca encolada). El
     * PIN no se guarda. Devuelve lo que pinta el teclado:
     * - liberada ⇒ se cierra; si era una cancelación, el coordinador vuelve a preguntar y cancela la orden (aquí no se borra nada);
     * - el SERVIDOR no aceptó el PIN (403 `SUPERVISOR_AUTHORIZATION_REQUIRED`) ⇒ el MISMO teclado, sobre la MISMA fila, dice «Ese PIN no
     *   es de un gerente con permiso»;
     * - sin red, o la sesión se renovó ⇒ el teclado lo dice y la fila sigue pendiente;
     * - cualquier otro rechazo ⇒ se cierra y la fila dice por qué.
     * 🔴 Corre en `viewModelScope`: si el teclado se va con la firma viajando, la respuesta se aplica igual.
     */
    suspend fun firmar(pin: String): OverrideResult {
        envio?.takeIf { it.isActive }?.let { return it.await() }
        // Codex android r1 #7: se relee justo antes de firmar; si la fila ya se resolvió, se cierra y no se manda nada.
        refrescar()
        val paso = _paso.value as? PasoDelGerente.Firmar ?: return TECLADO_CERRADO
        val cobro = paso.cobro
        val nuevo = viewModelScope.async {
            val r = terminalPaymentService.declararNoCobrado(cobro.requestId, cobro.venueId, supervisorPin = pin)
            fun cerrarEsteTeclado() { if (_paso.value === paso) _paso.value = PasoDelGerente.Cerrado }
            when (r) {
                ResultadoDeDeclaracion.Liberada -> {
                    cerrarEsteTeclado()
                    cobro.cancelacionId?.let(cancelacionDeCobro::procesarTrasDeclarar)
                    refrescar()
                    TECLADO_CERRADO
                }
                is ResultadoDeDeclaracion.Rechazada -> when (r.code) {
                    CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE -> OverrideResult.Failed(CancelacionDeCobro.PIN_NO_ES_DE_GERENTE)
                    CancelacionDeCobro.CODIGO_DEMASIADOS_INTENTOS -> OverrideResult.TooManyAttempts
                    else -> {
                        cerrarEsteTeclado()
                        _avisos.update { it + (cobro.requestId to r.mensaje) }
                        TECLADO_CERRADO
                    }
                }
                ResultadoDeDeclaracion.SinConexion -> OverrideResult.Failed(CancelacionDeCobro.FIRMA_SIN_RED)
                ResultadoDeDeclaracion.SesionRenovada -> OverrideResult.Failed(CancelacionDeCobro.DECLARACION_SESION_RENOVADA)
            }
        }
        envio = nuevo
        return nuevo.await()
    }

    /** Cerrar el teclado. Con una firma en vuelo no se cierra: decide la respuesta del servidor. */
    fun cerrar() {
        if (envio?.isActive == true) return
        _paso.value = PasoDelGerente.Cerrado
    }

    /** El aviso de un cobro que la terminal SÍ hizo; el resto son cancelaciones sin confirmar. */
    fun esAvisoDeCobro(intencion: IntencionDeCancelarCobro): Boolean =
        intencion.faseActual == FaseDeCancelacion.SE_COBRO

    private companion object {
        /** Lo que cierra el teclado sin mensaje propio: `Granted` es lo que limpia el PIN tecleado (no hay token: el PIN fue en el cuerpo). */
        val TECLADO_CERRADO = OverrideResult.Granted(token = "", authorizedByName = "")
    }
}
