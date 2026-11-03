package com.avoqado.pos.areatickets.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaOpcion
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.ResultadoPapel
import com.avoqado.pos.areatickets.domain.PapelDelAparato
import com.avoqado.pos.areatickets.domain.etiquetaDelRecuadro
import com.avoqado.pos.areatickets.domain.motivoParaNoGuardar
import com.avoqado.pos.areatickets.domain.papelActual
import com.avoqado.pos.areatickets.domain.textoCambioEnElPanel
import com.avoqado.pos.areatickets.domain.ventaAMedias
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D3: un momento en que la compuerta vuelve a preguntarle al servidor (lo sube `AppState`). [sesion] sube en cada arranque
 * con sesión y cada login: ahí se olvida el «Ahora no». [vez] sube también al volver a primer plano, al recuperar la red y
 * al cambiar de sucursal. `vez == 0` = todavía no hubo ninguno.
 */
data class ConsultaDelPapel(val vez: Int = 0, val sesion: Int = 0)

/** La hoja «¿Qué hará este aparato?»: la misma la primera vez y desde «Más». */
data class HojaDelPapel(
    val primeraVez: Boolean,
    val venueId: String,
    val terminalId: String,
    val areas: List<AreaOpcion>,
    /** Áreas activas que no llegaron (más de 200): se eligen en el panel. */
    val areasDeMas: Int,
    val seleccion: PapelDelAparato,
    val puedeConfigurar: Boolean,
    val ventaAMedias: Boolean,
    val guardando: Boolean = false,
    /** El rechazo del servidor, dentro de la hoja. */
    val error: String? = null,
    /** El papel que la hoja enseñó como el de hoy al abrirse; «Guardar» lo compara con el servidor (Codex r6). */
    val partida: PapelDelAparato = seleccion,
) {
    fun motivo(hayRed: Boolean): String? = motivoParaNoGuardar(puedeConfigurar, hayRed, ventaAMedias)
}

/**
 * D3 (Codex final #2): los ajustes de vales que dejó un guardado del papel. Cobrar (`AreaTicketOperationsViewModel`) los
 * toma al instante: cambiar sólo el área no cambia la llave del NavHost, y su copia vieja seguía mostrando «Cobrar».
 */
@Singleton
class CambiosDelPapel @Inject constructor() {
    private val _ajustes = MutableSharedFlow<AreaTicketSettingsData>(extraBufferCapacity = 1)
    val ajustes: SharedFlow<AreaTicketSettingsData> = _ajustes.asSharedFlow()

    fun publicar(ajustes: AreaTicketSettingsData) {
        _ajustes.tryEmit(ajustes)
    }
}

/** El valor del recuadro «Vales» de «Más»; null = no se muestra (vales apagados o servidor anterior a D3). */
fun valorDelRecuadro(ajustes: AreaTicketSettingsData): String? =
    if (ajustes.areas == null || !ajustes.areaTickets.enabled) null else etiquetaDelRecuadro(papelActual(ajustes.terminal))

/**
 * D3 (2-nov): la compuerta de primera vez del papel del aparato y la hoja que lo cambia. Vive arriba del
 * NavHost (como `AnnouncementGateViewModel`) para que guardar, que reconstruye la navegación, no se lleve el aviso.
 *
 * Nunca bloquea un cobro: sólo pregunta con red y en los momentos de [ConsultaDelPapel]; si alguien vende antes de
 * contestar, la pregunta vuelve en el siguiente. Cambiar el papel es online-only a propósito.
 */
@HiltViewModel
class PapelDelAparatoGateViewModel @Inject constructor(
    private val repository: AreaTicketRepository,
    private val secureStorage: SecureStorage,
    private val activeCartState: ActiveCartState,
    private val roleManager: RoleManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val cambiosDelPapel: CambiosDelPapel,
) : ViewModel() {

    private val _ajustes = MutableStateFlow<AreaTicketSettingsData?>(null)
    /** Los últimos ajustes de vales de ESTE negocio (alimentan el recuadro de «Más»). */
    val ajustes: StateFlow<AreaTicketSettingsData?> = _ajustes.asStateFlow()

    private val _hoja = MutableStateFlow<HojaDelPapel?>(null)
    val hoja: StateFlow<HojaDelPapel?> = _hoja.asStateFlow()

    private val _avisoSinPermiso = MutableStateFlow(false)
    val avisoSinPermiso: StateFlow<Boolean> = _avisoSinPermiso.asStateFlow()

    private val _guardado = MutableStateFlow<PapelDelAparato?>(null)
    /** El papel recién guardado: la pantalla enseña el aviso de éxito y rehace la navegación. */
    val guardado: StateFlow<PapelDelAparato?> = _guardado.asStateFlow()

    private val _irAlInicio = MutableStateFlow(false)
    /** Tras guardar (también el mismo papel): la navegación va a la pestaña de inicio del papel y lo marca con [yaFueAlInicio]. */
    val irAlInicio: StateFlow<Boolean> = _irAlInicio.asStateFlow()

    val hayRed: StateFlow<Boolean> = combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { a, b -> a && b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, connectivityMonitor.isFullyConnected)

    /** Negocios donde dijeron «Ahora no» (o «Entendido» sin permiso) en esta sesión. */
    private val calladas = mutableSetOf<String>()
    private var sesionVista = 0
    private var consulta: Job? = null

    fun consultar(disparo: ConsultaDelPapel) {
        if (disparo.vez == 0) return
        if (disparo.sesion != sesionVista) {
            // Otra sesión (login): se olvida el «Ahora no» y lo que haya quedado abierto de la anterior.
            calladas.clear()
            _hoja.value = null
            _avisoSinPermiso.value = false
            sesionVista = disparo.sesion
        }
        if (_ajustes.value?.venueId != secureStorage.venueId) _ajustes.value = null
        consulta?.cancel()
        consulta = viewModelScope.launch {
            // 409 DEVICE_NOT_REGISTERED, sin permiso de leer, sin sucursal…: no se enseña nada y el siguiente momento reintenta.
            val ajustes = runCatching { repository.settings() }.getOrNull() ?: return@launch
            if (ajustes.venueId != secureStorage.venueId) return@launch
            _ajustes.value = ajustes
            if (ajustes.areas == null || !ajustes.areaTickets.enabled || !ajustes.terminal.roleChoicePending) return@launch
            if (!connectivityMonitor.isFullyConnected) return@launch
            if (ajustes.venueId in calladas || _hoja.value != null || _avisoSinPermiso.value) return@launch
            // A media venta no interrumpe ni se calla: pregunta en el siguiente momento.
            if (ventaAMediasAhora(ajustes.venueId)) return@launch
            if (!puedeConfigurar()) {
                _avisoSinPermiso.value = true
                return@launch
            }
            _hoja.value = hojaPara(ajustes, primeraVez = true)
        }
    }

    fun puedeConfigurar(): Boolean =
        roleManager.hasVenuePermission("area-tickets:configure", fallbackRoles = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN"))

    /** El recuadro «Vales» de «Más»: la misma hoja, sin «Ahora no». */
    fun abrirDesdeMas() {
        val ajustes = _ajustes.value?.takeIf { it.venueId == secureStorage.venueId } ?: return
        if (_hoja.value != null || !puedeConfigurar()) return
        _hoja.value = hojaPara(ajustes, primeraVez = false)
    }

    fun elegir(papel: PapelDelAparato) {
        _hoja.update { it?.copy(seleccion = papel, error = null) }
    }

    fun guardar() {
        val hoja = _hoja.value ?: return
        if (hoja.guardando || hoja.seleccion == PapelDelAparato.SinElegir) return
        val venta = ventaAMediasAhora(hoja.venueId)
        if (motivoParaNoGuardar(hoja.puedeConfigurar, connectivityMonitor.isFullyConnected, venta) != null) {
            _hoja.value = hoja.copy(ventaAMedias = venta)
            return
        }
        _hoja.value = hoja.copy(guardando = true, error = null, ventaAMedias = false)
        viewModelScope.launch {
            // Codex r6: la hoja pudo abrirse con la copia de sin red. Si el panel cambió el papel mientras tanto, no se pisa
            // a ciegas: la hoja toma el papel de ahora y pide revisar.
            val frescos = runCatching { repository.settings(soloServidor = true) }.getOrNull()
            if (frescos == null || frescos.venueId != hoja.venueId) {
                _hoja.update {
                    it?.copy(guardando = false, error = motivoParaNoGuardar(puedeConfigurar = true, hayRed = false, ventaAMedias = false))
                }
                return@launch
            }
            val ahora = hojaPara(frescos, hoja.primeraVez)
            if (ahora.partida != hoja.partida) {
                _ajustes.value = frescos
                _hoja.value = ahora.copy(error = textoCambioEnElPanel(ahora.partida))
                return@launch
            }
            val error = when (val resultado = repository.guardarPapel(hoja.venueId, hoja.terminalId, hoja.seleccion)) {
                is ResultadoPapel.Guardado -> {
                    // 🔴 Codex final #1: el papel nuevo se aplica con ESTA respuesta, con la hoja abierta y el carrito vacío.
                    val terminal = resultado.ajustes.terminal
                    tpvSettingsRepository.aplicarPapelDeVales(
                        venueId = hoja.venueId,
                        terminalId = terminal.id,
                        defaultWorkspace = terminal.defaultWorkspace,
                        canIssueAreaTickets = terminal.canIssueAreaTickets,
                        canCheckoutAreaTickets = terminal.canCheckoutAreaTickets,
                        canDeliverAreaTickets = terminal.canDeliverAreaTickets,
                        fulfillmentAreaId = terminal.fulfillmentArea?.id,
                    )
                    cambiosDelPapel.publicar(resultado.ajustes)
                    _ajustes.value = resultado.ajustes
                    _hoja.value = null
                    _guardado.value = hoja.seleccion
                    _irAlInicio.value = true
                    return@launch
                }
                ResultadoPapel.SinPermiso -> motivoParaNoGuardar(puedeConfigurar = false, hayRed = true, ventaAMedias = false)
                ResultadoPapel.SinRed -> motivoParaNoGuardar(puedeConfigurar = true, hayRed = false, ventaAMedias = false)
                is ResultadoPapel.Rechazado -> resultado.mensaje
            }
            _hoja.update { it?.copy(guardando = false, error = error) }
        }
    }

    /** La X, atrás o tocar fuera: la cierra SIN callarla (vuelve en el siguiente momento), como iOS. */
    fun cerrar() {
        if (_hoja.value?.guardando == true) return
        _hoja.value = null
    }

    /** «Ahora no» (sólo la primera vez): la calla en este negocio hasta el siguiente arranque o login. */
    fun ahoraNo() {
        val hoja = _hoja.value ?: return
        if (hoja.guardando) return
        calladas += hoja.venueId
        _hoja.value = null
    }

    /** El aviso sin permiso cerrado con la X o tocando fuera: sin callarlo, como [cerrar]. */
    fun cerrarAviso() {
        _avisoSinPermiso.value = false
    }

    /** «Entendido» en el aviso sin permiso: la misma regla de silencio que «Ahora no». */
    fun entendido() {
        _avisoSinPermiso.value = false
        secureStorage.venueId?.let { calladas += it }
    }

    fun avisoVisto() {
        _guardado.value = null
    }

    fun yaFueAlInicio() {
        _irAlInicio.value = false
    }

    private fun hojaPara(ajustes: AreaTicketSettingsData, primeraVez: Boolean): HojaDelPapel {
        val areas = ajustes.areas.orEmpty()
        val seleccion = when (val actual = papelActual(ajustes.terminal)) {
            is PapelDelAparato.EmiteVales -> actual.copy(tambienCobra = false) // la hoja no ofrece «también cobra»
            PapelDelAparato.Cobra -> PapelDelAparato.Cobra
            else -> PapelDelAparato.SinElegir
        }
        return HojaDelPapel(
            primeraVez = primeraVez,
            venueId = ajustes.venueId,
            terminalId = ajustes.terminal.id,
            areas = areas,
            areasDeMas = (ajustes.areasTotal - areas.size).coerceAtLeast(0),
            seleccion = seleccion,
            puedeConfigurar = puedeConfigurar(),
            ventaAMedias = ventaAMediasAhora(ajustes.venueId),
        )
    }

    private fun ventaAMediasAhora(venueId: String): Boolean = ventaAMedias(
        carritoConProductos = activeCartState.hasItems,
        cobroDeValesAbierto = repository.session.checkout.value != null,
        cobroDeValesGuardado = secureStorage.areaTicketCheckoutId != null,
        valePorImprimir = secureStorage.pendingAreaTicketPrintCode != null,
        valeSinConfirmar = secureStorage.valeSinConfirmar(venueId) != null,
    )
}
