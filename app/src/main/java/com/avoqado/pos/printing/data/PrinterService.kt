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
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Connection timeout in milliseconds */
private const val CONNECTION_TIMEOUT_MS = 10_000L

@Singleton
class PrinterService @Inject constructor(
    @ApplicationContext private val context: Context,
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
    private val storage = PrinterStorage(context)
    private var nsdManager: NsdManager? = null
    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadSavedPrinters()
    }

    // MARK: - Printer Management

    fun loadSavedPrinters() {
        _savedPrinters.value = storage.loadPrinters()
        val statuses = mutableMapOf<String, PrinterStatus>()
        _savedPrinters.value.forEach { statuses[it.id] = PrinterStatus.Disconnected }
        _printerStatuses.value = statuses
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
        updateStatus(printer.id, PrinterStatus.Disconnected)
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
                PrinterConnectionType.BLUETOOTH -> connectBluetooth(printer)
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
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting: ${e.message}")
        }
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
        } catch (e: Exception) {
            socket.close()
            throw PrinterException.ConnectionFailed(e.message ?: "No se pudo conectar a ${printer.address}:$port")
        }
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
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw PrinterException.ConnectionFailed(e.message ?: "No se pudo conectar por Bluetooth")
        }
    }

    // MARK: - Printing

    suspend fun printReceipt(receipt: ReceiptData, printer: SavedPrinter) {
        val escpos = ESCPOSPrinter(paperWidth = printer.paperWidth)
        val data = escpos.generateReceipt(receipt)
        sendData(data, printer)
    }

    suspend fun printKitchenTicket(ticket: KitchenTicketData, printer: SavedPrinter) {
        val escpos = ESCPOSPrinter(paperWidth = printer.paperWidth)
        val data = escpos.generateKitchenTicket(ticket)
        sendData(data, printer)
    }

    suspend fun printTestPage(printer: SavedPrinter) {
        val escpos = ESCPOSPrinter(paperWidth = printer.paperWidth)
        val data = escpos.generateTestPrint()
        sendData(data, printer)
    }

    suspend fun openCashDrawer(printer: SavedPrinter) {
        val escpos = ESCPOSPrinter(paperWidth = printer.paperWidth)
        escpos.reset()
        escpos.openCashDrawer()
        sendData(escpos.getData(), printer)
    }

    private suspend fun sendData(data: ByteArray, printer: SavedPrinter) {
        // Auto-connect if not connected
        val status = _printerStatuses.value[printer.id] ?: PrinterStatus.Disconnected
        if (!status.isConnected) {
            connect(printer)
        }

        updateStatus(printer.id, PrinterStatus.Printing)

        try {
            when (printer.connectionTypeEnum) {
                PrinterConnectionType.WIFI -> sendDataWiFi(data, printer)
                PrinterConnectionType.BLUETOOTH -> sendDataBluetooth(data, printer)
            }
            updateStatus(printer.id, PrinterStatus.Connected)
        } catch (e: Exception) {
            updateStatus(printer.id, PrinterStatus.Error(e.message ?: "Error al imprimir"))
            throw PrinterException.PrintFailed(e.message ?: "Error desconocido")
        }
    }

    private suspend fun sendDataWiFi(data: ByteArray, printer: SavedPrinter) = withContext(Dispatchers.IO) {
        val socket = wifiConnections[printer.id] ?: throw PrinterException.NotConnected()
        val output: OutputStream = socket.getOutputStream()
        output.write(data)
        output.flush()
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
    suspend fun manualPrintReceipt(receipt: ReceiptData): Int {
        val eligible = _savedPrinters.value
            .filter { it.isEnabled && it.hasRole(PrinterRole.RECEIPT) }
        var successCount = 0
        eligible.forEach { printer ->
            try {
                printReceipt(receipt, printer)
                successCount++
                Log.d(TAG, "Manual reprint succeeded on ${printer.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Manual reprint failed on ${printer.name}: ${e.message}")
            }
        }
        return successCount
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
        startNetworkDiscovery()
        startBluetoothDiscovery()
    }

    fun stopDiscovery() {
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
                        // Resolve service to get IP
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Resolve failed for ${si.serviceName}: $errorCode")
                            }

                            override fun onServiceResolved(si: NsdServiceInfo) {
                                val address = si.host?.hostAddress ?: return
                                val port = si.port
                                val printer = DiscoveredPrinter(
                                    id = "${si.serviceName}_${address}",
                                    name = si.serviceName,
                                    connectionType = PrinterConnectionType.WIFI,
                                    address = address,
                                    port = port,
                                )
                                val current = _discoveredPrinters.value.toMutableList()
                                if (current.none { it.id == printer.id }) {
                                    current.add(printer)
                                    _discoveredPrinters.value = current
                                }
                                Log.d(TAG, "Resolved printer: ${si.serviceName} at $address:$port")
                            }
                        })
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
