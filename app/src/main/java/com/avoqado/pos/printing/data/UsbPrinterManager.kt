package com.avoqado.pos.printing.data

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "UsbPrinterManager"

/** Custom action for the USB permission reply broadcast (scoped to our package). */
private const val ACTION_USB_PERMISSION = "com.avoqado.pos.USB_PRINTER_PERMISSION"

/** Epson's USB vendor id (0x04B8) — TM-m30III, TM-T20, TM-T88, etc. */
internal const val EPSON_VENDOR_ID = 1208

/** USB interface class 7 = Printer (bidirectional/unidirectional ESC/POS). */
private const val USB_CLASS_PRINTER = UsbConstants.USB_CLASS_PRINTER

/** bulkTransfer historically caps around 16KB per call — chunk writes at this size. */
private const val USB_CHUNK_SIZE = 16_384

/** Per-chunk write timeout. Thermal printers buffer fast; 10s is generous. */
private const val USB_WRITE_TIMEOUT_MS = 10_000

/**
 * Pure predicate for "does this USB device look like a receipt printer?".
 * Matches either an interface of USB class 7 (printer) or Epson's vendor id
 * (some Epson models expose a vendor-specific class instead of class 7).
 * Top-level `internal` so it is unit-testable without USB hardware, matching
 * the [shouldReconnect] pattern in PrinterService.
 */
internal fun isLikelyUsbPrinter(vendorId: Int, interfaceClasses: List<Int>): Boolean =
    vendorId == EPSON_VENDOR_ID || interfaceClasses.contains(USB_CLASS_PRINTER)

/**
 * Stable identity for a USB printer, persisted as [SavedPrinter.address].
 * vendorId:productId survives replugging and port changes (unlike the OS
 * device name /dev/bus/usb/xxx/yyy). Two identical printer models on one
 * tablet is out of scope for v1 (same as Square's single-USB-port setups).
 */
internal fun usbAddress(vendorId: Int, productId: Int): String = "usb:$vendorId:$productId"

/** Parses "usb:VID:PID" back to (vendorId, productId), or null if malformed. */
internal fun parseUsbAddress(address: String): Pair<Int, Int>? {
    val parts = address.split(":")
    if (parts.size != 3 || parts[0] != "usb") return null
    val vid = parts[1].toIntOrNull() ?: return null
    val pid = parts[2].toIntOrNull() ?: return null
    return vid to pid
}

/**
 * USB-host transport for ESC/POS receipt printers (e.g. Epson TM-m30III plugged
 * into the tablet by cable — the standard Square counter setup).
 *
 * Responsibilities:
 * - enumerate attached USB printers ([discoverPrinters]) for the discovery sheet,
 * - the system permission dialog flow ([ensurePermission], the "¿Deseas permitir
 *   que Avoqado acceda a TM-m30III?" prompt),
 * - open/claim the printer interface and write raw ESC/POS bytes to its bulk-OUT
 *   endpoint ([open]/[write]/[close]).
 *
 * PrinterService owns statuses/caching and calls into this class; this class owns
 * nothing but USB. The ESC/POS byte generation (ESCPOSPrinter) is transport
 * agnostic and reused as-is.
 */
internal class UsbPrinterManager(private val context: Context) {

    /** An opened, claimed USB printer ready for bulk writes. */
    private class OpenUsbPrinter(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val bulkOut: UsbEndpoint,
    )

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** Open connections keyed by SavedPrinter.id (parallel to wifi/bt caches in PrinterService). */
    private val openPrinters = ConcurrentHashMap<String, OpenUsbPrinter>()

    // MARK: - Discovery

    /** All currently-attached USB devices that look like printers. */
    fun discoverPrinters(): List<DiscoveredPrinter> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { device ->
                isLikelyUsbPrinter(device.vendorId, device.interfaceClasses())
            }
            .map { device ->
                DiscoveredPrinter(
                    id = "usb_${device.vendorId}_${device.productId}",
                    name = device.productName ?: "Impresora USB",
                    connectionType = PrinterConnectionType.USB,
                    address = usbAddress(device.vendorId, device.productId),
                )
            }
    }

    /** The attached UsbDevice matching a saved "usb:VID:PID" address, or null if unplugged. */
    fun findDevice(address: String): UsbDevice? {
        val (vid, pid) = parseUsbAddress(address) ?: return null
        return usbManager?.deviceList?.values?.firstOrNull {
            it.vendorId == vid && it.productId == pid
        }
    }

    // MARK: - Permission

    /**
     * Suspends until the user answers the system USB permission dialog (or returns
     * immediately if already granted). Launching the app from the OS "device
     * attached" prompt also grants permission implicitly, so in the Square-style
     * flow this usually resolves without showing anything.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag", "MutableImplicitPendingIntent")
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission ${if (granted) "granted" else "denied"} for ${device.deviceName}")
                    if (continuation.isActive) continuation.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }

            // setPackage keeps the reply broadcast private to us; FLAG_MUTABLE is
            // required so the system can attach EXTRA_PERMISSION_GRANTED (API 31+).
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            manager.requestPermission(device, pendingIntent)

            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    // MARK: - Connection

    /**
     * Opens and claims the printer interface for a saved printer id + address.
     * Throws [PrinterException] with a user-facing (Spanish) message on failure.
     */
    suspend fun open(printerId: String, address: String) {
        // Reuse an already-open connection (parallel to the wifi/bt socket caches).
        if (isOpen(printerId)) return
        close(printerId)

        val device = findDevice(address)
            ?: throw PrinterException.ConnectionFailed("Impresora USB no conectada. Revisa el cable.")

        if (!ensurePermission(device)) {
            throw PrinterException.ConnectionFailed("Permiso USB denegado para ${device.productName ?: "la impresora"}")
        }

        val manager = usbManager
            ?: throw PrinterException.ConnectionFailed("USB no disponible en este dispositivo")

        val usbInterface = device.printerInterface()
            ?: throw PrinterException.ConnectionFailed("El dispositivo USB no expone una interfaz de impresora")

        val bulkOut = usbInterface.bulkOutEndpoint()
            ?: throw PrinterException.ConnectionFailed("La impresora USB no tiene endpoint de escritura")

        val connection = manager.openDevice(device)
            ?: throw PrinterException.ConnectionFailed("No se pudo abrir el dispositivo USB")

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw PrinterException.ConnectionFailed("No se pudo reclamar la interfaz de la impresora USB")
        }

        openPrinters[printerId] = OpenUsbPrinter(connection, usbInterface, bulkOut)
        Log.d(TAG, "USB printer opened: ${device.productName} ($address)")
    }

    /** True if we hold a claimed connection for this printer AND it is still attached. */
    fun isOpen(printerId: String): Boolean = openPrinters.containsKey(printerId)

    fun close(printerId: String) {
        openPrinters.remove(printerId)?.let { open ->
            runCatching { open.connection.releaseInterface(open.usbInterface) }
            runCatching { open.connection.close() }
        }
    }

    /** Writes raw ESC/POS bytes to the printer's bulk-OUT endpoint, chunked. */
    fun write(printerId: String, data: ByteArray) {
        val open = openPrinters[printerId] ?: throw PrinterException.NotConnected()
        var offset = 0
        while (offset < data.size) {
            val length = minOf(USB_CHUNK_SIZE, data.size - offset)
            val chunk = if (offset == 0 && length == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + length)
            }
            val written = open.connection.bulkTransfer(open.bulkOut, chunk, chunk.size, USB_WRITE_TIMEOUT_MS)
            if (written < 0) {
                // Endpoint rejected the transfer — printer unplugged/off. Drop the
                // stale connection so the next attempt reconnects cleanly.
                close(printerId)
                throw PrinterException.PrintFailed("La impresora USB no aceptó los datos. Revisa el cable.")
            }
            offset += written
        }
    }

    // MARK: - Device helpers

    /** Preferred interface: USB class 7 (printer); fallback: first interface with a bulk-OUT endpoint (vendor-specific Epson modes). */
    private fun UsbDevice.printerInterface(): UsbInterface? {
        val interfaces = (0 until interfaceCount).map { getInterface(it) }
        return interfaces.firstOrNull { it.interfaceClass == USB_CLASS_PRINTER }
            ?: interfaces.firstOrNull { it.bulkOutEndpoint() != null }
    }

    private fun UsbInterface.bulkOutEndpoint(): UsbEndpoint? =
        (0 until endpointCount)
            .map { getEndpoint(it) }
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_OUT
            }
}

/** All interface classes a device exposes (for the printer-likeness predicate). */
private fun UsbDevice.interfaceClasses(): List<Int> =
    (0 until interfaceCount).map { getInterface(it).interfaceClass }
