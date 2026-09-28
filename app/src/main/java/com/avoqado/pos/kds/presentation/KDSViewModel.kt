package com.avoqado.pos.kds.presentation

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.data.KdsPrefs
import com.avoqado.pos.kds.domain.AccionDeCocina
import com.avoqado.pos.kds.domain.AvisoDeCocina
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.EntradaDeCasilla
import com.avoqado.pos.kds.domain.EstadoDeCasilla
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.kds.domain.avisoDeFallo
import com.avoqado.pos.kds.domain.esSinRed
import com.avoqado.pos.kds.domain.estacionElegida
import com.avoqado.pos.kds.domain.estacionesParaElegir
import com.avoqado.pos.kds.domain.estadoDeCasilla
import com.avoqado.pos.kds.domain.idsParaMarcarTodas
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

private const val TAG = "🍳 KDS-VM"

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
) : ViewModel() {

    // MARK: - Estado

    private val _vista = MutableStateFlow<VistaDeCocina>(VistaDeCocina.ElegirEstacion(emptyList(), laGuardadaYaNoExiste = false))
    val vista: StateFlow<VistaDeCocina> = _vista.asStateFlow()

    /** Las comandas pendientes de la estación, en orden de llegada (como las manda el servidor). */
    private val _comandas = MutableStateFlow<List<KDSOrder>>(emptyList())
    val comandas: StateFlow<List<KDSOrder>> = _comandas.asStateFlow()

    private val _recientes = MutableStateFlow<List<KDSOrder>>(emptyList())
    val recientes: StateFlow<List<KDSOrder>> = _recientes.asStateFlow()

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
    private var previousOrderIds: Set<String> = emptySet()
    private var hasLoadedFromAPI = false

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
    }

    /** Relee la config (cache-first), decide qué enseñar y, si es tablero, trae sus comandas. */
    internal suspend fun refrescar() {
        val venueId = kdsRepository.venueIdActual() ?: return
        // Cache-first: primero lo que el aparato ya sabe, sin esperar a la red…
        _config.value = printConfigRepository.getCurrentConfig()
        if (!eligiendoAMano && _config.value.version.isNotEmpty()) _vista.value = vistaPara(prefs.estacion(venueId))
        // …y luego la verdad del servidor.
        printConfigRepository.refresh(venueId)
        _config.value = printConfigRepository.getCurrentConfig()
        if (!eligiendoAMano) _vista.value = vistaPara(prefs.estacion(venueId))
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
                val ids = nuevas.map { it.id }.toSet()
                if (hasLoadedFromAPI && (ids - previousOrderIds).isNotEmpty()) playNotificationSound()
                previousOrderIds = ids
                hasLoadedFromAPI = true
                _comandas.value = nuevas
                // Después de publicar: la cocina ve el pedido primero, el papel sale enseguida.
                viewModelScope.launch { imprimirComandasPendientes(nuevas) }
            },
            onFailure = { e ->
                // Se CONSERVA lo que ya se veía: sin red la cocina sigue trabajando con eso.
                if (esSinRed(e)) _sinConexion.value = true
                Log.d(TAG, "No se pudo leer el tablero (se conserva lo que había): ${e.message}")
            },
        )
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
        viewModelScope.launch { refrescarTablero() }
    }

    fun cambiarEstacion() {
        eligiendoAMano = true
        _vista.value = VistaDeCocina.ElegirEstacion(estacionesParaElegir(_config.value.stations), laGuardadaYaNoExiste = false)
    }

    // MARK: - LISTO, lote y deshacer

    /** LISTO de un toque: sale del tablero al instante; si el servidor no se entera, REGRESA y se dice por qué. */
    fun listo(id: String) {
        val comanda = _comandas.value.firstOrNull { it.id == id } ?: return
        _comandas.value = _comandas.value.filterNot { it.id == id }
        viewModelScope.launch {
            kdsRepository.bumpOrder(id).onFailure { e ->
                if (_comandas.value.none { it.id == id }) _comandas.value = (_comandas.value + comanda).sortedBy { it.createdAt }
                if (esSinRed(e)) _sinConexion.value = true
                _aviso.value = avisoDeFallo(e, AccionDeCocina.LISTO)
            }
        }
    }

    /** «Marcar todas listas» (la pantalla confirma antes). Nunca un delivery sin aceptar. */
    fun marcarTodasListas() {
        val ids = idsParaMarcarTodas(_comandas.value)
        if (ids.isEmpty()) return
        val quitadas = _comandas.value.filter { it.id in ids }
        _comandas.value = _comandas.value.filterNot { it.id in ids }
        viewModelScope.launch {
            kdsRepository.bumpBatch(ids)
                .onSuccess { refrescarTablero() }
                .onFailure { e ->
                    val regresan = quitadas.filter { q -> _comandas.value.none { it.id == q.id } }
                    _comandas.value = (_comandas.value + regresan).sortedBy { it.createdAt }
                    if (esSinRed(e)) _sinConexion.value = true
                    _aviso.value = avisoDeFallo(e, AccionDeCocina.MARCAR_TODAS)
                }
        }
    }

    fun abrirRecientes() {
        val tablero = _vista.value as? VistaDeCocina.Tablero ?: return
        viewModelScope.launch {
            kdsRepository.fetchRecientes(tablero.estacion.id)
                .onSuccess { _recientes.value = it }
                .onFailure { e -> _aviso.value = avisoDeFallo(e, AccionDeCocina.RECIENTES) }
        }
    }

    /** «Deshacer»: sin optimismo — la comanda sigue en Recientes hasta que el servidor la regresa. */
    fun deshacer(id: String) {
        viewModelScope.launch {
            kdsRepository.recall(id)
                .onSuccess {
                    _recientes.value = _recientes.value.filterNot { it.id == id }
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
