package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.AreaTicketData
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
    /**
     * Corrimiento a la DERECHA, en columnas de fuente A, que se aplica con `GS L`.
     *
     * 🔴 Existe porque montar un rollo angosto con adaptadores en un cabezal más
     * ancho NO deja el papel en el origen del cabezal, y nada lo reporta: estas
     * impresoras traen sensor de PRESENCIA de papel, no de ancho ni de posición.
     * Sin el corrimiento el POS escribe a la izquierda de donde empieza el papel
     * y esos caracteres caen sobre el rodillo: el ticket sale mocho. Medido en
     * una Epson con adaptadores de 58 mm, se perdían las 6 primeras columnas de
     * CADA línea alineada a la izquierda, mientras las centradas salían enteras
     * — el `ESC a 1` las centraba sobre los 80 mm del cabezal, que casualmente
     * es donde estaba el rollo.
     *
     * 0 = sin corrimiento, y es el default: impresora nativa de 58 mm, o rollo
     * pegado al origen. Lo calibra quien instala, contando en la regla que
     * imprime [generateTestPrint].
     */
    private val leftMarginChars: Int = 0,
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

        // MARK: - Área de impresión (GS L / GS W)

        /**
         * Ancho de una columna de fuente A, en puntos. Es 12 tanto a 180 como a
         * 203 dpi: la fuente se define en PUNTOS, no en milímetros. Por eso el
         * corrimiento se configura en COLUMNAS y no en mm — el número que se
         * cuenta en la regla de la página de prueba es el mismo que entra al
         * comando, sin conversiones que cambien según el modelo de impresora.
         */
        const val CHAR_WIDTH_DOTS = 12

        /**
         * Tope del corrimiento: es el desperdicio máximo posible, 48 columnas de
         * un cabezal de 80 mm menos las 32 de un rollo de 58 mm. Pedir más
         * empujaría el ticket fuera del papel por el OTRO lado.
         */
        const val MAX_LEFT_MARGIN_CHARS = 16

        /** `GS L` — margen izquierdo, en puntos desde el borde del área imprimible. */
        fun setLeftMargin(dots: Int): ByteArray =
            byteArrayOf(0x1D, 0x4C, (dots and 0xFF).toByte(), ((dots shr 8) and 0xFF).toByte())

        /** `GS W` — ancho del área de impresión, en puntos a partir del margen izquierdo. */
        fun setPrintAreaWidth(dots: Int): ByteArray =
            byteArrayOf(0x1D, 0x57, (dots and 0xFF).toByte(), ((dots shr 8) and 0xFF).toByte())

        // FS . — pasa a modo de UN SOLO BYTE. Obligatorio en la integrada de
        // Sunmi, que arranca en multibyte GB18030; sin esto el code page de
        // arriba no aplica y se come el ticket entero.
        val SINGLE_BYTE_MODE = byteArrayOf(0x1C, 0x2E)

        // MARK: - Códigos de barras 1D — modelo de ancho

        /** ~20 mm a 203 dpi. Un código bajito sólo se lee si la pistola entra derecha. */
        const val DEFAULT_BARCODE_HEIGHT_DOTS = 162

        /** Default de ESC/POS. Con 10 dígitos en CODE128-C cabe en 58 mm. */
        const val DEFAULT_MODULE_WIDTH = 3

        /** `GS w` sólo acepta 2..6 en las Epson y en los clones que las copian. */
        const val MIN_MODULE_WIDTH = 2
        const val MAX_MODULE_WIDTH = 6

        /**
         * Zona muda a CADA lado, en módulos. Es parte del símbolo, no un margen
         * estético: sin ella el decodificador no encuentra dónde empieza la
         * primera barra y falla en silencio.
         */
        const val QUIET_ZONE_MODULES = 10

        /**
         * Razón ancho:angosto que se asume para CODE39. ESC/POS no la fija y
         * cada fabricante usa la suya (2:1, 2.5:1, 3:1). Se toma la MÁS ancha:
         * suponer de menos imprime un código cortado, y un código cortado no se
         * lee — suponer de más sólo lo hace más flaco de lo necesario.
         */
        const val CODE39_WIDE_RATIO = 3

        /** CODE39 sin los `*`: los delimitadores los agrega la impresora sola. */
        private const val CODE39_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%+-./"

        /**
         * Ancho TOTAL del símbolo en módulos, zonas mudas incluidas.
         *
         * CODE128: cada carácter —arranque, datos y verificador— son 11 módulos,
         * y el de paro 13. En modo C un carácter son DOS dígitos, así que los 10
         * del vale son 5 caracteres de datos:
         *
         *     11 (arranque) + 5×11 (datos) + 11 (verificador) + 13 (paro) = 90
         *
         * El verificador lo calcula la impresora: se cuenta para el ancho pero
         * no se manda.
         *
         * CODE39: 9 elementos por carácter (6 angostos + 3 anchos) más un
         * espacio de separación entre caracteres. Los delimitadores `*` los pone
         * la impresora, pero ocupan papel igual — por eso van +2 caracteres.
         */
        fun barcodeWidthInModules(data: String, symbology: BarcodeSymbology): Int {
            val symbolModules = when (symbology) {
                BarcodeSymbology.CODE128_C -> 11 * (1 + data.length / 2 + 1) + 13
                BarcodeSymbology.CODE39 -> {
                    val perChar = 6 + 3 * CODE39_WIDE_RATIO
                    (data.length + 2) * (perChar + 1) - 1
                }
            }
            return symbolModules + 2 * QUIET_ZONE_MODULES
        }

        /**
         * Baja el ancho de módulo hasta que el código quepa en el papel.
         *
         * Un vale de 10 dígitos con `moduleWidth = 4` mide 440 puntos y el rollo
         * de 58 mm imprime 384: la impresora no avisa nada, corta las últimas
         * barras y la pistola nunca pita. Más vale un código flaco que sí se lee
         * que uno holgado que sale mocho.
         *
         * Nunca baja de `MIN_MODULE_WIDTH`. Si ni al mínimo cabe —CODE39 con 10
         * dígitos en 58 mm— imprime igual al mínimo: negarse a imprimir dejaría
         * al cliente sin vale y sin poder pagar, y con el HRI abajo el cajero
         * todavía puede teclear el código a mano.
         */
        fun fittingModuleWidth(
            data: String,
            symbology: BarcodeSymbology,
            requested: Int,
            paper: PaperWidth,
        ): Int {
            val modules = barcodeWidthInModules(data, symbology)
            val start = requested.coerceIn(MIN_MODULE_WIDTH, MAX_MODULE_WIDTH)
            for (width in start downTo MIN_MODULE_WIDTH) {
                if (modules * width <= paper.dots) return width
            }
            return MIN_MODULE_WIDTH
        }

        /**
         * Traduce el payload a los bytes que espera `GS k`, o `null` si no es
         * representable en esa simbología. Nunca devuelve una trama a medias: o
         * sale completa o no sale.
         */
        internal fun encodeBarcodeData(data: String, symbology: BarcodeSymbology): ByteArray? {
            if (data.isEmpty()) return null
            return when (symbology) {
                BarcodeSymbology.CODE128_C -> {
                    // El modo C sólo sabe de dígitos y de a pares. Un payload con
                    // letras o de largo impar significa que alguien cambió el
                    // formato del vale (`9PPNNNNNNC`, 10 dígitos) sin cambiar la
                    // simbología. Se rechaza: forzar la letra a dígito imprimiría
                    // un código que escanea OTRO número — el peor de los fallos,
                    // porque escanea bien y cobra la cuenta equivocada.
                    if (data.length % 2 != 0) return null
                    if (!data.all { it in '0'..'9' }) return null
                    val pairs = ByteArray(data.length / 2) { i ->
                        ((data[2 * i] - '0') * 10 + (data[2 * i + 1] - '0')).toByte()
                    }
                    if (pairs.size + 2 > 255) return null
                    // `{C` (0x7B 0x43) selecciona el modo C. Cada par viaja como
                    // el BYTE CRUDO 0..99, no como texto: "00" es 0x00.
                    byteArrayOf(0x7B, 0x43) + pairs
                }

                BarcodeSymbology.CODE39 -> {
                    if (data.length > 255) return null
                    if (!data.all { it in CODE39_CHARSET }) return null
                    data.toByteArray(Charsets.US_ASCII)
                }
            }
        }
    }

    // MARK: - Buffer Management

    fun reset() {
        buffer.reset()
        appendCommand(INITIALIZE)
        if (switchToSingleByteFirst) appendCommand(SINGLE_BYTE_MODE)
        appendCommand(CODE_PAGE_LATIN1)
        applyPrintArea()
    }

    /**
     * Le dice a la impresora CUÁL es su área de impresión, en vez de dejarla con
     * la de fábrica.
     *
     * Sin esto, elegir 58 mm sólo cambiaba cuántos caracteres arma la app: la
     * impresora seguía creyendo que su papel mide 80 mm. Dos consecuencias, y
     * las dos se veían en el papel:
     *
     * 1. El origen quedaba fuera del rollo angosto → ticket mocho de la
     *    izquierda (lo que arregla [leftMarginChars]).
     * 2. La impresora daba vuelta a la línea a las 48 columnas y no a las 32, así
     *    que una línea larga se salía del papel en vez de partirse.
     *
     * Va DESPUÉS de `ESC @`, que es justo lo que resetea ambos valores: así se
     * fijan siempre desde un estado conocido. Si margen + ancho se pasaran del
     * área real, la impresora recorta sola el ancho (spec de `GS W`), así que
     * pedir de más nunca es un error duro.
     */
    private fun applyPrintArea() {
        val marginDots = leftMarginChars.coerceIn(0, MAX_LEFT_MARGIN_CHARS) * CHAR_WIDTH_DOTS
        appendCommand(setLeftMargin(marginDots))
        appendCommand(setPrintAreaWidth(paperWidth.dots))
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

    /**
     * Imprime un código QR con los comandos NATIVOS de ESC/POS (`GS ( k`,
     * modelo 2). Es lo que soportan las Epson de red/Bluetooth y la mayoría de
     * cabezales térmicos; en la integrada de Sunmi hay que probarlo (si no lo
     * dibuja, se cae a ráster — pero primero lo nativo, que es nítido y liviano).
     *
     * `moduleSize` = tamaño del punto (1–16). 6–8 da un QR cómodo de escanear en
     * 80 mm sin comerse el rollo.
     */
    fun printQr(data: String, moduleSize: Int = 7) {
        val bytes = data.toByteArray(Charsets.ISO_8859_1)
        // Modelo 2
        appendCommand(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        // Tamaño de módulo
        appendCommand(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, moduleSize.coerceIn(1, 16).toByte()))
        // Corrección de errores M (más tolerante a manchas del cabezal que L)
        appendCommand(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31))
        // Cargar datos: pL,pH = longitud + 3
        val len = bytes.size + 3
        appendCommand(byteArrayOf(0x1D, 0x28, 0x6B, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), 0x31, 0x50, 0x30))
        appendCommand(bytes)
        // Imprimir el símbolo del buffer
        appendCommand(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
    }

    // MARK: - Códigos de barras 1D (vale de área)

    /**
     * Simbologías 1D del vale de área. `functionCode` es la `m` del comando
     * `GS k` en su **función B** (`GS k m n d1…dn`), la que lleva la longitud
     * explícita.
     */
    enum class BarcodeSymbology(internal val functionCode: Int) {
        /**
         * CODE128 en **modo C**: empaqueta DOS dígitos por símbolo, así que los
         * 10 del vale ocupan la mitad de papel que en CODE39. Es el default
         * porque la pistola del cliente de Culiacán lo lee — probado contra su
         * hardware el 2026-07-28.
         */
        CODE128_C(73),

        /**
         * CODE39 — la simbología de su sistema actual (los asteriscos del texto
         * legible son su firma). Queda como respaldo configurable por si aparece
         * una pistola vieja que no lea 128. Ojo: con 10 dígitos NO cabe en 58 mm
         * ni al ancho de módulo mínimo (ver `barcodeWidthInModules`); necesita
         * rollo de 80 mm.
         */
        CODE39(69),
    }

    /** `GS H` — dónde imprime la impresora el texto legible (HRI) del código. */
    enum class HriPosition(internal val value: Int) {
        NONE(0),
        ABOVE(1),
        BELOW(2),
        BOTH(3),
    }

    /**
     * Imprime un código de barras 1D con `GS k` NATIVO de ESC/POS — mismo
     * criterio que `printQr`: lo nativo sale nítido, no depende de rasterizar y
     * pesa unos cuantos bytes.
     *
     * Se usa la **función B** (`GS k m n d1…dn`, con longitud explícita) y no la
     * función A (terminada en NUL) porque CODE128 modo C manda el par "00" como
     * un byte `0x00`: con la variante terminada en NUL un código como
     * `9470000013` se cortaría a la mitad y la impresora escupiría el resto como
     * texto suelto.
     *
     * Devuelve `false` **sin escribir un solo byte** cuando el payload no es
     * codificable. NO lanza: aquí el fail-safe no puede ser dejar el ticket a
     * medias, porque sin vale impreso el cliente no puede pagar. El que llama
     * cae a texto plano y el cajero lo teclea:
     *
     * ```
     * if (!printer.printBarcode(codigo)) printer.printLine(codigo)
     * ```
     *
     * No alinea ni alimenta papel: eso lo decide el que arma el vale, igual que
     * con el QR.
     */
    fun printBarcode(
        data: String,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128_C,
        heightDots: Int = DEFAULT_BARCODE_HEIGHT_DOTS,
        moduleWidth: Int = DEFAULT_MODULE_WIDTH,
        hriPosition: HriPosition = HriPosition.BELOW,
    ): Boolean {
        // Codificar ANTES de tocar el buffer. Si se emitieran primero GS h/GS w/
        // GS H y después resultara que el payload no sirve, el ticket se quedaría
        // con tres comandos huérfanos que además cambian el estado de la
        // impresora para todo lo que venga detrás.
        val payload = encodeBarcodeData(data, symbology) ?: return false

        val height = heightDots.coerceIn(1, 255)
        val width = fittingModuleWidth(data, symbology, moduleWidth, paperWidth)

        // GS h n — altura del código en puntos
        appendCommand(byteArrayOf(0x1D, 0x68, height.toByte()))
        // GS w n — ancho de la barra angosta, ya ajustado al papel
        appendCommand(byteArrayOf(0x1D, 0x77, width.toByte()))
        // GS H n — posición del texto legible
        appendCommand(byteArrayOf(0x1D, 0x48, hriPosition.value.toByte()))
        // GS k m n — cabecera; los datos van aparte porque pueden traer 0x00
        appendCommand(byteArrayOf(0x1D, 0x6B, symbology.functionCode.toByte(), payload.size.toByte()))
        appendCommand(payload)
        return true
    }

    // MARK: - Formatting Helpers

    fun printDivider(char: Char = '-') {
        printLine(String(CharArray(paperWidth.charsPerLine) { char }))
    }

    fun printDoubleDivider() {
        printDivider('=')
    }

    /**
     * Una línea de la regla de calibración: [tens] da la fila de decenas y
     * `false` la de unidades. Mide EXACTAMENTE lo que la impresora acepta por
     * línea y va alineada a la izquierda a propósito.
     *
     * El papel la recorta solo, y ahí está el truco: **el primer número que se
     * alcanza a leer ES el corrimiento que hay que configurar.** Es la única
     * manera de medirlo, porque estas impresoras no traen sensor de ancho ni de
     * posición del rollo — de qué lado se puso el adaptador no lo sabe ni la
     * impresora ni el protocolo, sólo quien lo instaló.
     *
     * Como se imprime con el margen YA aplicado, también sirve de verificación:
     * si la regla empieza en 0, la impresora está bien calibrada.
     */
    private fun calibrationRuler(tens: Boolean): String =
        (0 until paperWidth.charsPerLine).joinToString("") { i ->
            when {
                !tens -> ('0' + i % 10).toString()
                i < 10 -> " "
                else -> ('0' + (i / 10) % 10).toString()
            }
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
        printThreeColumns("Cant", "Artículo", "Precio")
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
            item.areaSourceLabel?.let { printLine("  $it") }
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

        // Comprobante pagado para entrega por área. El área puede escanearlo o,
        // si el papel/pistola falla, teclear los 10 dígitos impresos debajo.
        receipt.areaDeliveryCode?.takeIf { it.isNotBlank() }?.let { code ->
            printLine()
            printDoubleDivider()
            setAlignment(TextAlignment.CENTER)
            setBold(true)
            printLine("ENTREGA POR ÁREA")
            setBold(false)
            printLine("Presenta este comprobante en el área")
            printLine()
            printBarcode(code)
            printLine()
            setLargeText(true)
            setBold(true)
            printLine(code)
            setBold(false)
            setLargeText(false)
        }

        // QR del recibo digital: escanear → recibo, calificar, facturar.
        // Es lo que hace avoqado-tpv. Sin llave no se dibuja nada.
        receipt.receiptUrl?.takeIf { it.isNotBlank() }?.let { url ->
            printLine()
            printDivider()
            setAlignment(TextAlignment.CENTER)
            printLine("Escanea para tu recibo y factura")
            printLine()
            printQr(url)
            printLine()
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

    // MARK: - Vale de área (AREA_TICKETS)

    /**
     * El vale que el área entrega al cliente y que la caja escanea.
     *
     * Dos decisiones de maquetación que no son estéticas:
     *
     * 1. **El código va DOS veces**: como código de barras y en texto grande abajo. El HRI que
     *    imprime la propia impresora bajo las barras es diminuto, y este papel viaja en la mano
     *    de alguien por una cremería — se moja, se arruga y se dobla. Cuando la pistola no lee,
     *    lo único que salva la venta es que el cajero pueda teclear 10 dígitos.
     * 2. **Si el código de barras falla, el vale se imprime igual.** `printBarcode` devuelve
     *    `false` sin escribir bytes; aquí eso sólo significa quedarse sin barras, nunca quedarse
     *    sin vale. Un vale sin barras se teclea; un vale que no salió es un cliente parado en el
     *    mostrador con su jamón rebanado y sin forma de pagarlo.
     */
    fun generateAreaTicket(
        ticket: AreaTicketData,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128_C,
    ): ByteArray {
        reset()

        setAlignment(TextAlignment.CENTER)
        ticket.venueName?.let { printLine(it) }

        // El área ES el título: el cliente puede traer tres vales en la mano y tiene que
        // distinguirlos de un vistazo.
        setLargeText(true)
        setBold(true)
        printLine(ticket.areaName.uppercase(Locale("es", "MX")))
        setBold(false)
        setLargeText(false)

        printDoubleDivider()

        setAlignment(TextAlignment.LEFT)
        printTwoColumns("Vale #:", ticket.areaTicketCode)
        printTwoColumns("Hora:", ticket.formattedTime)
        ticket.staffName?.let { printTwoColumns("Atendió:", it) }

        printDivider()

        for (item in ticket.items) {
            if (ticket.showPrices) {
                printThreeColumns(item.quantity.toString(), item.name, item.formattedPrice)
            } else {
                printTwoColumns("${item.quantity}x", item.name)
            }
            // Granel: "0.435 kg × $420.00/kg" bajo el nombre, igual que en el recibo.
            item.weightSummary?.let { printLine("   $it") }
            item.note?.let { printLine("   Nota: $it") }
        }

        if (ticket.showPrices) {
            printDivider()
            setBold(true)
            setDoubleHeight(true)
            printTwoColumns("TOTAL", ticket.formattedTotal)
            setDoubleHeight(false)
            setBold(false)
        }

        printDoubleDivider()

        // El código, en barras y en grande. Ver el punto 1 del KDoc.
        setAlignment(TextAlignment.CENTER)
        printBarcode(ticket.areaTicketCode, symbology = symbology)
        printLine()
        setLargeText(true)
        setBold(true)
        printLine(ticket.areaTicketCode)
        setBold(false)
        setLargeText(false)

        printLine()
        printLine("Presenta este vale en caja")
        if (ticket.holdsProduct) {
            setBold(true)
            printLine("Tu producto te espera aquí")
            setBold(false)
            printLine("Regresa con el ticket pagado")
        }

        printDoubleDivider()
        cut()

        return getData()
    }

    // MARK: - Test Print

    fun generateTestPrint(): ByteArray {
        reset()

        setAlignment(TextAlignment.CENTER)
        setLargeText(true)
        printLine("PRUEBA DE IMPRESIÓN")
        setLargeText(false)
        printLine()

        printDivider()

        // Regla de calibración. Tiene que ir ARRIBA y alineada a la izquierda:
        // es lo primero que hay que mirar cuando el ticket sale mocho.
        setAlignment(TextAlignment.LEFT)
        printLine(calibrationRuler(tens = true))
        printLine(calibrationRuler(tens = false))
        setAlignment(TextAlignment.CENTER)
        printLine("Si no ves el 0, ese es tu margen")
        printLine("Margen actual: $leftMarginChars")
        printLine()

        printDivider()

        setAlignment(TextAlignment.LEFT)
        printLine("Ancho de papel: ${paperWidth.displayName}")
        printLine("Caracteres por línea: ${paperWidth.charsPerLine}")
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
        printThreeColumns("1", "Artículo de prueba", "$99.00")

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
