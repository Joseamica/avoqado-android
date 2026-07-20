package com.avoqado.pos.printing.data.model

import kotlinx.serialization.Serializable
import java.util.Date
import java.util.Locale
import java.util.UUID

// MARK: - Printer Connection Type

enum class PrinterConnectionType(val value: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    USB("usb"),
    ;

    val displayName: String
        get() = when (this) {
            WIFI -> "WiFi"
            BLUETOOTH -> "Bluetooth"
            USB -> "USB"
        }
}

// MARK: - Printer Role

enum class PrinterRole(val value: String) {
    RECEIPT("receipt"),
    KITCHEN("kitchen"),
    BAR("bar"),
    LABEL("label"),
    ;

    val displayName: String
        get() = when (this) {
            RECEIPT -> "Recibos"
            KITCHEN -> "Cocina"
            BAR -> "Bar"
            LABEL -> "Etiquetas"
        }
}

// MARK: - Paper Width

enum class PaperWidth(val mm: Int) {
    MM58(58),
    MM80(80),
    ;

    val displayName: String
        get() = "${mm}mm"

    val charsPerLine: Int
        get() = when (this) {
            MM58 -> 32
            MM80 -> 48
        }
}

// MARK: - Saved Printer (persisted to SharedPreferences)

@Serializable
data class SavedPrinter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: String, // "wifi", "bluetooth" or "usb"
    val address: String, // IP address, Bluetooth MAC, or "usb:VID:PID"
    val port: Int? = null, // Only for WiFi (typically 9100)
    val roles: List<String> = listOf("receipt"),
    val paperWidthMm: Int = 80,
    val isEnabled: Boolean = true,
    val autoPrintReceipts: Boolean = false,
    val autoPrintKitchenTickets: Boolean = false,
    val numberOfCopies: Int = 1,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastConnected: Long? = null,
) {
    val connectionTypeEnum: PrinterConnectionType
        get() = when (connectionType) {
            "bluetooth" -> PrinterConnectionType.BLUETOOTH
            "usb" -> PrinterConnectionType.USB
            else -> PrinterConnectionType.WIFI
        }

    val paperWidth: PaperWidth
        get() = if (paperWidthMm == 58) PaperWidth.MM58 else PaperWidth.MM80

    val roleEnums: List<PrinterRole>
        get() = roles.mapNotNull { role ->
            PrinterRole.entries.firstOrNull { it.value == role }
        }

    val displayAddress: String
        get() = when {
            connectionTypeEnum == PrinterConnectionType.USB -> "USB"
            port != null -> "$address:$port"
            else -> address
        }

    fun hasRole(role: PrinterRole): Boolean = roles.contains(role.value)
}

// MARK: - Discovered Printer (found during scan)

data class DiscoveredPrinter(
    val id: String,
    val name: String,
    val connectionType: PrinterConnectionType,
    val address: String,
    val port: Int? = null,
    val signalStrength: Int? = null, // Bluetooth RSSI
) {
    val displayName: String
        get() = name.ifEmpty { address }

    fun toSavedPrinter(): SavedPrinter = SavedPrinter(
        name = displayName,
        connectionType = connectionType.value,
        address = address,
        port = port,
    )
}

// MARK: - Printer Status

sealed class PrinterStatus {
    data object Disconnected : PrinterStatus()
    data object Connecting : PrinterStatus()
    data object Connected : PrinterStatus()
    data object Printing : PrinterStatus()
    data class Error(val message: String) : PrinterStatus()

    val displayName: String
        get() = when (this) {
            Disconnected -> "Desconectada"
            Connecting -> "Conectando..."
            Connected -> "Conectada"
            Printing -> "Imprimiendo..."
            is Error -> "Error: $message"
        }

    val isConnected: Boolean
        get() = this == Connected || this == Printing
}

// MARK: - Printer Error

sealed class PrinterException(message: String) : Exception(message) {
    class NotConnected : PrinterException("Impresora no conectada")
    class ConnectionFailed(reason: String) : PrinterException("Error de conexion: $reason")
    class PrintFailed(reason: String) : PrinterException("Error de impresion: $reason")
    class Timeout : PrinterException("Tiempo de espera agotado")
    class PrinterNotFound : PrinterException("Impresora no encontrada")
    class BluetoothUnavailable : PrinterException("Bluetooth no disponible")
    class NetworkUnavailable : PrinterException("Red no disponible")
}

// MARK: - Receipt Data

data class ReceiptData(
    val orderNumber: String,
    val orderType: String, // "En tienda", "Para llevar", etc.
    val items: List<ReceiptItem>,
    val subtotal: Int, // cents
    val taxAmount: Int, // cents
    val tipAmount: Int? = null, // cents
    val discountAmount: Int? = null, // cents
    val total: Int, // cents
    val paymentMethod: String? = null,
    val cardLastFour: String? = null,
    val venueName: String,
    val venueAddress: String? = null,
    val venuePhone: String? = null,
    val cashierName: String? = null,
    val customerName: String? = null,
    val date: Date = Date(),
    val transactionId: String? = null,
    val cashTendered: Int? = null, // cents
    val changeAmount: Int? = null, // cents
) {
    val isCashPayment: Boolean
        get() = paymentMethod == "Efectivo"

    val formattedDate: String
        get() = date.toInstant()
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm",
                    Locale("es", "MX"),
                ),
            )

    fun formattedAmount(cents: Int): String {
        val amount = cents / 100.0
        return String.format(Locale.US, "$%.2f", amount)
    }
}

// MARK: - Receipt Item

data class ReceiptItem(
    val name: String,
    val quantity: Int,
    val unitPrice: Int, // cents
    val totalPrice: Int, // cents
    val modifiers: List<String>? = null,
    val note: String? = null,
    val isCortesia: Boolean = false,
    /** Venta por peso: "0.435 kg × $420.00/kg" — se imprime bajo el nombre. Null si no hay peso. */
    val weightSummary: String? = null,
) {
    val formattedPrice: String
        get() = if (isCortesia) {
            "CORTESIA"
        } else {
            val amount = totalPrice / 100.0
            String.format(Locale.US, "$%.2f", amount)
        }
}

// MARK: - Kitchen Ticket Data

data class KitchenTicketData(
    val orderNumber: String,
    val orderType: String,
    val tableName: String? = null,
    val items: List<KitchenItem>,
    val notes: String? = null,
    val priority: KitchenPriority = KitchenPriority.NORMAL,
    val timestamp: Date = Date(),
    val serverName: String? = null,
    /** PRINT_STATIONS — optional station label ("Cocina", "Barra", "SIN ESTACIÓN")
     *  shown in the ticket header when per-station routing is active. Additive:
     *  null (the default) reproduces today's ticket exactly. */
    val stationName: String? = null,
) {
    val formattedTime: String
        get() = timestamp.toInstant()
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale("es", "MX")),
            )
}

// MARK: - Kitchen Item

data class KitchenItem(
    val name: String,
    val quantity: Int,
    val modifiers: List<String>? = null,
    val note: String? = null,
    val category: String? = null,
)

// MARK: - Kitchen Priority

enum class KitchenPriority {
    NORMAL,
    RUSH,
    VIP,
    ;

    val displayName: String
        get() = when (this) {
            NORMAL -> ""
            RUSH -> "*** URGENTE ***"
            VIP -> "*** VIP ***"
        }
}
