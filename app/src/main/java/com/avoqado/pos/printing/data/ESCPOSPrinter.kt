package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * ESC/POS command generation for thermal printers.
 * Pure Kotlin — no external SDK needed.
 */
class ESCPOSPrinter(
    private val paperWidth: PaperWidth = PaperWidth.MM80,
    /**
     * 🔴 La impresora INTEGRADA de Sunmi arranca en MULTIBYTE (GB18030, chino)
     * — así viene de fábrica, según su manual oficial. En ese modo `ESC t 16`
     * no basta: los bytes Latin-1 que mandamos se interpretan como cabeceras
     * multibyte y la impresora se come TODO lo que sigue. Ese era el ticket en
     * blanco que ni cortaba (el corte también iba después).
     *
     * El arreglo NO es quitar el code page —el 16 (Windows-1252) siempre fue el
     * valor correcto y trae los acentos del español— sino mandar ANTES
     * `FS .` (0x1C 0x2E), que pasa la impresora a modo de un solo byte.
     * Las Epson de red/Bluetooth ya están en single-byte y no lo necesitan.
     */
    private val switchToSingleByteFirst: Boolean = false,
) {
    private val buffer = ByteArrayOutputStream()

    // MARK: - ESC/POS Commands

    companion object {
        // Initialize printer
        val INITIALIZE = byteArrayOf(0x1B, 0x40)

        // Text alignment
        val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
        val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)

        // Text formatting
        val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
        val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
        val UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01)
        val UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)
        val DOUBLE_HEIGHT_ON = byteArrayOf(0x1B, 0x21, 0x10)
        val DOUBLE_WIDTH_ON = byteArrayOf(0x1B, 0x21, 0x20)
        val DOUBLE_HEIGHT_WIDTH_ON = byteArrayOf(0x1B, 0x21, 0x30)
        val NORMAL_SIZE = byteArrayOf(0x1B, 0x21, 0x00)

        // Feed and cut
        val LINE_FEED = byteArrayOf(0x0A)
        val FEED_LINES_5 = byteArrayOf(0x1B, 0x64, 0x05)
        val PARTIAL_CUT = byteArrayOf(0x1D, 0x56, 0x01)
        val FULL_CUT = byteArrayOf(0x1D, 0x56, 0x00)

        // Cash drawer
        val OPEN_CASH_DRAWER = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())

        // Character set (Latin America)
        // ESC t 16 = Windows-1252 (no 858, como decía el comentario viejo).
        // Trae los acentos del español.
        val CODE_PAGE_LATIN1 = byteArrayOf(0x1B, 0x74, 0x10)

        // FS . — pasa a modo de UN SOLO BYTE. Obligatorio en la integrada de
        // Sunmi, que arranca en multibyte GB18030; sin esto el code page de
        // arriba no aplica y se come el ticket entero.
        val SINGLE_BYTE_MODE = byteArrayOf(0x1C, 0x2E)
    }

    // MARK: - Buffer Management

    fun reset() {
        buffer.reset()
        appendCommand(INITIALIZE)
        if (switchToSingleByteFirst) appendCommand(SINGLE_BYTE_MODE)
        appendCommand(CODE_PAGE_LATIN1)
    }

    fun getData(): ByteArray = buffer.toByteArray()

    // MARK: - Command Helpers

    private fun appendCommand(command: ByteArray) {
        buffer.write(command)
    }

    private fun appendText(text: String) {
        // Use Latin-1 for Spanish character support, fallback to UTF-8
        try {
            buffer.write(text.toByteArray(Charsets.ISO_8859_1))
        } catch (_: Exception) {
            buffer.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    // MARK: - Text Formatting

    fun setAlignment(alignment: TextAlignment) {
        when (alignment) {
            TextAlignment.LEFT -> appendCommand(ALIGN_LEFT)
            TextAlignment.CENTER -> appendCommand(ALIGN_CENTER)
            TextAlignment.RIGHT -> appendCommand(ALIGN_RIGHT)
        }
    }

    fun setBold(enabled: Boolean) {
        appendCommand(if (enabled) BOLD_ON else BOLD_OFF)
    }

    fun setUnderline(enabled: Boolean) {
        appendCommand(if (enabled) UNDERLINE_ON else UNDERLINE_OFF)
    }

    fun setDoubleHeight(enabled: Boolean) {
        appendCommand(if (enabled) DOUBLE_HEIGHT_ON else NORMAL_SIZE)
    }

    fun setLargeText(enabled: Boolean) {
        appendCommand(if (enabled) DOUBLE_HEIGHT_WIDTH_ON else NORMAL_SIZE)
    }

    // MARK: - Printing

    fun printLine(text: String = "") {
        appendText(text)
        appendCommand(LINE_FEED)
    }

    fun feedLines(count: Int = 1) {
        repeat(count) { appendCommand(LINE_FEED) }
    }

    fun cut(partial: Boolean = true) {
        appendCommand(FEED_LINES_5)
        appendCommand(if (partial) PARTIAL_CUT else FULL_CUT)
    }

    fun openCashDrawer() {
        appendCommand(OPEN_CASH_DRAWER)
    }

    // MARK: - Formatting Helpers

    fun printDivider(char: Char = '-') {
        printLine(String(CharArray(paperWidth.charsPerLine) { char }))
    }

    fun printDoubleDivider() {
        printDivider('=')
    }

    /** Print two columns (left and right aligned) */
    fun printTwoColumns(left: String, right: String) {
        val maxWidth = paperWidth.charsPerLine
        val rightLen = right.length
        val leftLen = minOf(left.length, maxWidth - rightLen - 1)
        val paddingLen = maxWidth - leftLen - rightLen

        val truncatedLeft = left.take(leftLen)
        val padding = " ".repeat(maxOf(paddingLen, 1))

        printLine("$truncatedLeft$padding$right")
    }

    /** Print three columns (quantity, name, price) */
    fun printThreeColumns(qty: String, name: String, price: String) {
        val maxWidth = paperWidth.charsPerLine
        val qtyWidth = 4
        val priceWidth = 10

        val nameWidth = maxWidth - qtyWidth - priceWidth
        val truncatedName = name.take(nameWidth - 1)

        val qtyPadded = qty.padEnd(qtyWidth)
        val namePadded = truncatedName.padEnd(nameWidth)
        val pricePadded = price.padStart(priceWidth)

        printLine("$qtyPadded$namePadded$pricePadded")
    }

    // MARK: - Receipt Generation

    fun generateReceipt(receipt: ReceiptData): ByteArray {
        reset()

        // Header
        setAlignment(TextAlignment.CENTER)
        setLargeText(true)
        printLine(receipt.venueName)
        setLargeText(false)

        receipt.venueAddress?.let { printLine(it) }
        receipt.venuePhone?.let { printLine("Tel: $it") }

        printLine()
        printDivider()

        // Order info
        setAlignment(TextAlignment.LEFT)
        printTwoColumns("Orden #:", receipt.orderNumber)
        printTwoColumns("Fecha:", receipt.formattedDate)
        receipt.cashierName?.let { printTwoColumns("Atendio:", it) }
        printTwoColumns("Tipo:", receipt.orderType)

        printDivider()

        // Items header
        setBold(true)
        printThreeColumns("Cant", "Articulo", "Precio")
        setBold(false)
        printDivider()

        // Items
        for (item in receipt.items) {
            printThreeColumns(
                "${item.quantity}",
                item.name,
                item.formattedPrice,
            )
            // Venta por peso: peso × precio/kg bajo el nombre (mismo estilo que los modificadores).
            item.weightSummary?.let { printLine("  $it") }
            item.modifiers?.forEach { modifier ->
                printLine("  + $modifier")
            }
            item.note?.let { printLine("  Nota: $it") }
        }

        printDivider()

        // Totals
        setAlignment(TextAlignment.RIGHT)
        printTwoColumns("Subtotal:", receipt.formattedAmount(receipt.subtotal))

        receipt.discountAmount?.let {
            printTwoColumns("Descuento:", "-${receipt.formattedAmount(it)}")
        }

        printTwoColumns("IVA:", receipt.formattedAmount(receipt.taxAmount))

        receipt.tipAmount?.let {
            printTwoColumns("Propina:", receipt.formattedAmount(it))
        }

        printDivider()
        setBold(true)
        setDoubleHeight(true)
        printTwoColumns("TOTAL:", receipt.formattedAmount(receipt.total))
        setDoubleHeight(false)
        setBold(false)

        // Payment info
        receipt.paymentMethod?.let { method ->
            printLine()
            setAlignment(TextAlignment.LEFT)
            printTwoColumns("Pago:", method)

            receipt.cardLastFour?.let {
                printTwoColumns("Tarjeta:", "**** $it")
            }

            if (receipt.isCashPayment) {
                receipt.cashTendered?.let {
                    printTwoColumns("Recibido:", receipt.formattedAmount(it))
                }
                receipt.changeAmount?.let {
                    if (it > 0) {
                        setBold(true)
                        printTwoColumns("Cambio:", receipt.formattedAmount(it))
                        setBold(false)
                    }
                }
            }
        }

        // Footer
        printLine()
        printDivider()
        setAlignment(TextAlignment.CENTER)
        printLine("Gracias por su compra!")
        printLine()

        receipt.transactionId?.let { printLine("ID: $it") }

        cut()

        return getData()
    }

    // MARK: - Kitchen Ticket Generation

    fun generateKitchenTicket(ticket: KitchenTicketData): ByteArray {
        reset()

        // Priority banner
        if (ticket.priority.displayName.isNotEmpty()) {
            setAlignment(TextAlignment.CENTER)
            setLargeText(true)
            setBold(true)
            printLine(ticket.priority.displayName)
            setBold(false)
            setLargeText(false)
            printLine()
        }

        // Header — PRINT_STATIONS: the station IS the title when per-station routing
        // resolved one ("BARRA", "SIN ESTACIÓN"…). A bar ticket titled "COCINA" is
        // wrong/confusing for staff. Falls back to "COCINA" when there is no station
        // (legacy single-ticket path) so old tickets stay byte-for-byte unchanged.
        setAlignment(TextAlignment.CENTER)
        setLargeText(true)
        printLine(ticket.stationName?.uppercase(Locale("es", "MX")) ?: "COCINA")
        setLargeText(false)

        printDoubleDivider()

        // Order info
        setAlignment(TextAlignment.LEFT)
        setBold(true)
        printTwoColumns("Orden #:", ticket.orderNumber)
        setBold(false)
        printTwoColumns("Hora:", ticket.formattedTime)
        printTwoColumns("Tipo:", ticket.orderType)

        ticket.tableName?.let {
            setDoubleHeight(true)
            printTwoColumns("Mesa:", it)
            setDoubleHeight(false)
        }

        ticket.serverName?.let {
            printTwoColumns("Mesero:", it)
        }

        printDoubleDivider()

        // Items - Large and clear
        setDoubleHeight(true)
        for (item in ticket.items) {
            printLine("${item.quantity}x ${item.name}")
            setDoubleHeight(false)

            item.modifiers?.forEach { modifier ->
                printLine("   -> $modifier")
            }
            item.note?.let {
                setBold(true)
                printLine("   Nota: $it")
                setBold(false)
            }

            setDoubleHeight(true)
        }
        setDoubleHeight(false)

        // General notes
        ticket.notes?.let {
            printDoubleDivider()
            setBold(true)
            printLine("NOTAS:")
            setBold(false)
            printLine(it)
        }

        printDoubleDivider()

        // Timestamp (in venue timezone)
        setAlignment(TextAlignment.CENTER)
        val ticketTime = ticket.timestamp.toInstant()
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm:ss",
                    Locale("es", "MX"),
                ),
            )
        printLine(ticketTime)

        cut()

        return getData()
    }

    // MARK: - Test Print

    fun generateTestPrint(): ByteArray {
        reset()

        setAlignment(TextAlignment.CENTER)
        setLargeText(true)
        printLine("PRUEBA DE IMPRESION")
        setLargeText(false)
        printLine()

        printDivider()

        setAlignment(TextAlignment.LEFT)
        printLine("Ancho de papel: ${paperWidth.displayName}")
        printLine("Caracteres por linea: ${paperWidth.charsPerLine}")
        printLine()

        printLine("Texto normal")
        // Chequeo de acentos: en la integrada no mandamos el comando de code
        // page, así que esta línea es la que dice si el español sale bien.
        printLine("Acentos: aeiou AEIOU nN - áéíóú ÁÉÍÓÚ ñÑ ¿? °")
        setBold(true)
        printLine("Texto en negritas")
        setBold(false)
        setDoubleHeight(true)
        printLine("Texto grande")
        setDoubleHeight(false)

        printLine()
        printDivider()

        printTwoColumns("Columna izq", "Columna der")
        printThreeColumns("1", "Articulo de prueba", "$99.00")

        printLine()
        printDivider()

        setAlignment(TextAlignment.CENTER)
        printLine("Impresora configurada!")
        printLine()

        val nowFormatted = java.time.ZonedDateTime.now(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm:ss",
                    Locale("es", "MX"),
                ),
            )
        printLine(nowFormatted)

        cut()

        return getData()
    }

    enum class TextAlignment { LEFT, CENTER, RIGHT }
}
