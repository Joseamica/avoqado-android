package com.avoqado.pos.kds.presentation

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.data.KdsPrefs
import com.avoqado.pos.kds.data.KdsTicketsLocalesStore
import com.avoqado.pos.kds.data.ReceptorDeComandas
import com.avoqado.pos.kds.domain.AccionDeCocina
import com.avoqado.pos.kds.domain.AvisoDeCocina
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.EntradaDeCasilla
import com.avoqado.pos.kds.domain.EstadoDeCasilla
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KdsTicketLocal
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.kds.domain.avisoDeFallo
import com.avoqado.pos.kds.domain.esLocal
import com.avoqado.pos.kds.domain.esSinRed
import com.avoqado.pos.kds.domain.estacionElegida
import com.avoqado.pos.kds.domain.estacionesParaElegir
import com.avoqado.pos.kds.domain.estadoDeCasilla
import com.avoqado.pos.kds.domain.idsParaMarcarTodas
import com.avoqado.pos.kds.domain.juntarPorFolio
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider

private const val TAG = "🍳 KDS-VM"

/** M4: ventana en la que un LISTO recién confirmado protege su comanda de un sondeo viejo que la traiga de vuelta. */
private const val VENTANA_BUMP_RECIENTE_MS = 15_000L

/** Ajustes de ESTA tablet (se guardan en [KdsPrefs]). «Auto-completar» se quitó: nadie cierra por tiempo (spec §7). */
data class KDSSettings(
    val soundEnabled: Boolean = true,
    val largeFontEnabled: Boolean = false,
)

/** Qué enseña la pantalla de cocina (spec 2026-09-27 §4 «Tablet» y §7). Espejo de `VistaDeCocina` de iOS. */
sealed interface VistaDeCocina {
    /** Sin estación elegida — o la guardada ya no existe o se desactivó, y se dice. */
    data class ElegirEstacion(val estaciones: List<StationInfo>, val laGuardadaYaNoExiste: Boolean) : VistaDeCocina

    /** La estación no tiene pantalla (efectiva): se explica y, si se puede, se ofrece prenderla. */
    data class SinPantalla(val estacion: StationInfo, val estado: EstadoDeCasilla) : VistaDeCocina

    /** El tablero de la estación. */
    data class Tablero(val estacion: StationInfo, val estado: EstadoDeCasilla) : VistaDeCocina
}

@HiltViewModel
class KDSViewModel @Inject constructor(
    private val kdsRepository: KDSRepository,
    // El MISMO despachador que usan mesas y vales: ruteo, fallbacks y la regla de que un guard de configuración jamás
    // va delante de la impresión.
    private val comandaDispatcher: ComandaDispatcher,
    // Para el ticket de EMPAQUE, que no pasa por el ruteo.
    private val comandaPrinter: ComandaPrinter,
    private val printConfigRepository: PrintConfigRepository,
    // El deviceId del outbox, NO uno nuevo (regla de offline-first).
    private val syncOutbox: Provider<SyncOutbox>,
    private val prefs: KdsPrefs,
    private val roleManager: RoleManager,
    private val planManager: PlanManager,
    @ApplicationContext private val appContext: Context,
    // Etapa 3 del KDS (3.5): el WiFi del local. Los dos viven fuera del ViewModel (D8).
    private val receptor: ReceptorDeComandas,
    private val ticketsLocales: KdsTicketsLocalesStore,
) : ViewModel() {

    // MARK: - Estado

    private val _vista = MutableStateFlow<VistaDeCocina>(VistaDeCocina.ElegirEstacion(emptyList(), laGuardadaYaNoExiste = false))
    val vista: StateFlow<VistaDeCocina> = _vista.asStateFlow()

    /** Las comandas pendientes de la estación, en orden de llegada (como las manda el servidor). */
    private val _comandas = MutableStateFlow<List<KDSOrder>>(emptyList())
    val comandas: StateFlow<List<KDSOrder>> = _comandas.asStateFlow()

    private val _recientes = MutableStateFlow<List<KDSOrder>>(emptyList())
    val recientes: StateFlow<List<KDSOrder>> = _recientes.asStateFlow()

    /** I2: si la ÚLTIMA lectura de Recientes falló, qué se le dice — la hoja lo pinta en vez de afirmar que no hay
     *  nada. `null` cuando la última lectura sí funcionó (aunque haya salido vacía de verdad). */
    private val _recientesNoLeidas = MutableStateFlow<AvisoDeCocina?>(null)
    val recientesNoLeidas: StateFlow<AvisoDeCocina?> = _recientesNoLeidas.asStateFlow()

    /** Sin red: el tablero se queda con lo que tenía y lo DICE (neutro, nunca rojo). */
    private val _sinConexion = MutableStateFlow(false)
    val sinConexion: StateFlow<Boolean> = _sinConexion.asStateFlow()

    /** Un mensaje que la cocina TIENE que leer: barra fija hasta «Entendido». */
    private val _aviso = MutableStateFlow<AvisoDeCocina?>(null)
    val aviso: StateFlow<AvisoDeCocina?> = _aviso.asStateFlow()

    private val _exito = MutableStateFlow<String?>(null)
    val exito: StateFlow<String?> = _exito.asStateFlow()

    private val _cambiandoPantalla = MutableStateFlow(false)
    val cambiandoPantalla: StateFlow<Boolean> = _cambiandoPantalla.asStateFlow()

    private val _settings = MutableStateFlow(KDSSettings(soundEnabled = prefs.sonido, largeFontEnabled = prefs.letraGrande))
    val settings: StateFlow<KDSSettings> = _settings.asStateFlow()

    private val _canalesReparto = MutableStateFlow<List<CanalReparto>>(emptyList())
    val canalesReparto: StateFlow<List<CanalReparto>> = _canalesReparto.asStateFlow()

    /** La config con que se armó la vista: nombres de estaciones e impresoras para los textos de la pantalla. */
    private val _config = MutableStateFlow(PrintConfig())
    val config: StateFlow<PrintConfig> = _config.asStateFlow()

    /** «Cambiar de estación» a mano: el sondeo no puede regresar a la guardada mientras se elige. */
    private var eligiendoAMano = false
    /** Lo que ya se veía, por FOLIO (o id si no tiene): la copia `lan:` que el servidor reemplaza es la MISMA comanda — no suena dos veces. */
    private var previousOrderIds: Set<String> = emptySet()
    private var hasLoadedFromAPI = false

    /** M4: ids marcados LISTO hace poco → cuándo. Ver `bumpsVigentes()`. */
    private val bumpsRecientes = mutableMapOf<String, Long>()

    // Etapa 3 del KDS (3.5, D9): las dos fuentes de la mezcla por folio.
    private var delServidor: List<KDSOrder> = emptyList()
    private var locales: List<KdsTicketLocal> = emptyList()
    private var localesJob: Job? = null
    private var estacionObservada: String? = null
    /** Sólo mientras `mientrasSeVe()` corre: el receptor NO puede acusar con la pantalla cerrada (D8). */
    private var pantallaVisible = false

    /** D11: sin internet pero recibiendo por el WiFi — la banda lo dice distinto. */
    val recibiendoPorWifi: StateFlow<Boolean> get() = receptor.activo

    fun cerrarAviso() { _aviso.value = null }

    fun cerrarExito() { _exito.value = null }

    // MARK: - Sondeo (sólo mientras la pantalla se ve)

    /**
     * 🔴 Lo llama `KDSScreen` desde un `LaunchedEffect`: el sondeo vive MIENTRAS la pantalla está a la vista y se cancela
     * al cerrarla. Antes arrancaba en `init` y este ViewModel —atado al menú «Más»— seguía pidiendo comandas cada 10 s
     * con la pantalla cerrada, en cada aparato donde alguien la abrió una vez.
     */
    suspend fun mientrasSeVe() {
        // Al volver a abrir la pantalla se regresa a la estación guardada: este ViewModel vive con «Más», y un «Cambiar
        // de estación» que se cerró sin elegir no puede dejarla atorada en el selector.
        eligiendoAMano = false
        // M3: este ViewModel sigue vivo con la pantalla cerrada, así que sin esto lo que llegó mientras tanto sonaba
        // como «comanda nueva». El primer tablero de ESTA apertura es la línea base — nunca suena.
        hasLoadedFromAPI = false
        pantallaVisible = true
        try {
            refrescar()
            var vuelta = 0
            while (true) {
                delay(10_000)
                vuelta++
                // La config cada minuto en CUALQUIER vista (¿prendieron o apagaron la pantalla desde el dashboard?): el
                // tablero aparece a lo más un minuto después; prenderla desde esta tablet refresca al instante. Entre
                // vueltas, sólo las comandas (no hace nada fuera del tablero).
                if (vuelta % 6 == 0) refrescar() else refrescarTablero()
                fetchCanalesReparto()
            }
        } finally {
            // Al cerrar la pantalla el receptor se apaga: una comanda acusada aquí sin nadie mirando sería una comanda perdida.
            pantallaVisible = false
            sincronizarReceptor()
        }
    }

    /** Relee la config (cache-first), decide qué enseñar y, si es tablero, trae sus comandas. */
    internal suspend fun refrescar() {
        val venueId = kdsRepository.venueIdActual() ?: return
        // Cache-first: primero lo que el aparato ya sabe, sin esperar a la red…
        _config.value = printConfigRepository.getCurrentConfig()
        if (!eligiendoAMano && _config.value.version.isNotEmpty()) _vista.value = vistaPara(prefs.estacion(venueId))
        sincronizarReceptor()
        // …y luego la verdad del servidor.
        printConfigRepository.refresh(venueId)
        _config.value = printConfigRepository.getCurrentConfig()
        if (!eligiendoAMano) _vista.value = vistaPara(prefs.estacion(venueId))
        sincronizarReceptor()
        // En el tablero sólo el fetch de comandas decide si hay red (abajo); fuera de él, si nunca vio la config
        // (`version` vacía = sin red desde que se instaló: se dice, en vez de afirmar que no hay estaciones).
        if (_vista.value !is VistaDeCocina.Tablero) _sinConexion.value = _config.value.version.isEmpty()
        refrescarTablero()
    }

    private fun vistaPara(guardada: String?): VistaDeCocina {
        val config = _config.value
        val elegida = estacionElegida(guardada, config.stations)
            ?: return VistaDeCocina.ElegirEstacion(
                estacionesParaElegir(config.stations),
                laGuardadaYaNoExiste = guardada != null && config.version.isNotEmpty(),
            )
        val estado = estadoDeCasilla(
            EntradaDeCasilla(
                abiertaAClientes = config.kitchenDisplayOpenToClients,
                esSuperadmin = roleManager.role == "SUPERADMIN",
                puedeConfigurar = roleManager.canManagePrinters,
                tieneAccesoPro = planManager.hasFeature("KITCHEN_DISPLAY"),
                prendida = elegida.hasKitchenDisplay,
            ),
        )
        return if (elegida.hasKitchenDisplay) VistaDeCocina.Tablero(elegida, estado) else VistaDeCocina.SinPantalla(elegida, estado)
    }

    private suspend fun refrescarTablero() {
        val tablero = _vista.value as? VistaDeCocina.Tablero ?: return
        kdsRepository.fetchOrders(tablero.estacion.id).fold(
            onSuccess = { nuevas ->
                _sinConexion.value = false
                // M4: un sondeo que arrancó ANTES de un LISTO puede traer todavía esa comanda — se ignora mientras
                // el bump está en vuelo, o hasta que el servidor confirme que ya no la manda.
                olvidarBumpsConfirmadosPorElServidor(nuevas)
                delServidor = nuevas
                publicar(desdeServidor = true)
                // Después de publicar: la cocina ve el pedido primero, el papel sale enseguida.
                viewModelScope.launch { imprimirComandasPendientes(delServidor.filterNot { it.id in bumpsVigentes() }) }
                // Ronda 1: la copia del servidor gana también en el disco. La local pendiente de estos folios sobra; si se
                // quedara, al terminarla en línea (aquí u otra pantalla) el servidor deja de mandarla y resucitaba.
                ticketsLocales.retirarPendientes(nuevas.mapNotNull { it.sourceKey })
            },
            onFailure = { e ->
                // Se CONSERVA lo que ya se veía y se sigue mezclando con lo local: sin red la cocina trabaja con eso (D9).
                if (esSinRed(e)) _sinConexion.value = true
                publicar(desdeServidor = false)
                // M3 sin internet: lo que se ve ahora ES la línea base. Sin esto, con la pantalla abierta sin red, lo que
                // llega por el WiFi del local nunca sonaba (la base sólo la ponía una lectura buena del servidor).
                hasLoadedFromAPI = true
                Log.d(TAG, "No se pudo leer el tablero (se conserva lo que había): ${e.message}")
            },
        )
    }

    /** D9: `servidor ∪ locales` por folio, menos los bumps en vuelo (M4). Suena sólo si ya hubo un tablero base. */
    private fun publicar(desdeServidor: Boolean) {
        val juntas = juntarPorFolio(delServidor, locales).filterNot { it.id in bumpsVigentes() }
        val claves = juntas.map { it.sourceKey ?: it.id }.toSet()
        if (hasLoadedFromAPI && (claves - previousOrderIds).isNotEmpty()) playNotificationSound()
        previousOrderIds = claves
        if (desdeServidor) hasLoadedFromAPI = true
        _comandas.value = juntas
    }

    /**
     * D8: el receptor sólo vive con un Tablero en pantalla, y anuncia SU estación. Se llama cada vez que cambia la vista
     * y al cerrar la pantalla. Observa el almacén local de esa estación para mezclar lo que llega por WiFi.
     */
    private fun sincronizarReceptor() {
        val venueId = kdsRepository.venueIdActual()
        val tablero = _vista.value as? VistaDeCocina.Tablero
        if (venueId == null || tablero == null || !pantallaVisible) {
            receptor.desactivar()
            localesJob?.cancel(); localesJob = null
            estacionObservada = null
            locales = emptyList()
            return
        }
        receptor.activar(venueId, tablero.estacion.id)
        if (localesJob?.isActive != true || estacionObservada != tablero.estacion.id) {
            localesJob?.cancel()
            estacionObservada = tablero.estacion.id
            // Lo de la estación anterior no se mezcla con la nueva mientras llega la primera lectura.
            locales = emptyList()
            localesJob = viewModelScope.launch {
                ticketsLocales.deLaEstacion(venueId, tablero.estacion.id).collect { locales = it; publicar(desdeServidor = false) }
            }
        }
    }

    /** M4: ids protegidos AHORA MISMO — poda primero los que ya caducaron (ventana de 15 s). */
    private fun bumpsVigentes(): Set<String> {
        val corte = System.currentTimeMillis() - VENTANA_BUMP_RECIENTE_MS
        bumpsRecientes.entries.removeAll { it.value < corte }
        return bumpsRecientes.keys
    }

    /** El servidor ya no manda estos ids: dejó de hacer falta protegerlos (no hay que esperar los 15 s). */
    private fun olvidarBumpsConfirmadosPorElServidor(nuevas: List<KDSOrder>) {
        bumpsVigentes().filterNot { id -> nuevas.any { it.id == id } }.forEach(bumpsRecientes::remove)
    }

    // MARK: - Estación

    fun elegirEstacion(id: String) {
        val venueId = kdsRepository.venueIdActual() ?: return
        prefs.guardarEstacion(venueId, id)
        eligiendoAMano = false
        _comandas.value = emptyList()
        previousOrderIds = emptySet()
        hasLoadedFromAPI = false
        _vista.value = vistaPara(id)
        delServidor = emptyList()
        sincronizarReceptor()
        viewModelScope.launch { refrescarTablero() }
    }

    fun cambiarEstacion() {
        eligiendoAMano = true
        _vista.value = VistaDeCocina.ElegirEstacion(estacionesParaElegir(_config.value.stations), laGuardadaYaNoExiste = false)
        sincronizarReceptor()
    }

    // MARK: - LISTO, lote y deshacer

    /** LISTO de un toque: sale del tablero al instante; sin red o sólo local va como marca `BUMP` (D10). */
    fun listo(id: String) {
        val comanda = _comandas.value.firstOrNull { it.id == id } ?: return
        _comandas.value = _comandas.value.filterNot { it.id == id }
        if (esLocal(id)) {
            // Sólo existe aquí: no hay id del servidor que «bumpear». La marca por folio lo resuelve al sincronizar.
            viewModelScope.launch { marcarListaSinServidor(comanda) }
            return
        }
        // M4: protege contra un sondeo que ya estaba en vuelo y todavía no sabe de este bump.
        bumpsRecientes[id] = System.currentTimeMillis()
        viewModelScope.launch {
            kdsRepository.bumpOrder(id).onFailure { e ->
                bumpsRecientes.remove(id)
                if (esSinRed(e) && comanda.sourceKey != null) {
                    // D10: sin red, con folio ⇒ marca BUMP por la cola + `listaEnMillis`. Sale de la pantalla y NO dice error.
                    _sinConexion.value = true
                    marcarListaSinServidor(comanda)
                    return@onFailure
                }
                // Sin folio (Uber, filas viejas) o un «no» del servidor: REGRESA y se dice por qué, como antes.
                if (_comandas.value.none { it.id == id }) _comandas.value = (_comandas.value + comanda).sortedBy { it.createdAt }
                if (esSinRed(e)) _sinConexion.value = true
                _aviso.value = avisoDeFallo(e, AccionDeCocina.LISTO)
            }
        }
    }

    /** D10: persiste `listaEnMillis` ANTES de encolar la marca (la pantalla la esconde ya; el servidor se entera al sincronizar). */
    private suspend fun marcarListaSinServidor(comanda: KDSOrder) {
        val sourceKey = comanda.sourceKey ?: return
        val venueId = kdsRepository.venueIdActual() ?: return
        val stationId = comanda.printStationId ?: (_vista.value as? VistaDeCocina.Tablero)?.estacion?.id ?: return
        // 🔴 Ronda 1: PRIMERO la marca, DESPUÉS `listaEnMillis`. La cola es durable (se escribe antes de tocar la red): si el
        // proceso muere entre las dos, lo peor es que la comanda se siga viendo hasta que el servidor procese el BUMP. Al
        // revés, la pantalla la escondía para siempre y el servidor nunca se enteraba: una marca perdida.
        // Las dos pueden fallar (disco lleno) y ninguna tumba la app (M14).
        val encolada = runCatching {
            syncOutbox.get().enqueue(
                venueId,
                SyncIntentTypes.KDS_TICKET_MARK,
                buildJsonObject {
                    put("sourceKey", sourceKey)
                    put("stationId", stationId)
                    put("action", KitchenDeliveryPolicy.BUMP)
                    put("label", comanda.orderNumber)
                },
            )
        }.onFailure { Log.w(TAG, "No se pudo encolar el BUMP de $sourceKey: ${it.message}") }.isSuccess
        // Sin marca no se esconde: vuelve a salir en la siguiente lectura y se puede marcar otra vez.
        if (!encolada) return
        runCatching { ticketsLocales.marcarLista(comanda, venueId, stationId) }
            .onFailure { Log.w(TAG, "No se pudo persistir el LISTO de $sourceKey: ${it.message}") }
    }

    /** «Marcar todas listas» (la pantalla confirma antes). Nunca un delivery sin aceptar. Sin red: una marca BUMP por comanda con folio. */
    fun marcarTodasListas() {
        val ids = idsParaMarcarTodas(_comandas.value)
        if (ids.isEmpty()) return
        val quitadas = _comandas.value.filter { it.id in ids }
        _comandas.value = _comandas.value.filterNot { it.id in ids }
        val (localesIds, delServidorIds) = ids.partition { esLocal(it) }
        viewModelScope.launch { quitadas.filter { it.id in localesIds }.forEach { marcarListaSinServidor(it) } }
        if (delServidorIds.isEmpty()) return
        // M4/Ronda 3: mismo blindaje que listo() — un sondeo que ya estaba en vuelo no puede resucitar el lote.
        val ahora = System.currentTimeMillis()
        delServidorIds.forEach { bumpsRecientes[it] = ahora }
        viewModelScope.launch {
            kdsRepository.bumpBatch(delServidorIds)
                .onSuccess { refrescarTablero() }
                .onFailure { e ->
                    // El lote no aplicó: se sueltan del blindaje también, o un sondeo real que SÍ las trae de
                    // vuelta (porque siguen pendientes de verdad) las escondería por hasta 15 s.
                    delServidorIds.forEach(bumpsRecientes::remove)
                    val sinRed = esSinRed(e)
                    if (sinRed) _sinConexion.value = true
                    val delServidorQuitadas = quitadas.filter { it.id in delServidorIds }
                    // D10: sin red, las que tienen folio van como marca BUMP; las que no (Uber, filas viejas) regresan.
                    val marcables = if (sinRed) delServidorQuitadas.filter { it.sourceKey != null } else emptyList()
                    marcables.forEach { marcarListaSinServidor(it) }
                    val regresan = (delServidorQuitadas - marcables.toSet()).filter { q -> _comandas.value.none { it.id == q.id } }
                    _comandas.value = (_comandas.value + regresan).sortedBy { it.createdAt }
                    if (regresan.isNotEmpty() || !sinRed) _aviso.value = avisoDeFallo(e, AccionDeCocina.MARCAR_TODAS)
                }
        }
    }

    fun abrirRecientes() {
        val tablero = _vista.value as? VistaDeCocina.Tablero ?: return
        viewModelScope.launch {
            kdsRepository.fetchRecientes(tablero.estacion.id)
                .onSuccess {
                    _recientes.value = it
                    _recientesNoLeidas.value = null
                }
                .onFailure { e ->
                    // I2: la UI no puede mentir — sin esto la hoja decía «nada se marcó como listo» cuando en
                    // realidad no se pudo leer. La barra de atrás se queda igual, por si se cierra la hoja sin leerlo.
                    val aviso = avisoDeFallo(e, AccionDeCocina.RECIENTES)
                    _recientesNoLeidas.value = aviso
                    _aviso.value = aviso
                }
        }
    }

    /** «Deshacer»: sin optimismo — la comanda sigue en Recientes hasta que el servidor la regresa. */
    fun deshacer(id: String) {
        val folio = _recientes.value.firstOrNull { it.id == id }?.sourceKey
        viewModelScope.launch {
            kdsRepository.recall(id)
                .onSuccess {
                    _recientes.value = _recientes.value.filterNot { it.id == id }
                    // Ronda 4: LISTO + Deshacer inmediato (antes de que un sondeo confirme el bump) no puede
                    // quedar tapado por el blindaje de M4 — el servidor SÍ va a volver a mandar esta comanda.
                    bumpsRecientes.remove(id)
                    // Ronda 1 (Task 8): lo mismo con la marca LISTO sin red de este folio, que la escondería 12 h. En
                    // memoria al instante (se ve ya) y en el disco (sobrevive a cerrar la pantalla).
                    if (folio != null) {
                        locales = locales.map { if (it.sourceKey == folio) it.copy(listaEnMillis = null) else it }
                        ticketsLocales.quitarLista(folio)
                    }
                    refrescarTablero()
                }
                .onFailure { e ->
                    if (esSinRed(e)) _sinConexion.value = true
                    _aviso.value = avisoDeFallo(e, AccionDeCocina.DESHACER)
                }
        }
    }

    // MARK: - Prender / apagar la pantalla desde la tablet (la pantalla confirma antes; abrir NUNCA la prende)

    fun cambiarPantalla(prender: Boolean) {
        val estacion = when (val v = _vista.value) {
            is VistaDeCocina.Tablero -> v.estacion
            is VistaDeCocina.SinPantalla -> v.estacion
            is VistaDeCocina.ElegirEstacion -> return
        }
        if (_cambiandoPantalla.value) return
        _cambiandoPantalla.value = true
        viewModelScope.launch {
            kdsRepository.setKitchenDisplay(estacion.id, prender)
                .onSuccess {
                    _exito.value = if (prender) TextosDeCocina.prendida(estacion.name) else TextosDeCocina.apagada(estacion.name)
                    refrescar()
                }
                .onFailure { e -> _aviso.value = avisoDeFallo(e, AccionDeCocina.PANTALLA) }
            _cambiandoPantalla.value = false
        }
    }

    // MARK: - Ajustes del aparato

    fun toggleSound() = guardarAjustes(_settings.value.copy(soundEnabled = !_settings.value.soundEnabled))

    fun toggleLargeFont() = guardarAjustes(_settings.value.copy(largeFontEnabled = !_settings.value.largeFontEnabled))

    private fun guardarAjustes(ajustes: KDSSettings) {
        _settings.value = ajustes
        prefs.sonido = ajustes.soundEnabled
        prefs.letraGrande = ajustes.largeFontEnabled
    }

    // MARK: - Delivery, impresión de lo que llegó solo y «me saturé»

    /**
     * "Sí lo preparo." Sólo aparece en canales configurados en MANUAL, donde el sistema NO
     * acepta solo y el plazo del proveedor (~11.5 min en Uber) ya está corriendo.
     *
     * 🔴 NO se pinta como aceptado antes de que el proveedor conteste. Con el estado del
     * pedido no aplica el optimismo que sí usa `listo`: ahí un error sólo desordena
     * un tablero, aquí haría creer a la cocina que el pedido está confirmado y que puede
     * ponerse a cocinar. Si el plazo venció, ese platillo ya no lo va a recoger nadie.
     */
    fun acceptDeliveryOrder(kdsId: String) {
        val comanda = _comandas.value.firstOrNull { it.id == kdsId } ?: return
        val orderId = comanda.orderId ?: return

        viewModelScope.launch {
            kdsRepository.acceptDeliveryOrder(orderId)
                .onSuccess {
                    // Se relee del servidor en vez de asumir: es el server quien sabe si el
                    // proveedor de verdad lo tomó.
                    refrescarTablero()
                }
                .onFailure { e ->
                    // El mensaje viene del servidor y está escrito para leerse en la cocina
                    // (por ejemplo: el plazo venció y no sirve reintentar).
                    _aviso.value = AvisoDeCocina(e.message ?: "No se pudo aceptar el pedido", esError = true)
                }
        }
    }

    /**
     * "No puedo prepararlo." El SERVIDOR decide si eso significa rechazar o cancelar según
     * si el pedido ya se había aceptado — la cocina sólo dice que no puede.
     */
    fun denyDeliveryOrder(kdsId: String, reason: String = "OUT_OF_ITEMS") {
        val comanda = _comandas.value.firstOrNull { it.id == kdsId } ?: return
        val orderId = comanda.orderId ?: return

        viewModelScope.launch {
            kdsRepository.denyDeliveryOrder(orderId, reason)
                .onSuccess { refrescarTablero() }
                .onFailure { e -> _aviso.value = AvisoDeCocina(e.message ?: "No se pudo rechazar el pedido", esError = true) }
        }
    }

    /**
     * Saca en papel las comandas que llegaron SOLAS y que nadie ha impreso.
     *
     * 🔴 Primero se RECLAMA en el servidor y sólo el ganador imprime. Un pedido de
     * marketplace aparece a la vez en todas las pantallas de cocina: sin árbitro, las tres
     * tablets del local sacan el mismo papel tres veces.
     *
     * Si la impresión falla se SUELTA en el acto, para que otro aparato lo intente sin
     * esperar a que caduque la reclamación. Una tablet sin papel no puede dejar a la cocina
     * sin enterarse del pedido.
     */
    private suspend fun imprimirComandasPendientes(pedidos: List<KDSOrder>) {
        val pendientes = pedidos.filter { it.needsPrint }
        if (pendientes.isEmpty()) return

        val deviceId = runCatching { syncOutbox.get().deviceId }.getOrNull() ?: return
        val venueId = kdsRepository.venueIdActual()

        for (pedido in pendientes) {
            if (!kdsRepository.reclamarImpresion(pedido.id, deviceId)) continue

            val lineas = pedido.items.map { item ->
                RoutableItem(
                    orderItemId = item.id,
                    productId = item.productId,
                    categoryId = item.categoryId,
                    productName = item.productName,
                    quantity = item.quantity,
                    modifiers = item.modifiers,
                    notes = item.notes,
                )
            }

            // 🔴 «No lanzó excepción» NO es «imprimió». `dispatch` NUNCA lanza ante un fallo de
            // impresora: devuelve el estado. Con `.isSuccess` el KDS mandaba `confirm-print`
            // sobre una comanda que no salió, y eso IMPIDE que otra tablet la recoja — el pedido
            // se pierde con la reclamación consumida (P1 #11 de la auditoría de Codex).
            val estadoDeLaComanda = runCatching {
                comandaDispatcher.dispatch(
                    venueId = venueId,
                    lines = lineas,
                    orderNumber = pedido.orderNumber,
                    orderType = "Delivery",
                    // 🔴 SIN reintento: a diferencia del mostrador, el KDS SÍ tiene tablets
                    // hermanas que pueden tomar este pedido si esta impresora no lo saca. Con
                    // el default (~1 min) la reclamación se quedaría retenida todo ese tiempo
                    // en vez de soltarse en segundos (`release-print`, abajo) para que otro
                    // aparato la reclame — comportamiento EXACTO de antes del reintento.
                    maxIntentos = 1,
                    // «La libreta» (Task 16) — el id REAL de la orden, ya presente en el pedido
                    // (KDSRepository lo llena del JSON del server); sin él, «la libreta» caía al
                    // orderNumber para armar el eventId, correcto pero menos preciso que el id real.
                    orderId = pedido.orderId,
                )
            }.getOrNull()
            val ok = laComandaSalio(estadoDeLaComanda)

            // El ticket de EMPAQUE: el pedido completo en UNA hoja, para quien mete todo en
            // la bolsa y se la da al repartidor. No es una comanda —esas dicen qué cocinar y
            // cada estación ve sólo su parte—: es la lista de verificación de la bolsa. En
            // una mesa el mesero lleva los platos y ve al cliente; aquí, si falta una salsa
            // el cliente se entera en su casa, y eso acaba en reembolso.
            //
            // Sólo si el negocio marcó una estación de empaque. Si no marcó ninguna, no sale
            // nada extra: no le cambiamos el papeleo a quien no lo pidió.
            if (ok) imprimirTicketDeEmpaque(pedido, lineas)

            kdsRepository.marcarImpresion(pedido.id, deviceId, if (ok) "confirm-print" else "release-print")
            if (!ok) Log.e(TAG, "No se pudo imprimir la comanda ${pedido.orderNumber}; soltada para que otro aparato lo intente")
        }
    }

    private suspend fun imprimirTicketDeEmpaque(pedido: KDSOrder, lineas: List<RoutableItem>) {
        val config = printConfigRepository.getCurrentConfig()
        val estacion = config.packingStationId ?: return

        // UN solo plan con TODOS los renglones, dirigido a la estación de empaque. Se arma
        // aquí en vez de rutear, justamente porque el punto es que NO se reparta.
        val plan = TicketPlan(
            stationId = estacion,
            unrouted = false,
            lines = lineas.map { l ->
                ConsolidatedLine(
                    productName = l.productName,
                    quantity = l.quantity,
                    modifiers = l.modifiers,
                    notes = l.notes,
                    orderItemIds = listOf(l.orderItemId),
                )
            },
        )

        runCatching {
            comandaPrinter.printComandas(
                plans = listOf(plan),
                config = config,
                orderNumber = pedido.orderNumber,
                // Lo que se lee ARRIBA del papel. Tiene que gritar que es para empacar, no
                // otra comanda de cocina.
                orderType = "EMPAQUE · Delivery",
            )
        }.onFailure { Log.e(TAG, "No se pudo imprimir el ticket de empaque de ${pedido.orderNumber}: ${it.message}") }
    }

    private suspend fun fetchCanalesReparto() {
        kdsRepository.fetchDeliveryChannels()
            .onSuccess { _canalesReparto.value = it }
            // Un fallo aquí NO se le grita a la cocina: el control desaparece y el tablero
            // sigue funcionando. Perder el botón de pausa no puede tapar los pedidos.
            .onFailure { Log.d(TAG, "No se pudieron leer los canales de reparto: ${it.message}") }
    }

    /**
     * "Me saturé": frena los pedidos de reparto un rato.
     *
     * Sin optimismo, por la misma razón que aceptar un pedido: pintar "pausado" antes de que
     * el marketplace lo confirme haría creer a la cocina que ya no van a entrar pedidos
     * mientras siguen entrando. Se relee del servidor, que es quien sabe.
     */
    fun pausarReparto(linkId: String, minutos: Int) {
        viewModelScope.launch {
            kdsRepository.snoozeDelivery(linkId, minutos)
                .onSuccess { fetchCanalesReparto() }
                .onFailure { e -> _aviso.value = AvisoDeCocina(e.message ?: "No se pudo pausar el reparto", esError = true) }
        }
    }

    /** "Ya nos pusimos al día." */
    fun reanudarReparto(linkId: String) {
        viewModelScope.launch {
            kdsRepository.reanudarDelivery(linkId)
                .onSuccess { fetchCanalesReparto() }
                .onFailure { e -> _aviso.value = AvisoDeCocina(e.message ?: "No se pudo reanudar el reparto", esError = true) }
        }
    }

    // MARK: - Sound

    private fun playNotificationSound() {
        if (!_settings.value.soundEnabled) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(appContext, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo sonido: ${e.message}")
        }
    }
}

/**
 * ¿La comanda SALIÓ de verdad? Decide si el KDS confirma la impresión o suelta el pedido.
 *
 * 🔴 PURA a propósito: es la única forma de probar esta decisión sin montar el ViewModel entero
 * con sus doce dependencias — y quedarse sin probarla fue justo lo que dejó vivo el defecto.
 *
 * Antes era `runCatching { dispatch(...) }.isSuccess`, que sólo dice «no lanzó excepción». Y
 * `dispatch` NUNCA lanza ante un fallo de impresora: devuelve el estado. Así que el KDS mandaba
 * `confirm-print` sobre una comanda que no había salido, y ese confirm **impide que otra tablet
 * la recoja**: el pedido se pierde con su reclamación consumida — peor que no haber impreso,
 * porque además cierra la puerta de la recuperación (P1 #11 de la auditoría de Codex, 2026-09-07).
 *
 * `null` significa que se atrapó una excepción o que no había nada que imprimir; en el KDS las
 * líneas nunca vienen vacías, así que un `null` aquí es un fallo. Se suelta, que es el lado
 * seguro: otro aparato lo intenta.
 */
internal fun laComandaSalio(estado: EstadoDeComanda?): Boolean = estado is EstadoDeComanda.Salio
