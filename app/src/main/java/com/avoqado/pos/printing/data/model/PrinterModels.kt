package com.avoqado.pos.printing.data.model

import com.avoqado.pos.printing.data.ESCPOSPrinter
import kotlinx.serialization.Serializable
import java.util.Date
import java.util.Locale
import java.util.UUID

// MARK: - Printer Connection Type

enum class PrinterConnectionType(val value: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    USB("usb"),

    /** Impresora térmica soldada al POS (Sunmi). No es un periférico buscable. */
    INTERNAL("internal"),
    ;

    val displayName: String
        get() = when (this) {
            WIFI -> "WiFi"
            BLUETOOTH -> "Bluetooth"
            USB -> "USB"
            INTERNAL -> "Integrada"
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

    /**
     * Puntos IMPRIMIBLES a 203 dpi. No es `mm × 8`: el rollo de 58 mm imprime
     * sobre 48 mm (384 puntos) y el de 80 mm sobre 72 mm (576) — el resto se lo
     * come el mecanismo. Medir contra el ancho del papel en lugar del área
     * imprimible es justo lo que hace que un código de barras salga cortado del
     * lado derecho, y un código cortado NO se lee: la pistola no marca error,
     * simplemente no pita y el cajero no sabe por qué.
     */
    val dots: Int
        get() = when (this) {
            MM58 -> 384
            MM80 -> 576
        }

    /**
     * Columnas de aire que sobran a CADA lado si el contenido se centra en el
     * rollo, en vez de pegarse a donde arranca el papel.
     *
     * El rollo de 58 mm mide 464 puntos y el contenido ocupa 384: sobran 80,
     * o sea 40 por lado, que son ~3 columnas. Medido en papel el 2026-08-10:
     * con el corrimiento pelón (6) el ticket queda a 2.5 mm del borde izquierdo
     * y 7.5 mm del derecho — cabe, pero se ve mal puesto. Con 6+3 queda parejo.
     *
     * Sirve para que la página de prueba diga QUÉ número capturar, en vez de
     * dejar que cada quien lo tantee.
     */
    val centeringSlackChars: Int
        get() = ((mm * DOTS_PER_MM - dots) / 2) / ESCPOSPrinter.CHAR_WIDTH_DOTS

    companion object {
        /** 203 dpi = 8 puntos por milímetro. */
        const val DOTS_PER_MM = 8
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
    /**
     * Corrimiento a la derecha en COLUMNAS, para `GS L`. Ver
     * [com.avoqado.pos.printing.data.ESCPOSPrinter.leftMarginChars].
     *
     * Tiene default para que las impresoras ya guardadas se deserialicen sin
     * romperse: quien no lo tenga lee 0, que es el comportamiento de siempre.
     */
    val leftMarginChars: Int = 0,
    val isEnabled: Boolean = true,
    val autoPrintReceipts: Boolean = false,
    val autoPrintKitchenTickets: Boolean = false,
    // Abrir el cajón de dinero automáticamente al COBRAR EN EFECTIVO (conducta
    // estándar de POS). Prendido por default; se apaga desde la config de la
    // impresora de recibos. Solo aplica a la impresora con rol RECEIPT.
    val autoOpenCashDrawer: Boolean = true,
    val numberOfCopies: Int = 1,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastConnected: Long? = null,
) {
    val connectionTypeEnum: PrinterConnectionType
        get() = when (connectionType) {
            "bluetooth" -> PrinterConnectionType.BLUETOOTH
            "usb" -> PrinterConnectionType.USB
            "internal" -> PrinterConnectionType.INTERNAL
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
            // "internal" es un identificador interno, no una dirección que le
            // sirva a nadie: va soldada al equipo.
            connectionTypeEnum == PrinterConnectionType.INTERNAL -> "Impresora integrada"
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
    /** Lo reporta el hardware (impresora integrada); null = usar el default. */
    val paperWidthMm: Int? = null,
) {
    val displayName: String
        get() = name.ifEmpty { address }

    fun toSavedPrinter(): SavedPrinter = SavedPrinter(
        name = displayName,
        connectionType = connectionType.value,
        address = address,
        port = port,
        // 🔴 Adivinar el ancho corta el ticket: 80 mm de ESC/POS en un cabezal
        // de 58 mm sale con las líneas partidas. Se respeta lo que reporta el
        // hardware cuando lo sabe.
        paperWidthMm = paperWidthMm ?: 80,
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
    class ConnectionFailed(reason: String) : PrinterException("Error de conexión: $reason")
    class PrintFailed(reason: String) : PrinterException("Error de impresión: $reason")

    /**
     * La impresora respondió que se quedó SIN PAPEL.
     *
     * 🔴 Existe porque el puerto 9100 es fuego-y-olvido: el socket acepta los
     * bytes aunque no salga nada, así que sin preguntarle a la impresora la app
     * cantaba "Recibo impreso" con el rollo vacío. Encontrado en la T3 con una
     * EPSON TM-m30III el 2026-08-10.
     */
    class OutOfPaper : PrinterException("La impresora no tiene papel")
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
    /** URL del recibo digital para el QR (escanear → recibo, calificar, facturar). */
    val receiptUrl: String? = null,
    /**
     * Código opaco del comprobante pagado que cada área escanea para entregar.
     * Sólo existe en ventas materializadas desde vales; los recibos normales quedan iguales.
     */
    val areaDeliveryCode: String? = null,
    // Encabezado fiscal estilo SoftRestaurant (founder, 2026-09-01). Todos
    // opcionales: un venue sin emisor fiscal imprime el ticket de siempre.
    // Los llena ReceiptBranding en PrinterService — los ViewModels no los tocan.
    /** Razón social del emisor fiscal ("TESTARUDO CAFE S.A.P.I. DE C.V."). */
    val venueLegalName: String? = null,
    /** RFC del emisor fiscal. */
    val venueRfc: String? = null,
    /** CP fiscal (lugar de expedición del CFDI). Se imprime "Lugar de expedición: CP X". */
    val venueLugarExpedicion: String? = null,
    /** Logo del negocio ya rasterizado para el ancho del papel. Null ⇒ solo texto. */
    val venueLogoRaster: MonoRaster? = null,
    /** Isotipo de Avoqado para el pie "Powered by Avoqado". Null ⇒ solo el texto. */
    val poweredByAvoqadoRaster: MonoRaster? = null,
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
    /**
     * Origen operativo de una línea ya preparada por un área, por ejemplo
     * "Cremería · Vale 9016719357". El recibo final lo conserva para que la
     * verificación visual sea posible aun si el área no escanea el código de entrega.
     */
    val areaSourceLabel: String? = null,
    /**
     * COMBOS (founder 2026-08-18, patrón Fudo/Square/Toast) — este renglón ES el
     * nombre del combo y lleva el precio de TODO el combo; debajo van sus
     * componentes. Aditivo: false reproduce el ticket de hoy tal cual.
     */
    val isComboHeader: Boolean = false,
    /** COMBOS — producto que pertenece al combo del renglón de arriba. Se imprime
     *  indentado y SIN precio (su importe ya está en el renglón del combo). */
    val isComboComponent: Boolean = false,
) {
    val formattedPrice: String
        get() = when {
            // 🔴 DINERO: el importe del componente vive en el renglón del combo.
            // Repetirlo aquí haría que las líneas sumaran más que el combo y el
            // ticket no cuadraría consigo mismo delante del cliente.
            isComboComponent -> ""
            isCortesia -> "CORTESIA"
            else -> String.format(Locale.US, "$%.2f", totalPrice / 100.0)
        }
}

// MARK: - Vale de área (AREA_TICKETS)

/**
 * El papel que el ÁREA le da al cliente y que la caja escanea. No es comanda (esa se queda en
 * la estación) ni recibo (ese sale pagado): es el tercer documento, el único que viaja en la mano
 * del cliente.
 *
 * Reusa [ReceiptItem] a propósito — ya sabe pintar una línea de granel con su `weightSummary`, y
 * que el vale y el recibo se vean idénticos es justo lo que hace que el cliente no note que
 * cambiaron de sistema.
 */
data class AreaTicketData(
    /** `9PPNNNNNC` — lo que va en el código de barras y en grande para teclear. */
    val areaTicketCode: String,
    /** "Cremería", "Panadería"… Es el título del vale. */
    val areaName: String,
    val items: List<ReceiptItem>,
    val totalCents: Int,
    val venueName: String? = null,
    val staffName: String? = null,
    val timestamp: Date = Date(),
    /**
     * §5.3 — configurable por venue. Algunos negocios no quieren que el cliente vea el desglose
     * antes de llegar a la caja; el vale sigue siendo escaneable sin precios.
     */
    val showPrices: Boolean = true,
    /**
     * Sale de `FulfillmentArea.fulfillmentMode`. Cambia SOLO el pie del vale, que es lo que le
     * dice al cliente si tiene que volver por su producto o ya se lo llevó.
     */
    val holdsProduct: Boolean = true,
) {
    val formattedTotal: String
        get() = String.format(Locale.US, "$%.2f", totalCents / 100.0)

    val formattedTime: String
        get() = timestamp.toInstant()
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale("es", "MX")))
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
    /**
     * COMBOS (founder 2026-08-18, patrón Fudo: "en la comanda se imprime el nombre
     * del combo y, debajo, cada producto asociado") — este renglón ES el nombre del
     * combo. La cocina no prepara "un combo": prepara los productos de abajo, así
     * que el encabezado se imprime SIN cantidad. Aditivo: false = comanda de hoy.
     */
    val isComboHeader: Boolean = false,
    /** COMBOS — producto que pertenece al combo del renglón de arriba: se imprime
     *  indentado y CON su cantidad (que es lo que la cocina necesita). */
    val isComboComponent: Boolean = false,
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
