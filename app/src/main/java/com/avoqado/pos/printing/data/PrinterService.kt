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
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterException
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.SavedPrinter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/** How long a discovery scan runs before auto-stopping. mDNS browsing never
 *  "finishes" on its own, so without this the UI spinner spins forever. */
private const val DISCOVERY_WINDOW_MS = 12_000L

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
     */
    private fun estadoInicial(printer: SavedPrinter): PrinterStatus =
        if (printer.connectionTypeEnum == PrinterConnectionType.INTERNAL && innerPrinter.hasPhysicalPrinter) {
            PrinterStatus.Connected
        } else {
            PrinterStatus.Disconnected
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

    suspend fun connect(printer: SavedPrinter) {
        updateStatus(printer.id, PrinterStatus.Connecting)
        try {
            when (printer.connectionTypeEnum) {
                PrinterConnectionType.WIFI -> connectWiFi(printer)
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

    fun disconnectAll() {
        _savedPrinters.value.forEach { disconnect(it) }
    }

    private suspend fun connectWiFi(printer: SavedPrinter) = withContext(Dispatchers.IO) {
        val port = printer.port ?: DEFAULT_PORT
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(printer.address, port), CONNECTION_TIMEOUT_MS.toInt())
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
        val escpos = escposFor(printer)
        val data = escpos.generateReceipt(receipt)
        sendData(data, printer)
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
    )

    suspend fun printTestPage(printer: SavedPrinter) {
        val escpos = escposFor(printer)
        val data = escpos.generateTestPrint()
        sendData(data, printer)
    }

    suspend fun openCashDrawer(printer: SavedPrinter) {
        val escpos = escposFor(printer)
        escpos.reset()
        escpos.openCashDrawer()
        sendData(escpos.getData(), printer)
    }

    /**
     * Envía bytes ESC/POS ya armados a una impresora. Espejo de `sendPrintData`
     * de iOS: lo usa quien construye su propio ticket (p. ej. el corte de caja)
     * en vez de pasar por [printReceipt].
     */
    suspend fun sendPrintData(data: ByteArray, printer: SavedPrinter) = sendData(data, printer)

    private suspend fun sendData(data: ByteArray, printer: SavedPrinter) {
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
            connect(printer)
        }

        updateStatus(printer.id, PrinterStatus.Printing)

        try {
            when (printer.connectionTypeEnum) {
                PrinterConnectionType.WIFI -> sendDataWiFi(data, printer)
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

    private suspend fun sendDataWiFi(data: ByteArray, printer: SavedPrinter) = withContext(Dispatchers.IO) {
        val socket = wifiConnections[printer.id] ?: throw PrinterException.NotConnected()

        // 🔴 Preguntar ANTES de escribir: el 9100 es fuego-y-olvido.
        //
        // El socket acepta los bytes aunque el rollo esté vacío, así que sin esta
        // consulta la app cantaba "Recibo impreso" y no salía nada. Encontrado en
        // la T3 con una EPSON TM-m30III el 2026-08-10: el cajero se queda sin
        // ticket y creyendo que sí se imprimió.
        if (isOutOfPaper(socket)) throw PrinterException.OutOfPaper()

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

    suspend fun autoPrintKitchenTicket(ticket: KitchenTicketData) {
        _savedPrinters.value
            .filter { it.isEnabled && it.autoPrintKitchenTickets && it.hasRole(PrinterRole.KITCHEN) }
            .forEach { printer ->
                try {
                    repeat(printer.numberOfCopies) {
                        printKitchenTicket(ticket, printer)
                    }
                    Log.d(TAG, "Auto-printed kitchen ticket on ${printer.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-print failed on ${printer.name}: ${e.message}")
                }
            }
    }

    // MARK: - Discovery

    fun startDiscovery() {
        _isDiscovering.value = true
        _discoveredPrinters.value = emptyList()
        addInternalPrinter()
        startUsbDiscovery()
        startNetworkDiscovery()
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

    private fun updateLastConnected(printer: SavedPrinter) {
        updatePrinter(printer.copy(lastConnected = System.currentTimeMillis()))
    }
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
