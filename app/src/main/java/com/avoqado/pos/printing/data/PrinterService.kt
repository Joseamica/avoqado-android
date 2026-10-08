package com.avoqado.pos.printing.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.TablaDeAcentos
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterException
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PrinterService"

/** Standard SPP UUID for Bluetooth serial printing */
private val BT_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Default ESC/POS raw printing port */
private const val DEFAULT_PORT = 9100

/** Cuánto se espera la respuesta de estado antes de imprimir de todos modos. */
private const val PAPER_STATUS_TIMEOUT_MS = 1500

/** Connection timeout in milliseconds */
private const val CONNECTION_TIMEOUT_MS = 10_000L

/**
 * Ventana CORTA para "¿esta impresora responde?" — usada sólo por
 * [PrinterService.probarTodas] al abrir Ajustes › Impresoras. Deliberadamente
 * más corta que [CONNECTION_TIMEOUT_MS]: probar varias impresoras guardadas
 * no puede colgar la pantalla varios segundos porque una se quedó apagada.
 * Se prueban EN PARALELO, así que el costo para quien mira la pantalla es
 * ~esta ventana, no la suma de todas.
 */
private const val PROBE_TIMEOUT_MS = 2_500

/** How long a discovery scan runs before auto-stopping. mDNS browsing never
 *  "finishes" on its own, so without this the UI spinner spins forever. */
private const val DISCOVERY_WINDOW_MS = 12_000L

/**
 * Tras buscar una impresora movida sin éxito, cuánto esperar antes de volver a barrer la red.
 * El reintento de comandas insiste 6 veces en ~50 s; barrer en cada intento ahogaría el WiFi.
 */
private const val ESPERA_ENTRE_BUSQUEDAS_MS = 20_000L

/**
 * Una impresora cuya identidad no aparece en ningún lado sólo se da por REEMPLAZADA si la otra que
 * ocupa su lugar se ve en dos rondas separadas por al menos esto: una apagada o arrancando no es un
 * reemplazo (auditoría del 2-oct, A3).
 */
private const val REEMPLAZO_CONFIRMADO_MS = 9 * 60_000L

/** Cuánto se espera a que la impresora aparezca por su nombre en mDNS antes de barrer la red. */
private const val MDNS_POR_NOMBRE_MS = 3_000L

/** Respiro antes de reintentar un resolve que chocó dentro del SO. */
private const val RESOLVE_RETRY_MS = 250L

/** Espera al bind del servicio de la impresora integrada: 10 × 200 ms = 2 s. */
private const val BIND_WAIT_TRIES = 10
private const val BIND_WAIT_STEP_MS = 200L

/**
 * Fusiona una impresora WiFi recién resuelta con la lista ya descubierta.
 * Devuelve la lista nueva, o `null` si no hay nada que cambiar.
 *
 * UNA impresora física se anuncia en los TRES tipos de servicio que browseamos
 * (`_printer`=515/LPR, `_ipp`=631/IPP, `_pdl-datastream`=9100/raw) y cada
 * anuncio resuelve por separado, así que hay que deduplicar por DIRECCIÓN.
 *
 * Pero deduplicar "gana el primero" es una CARRERA, y perderla no se ve: sólo
 * el 9100 acepta un flujo ESC/POS crudo — el 631 y el 515 aceptan la conexión
 * TCP y se tragan los bytes. Medido en hardware (Epson TM-m30III por Ethernet,
 * 2026-07-29): resolvió primero por `_ipp` y quedó listada en `:631`, o sea
 * una impresora que dice "Conectada" y no imprime nunca. Por eso el 9100
 * ASCIENDE a una entrada previa en otro puerto, en vez de descartarse.
 *
 * iOS ya lo resolvía así (`PrinterService.preferredRawSocketPort`); Android se
 * había quedado atrás.
 */
internal fun mergeResolvedWifiPrinter(
    current: List<DiscoveredPrinter>,
    printer: DiscoveredPrinter,
): List<DiscoveredPrinter>? {
    val existing = current.indexOfFirst {
        it.address == printer.address && it.connectionType == PrinterConnectionType.WIFI
    }
    if (existing < 0) return current + printer
    val isUpgrade = printer.port == DEFAULT_PORT && current[existing].port != DEFAULT_PORT
    if (!isUpgrade) return null
    return current.toMutableList().apply { this[existing] = printer }
}

/**
 * Pure decision for whether [PrinterService.sendData] must drop the cached socket
 * and reconnect before writing, instead of reusing the socket in the connection
 * cache. Kept side-effect free and top-level `internal` so it is exhaustively
 * unit-testable without touching real sockets or a Context.
 *
 * Reconnect is required when: the status doesn't say connected (today's existing
 * behavior), there is no cached endpoint, the cached endpoint no longer matches
 * the printer's current endpoint (e.g. its IP was edited in the dashboard and the
 * config was refetched — the bug this guards against), or the cached socket is
 * already closed.
 *
 * Raw-port printers commonly close port 9100 after every job. [PrinterService]
 * therefore releases WiFi sockets after a successful write; the next job always
 * reconnects instead of trying to reuse a half-open connection.
 */
internal fun shouldReconnect(
    status: PrinterStatus,
    cachedEndpoint: String?,
    requestedEndpoint: String,
    socketClosed: Boolean,
): Boolean {
    if (!status.isConnected) return true
    if (cachedEndpoint == null || cachedEndpoint != requestedEndpoint) return true
    if (socketClosed) return true
    return false
}

/**
 * Selecciona la impresora por defecto sin inventar rutas de cocina. En un Sunmi
 * con cabezal físico, la integrada funciona como respaldo plug-and-play sólo
 * para recibos cuando el negocio todavía no guardó una impresora explícita.
 */
internal fun selectDefaultPrinter(
    role: PrinterRole,
    configured: List<SavedPrinter>,
    integratedAvailable: Boolean,
    integratedPaperWidthMm: Int,
): SavedPrinter? {
    configured.firstOrNull { it.isEnabled && it.hasRole(role) }?.let { return it }
    if (role != PrinterRole.RECEIPT || !integratedAvailable) return null
    return SavedPrinter(
        id = "internal-auto-receipt",
        name = "Impresora integrada",
        connectionType = PrinterConnectionType.INTERNAL.value,
        address = "internal",
        roles = listOf(PrinterRole.RECEIPT.value),
        paperWidthMm = integratedPaperWidthMm,
    )
}

@Singleton
class PrinterService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innerPrinter: SunmiInnerPrinter,
    private val receiptBranding: ReceiptBranding,
) {
    // MARK: - State

    private val _savedPrinters = MutableStateFlow<List<SavedPrinter>>(emptyList())
    val savedPrinters: StateFlow<List<SavedPrinter>> = _savedPrinters.asStateFlow()

    private val _printerStatuses = MutableStateFlow<Map<String, PrinterStatus>>(emptyMap())
    val printerStatuses: StateFlow<Map<String, PrinterStatus>> = _printerStatuses.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters.asStateFlow()

    // MARK: - Private

    private val wifiConnections = java.util.concurrent.ConcurrentHashMap<String, Socket>()
    private val btConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothSocket>()

    /**
     * Endpoint ("host:port" for WIFI, MAC address for BLUETOOTH) that the cached
     * socket in [wifiConnections]/[btConnections] was actually opened for, keyed
     * by printer.id. Used by [sendData] to detect a stale cache when a printer's
     * address/port changes without the connection status changing — see
     * [shouldReconnect].
     */
    private val connectionEndpoints = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val storage = PrinterStorage(context)
    private var nsdManager: NsdManager? = null
    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /** USB-host transport (Epson TM-m30III et al. plugged in by cable). */
    private val usbPrinters = UsbPrinterManager(context)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 🔴 Los resolves de mDNS van EN SERIE, uno a la vez.
     *
     * `NsdManager.resolveService` revienta con FAILURE_ALREADY_ACTIVE (3) si
     * hay otro en curso, y el fallo es SILENCIOSO: la impresora simplemente no
     * aparece. Medido en la Sunmi (2026-07-28): de 4 anuncios encontrados, 3
     * fallaron con error 3 y sólo 1 llegó a la lista.
     *
     * En un local con impresora de cocina + barra + caja eso significa ver UNA
     * sola y no poder configurar el resto — y no hay alta manual por IP que
     * salve la situación. Es el mismo tropiezo que ya costó una ronda de
     * diagnóstico en el hub LAN (`LanDiscovery`).
     */
    private val resolveMutex = Mutex()
    private var discoveryTimeoutJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - La impresora que se encuentra sola

    /**
     * Quién barre la red buscando ticketeras. `internal var` para que las pruebas pongan uno
     * falso sin sockets (no va en el constructor: Hilt y varias pruebas ya construyen éste).
     */
    internal var buscador: BuscadorDeImpresora = BuscadorDeImpresoraEnLan(context)

    /** Mudanzas recordadas por id de impresora (también las de la config del servidor). */
    private val mudanzas = RegistroEnPrefs(context, json, "avoqado_printer_moves", Mudanza.serializer())

    /** Direcciones de las impresoras de la config del servidor, por id. Ver [conocerImpresorasDeRed]. */
    private val impresorasDeLaConfig = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Identidades (`mac:…`/`mdns:…`) que trae la config del servidor (`Printer.stableKey`), por id. */
    private val identidadesDeLaConfig = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Identidades aprendidas en ESTA tablet (ganan a las del servidor: son más recientes). */
    private val identidades = RegistroEnPrefs(context, json, "avoqado_printer_identities", kotlinx.serialization.serializer<String>())

    /**
     * Avisos al servidor que faltan por mandar («Cocina ahora está en .67», «su identidad es mac:…»),
     * guardados ANTES de tocar la red: sobreviven a un reinicio y a horas sin internet. Los manda
     * [VigilanteDeImpresoras]. Uno por impresora; `previousAddress` es la dirección que el servidor tenía.
     */
    internal val avisosPendientes = RegistroEnPrefs(context, json, "avoqado_printer_reports", AvisoDeImpresora.serializer())

    /** La sucursal actual, para fechar los avisos. La pone [VigilanteDeImpresoras]. */
    @Volatile internal var venueActual: () -> String? = { null }

    /** Avisa que hay un aviso nuevo para el servidor, para mandarlo pronto. Lo pone [VigilanteDeImpresoras]. */
    @Volatile internal var alEncolarAviso: () -> Unit = {}

    /** Una búsqueda a la vez: dos comandas fallando juntas no barren la red dos veces. */
    private val busquedaMutex = Mutex()

    /** Una ronda del vigilante a la vez. NO es [busquedaMutex]: una ronda larga no detiene una comanda (M1). */
    private val vigilanteMutex = Mutex()

    /** Lecturas de páginas que no se esperan enteras (una que no contesta no frena a las demás). */
    private val fondo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** El reloj, para que las pruebas confirmen un reemplazo sin esperar 9 minutos. */
    internal var reloj: () -> Long = { System.currentTimeMillis() }

    /**
     * Direcciones donde el vigilante vio OTRA impresora conocida (id → dirección). Una comanda no se
     * manda ahí: busca la suya. Sin esto la de Cocina salía en Barra mientras tanto (auditoría A3).
     */
    private val conOtraImpresora = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reemplazo en observación: id → (identidad vista en su lugar, cuándo se vio por primera vez). */
    private val reemplazoEnObservacion = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val ultimaBusquedaFallida = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        loadSavedPrinters()
    }

    // MARK: - Printer Management

    fun loadSavedPrinters() {
        _savedPrinters.value = storage.loadPrinters()
        val statuses = mutableMapOf<String, PrinterStatus>()
        _savedPrinters.value.forEach { statuses[it.id] = estadoInicial(it) }
        _printerStatuses.value = statuses
    }

    /**
     * El estado con el que arranca una impresora al abrir la app.
     *
     * 🔴 La INTEGRADA no puede arrancar en `Disconnected`. No hay socket que
     * abrir: va soldada al equipo y el servicio se re-liga solo, así que
     * [needsReconnect] ya devuelve `false` cuando el hardware responde — o sea
     * que imprime perfecto mientras la pantalla dice "Desconectada".
     *
     * Reportado en una D3 (2026-08-10): la lista de guardadas decía
     * "Desconectada" y la de disponibles marcaba la misma impresora con palomita
     * verde. Dos afirmaciones opuestas del mismo aparato en la misma pantalla, y
     * la que estaba mal era ésta. La verdad para la integrada es si el hardware
     * está ahí, no si alguien le picó "Conectar".
     *
     * 🔴 Se pregunta por `hasPhysicalPrinter`, NO por `isAvailable`. Sunmi
     * preinstala el servicio AIDL en toda su gama, así que en una T3 Pro —que no
     * tiene cabezal— `isAvailable` también da `true` y diríamos "Conectada" de
     * una impresora que no existe. Sería la misma mentira al revés, y en el lado
     * peor: el local creería que tiene con qué imprimir.
     *
     * 🔴 Task 7 — el arreglo de agosto de arriba sólo cubrió la integrada. Las
     * impresoras de RED/Bluetooth/USB seguían cayendo al `else` y arrancaban
     * en [PrinterStatus.Disconnected] SIN que nadie hubiera probado nada: el
     * mismo letrero mentiroso, en otro transporte. Caso real: el cajero veía
     * la impresora de cocina como "Desconectada", picaba "Conectar", respondía
     * al instante (siempre estuvo bien) y creía haber arreglado algo — y como
     * el estado sólo vive en memoria, cada reinicio de la app repetía la
     * mentira. Ahora esas arrancan en [PrinterStatus.SinComprobar] — un "no sé
     * todavía" honesto — hasta que [probarTodas] mida la verdad.
     */
    private fun estadoInicial(printer: SavedPrinter): PrinterStatus = when {
        printer.connectionTypeEnum == PrinterConnectionType.INTERNAL && innerPrinter.hasPhysicalPrinter ->
            PrinterStatus.Connected
        // La integrada SIN cabezal (residuo de un T3 mal configurado) sigue
        // siendo Disconnected a propósito: no hace falta "comprobar" nada,
        // ya lo sabemos con certeza vía hasPhysicalPrinter — no es un "no sé".
        printer.connectionTypeEnum == PrinterConnectionType.INTERNAL ->
            PrinterStatus.Disconnected
        else -> PrinterStatus.SinComprobar
    }

    fun savePrinter(printer: SavedPrinter) {
        val list = _savedPrinters.value.toMutableList()
        val index = list.indexOfFirst { it.id == printer.id }
        if (index >= 0) {
            list[index] = printer
        } else {
            list.add(printer)
        }
        _savedPrinters.value = list
        storage.savePrinters(list)
        // Ver [estadoInicial]: la integrada no nace desconectada. Éste es el
        // camino que se recorre al agregarla desde "Impresoras disponibles", que
        // es donde se vio el defecto.
        updateStatus(printer.id, estadoInicial(printer))
    }

    fun deletePrinter(printer: SavedPrinter) {
        disconnect(printer)
        val list = _savedPrinters.value.toMutableList()
        list.removeAll { it.id == printer.id }
        _savedPrinters.value = list
        storage.savePrinters(list)
        val statuses = _printerStatuses.value.toMutableMap()
        statuses.remove(printer.id)
        _printerStatuses.value = statuses
    }

    fun updatePrinter(printer: SavedPrinter) {
        val list = _savedPrinters.value.toMutableList()
        val index = list.indexOfFirst { it.id == printer.id }
        if (index >= 0) {
            list[index] = printer
            _savedPrinters.value = list
            storage.savePrinters(list)
        }
    }

    // MARK: - Connection

    suspend fun connect(printer: SavedPrinter, wifiTimeoutMs: Int = CONNECTION_TIMEOUT_MS.toInt()) {
        updateStatus(printer.id, PrinterStatus.Connecting)
        try {
            when (printer.connectionTypeEnum) {
                PrinterConnectionType.WIFI -> connectWiFi(printer, wifiTimeoutMs)
                PrinterConnectionType.BLUETOOTH -> {
                    // Residuo: equipos que ya guardaron el "InnerPrinter" por BT
                    // antes de este fix. El socket abre y los bytes se pierden,
                    // así que el silencio parecería "impreso". Mejor gritar.
                    if (isSunmiInner(printer.name) && !innerPrinter.hasPhysicalPrinter) {
                        throw PrinterException.ConnectionFailed(
                            "Este equipo no tiene impresora integrada. Elige otra impresora en Ajustes › Impresora.",
                        )
                    }
                    connectBluetooth(printer)
                }
                PrinterConnectionType.USB -> connectUsb(printer)
                // Va soldada: "conectar" = asegurar el bind del servicio (que es
                // asíncrono y pudo no haber ocurrido esta sesión). No adivina que
                // ya está: lo garantiza.
                PrinterConnectionType.INTERNAL -> {
                    if (!innerPrinter.ensureBound()) {
                        throw PrinterException.ConnectionFailed("La impresora integrada no está disponible")
                    }
                    // Residuo de equipos configurados ANTES de detectar el
                    // hardware: una T3 pudo guardar la "integrada" que nunca
                    // tuvo. Falla RUIDOSO — tragarse la comanda es peor: la
                    // cocina no se entera y sólo se descubre al servir.
                    if (!innerPrinter.hasPhysicalPrinter) {
                        throw PrinterException.ConnectionFailed(
                            "Este equipo no tiene impresora integrada. Elige otra impresora en Ajustes › Impresora.",
                        )
                    }
                }
            }
            updateStatus(printer.id, PrinterStatus.Connected)
            updateLastConnected(printer)
            Log.d(TAG, "Connected to printer: ${printer.name}")
        } catch (e: Exception) {
            updateStatus(printer.id, PrinterStatus.Error(e.message ?: "Error desconocido"))
            throw e
        }
    }

    fun disconnect(printer: SavedPrinter) {
        try {
            wifiConnections.remove(printer.id)?.close()
            btConnections.remove(printer.id)?.close()
            usbPrinters.close(printer.id)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting: ${e.message}")
        }
        connectionEndpoints.remove(printer.id)
        updateStatus(printer.id, PrinterStatus.Disconnected)
    }

    /**
     * Suelta las conexiones abiertas — **menos las que están IMPRIMIENDO**.
     *
     * 🔴 La excepción no es un detalle: esto lo llama el `onDispose` de las hojas de ajustes, y
     * cerrar el socket de una impresora a media escritura deja la comanda cortada por la mitad
     * —o sin salir— sin que nadie se entere. Puede ser incluso OTRA impresora: una comanda
     * reintentando en segundo plano mientras el cajero configura una distinta (P1 #5 de la
     * auditoría de Codex, 2026-09-07; el defecto lo introdujo el propio arreglo de la Tarea 5).
     *
     * Lo que sí cierra es lo que está ocioso, que es justo el objetivo: el socket que deja
     * abierto el botón «Conectar» y que nadie vuelve a cerrar — la sospecha principal de por qué
     * la impresora de Testarudo no contesta al día siguiente (muchas impresoras de puerto 9100
     * aceptan UNA sola conexión).
     */
    fun disconnectAll() {
        _savedPrinters.value.forEach { printer ->
            // Se relee el estado impresora por impresora, no una foto de antes del bucle: una
            // impresión puede arrancar mientras se cierran las anteriores.
            if (!puedeSoltarseLaConexion(_printerStatuses.value[printer.id])) {
                Log.d(TAG, "⏭️ ${printer.name} está imprimiendo — su conexión NO se cierra")
                return@forEach
            }
            disconnect(printer)
        }
    }

    // MARK: - Comprobación ("¿respondes?", no imprime nada)

    /**
     * Comprueba las impresoras guardadas EN PARALELO y sustituye el "no sé
     * todavía" ([PrinterStatus.SinComprobar]) por la verdad medida — **sólo
     * para WIFI**. Bluetooth y USB se quedan en `SinComprobar` hasta que
     * alguien toque "Conectar" a mano; ver [probar] para el porqué.
     *
     * 🔴 El caso real de Task 7: `estadoInicial()` marcaba "Desconectada" a
     * toda impresora que no fuera la integrada SIN PROBAR NADA — un texto que
     * vivía sólo en memoria, así que cada reinicio de la app volvía a
     * mostrarlo aunque la impresora estuviera perfecta. El cajero picaba
     * "Conectar", el socket abría al instante (la impresora SIEMPRE estuvo
     * bien) y creía haber arreglado algo. Se llama al abrir
     * [com.avoqado.pos.printing.presentation.PrinterSettingsSheet] para
     * sustituir esa mentira por una medición real.
     *
     * EN PARALELO, no en serie: tres impresoras apagadas en serie colgarían
     * la pantalla ~3× la ventana de una sola. En paralelo, el costo para
     * quien mira la pantalla es ~[PROBE_TIMEOUT_MS], no la suma.
     */
    suspend fun probarTodas() = coroutineScope {
        _savedPrinters.value.map { printer -> async { probar(printer) } }.awaitAll()
    }

    /**
     * Comprueba UNA impresora guardada. Nunca lanza — cualquier fallo se
     * traduce a [PrinterStatus.Disconnected] ("No responde"), igual que hoy
     * hace un [connect] fallido desde la hoja de configuración.
     *
     * 🔴 Ronda 1 de revisión de Task 7 — SÓLO prueba WIFI. `connect()` aquí es
     * el PRIMER lugar del repo que conecta SIN QUE NADIE LO PIDIERA: los otros
     * tres llamadores ([PrinterConfigSheet] "Conectar",
     * [com.avoqado.pos.printing.presentation.PrinterSettingsSheet] al agregar
     * una impresora nueva, `ManualIpSection` al darla de alta) van todos
     * detrás de un toque explícito del cajero. Bluetooth y USB se saltan por
     * dos razones distintas, cada una real:
     *
     * - **Bluetooth NO libera su socket tras imprimir** — a diferencia de
     *   WIFI (ver `sendData`/`releaseWifiConnection`, que suelta el puerto
     *   9100 después de CADA trabajo), un socket Bluetooth se queda VIVO en
     *   [btConnections] por diseño. Escenario real: el cajero imprime un
     *   recibo por Bluetooth, entra a Ajustes › Impresoras por cualquier
     *   motivo, y si `probarTodas()` la tocara, `connectBluetooth()`
     *   sobreescribiría `btConnections[id]` con un socket NUEVO sin cerrar el
     *   viejo — la impresora térmica (que casi siempre sólo acepta UNA
     *   conexión SPP) queda inutilizable hasta apagarla. Es el mismo
     *   "teléfono descolgado" que Task 5 cerró para WiFi, reabierto para
     *   Bluetooth por este mecanismo nuevo.
     * - **USB puede hacer saltar el diálogo de permiso del sistema** sólo por
     *   abrir esta pantalla, sin que el usuario haya tocado nada
     *   (`UsbPrinterManager.open()` → `ensurePermission()`).
     *
     * 🔴 Defensa barata contra el mismo hueco en WIFI: si la impresora YA
     * está conectada, no se re-prueba. Sin esto, una WiFi con un socket vivo
     * (alguien tocó "Conectar" a mano y no ha impreso desde entonces) sufriría
     * el mismo problema — `connectWiFi()` tampoco cierra un socket cacheado
     * antes de sobreescribirlo.
     *
     * SIEMPRE suelta lo que abre (WiFi): un socket vivo tras la prueba sería
     * el mismo "teléfono descolgado", sólo que contra una impresora que apenas
     * tardó en contestar. `disconnect()` ya deja el estado en Desconectada; lo
     * corregimos después a la verdad que sí se midió.
     *
     * WIFI usa la ventana CORTA ([PROBE_TIMEOUT_MS]) porque su socket acepta
     * un timeout explícito sin arriesgar dejar una conexión huérfana si la
     * corrutina se cancelara a medias — el timeout lo aplica el propio
     * `Socket.connect(addr, ms)` de Java, no una cancelación de corrutina que
     * carrera contra una llamada bloqueante que no se puede interrumpir. La
     * INTERNA no pasa por el filtro de tipo: no hay socket, la pregunta es
     * síncrona.
     */
    private suspend fun probar(printer: SavedPrinter) {
        if (printer.connectionTypeEnum == PrinterConnectionType.INTERNAL) {
            // Va soldada al equipo: no hay socket que abrir. La pregunta
            // síncrona de estadoInicial() YA es la verdad completa.
            updateStatus(
                printer.id,
                if (innerPrinter.hasPhysicalPrinter) PrinterStatus.Connected else PrinterStatus.Disconnected,
            )
            return
        }
        // USB: sólo si se puede preguntar SIN abrirla (la cola de Windows en escritorio). En Android
        // `estaLista` es null y se queda como estaba. Ver el doc de arriba.
        if (printer.connectionTypeEnum == PrinterConnectionType.USB) {
            val lista = withContext(Dispatchers.IO) { usbPrinters.estaLista(printer.address) } ?: return
            updateStatus(printer.id, if (lista) PrinterStatus.Connected else PrinterStatus.Disconnected)
            return
        }
        // Bluetooth: se queda en SinComprobar. Ver el doc de arriba.
        if (printer.connectionTypeEnum != PrinterConnectionType.WIFI) return

        // Ya conectada: no la toques (evita el mismo hueco en WiFi).
        if (_printerStatuses.value[printer.id]?.isConnected == true) return

        var respondio = false
        try {
            connect(printer, wifiTimeoutMs = PROBE_TIMEOUT_MS)
            respondio = true
        } catch (e: Exception) {
            respondio = false
        } finally {
            disconnect(printer)
            updateStatus(printer.id, if (respondio) PrinterStatus.Connected else PrinterStatus.Disconnected)
        }
    }

    private suspend fun connectWiFi(printer: SavedPrinter, timeoutMs: Int) = withContext(Dispatchers.IO) {
        val port = printer.port ?: DEFAULT_PORT
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(printer.address, port), timeoutMs)
            wifiConnections[printer.id] = socket
            connectionEndpoints[printer.id] = "${printer.address}:$port"
        } catch (e: Exception) {
            socket.close()
            throw PrinterException.ConnectionFailed(e.message ?: "No se pudo conectar a ${printer.address}:$port")
        }
    }

    private suspend fun connectUsb(printer: SavedPrinter) = withContext(Dispatchers.IO) {
        // ensurePermission inside open() may pop the system USB dialog and suspend
        // until the user answers — same UX Square shows on first connect.
        usbPrinters.open(printer.id, printer.address)
        connectionEndpoints[printer.id] = printer.address
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectBluetooth(printer: SavedPrinter) = withContext(Dispatchers.IO) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: throw PrinterException.BluetoothUnavailable()
        if (!hasBluetoothConnectPermission()) {
            throw PrinterException.ConnectionFailed("Falta permiso BLUETOOTH_CONNECT")
        }

        if (!adapter.isEnabled) throw PrinterException.BluetoothUnavailable()

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(printer.address)
        } catch (e: IllegalArgumentException) {
            throw PrinterException.PrinterNotFound()
        }

        val socket = device.createRfcommSocketToServiceRecord(BT_SPP_UUID)

        try {
            if (hasBluetoothScanPermission()) {
                adapter.cancelDiscovery() // Must cancel before connect
            }
            socket.connect()
            btConnections[printer.id] = socket
            connectionEndpoints[printer.id] = printer.address
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw PrinterException.ConnectionFailed(e.message ?: "No se pudo conectar por Bluetooth")
        }
    }

    // MARK: - Printing

    suspend fun printReceipt(receipt: ReceiptData, printer: SavedPrinter) {
        // 🔴 SIEMPRE por `escposFor`: construir el ESCPOSPrinter a mano pierde el `FS .` de la
        // integrada de Sunmi y el ticket sale en blanco.
        val escpos = escposFor(printer)
        val plan = receiptBranding.plan(receipt, printer.paperWidth)
        val lines = ReceiptLayoutInterpreter.interpret(plan.blocks, plan.input, printer.paperWidth.charsPerLine)
        sendData(escpos.renderReceipt(lines, plan.rasterFor), printer)
    }

    suspend fun printKitchenTicket(ticket: KitchenTicketData, printer: SavedPrinter) {
        val escpos = escposFor(printer)
        val data = escpos.generateKitchenTicket(ticket)
        sendData(data, printer)
    }

    /**
     * Vale de área (AREA_TICKETS): el papel que se lleva el cliente y que la caja escanea.
     *
     * `symbology` es configurable por venue porque no toda pistola lee CODE128 — la del cliente
     * de Culiacán sí (probado contra su hardware), pero su sistema viejo emite CODE39 y otra
     * sucursal podría tener una pistola de esa época. Ojo: CODE39 con 10 dígitos NO cabe en papel
     * de 58 mm; ese respaldo exige rollo de 80.
     */
    suspend fun printAreaTicket(
        ticket: AreaTicketData,
        printer: SavedPrinter,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128_C,
    ) {
        val escpos = escposFor(printer)
        val data = escpos.generateAreaTicket(ticket, symbology)
        sendData(data, printer)
    }

    /**
     * La integrada de Sunmi arranca en multibyte (GB18030) y necesita `FS .`
     * antes del code page. Las de red/Bluetooth ya están en single-byte.
     */
    private fun escposFor(printer: SavedPrinter) = ESCPOSPrinter(
        paperWidth = printer.paperWidth,
        switchToSingleByteFirst = printer.connectionTypeEnum == PrinterConnectionType.INTERNAL,
        leftMarginChars = printer.leftMarginChars,
        tablaDeAcentos = TablaDeAcentos.deGuardada(printer.tablaDeAcentos),
    )

    suspend fun printTestPage(printer: SavedPrinter) {
        val escpos = escposFor(printer)
        val data = escpos.generateTestPrint()
        sendData(data, printer)
    }

    suspend fun openCashDrawer(printer: SavedPrinter) {
        val escpos = escposFor(printer)
        escpos.reset()
        escpos.openCashDrawer(printer.cashDrawerPin)
        // El pulso no usa papel: una impresora sin rollo igual debe poder abrir el cajón
        // (auditoría de Codex H6), sobre todo con el cliente esperando su cambio.
        sendData(escpos.getData(), printer, requierePapel = false)
    }

    /**
     * Envía bytes ESC/POS ya armados a una impresora. Espejo de `sendPrintData`
     * de iOS: lo usa quien construye su propio ticket (p. ej. el corte de caja)
     * en vez de pasar por [printReceipt].
     */
    suspend fun sendPrintData(data: ByteArray, printer: SavedPrinter) = sendData(data, printer)

    private suspend fun sendData(data: ByteArray, configurada: SavedPrinter, requierePapel: Boolean = true) {
        // Si esta impresora ya se encontró en otra dirección, se intenta directo ahí.
        var printer = conDireccionVigente(configurada)
        // Auto-connect if not connected, or if the cached socket is stale: the
        // status says "connected" but it was opened for a different endpoint
        // (e.g. the printer's IP was edited and the config was refetched) or the
        // socket has since been closed. See shouldReconnect() for the exact
        // decision and its limits.
        val status = _printerStatuses.value[printer.id] ?: PrinterStatus.Disconnected
        val requestedEndpoint = resolveEndpoint(printer)
        val cachedEndpoint = connectionEndpoints[printer.id]
        val socketClosed = isCachedSocketClosed(printer)
        if (shouldReconnect(status, cachedEndpoint, requestedEndpoint, socketClosed)) {
            if (status.isConnected) {
                // Legacy path (status was disconnected) never called disconnect()
                // here, it just connected fresh — only drop the socket first when
                // we're overriding a "connected" status that turned out stale.
                disconnect(printer)
            }
            printer = conectarOEncontrar(printer)
        }

        updateStatus(printer.id, PrinterStatus.Printing)

        try {
            when (printer.connectionTypeEnum) {
                PrinterConnectionType.WIFI -> sendDataWiFi(data, printer, requierePapel)
                PrinterConnectionType.BLUETOOTH -> sendDataBluetooth(data, printer)
                PrinterConnectionType.USB -> sendDataUsb(data, printer)
                PrinterConnectionType.INTERNAL -> innerPrinter.printRaw(data)
            }
            if (printer.connectionTypeEnum == PrinterConnectionType.WIFI) {
                releaseWifiConnection(printer.id)
            }
            updateStatus(printer.id, PrinterStatus.Connected)
        } catch (e: Exception) {
            if (printer.connectionTypeEnum == PrinterConnectionType.WIFI) {
                releaseWifiConnection(printer.id)
            }
            updateStatus(printer.id, PrinterStatus.Error(e.message ?: "Error al imprimir"))
            throw PrinterException.PrintFailed(e.message ?: "Error desconocido")
        }
    }

    /**
     * Las impresoras de red de la config del servidor (id → dirección), para que la búsqueda
     * sepa cuáles direcciones ya son de OTRA impresora. Lo llama [ComandaPrinter] antes de imprimir.
     */
    fun conocerImpresorasDeRed(direccionesPorId: Map<String, String>, identidadesPorId: Map<String, String> = emptyMap()) {
        // REEMPLAZA, no acumula: siempre llega la config completa, y una impresora borrada,
        // apagada o de otra sucursal no debe seguir contando como «ocupada».
        impresorasDeLaConfig.keys.retainAll(direccionesPorId.keys)
        impresorasDeLaConfig.putAll(direccionesPorId)
        identidadesDeLaConfig.keys.retainAll(identidadesPorId.keys)
        identidadesDeLaConfig.putAll(identidadesPorId)
    }

    /** Las identidades de las OTRAS impresoras conocidas: una candidata con una de éstas no es la nuestra. */
    private fun identidadesDeLasOtras(idImpresora: String): Set<String> =
        (impresorasDeLaConfig.keys + _savedPrinters.value.map { it.id })
            .filter { it != idImpresora }
            .mapNotNull { identidadDe(it) }
            .toSet()

    /** La identidad conocida de una impresora: la aprendida aquí, o la que trae el servidor. */
    internal fun identidadDe(idImpresora: String): String? =
        identidades.todas()[idImpresora] ?: identidadesDeLaConfig[idImpresora]

    /** La dirección con la que de verdad se le habla a una impresora (con su mudanza, si la hubo). */
    fun direccionVigente(idImpresora: String, direccionConfigurada: String): String =
        ImpresoraMovida.direccionVigente(mudanzas.todas(), idImpresora, direccionConfigurada)

    /**
     * Comprueba las impresoras de RED que vienen de la config del panel (las de las estaciones,
     * como «Cocina»), y si alguna no contesta la BUSCA en la red — la misma búsqueda que una
     * comanda. El resultado queda en [printerStatuses] bajo el id de cada una.
     *
     * 🔴 Existe porque la pantalla de Impresoras sólo enseñaba las guardadas en la tablet:
     * Testarudo (2-oct) veía «Cocina no está conectada» mientras Cocina imprimía perfecto,
     * porque Cocina vive en el panel. Siempre suelta el puerto: no deja el teléfono descolgado.
     */
    suspend fun probarDelPanel(impresoras: List<com.avoqado.pos.printing.routing.PrinterInfo>) = coroutineScope {
        val deRed = impresoras.filter { it.active }.mapNotNull { info ->
            val raw = info.address?.trim()
            if (info.connectionType.trim().uppercase() != "NETWORK" || raw.isNullOrEmpty()) return@mapNotNull null
            val separador = raw.lastIndexOf(':')
            val puerto = if (separador > 0) raw.substring(separador + 1).toIntOrNull() else null
            SavedPrinter(
                id = info.id,
                name = info.name,
                connectionType = PrinterConnectionType.WIFI.value,
                address = if (puerto != null) raw.substring(0, separador) else raw,
                port = puerto ?: DEFAULT_PORT,
                roles = listOf(PrinterRole.KITCHEN.value),
            )
        }
        conocerImpresorasDeRed(
            deRed.associate { it.id to it.address },
            impresoras.mapNotNull { info -> info.stableKey?.takeIf { it.isNotBlank() }?.let { info.id to it } }.toMap(),
        )
        deRed.map { printer ->
            async(Dispatchers.IO) {
                // Imprimiendo o conectando para una comanda: no se toca (auditoría M5).
                val antes = _printerStatuses.value[printer.id]
                if (antes == PrinterStatus.Printing || antes == PrinterStatus.Connecting) return@async
                // Se prueba con un socket PROPIO que se cierra al instante: nunca pisa la conexión de
                // una comanda (el caché de conexiones es sólo de quien imprime).
                val p = conDireccionVigente(printer)
                val puerto = p.port ?: DEFAULT_PORT
                val esLaDeEstaTablet = buscador.direccionesPropias().any { it.ip == p.address }
                val respondio = (!esLaDeEstaTablet && conOtraImpresora[p.id] != p.address && abre(p.address, puerto)) ||
                    (runCatching { encontrarEnLaRed(p, esLaDeEstaTablet) }.getOrNull()?.let { abre(it, puerto) } ?: false)
                val ahora = _printerStatuses.value[printer.id]
                if (ahora != PrinterStatus.Printing && ahora != PrinterStatus.Connecting) {
                    updateStatus(printer.id, if (respondio) PrinterStatus.Connected else PrinterStatus.Disconnected)
                }
            }
        }.awaitAll()
    }

    private fun abre(host: String, puerto: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, puerto), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    private fun conDireccionVigente(printer: SavedPrinter): SavedPrinter {
        if (printer.connectionTypeEnum != PrinterConnectionType.WIFI) return printer
        val vigente = ImpresoraMovida.direccionVigente(mudanzas.todas(), printer.id, printer.address)
        return if (vigente == printer.address) printer else printer.copy(address = vigente)
    }

    /**
     * Conecta; y si una impresora de RED no contesta, la busca en la red del local.
     *
     * 🔴 El caso que lo originó (Testarudo, 2-oct-2026): la ticketera de cocina tiene DHCP y el
     * módem (Telmex) le dio su vieja dirección, 192.168.1.64, a la TABLET. Cada comanda tocaba
     * la puerta de la propia tablet y tronaba con ECONNREFUSED, con la impresora sana en la .67.
     * Si la dirección guardada ES la de esta tablet ni se intenta: se busca directo.
     *
     * Devuelve la impresora con la dirección con la que SÍ conectó.
     */
    private suspend fun conectarOEncontrar(printer: SavedPrinter): SavedPrinter {
        if (printer.connectionTypeEnum != PrinterConnectionType.WIFI) {
            connect(printer)
            return printer
        }
        val esLaDeEstaTablet = buscador.direccionesPropias().any { it.ip == printer.address }
        if (conOtraImpresora[printer.id] == printer.address) {
            Log.w(TAG, "📍 En la dirección de ${printer.name} (${printer.address}) está OTRA impresora — busco la suya")
        } else if (!esLaDeEstaTablet) {
            try {
                connect(printer)
                return printer
            } catch (e: PrinterException.ConnectionFailed) {
                Log.w(TAG, "📍 ${printer.name} no contesta en ${printer.address} — la busco en la red")
            }
        } else {
            Log.w(TAG, "📍 ${printer.name} tiene la dirección de ESTA tablet (${printer.address}) — la busco en la red")
        }
        val nueva = encontrarEnLaRed(printer, esLaDeEstaTablet)
        val movida = printer.copy(address = nueva)
        connect(movida)
        return movida
    }

    private suspend fun encontrarEnLaRed(printer: SavedPrinter, esLaDeEstaTablet: Boolean): String =
        busquedaMutex.withLock {
            // Otra comanda pudo encontrarla mientras ésta esperaba el candado.
            val yaEncontrada = conDireccionVigente(printer).address
            if (yaEncontrada != printer.address) return@withLock yaEncontrada

            val noEncontrada = PrinterException.NoEstaEnSuDireccion(
                if (esLaDeEstaTablet) ImpresoraMovida.avisoDireccionPropia(printer.address)
                else ImpresoraMovida.avisoNinguna(printer.address),
            )
            val ahora = System.currentTimeMillis()
            val anterior = ultimaBusquedaFallida[printer.id]
            if (anterior != null && ahora - anterior < ESPERA_ENTRE_BUSQUEDAS_MS) throw noEncontrada

            val ocupadas = direccionesDeLasOtras(printer.id)
            // 0) Por su IDENTIDAD, si ya se conoce: es la única forma de no confundirla con otra.
            //    🔴 Y si se conoce y NO aparece, no se adivina con el barrido: tras reiniciarse el
            //    módem, «la única libre» suele ser OTRA impresora que ya arrancó (auditoría A2).
            if (identidadDe(printer.id) != null) {
                val encontrada = buscarPorIdentidad(printer, ocupadas)
                if (encontrada == null) {
                    ultimaBusquedaFallida[printer.id] = ahora
                    throw noEncontrada
                }
                ultimaBusquedaFallida.remove(printer.id)
                recordarMudanza(printer, encontrada)
                return@withLock encontrada
            }
            // 1) Por su NOMBRE en la red (Epson, Star…: se anuncian). Es su identidad: mDNS no
            //    deja repetir nombres, así que no hay que adivinar.
            buscador.anunciadaConNombre(printer.name, MDNS_POR_NOMBRE_MS)
                ?.takeIf { it !in ocupadas }
                ?.let { anunciada ->
                    ultimaBusquedaFallida.remove(printer.id)
                    recordarMudanza(printer, anunciada)
                    aprenderIdentidad(printer.id, IdentidadDeImpresora.deNombre(printer.name))
                    return@withLock anunciada
                }
            // 2) Barrido de la red (genéricas que no se anuncian, como la de Testarudo).
            val ticketeras = buscador.ticketerasEnLaRed(printer.port ?: DEFAULT_PORT, noTocar = ocupadas)
            when (val eleccion = ImpresoraMovida.elegir(ticketeras, ocupadas)) {
                is Eleccion.Una -> {
                    // Si su página dice que es OTRA impresora conocida (Barra), no es la nuestra.
                    val suMac = buscador.leerMac(eleccion.direccion)
                    if (suMac != null && suMac in identidadesDeLasOtras(printer.id)) {
                        ultimaBusquedaFallida[printer.id] = ahora
                        throw noEncontrada
                    }
                    ultimaBusquedaFallida.remove(printer.id)
                    recordarMudanza(printer, eleccion.direccion)
                    aprenderIdentidad(printer.id, suMac)
                    eleccion.direccion
                }
                is Eleccion.Varias -> {
                    ultimaBusquedaFallida[printer.id] = ahora
                    throw PrinterException.NoEstaEnSuDireccion(
                        ImpresoraMovida.avisoVarias(printer.address, eleccion.direcciones.size),
                    )
                }
                Eleccion.Ninguna -> {
                    ultimaBusquedaFallida[printer.id] = ahora
                    throw noEncontrada
                }
            }
        }

    /**
     * La ronda del vigilante: revisa que cada impresora de red conocida siga siendo la MISMA en su
     * dirección, y aprende la identidad de las que no la tienen. Devuelve cuántas corrigió.
     *
     * 🔴 Es lo que detecta que dos ticketeras genéricas se INTERCAMBIARON la IP (el módem se reinicia
     * y reparte direcciones al azar): ambas contestan, así que imprimir no lo nota; sólo su identidad
     * lo dice. Corre al abrir la app, cuando vuelve la red (un módem que se reinicia tira el WiFi) y
     * cada 10 min — ver [VigilanteDeImpresoras]. Nunca bloquea una comanda.
     *
     * Si en nuestra dirección hay OTRA impresora y la nuestra no aparece en ningún lado, la de ahí es
     * su reemplazo (alguien cambió el aparato): se adopta su identidad en vez de dejar de imprimir.
     */
    suspend fun vigilar(): Int = vigilanteMutex.withLock {
        var corregidas = 0
        var anunciadas: Map<String, String>? = null
        val guardadas = _savedPrinters.value
            .filter { it.connectionTypeEnum == PrinterConnectionType.WIFI }
            .associate { it.id to (it.address to (it.port ?: DEFAULT_PORT)) }
        val conocidas = impresorasDeLaConfig.mapValues { (_, d) -> d to DEFAULT_PORT } + guardadas
        for ((id, datos) in conocidas) {
            val (configurada, puerto) = datos
            val vigente = direccionVigente(id, configurada)
            val enSuLugar = SavedPrinter(id = id, name = id, connectionType = PrinterConnectionType.WIFI.value, address = vigente, port = puerto)
            val identidad = identidadDe(id)
            try {
                if (identidad == null) {
                    val todas = anunciadas ?: buscador.anunciadas(MDNS_POR_NOMBRE_MS).also { anunciadas = it }
                    aprenderIdentidad(id, todas[vigente]?.let { IdentidadDeImpresora.deNombre(it) } ?: buscador.leerMac(vigente))
                    continue
                }
                val nombre = IdentidadDeImpresora.nombreAnunciado(identidad)
                if (nombre != null) {
                    val ip = buscador.anunciadaConNombre(nombre, MDNS_POR_NOMBRE_MS)
                    if (ip != null && ip != vigente) { recordarMudanza(enSuLugar, ip); corregidas++ }
                    continue
                }
                val ahi = buscador.leerMac(vigente) ?: continue // no contesta: la comanda la buscará
                if (ahi == identidad) {
                    conOtraImpresora.remove(id)
                    reemplazoEnObservacion.remove(id)
                    continue
                }
                Log.w(TAG, "🪪 En la dirección de $id ($vigente) ahora hay OTRA impresora ($ahi) — busco la suya")
                val suya = buscarPorIdentidad(enSuLugar, direccionesDeLasOtras(id))
                if (suya != null) {
                    recordarMudanza(enSuLugar, suya)
                    corregidas++
                    continue
                }
                if (ahi in identidadesDeLasOtras(id)) {
                    // Es la de OTRA impresora conocida: nunca su reemplazo. La comanda no va ahí.
                    conOtraImpresora[id] = vigente
                    Log.w(TAG, "🪪 En el lugar de $id está otra impresora conocida ($ahi): no se le manda su comanda")
                    continue
                }
                // ¿Reemplazo? Sólo si la misma desconocida sigue ahí en otra ronda, ≥ 9 min después.
                val visto = reemplazoEnObservacion[id]
                val ahora = reloj()
                if (visto == null || visto.first != ahi) {
                    reemplazoEnObservacion[id] = ahi to ahora
                } else if (ahora - visto.second >= REEMPLAZO_CONFIRMADO_MS) {
                    identidades.poner(id, ahi)
                    reemplazoEnObservacion.remove(id)
                    conOtraImpresora.remove(id)
                    Log.w(TAG, "🪪 $identidad no aparece en la red: la impresora de $vigente es su reemplazo ($ahi)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vigilante: no se pudo revisar $id (${e.message})")
            }
        }
        corregidas
    }

    /** Las direcciones (ya con sus mudanzas) de las OTRAS impresoras de red conocidas. */
    private fun direccionesDeLasOtras(idImpresora: String): Set<String> {
        val registro = mudanzas.todas()
        val guardadas = _savedPrinters.value
            .filter { it.connectionTypeEnum == PrinterConnectionType.WIFI && it.id != idImpresora }
            .associate { it.id to it.address }
        return (impresorasDeLaConfig.filterKeys { it != idImpresora } + guardadas)
            .map { (id, direccion) -> ImpresoraMovida.direccionVigente(registro, id, direccion) }
            .toSet()
    }

    /**
     * Dónde está la impresora según su IDENTIDAD, o null si no tiene o no apareció.
     *
     * - `mdns:<nombre>` → quien se anuncia con ese nombre.
     * - `mac:…` → la ticketera cuya página dice esa MAC. Se revisan también las direcciones de las
     *   OTRAS impresoras conocidas: si dos se intercambiaron la IP, la nuestra está justo ahí.
     *   Leer su página no toca el puerto de impresión, así que no estorba a una comanda en curso.
     */
    private suspend fun buscarPorIdentidad(printer: SavedPrinter, ocupadas: Set<String>): String? {
        val identidad = identidadDe(printer.id) ?: return null
        IdentidadDeImpresora.nombreAnunciado(identidad)?.let { nombre ->
            return buscador.anunciadaConNombre(nombre, MDNS_POR_NOMBRE_MS)
        }
        if (!IdentidadDeImpresora.esMac(identidad)) return null
        val candidatas = (buscador.ticketerasEnLaRed(printer.port ?: DEFAULT_PORT, noTocar = ocupadas) + ocupadas).distinct()
        if (candidatas.isEmpty()) return null
        // La PRIMERA que coincida gana: no se espera a la página más lenta (auditoría M2). Las
        // lecturas corren aparte y se acaban solas con su plazo.
        val hallada = kotlinx.coroutines.CompletableDeferred<String?>()
        val pendientes = java.util.concurrent.atomic.AtomicInteger(candidatas.size)
        candidatas.forEach { ip ->
            fondo.launch {
                val esElla = runCatching { buscador.leerMac(ip) == identidad }.getOrDefault(false)
                if (esElla) hallada.complete(ip)
                if (pendientes.decrementAndGet() == 0) hallada.complete(null)
            }
        }
        return hallada.await()
    }

    /**
     * Guarda la identidad aprendida de una impresora (si no tenía) y, si es del panel, se la avisa
     * al servidor. Nunca reemplaza una identidad: eso lo decide [vigilar] con evidencia.
     */
    private fun aprenderIdentidad(idImpresora: String, identidad: String?) {
        if (identidad == null || identidadDe(idImpresora) != null) return
        identidades.poner(idImpresora, identidad)
        Log.i(TAG, "🪪 Impresora $idImpresora aprendió su identidad: $identidad")
        encolarAviso(idImpresora)
    }

    /** Deja (o actualiza) el aviso al servidor de una impresora del panel. Se manda después, con o sin red. */
    private fun encolarAviso(idImpresora: String) {
        val configurada = impresorasDeLaConfig[idImpresora] ?: return // las guardadas sólo en esta tablet no se avisan
        val venue = venueActual() ?: return
        val previo = avisosPendientes.todas()[idImpresora]
        avisosPendientes.poner(
            idImpresora,
            AvisoDeImpresora(
                venueId = previo?.venueId ?: venue,
                // La dirección que el servidor TENÍA: es la que acepta como «anterior».
                previousAddress = previo?.previousAddress ?: configurada,
                address = direccionVigente(idImpresora, configurada),
                stableKey = identidadDe(idImpresora),
            ),
        )
        runCatching { alEncolarAviso() }
    }

    private fun recordarMudanza(printer: SavedPrinter, nueva: String) {
        if (nueva == printer.address) return
        // 🔴 La mudanza se ancla SIEMPRE a la dirección CONFIGURADA (la del servidor o la guardada),
        // no a la intermedia ni a una mudanza vieja: si se muda dos veces antes de que el servidor se
        // entere (.64 → .67 → .70), o REGRESA a una dirección que ya tuvo después de que el servidor
        // aceptó la anterior, la app no puede volver a buscarla donde no está (auditoría A1).
        val configurada = impresorasDeLaConfig[printer.id]
            ?: _savedPrinters.value.firstOrNull { it.id == printer.id }?.address
            ?: mudanzas.todas()[printer.id]?.takeIf { it.nueva == printer.address }?.original
            ?: printer.address
        if (nueva == configurada) mudanzas.quitar(printer.id) else mudanzas.poner(printer.id, Mudanza(original = configurada, nueva = nueva))
        conOtraImpresora.remove(printer.id)
        encolarAviso(printer.id)
        // Si es una guardada en ESTA tablet, se corrige su dirección de una vez (y su nombre,
        // si era el automático «Impresora 192.168.1.64»).
        _savedPrinters.value.firstOrNull { it.id == printer.id }?.let { guardada ->
            val nombre = if (guardada.name == "Impresora ${printer.address}") "Impresora $nueva" else guardada.name
            updatePrinter(guardada.copy(address = nueva, name = nombre))
        }
        Log.w(TAG, "📍 ${printer.name} se movió de ${printer.address} a $nueva — se recuerda")
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .log("Impresora ${printer.id} se movió de ${printer.address} a $nueva")
        }
    }

    /** Same "host:port" / MAC resolution used by connectWiFi/connectBluetooth, used to detect a stale cached endpoint. */
    private fun resolveEndpoint(printer: SavedPrinter): String =
        when (printer.connectionTypeEnum) {
            PrinterConnectionType.WIFI -> "${printer.address}:${printer.port ?: DEFAULT_PORT}"
            PrinterConnectionType.BLUETOOTH -> printer.address
            PrinterConnectionType.USB -> printer.address
            PrinterConnectionType.INTERNAL -> "internal"
        }

    private fun isCachedSocketClosed(printer: SavedPrinter): Boolean =
        when (printer.connectionTypeEnum) {
            PrinterConnectionType.WIFI -> wifiConnections[printer.id]?.isClosed ?: true
            PrinterConnectionType.BLUETOOTH -> btConnections[printer.id]?.isConnected?.not() ?: true
            // "Closed" also when the printer was unplugged — forces a clean reconnect
            // (and a fresh permission check) on the next print instead of a dead write.
            PrinterConnectionType.USB -> !usbPrinters.isOpen(printer.id) || usbPrinters.findDevice(printer.address) == null
            // No hay socket que se caiga: el servicio se re-liga solo.
            PrinterConnectionType.INTERNAL -> !innerPrinter.isAvailable
        }

    private suspend fun sendDataWiFi(data: ByteArray, printer: SavedPrinter, requierePapel: Boolean = true) = withContext(Dispatchers.IO) {
        val socket = wifiConnections[printer.id] ?: throw PrinterException.NotConnected()

        // 🔴 Preguntar ANTES de escribir: el 9100 es fuego-y-olvido.
        //
        // El socket acepta los bytes aunque el rollo esté vacío, así que sin esta
        // consulta la app cantaba "Recibo impreso" y no salía nada. Encontrado en
        // la T3 con una EPSON TM-m30III el 2026-08-10: el cajero se queda sin
        // ticket y creyendo que sí se imprimió.
        if (requierePapel && isOutOfPaper(socket)) throw PrinterException.OutOfPaper()

        val output: OutputStream = socket.getOutputStream()
        output.write(data)
        output.flush()
    }

    /**
     * ¿La impresora dice que se quedó sin papel? (ESC/POS `DLE EOT 4`)
     *
     * `DLE EOT n` es un comando de TIEMPO REAL: la impresora lo contesta aunque
     * esté en estado de error, que es justo cuando importa. n=4 pide el sensor
     * del rollo; en la respuesta los bits 5 y 6 (0x60) encendidos significan
     * papel agotado.
     *
     * 🔴 FALLA ABIERTO a propósito. Si la impresora no contesta a tiempo —modelo
     * viejo que no soporta el comando, red lenta— se imprime igual. En este
     * dominio el "fail-safe" NO puede ser dejar de imprimir: una comanda que no
     * llega a la cocina es peor que un aviso que no aparece. Sólo se bloquea
     * cuando la impresora dice EXPLÍCITAMENTE que no tiene papel.
     */
    private fun isOutOfPaper(socket: Socket): Boolean = try {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = PAPER_STATUS_TIMEOUT_MS
        try {
            socket.getOutputStream().apply {
                write(byteArrayOf(0x10, 0x04, 0x04)) // DLE EOT 4 — sensor del rollo
                flush()
            }
            val status = socket.getInputStream().read()
            // read() == -1 → la impresora cerró; no es "sin papel", no bloquear.
            val sinPapel = status >= 0 && (status and 0x60) == 0x60
            if (sinPapel) Log.w(TAG, "Impresora sin papel (estado 0x${status.toString(16)})")
            sinPapel
        } finally {
            socket.soTimeout = previousTimeout
        }
    } catch (e: Exception) {
        // Timeout o modelo que no soporta DLE EOT: se imprime igual (fail-open).
        Log.d(TAG, "Sin respuesta al estado de papel (${e.message}) — se imprime igual")
        false
    }

    private fun releaseWifiConnection(printerId: String) {
        runCatching { wifiConnections.remove(printerId)?.close() }
            .onFailure { Log.w(TAG, "Error closing WiFi print job: ${it.message}") }
        connectionEndpoints.remove(printerId)
    }

    private suspend fun sendDataUsb(data: ByteArray, printer: SavedPrinter) = withContext(Dispatchers.IO) {
        usbPrinters.write(printer.id, data)
    }

    private suspend fun sendDataBluetooth(data: ByteArray, printer: SavedPrinter) = withContext(Dispatchers.IO) {
        val socket = btConnections[printer.id] ?: throw PrinterException.NotConnected()
        val output: OutputStream = socket.outputStream
        // Send in chunks for BLE reliability (max 512 bytes per write)
        val chunkSize = 512
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            output.write(data, offset, end - offset)
            output.flush()
            offset = end
            if (offset < data.size) delay(50) // Small delay between chunks
        }
    }

    // MARK: - Auto Print

    suspend fun autoPrintReceipt(receipt: ReceiptData) {
        _savedPrinters.value
            .filter { it.isEnabled && it.autoPrintReceipts && it.hasRole(PrinterRole.RECEIPT) }
            .forEach { printer ->
                try {
                    repeat(printer.numberOfCopies) {
                        printReceipt(receipt, printer)
                    }
                    Log.d(TAG, "Auto-printed receipt on ${printer.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-print failed on ${printer.name}: ${e.message}")
                }
            }
    }

    /**
     * Manually (re)print a receipt to all enabled RECEIPT-role printers,
     * regardless of their [SavedPrinter.autoPrintReceipts] flag. Intended for
     * the "Imprimir recibo" button on the payment success screen.
     *
     * @return number of printers that successfully printed at least one copy
     */
    /**
     * Desenlace de una reimpresión manual. Un simple contador no alcanzaba: con
     * 0 impresiones la pantalla decía "No hay impresora configurada" aunque SÍ
     * hubiera una, sólo que sin papel. El motivo tiene que llegar a la UI para
     * que el cajero sepa qué hacer (poner papel ≠ configurar impresora).
     */
    sealed interface PrintOutcome {
        data class Printed(val count: Int) : PrintOutcome
        data object NoPrinter : PrintOutcome
        data object OutOfPaper : PrintOutcome
        data class Failed(val reason: String) : PrintOutcome
    }

    /**
     * Etiquetas de precio (PRO `PRICE_LABELS`). Va a la impresora con rol «Etiquetas» si el
     * negocio configuró una; si no, a la de recibos (con la integrada como respaldo). Una sola
     * impresora: dos copias de cada etiqueta en dos impresoras no le sirven a nadie.
     */
    suspend fun printPriceLabels(etiquetas: List<EtiquetaDePrecio>): PrintOutcome {
        val printer = getDefaultPrinter(PrinterRole.LABEL)
            ?: getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT)
            ?: return PrintOutcome.NoPrinter
        return try {
            sendData(escposFor(printer).generatePriceLabels(etiquetas), printer)
            PrintOutcome.Printed(1)
        } catch (e: PrinterException.OutOfPaper) {
            PrintOutcome.OutOfPaper
        } catch (e: Exception) {
            PrintOutcome.Failed(e.message ?: "Error al imprimir")
        }
    }

    /** «Lista de inventario» (gratis): a la impresora de recibos, con la integrada de respaldo. */
    suspend fun printInventoryList(lista: ListaDeInventario): PrintOutcome =
        imprimirEnRecibos { it.generateInventoryList(lista) }

    /** Comprobante de un conteo completado: a la impresora de recibos. */
    suspend fun printCountReceipt(comprobante: ComprobanteDeConteo): PrintOutcome =
        imprimirEnRecibos { it.generateCountReceipt(comprobante) }

    suspend fun printWasteReceipt(comprobante: ComprobanteDeMerma): PrintOutcome =
        imprimirEnRecibos { it.generateWasteReceipt(comprobante) }

    private suspend fun imprimirEnRecibos(armar: (ESCPOSPrinter) -> ByteArray): PrintOutcome {
        val printer = getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT)
            ?: return PrintOutcome.NoPrinter
        return try {
            sendData(armar(escposFor(printer)), printer)
            PrintOutcome.Printed(1)
        } catch (e: PrinterException.OutOfPaper) {
            PrintOutcome.OutOfPaper
        } catch (e: Exception) {
            PrintOutcome.Failed(e.message ?: "Error al imprimir")
        }
    }

    suspend fun manualPrintReceipt(receipt: ReceiptData): PrintOutcome {
        val configured = getPrinters(PrinterRole.RECEIPT)
        val eligible = if (configured.isNotEmpty()) {
            configured
        } else {
            listOfNotNull(getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT))
        }
        if (eligible.isEmpty()) return PrintOutcome.NoPrinter

        var successCount = 0
        var outOfPaper = false
        var lastError: String? = null
        eligible.forEach { printer ->
            try {
                printReceipt(receipt, printer)
                successCount++
                Log.d(TAG, "Manual reprint succeeded on ${printer.name}")
            } catch (e: PrinterException.OutOfPaper) {
                outOfPaper = true
                Log.e(TAG, "Manual reprint: ${printer.name} sin papel")
            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "Manual reprint failed on ${printer.name}: ${e.message}")
            }
        }
        return when {
            successCount > 0 -> PrintOutcome.Printed(successCount)
            // Sin papel gana sobre un error genérico: es el motivo accionable.
            outOfPaper -> PrintOutcome.OutOfPaper
            lastError != null -> PrintOutcome.Failed(lastError!!)
            else -> PrintOutcome.NoPrinter
        }
    }

    /**
     * Ticket de cocina del camino LEGADO (venue sin estaciones configuradas).
     *
     * 🔴 Devuelve las impresoras que TRONARON en vez de tragarse el fallo. Antes era `Unit` y
     * cada error moría en un `Log.e`: ese camino se quedaba sin aviso al cajero, sin reintento
     * y sin telemetría — o sea, EXACTAMENTE el fallo silencioso que este trabajo existe para
     * matar, conservado en la rama que nadie miró (P1 #10 de la auditoría de Codex, 2026-09-07).
     *
     * 🔴 Devuelve TAMBIÉN cuántas se intentaron. Con sólo la lista de fallidas, «ninguna falló»
     * y «no había ninguna impresora que intentarlo» se ven idénticos, y el despachador cantaba
     * `Salio` sin haber mandado un solo byte (P2 #14 de la 2ª auditoría de Codex, 2026-09-07).
     */
    suspend fun autoPrintKitchenTicket(ticket: KitchenTicketData): ResultadoLegado {
        val fallidas = mutableListOf<String>()
        val candidatas = _savedPrinters.value
            .filter { it.isEnabled && it.autoPrintKitchenTickets && it.hasRole(PrinterRole.KITCHEN) }
        candidatas
            .forEach { printer ->
                try {
                    repeat(printer.numberOfCopies) {
                        printKitchenTicket(ticket, printer)
                    }
                    Log.d(TAG, "Auto-printed kitchen ticket on ${printer.name}")
                } catch (e: Exception) {
                    fallidas += printer.name
                    Log.e(TAG, "Auto-print failed on ${printer.name}: ${e.message}")
                }
            }
        return ResultadoLegado(intentadas = candidatas.size, fallidas = fallidas)
    }

    // MARK: - Discovery

    fun startDiscovery() {
        _isDiscovering.value = true
        _discoveredPrinters.value = emptyList()
        addInternalPrinter()
        startUsbDiscovery()
        startNetworkDiscovery()
        startTicketeraDiscovery()
        startBluetoothDiscovery()

        // Auto-stop after a window: mDNS browsing never completes by itself, so
        // without this the "Buscando..." state sticks forever. Results already
        // found stay listed.
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = serviceScope.launch {
            delay(DISCOVERY_WINDOW_MS)
            stopDiscovery()
        }
    }

    /**
     * La impresora integrada no se "descubre": o el equipo la trae o no. Se
     * ofrece de entrada, sin esperar la ventana de búsqueda — antes el POS con
     * impresora incluida terminaba la búsqueda sin resultados y parecía descompuesta.
     */
    private fun addInternalPrinter() {
        innerPrinter.bind()
        serviceScope.launch {
            // El bind es ASÍNCRONO: preguntar de inmediato siempre da false y la
            // búsqueda volvería a salir vacía. Se espera dentro de la ventana de
            // búsqueda; si no responde, este equipo simplemente no la trae.
            repeat(BIND_WAIT_TRIES) {
                // hasPhysicalPrinter, no isAvailable: el bind tiene éxito
                // también en los Sunmi SIN impresora (T3), y ofrecerla ahí
                // manda las comandas a un destino que no existe.
                if (innerPrinter.hasPhysicalPrinter) {
                    val internal = DiscoveredPrinter(
                        id = "internal",
                        name = "Impresora integrada",
                        connectionType = PrinterConnectionType.INTERNAL,
                        address = "internal",
                        paperWidthMm = innerPrinter.paperWidthMm,
                    )
                    val current = _discoveredPrinters.value.toMutableList()
                    if (current.none { it.id == internal.id }) {
                        current.add(0, internal)
                        _discoveredPrinters.value = current
                    }
                    return@launch
                }
                delay(BIND_WAIT_STEP_MS)
            }
        }
    }

    /** USB is synchronous enumeration — attached printers appear instantly (Square-style). */
    private fun startUsbDiscovery() {
        try {
            val usbFound = usbPrinters.discoverPrinters()
            if (usbFound.isEmpty()) return
            val current = _discoveredPrinters.value.toMutableList()
            usbFound.forEach { printer ->
                if (current.none { it.id == printer.id }) current.add(printer)
            }
            _discoveredPrinters.value = current
        } catch (e: Exception) {
            Log.e(TAG, "USB discovery error: ${e.message}")
        }
    }

    /**
     * Las ticketeras genéricas (Xprinter, 3nStar, Rongta…) casi nunca se anuncian por mDNS:
     * por eso la de cocina de Testarudo no aparecía en «Impresoras disponibles» y había que
     * teclear su IP. Aquí se barre la red del local y cada ticketera que contesta se ofrece
     * como «Ticketera de red». No toca las guardadas: pueden estar imprimiendo.
     */
    private fun startTicketeraDiscovery() {
        serviceScope.launch {
            val guardadas = _savedPrinters.value
                .filter { it.connectionTypeEnum == PrinterConnectionType.WIFI }
                .map { it.address }
                .toSet()
            val ticketeras = runCatching { buscador.ticketerasEnLaRed(DEFAULT_PORT, noTocar = guardadas) }
                .getOrDefault(emptyList())
            ticketeras.forEach { ip ->
                val encontrada = DiscoveredPrinter(
                    id = "ticketera-$ip",
                    name = "Ticketera de red",
                    connectionType = PrinterConnectionType.WIFI,
                    address = ip,
                    port = DEFAULT_PORT,
                )
                mergeResolvedWifiPrinter(_discoveredPrinters.value, encontrada)?.let { _discoveredPrinters.value = it }
            }
        }
    }

    fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        _isDiscovering.value = false
        stopNetworkDiscovery()
    }

    @Suppress("DEPRECATION")
    private fun startNetworkDiscovery() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            stopNetworkDiscovery()

            val serviceTypes = listOf("_printer._tcp", "_pdl-datastream._tcp", "_ipp._tcp")
            serviceTypes.forEach { serviceType ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(startedType: String) {
                        Log.d(TAG, "Network discovery started: $startedType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Found printer: ${serviceInfo.serviceName}")
                        // EN SERIE (ver resolveMutex): en paralelo, todos menos
                        // uno mueren con FAILURE_ALREADY_ACTIVE y se pierden.
                        serviceScope.launch {
                            resolveMutex.withLock { resolveOne(serviceInfo) }
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Printer lost: ${serviceInfo.serviceName}")
                    }

                    override fun onDiscoveryStopped(stoppedType: String) {
                        Log.d(TAG, "Network discovery stopped: $stoppedType")
                    }

                    override fun onStartDiscoveryFailed(failedType: String, errorCode: Int) {
                        Log.e(TAG, "Network discovery start failed ($failedType): $errorCode")
                    }

                    override fun onStopDiscoveryFailed(failedType: String, errorCode: Int) {
                        Log.e(TAG, "Network discovery stop failed ($failedType): $errorCode")
                    }
                }
                discoveryListeners.add(listener)
                nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network discovery error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return
            if (!hasBluetoothConnectPermission()) {
                Log.w(TAG, "Skipping Bluetooth discovery: missing BLUETOOTH_CONNECT permission")
                return
            }

            if (!adapter.isEnabled) return

            // Add bonded (paired) devices
            val bondedDevices = adapter.bondedDevices ?: return
            val current = _discoveredPrinters.value.toMutableList()

            for (device in bondedDevices) {
                // Filter for likely printer devices (printer major class = 0x0600)
                val majorClass = device.bluetoothClass?.majorDeviceClass
                val isPrinterClass = majorClass == 0x0600
                val looksLikePrinter = device.name?.lowercase()?.let {
                    it.contains("printer") || it.contains("star") || it.contains("epson") ||
                        it.contains("tm-") || it.contains("tsp") || it.contains("sp7")
                } ?: false

                // La impresora interna de Sunmi TAMBIÉN se anuncia por Bluetooth
                // ("InnerPrinter"). En una T3 Pro —que no trae cabezal— seguía
                // apareciendo aquí aunque el AIDL ya diga que no existe, y hasta
                // quedaba configurada como impresora de recibos mostrando
                // "Conectada": los tickets se iban a la nada. Cerrar sólo la
                // puerta AIDL no bastaba; es el MISMO hardware por otra vía.
                if (isSunmiInner(device.name) && !innerPrinter.hasPhysicalPrinter) {
                    Log.i(TAG, "Omito ${device.name} por BT: este equipo no trae impresora integrada")
                    continue
                }

                if (isPrinterClass || looksLikePrinter) {
                    val printer = DiscoveredPrinter(
                        id = "bt_${device.address}",
                        name = device.name ?: "Impresora Bluetooth",
                        connectionType = PrinterConnectionType.BLUETOOTH,
                        address = device.address,
                    )
                    if (current.none { it.id == printer.id }) {
                        current.add(printer)
                    }
                }
            }

            _discoveredPrinters.value = current
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth discovery error: ${e.message}")
        }
    }

    /**
     * El dispositivo Bluetooth que ES la impresora interna de Sunmi. Se
     * identifica por NOMBRE, no por MAC: la MAC (00:11:22:33:44:55) es un
     * patrón de ejemplo que Sunmi reutiliza y podría cambiar entre modelos.
     */
    private fun isSunmiInner(name: String?): Boolean =
        name?.replace(" ", "")?.equals("innerprinter", ignoreCase = true) == true

    /**
     * Un resolve, esperando su turno. Suspende hasta que el SO responde, así
     * el `withLock` de arriba garantiza que nunca hay dos a la vez.
     *
     * Reintenta UNA vez ante FAILURE_ALREADY_ACTIVE: aunque serialicemos lo
     * nuestro, el resolve anterior puede seguir liberándose dentro del SO y
     * perder una impresora por 100 ms sería el mismo fallo silencioso.
     */
    private suspend fun resolveOne(serviceInfo: NsdServiceInfo, attempt: Int = 0) {
        val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${si.serviceName}: $errorCode")
                    if (cont.isActive) cont.resume(null) {}
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(si) {}
                }
            }
            runCatching { nsdManager?.resolveService(serviceInfo, listener) }
                .onFailure { if (cont.isActive) cont.resume(null) {} }
        }

        if (resolved == null) {
            if (attempt == 0) {
                delay(RESOLVE_RETRY_MS)
                resolveOne(serviceInfo, attempt + 1)
            }
            return
        }

        val address = resolved.host?.hostAddress ?: return
        val printer = DiscoveredPrinter(
            id = "${resolved.serviceName}_$address",
            name = resolved.serviceName,
            connectionType = PrinterConnectionType.WIFI,
            address = address,
            port = resolved.port,
        )
        // Dedup por DIRECCIÓN + preferencia del puerto crudo: ver
        // [mergeResolvedWifiPrinter]. Seguro sin lock extra porque los resolves
        // están serializados por `resolveMutex`.
        mergeResolvedWifiPrinter(_discoveredPrinters.value, printer)?.let {
            _discoveredPrinters.value = it
        }
        Log.d(TAG, "Resolved printer: ${resolved.serviceName} at $address:${resolved.port}")
    }

    private fun stopNetworkDiscovery() {
        try {
            discoveryListeners.forEach { listener ->
                runCatching { nsdManager?.stopServiceDiscovery(listener) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stop discovery error: ${e.message}")
        }
        discoveryListeners.clear()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    // MARK: - Convenience

    fun getPrinters(role: PrinterRole): List<SavedPrinter> =
        _savedPrinters.value.filter { it.isEnabled && it.hasRole(role) }

    fun getDefaultPrinter(role: PrinterRole): SavedPrinter? =
        getPrinters(role).firstOrNull()

    /**
     * La impresora integrada de ESTE aparato como destino de RUTEO — es a lo que resuelve
     * una estación cuyo printer del dashboard es `POS_INTERNAL` (la comanda sale en el
     * aparato que cobró). Devuelve null si el equipo no trae cabezal físico (T3, tablets
     * genéricas): ahí la estación cae al respaldo KITCHEN de [ComandaPrinter.printComandas].
     *
     * NO es el respaldo plug-and-play de recibos de [selectDefaultPrinter] ("sin inventar
     * rutas de cocina"): aquí la ruta la configuró el NEGOCIO explícitamente en el
     * dashboard, por eso la integrada sí puede cargar comandas.
     */
    suspend fun internalPrinterForRouting(): SavedPrinter? {
        val available = innerPrinter.ensureBound() && innerPrinter.hasPhysicalPrinter
        if (!available) return null
        return SavedPrinter(
            id = "internal",
            name = "Impresora integrada",
            connectionType = PrinterConnectionType.INTERNAL.value,
            address = "internal",
            roles = listOf(PrinterRole.KITCHEN.value),
            paperWidthMm = innerPrinter.paperWidthMm,
        )
    }

    /**
     * Respaldo de hardware para acciones explícitas de recibo/vale. No altera
     * autoPrintReceipt ni asigna la integrada a cocina, bar o etiquetas.
     */
    suspend fun getDefaultPrinterWithHardwareFallback(role: PrinterRole): SavedPrinter? {
        val configured = getPrinters(role)
        if (configured.isNotEmpty() || role != PrinterRole.RECEIPT) {
            return selectDefaultPrinter(
                role = role,
                configured = configured,
                integratedAvailable = false,
                integratedPaperWidthMm = 58,
            )
        }
        val available = innerPrinter.ensureBound() && innerPrinter.hasPhysicalPrinter
        return selectDefaultPrinter(
            role = role,
            configured = configured,
            integratedAvailable = available,
            integratedPaperWidthMm = innerPrinter.paperWidthMm,
        )
    }

    fun hasConfiguredPrinters(): Boolean =
        _savedPrinters.value.isNotEmpty()

    fun hasEnabledPrinters(role: PrinterRole): Boolean =
        getPrinters(role).isNotEmpty()

    // MARK: - Private Helpers

    private fun updateStatus(printerId: String, status: PrinterStatus) {
        val statuses = _printerStatuses.value.toMutableMap()
        statuses[printerId] = status
        _printerStatuses.value = statuses
    }

    /**
     * Sólo marca la hora sobre el registro GUARDADO: la conexión pudo empezar con una copia
     * vieja y, mientras tanto, el cajero cambió el conector o el cajón automático. Guardar la
     * copia entera los revertía en silencio (auditoría de Codex H5). Espejo de iOS.
     */
    private fun updateLastConnected(printer: SavedPrinter) {
        val list = conUltimaConexion(_savedPrinters.value, printer.id, System.currentTimeMillis())
        if (list == _savedPrinters.value) return
        _savedPrinters.value = list
        storage.savePrinters(list)
    }
}

/**
 * Las impresoras guardadas con SÓLO la hora de conexión actualizada en [id]; el resto del
 * registro queda como está guardado. Si [id] ya no existe, la lista no cambia.
 */
internal fun conUltimaConexion(guardadas: List<SavedPrinter>, id: String, ahora: Long): List<SavedPrinter> {
    if (guardadas.none { it.id == id }) return guardadas
    return guardadas.map { if (it.id == id) it.copy(lastConnected = ahora) else it }
}

// MARK: - Storage

private class PrinterStorage(private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("avoqado_printers", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "saved_printers"

    fun loadPrinters(): List<SavedPrinter> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SavedPrinter>>(raw)
        } catch (e: Exception) {
            Log.e("PrinterStorage", "Failed to load printers: ${e.message}")
            emptyList()
        }
    }

    fun savePrinters(printers: List<SavedPrinter>) {
        try {
            val serialized = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SavedPrinter.serializer()),
                printers,
            )
            prefs.edit().putString(key, serialized).apply()
        } catch (e: Exception) {
            Log.e("PrinterStorage", "Failed to save printers: ${e.message}")
        }
    }
}

/**
 * ¿Se puede cerrar la conexión de una impresora en este estado?
 *
 * 🔴 PURA a propósito: la regla que impide cortar una comanda a media escritura tiene que poder
 * probarse sin montar Compose ni congelar una impresión real. Vive fuera de la clase porque
 * `PrinterService` arrastra Context, Bluetooth y USB — nada de eso hace falta para decidir esto.
 *
 * Sólo [PrinterStatus.Printing] retiene la conexión. Todo lo demás —conectada y ociosa, en
 * error, sin comprobar, desconocida— se suelta: dejarla abierta es justo el "teléfono
 * descolgado" que se sospecha detrás del caso de Testarudo.
 */
internal fun puedeSoltarseLaConexion(status: PrinterStatus?): Boolean = status != PrinterStatus.Printing

/**
 * Qué pasó en el camino LEGADO de impresión (venue sin estaciones configuradas).
 *
 * `intentadas == 0` NO es éxito: significa que no había ninguna impresora con rol de cocina y
 * autoimpresión encendida, o sea que la comanda no salió por falta de configuración.
 */
data class ResultadoLegado(val intentadas: Int, val fallidas: List<String>) {
    val salio: Boolean get() = intentadas > 0 && fallidas.isEmpty()
}

/**
 * Un aviso al servidor pendiente de mandar. `previousAddress` es la dirección que el servidor tenía:
 * el servidor sólo acepta el cambio si sigue siendo ésa (nunca pisa una corrección más nueva).
 */
@kotlinx.serialization.Serializable
data class AvisoDeImpresora(
    val venueId: String,
    val previousAddress: String,
    val address: String,
    val stableKey: String? = null,
)

/**
 * Un mapa «id de impresora → valor» guardado en el aparato: mudanzas, identidades y avisos pendientes.
 *
 * En memoria y en disco: se lee del disco UNA vez y cada cambio se escribe enseguida, así que
 * sobrevive a un reinicio sin decodificar el archivo en cada comanda. Incluye las impresoras de la
 * config del SERVIDOR, que no viven en esta tablet.
 */
internal class RegistroEnPrefs<T>(
    context: Context,
    private val json: Json,
    nombre: String,
    valor: kotlinx.serialization.KSerializer<T>,
) {
    private val prefs = context.getSharedPreferences(nombre, Context.MODE_PRIVATE)
    private val serializer = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), valor)
    private var cache: Map<String, T>? = null

    @Synchronized
    fun todas(): Map<String, T> = cache ?: (
        try {
            prefs.getString(KEY, null)?.let { json.decodeFromString(serializer, it) } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        ).also { cache = it }

    @Synchronized
    fun poner(id: String, v: T) = guardar(todas() + (id to v))

    @Synchronized
    fun quitar(id: String) = guardar(todas() - id)

    /** Quita sólo si sigue siendo `esperado` (no borra uno más nuevo que llegó mientras tanto). */
    @Synchronized
    fun quitarSi(id: String, esperado: T) {
        if (todas()[id] == esperado) guardar(todas() - id)
    }

    private fun guardar(nuevo: Map<String, T>) {
        cache = nuevo
        // commit (no apply): queda en disco ANTES de seguir — un aviso o una mudanza no se pierde si
        // el proceso muere enseguida. El archivo es chico.
        prefs.edit().putString(KEY, json.encodeToString(serializer, nuevo)).commit()
    }

    private companion object {
        const val KEY = "v1"
    }
}
