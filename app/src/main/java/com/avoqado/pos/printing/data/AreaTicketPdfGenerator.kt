package com.avoqado.pos.printing.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Respaldo digital del vale de área.
 *
 * El PDF conserva el mismo código inmutable que el papel. Es una salida operativa alternativa
 * dentro de AREA_TICKETS; no intenta emular bytes ESC/POS ni certifica ancho/corte de impresora.
 *
 * Caja externa (`ticket.externalRoute`): el vale lo cobra OTRO POS, así que el PDF sale con lo mismo
 * que el papel — extras, un código por pieza, «Importe de referencia», el vale en QR y el pie propio.
 * Fuera de ella el PDF es el de siempre, al punto.
 */
class AreaTicketPdfGenerator @Inject constructor() {
    fun generate(
        ticket: AreaTicketData,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128_C,
    ): ByteArray {
        val bodyPaint = paint(size = 9f)
        val nameWidth = if (ticket.showPrices) 112f else 154f
        val itemNameLines = ticket.items.map { item ->
            wrapText(item.name, bodyPaint, nameWidth)
        }
        // Caja externa: los extras salen como «+ nombre», partidos UNA sola vez, aquí. El alto de la
        // página y el dibujo leen estas mismas líneas, así que la página no puede quedar más chica que
        // lo dibujado (un extra largo ocupa varias líneas, no una).
        val extraPaint = paint(size = 8f)
        val itemExtraLines = ticket.items.map { item ->
            if (ticket.externalRoute) {
                item.modifiers.orEmpty().flatMap { extra -> wrapText("+ $extra", extraPaint, nameWidth) }
            } else {
                emptyList()
            }
        }
        val itemsHeight = ticket.items.indices.sumOf { index ->
            val item = ticket.items[index]
            (itemNameLines[index].size * 12) +
                (if (item.weightSummary != null) 13 else 0) +
                (if (item.note != null) 13 else 0) +
                7
        }
        // Caja externa: 34 fijos (20 de más del QR de 72 contra las barras de 52, y 14 de la segunda
        // línea del pie) + 8 de seguro sobre «Generado por Avoqado» más, por renglón, una línea por cada
        // línea de extra y un bloque por código. Los 8 son margen para un caso que hoy no se da: el
        // presupuesto base reserva 30 para el bloque TOTAL y éste dibuja 34, y con la línea «Atendió:»
        // (sólo si el vale trae nombre de cajero; `toAreaTicketData()` hoy no lo llena) el pie se montaría
        // en «Generado por Avoqado». El vale normal conserva su alto al punto (le pasaría lo mismo ese día).
        val extraExterno = if (ticket.externalRoute) {
            34 + 8 + ticket.items.indices.sumOf { index ->
                (itemExtraLines[index].size * EXTRA_LINE_HEIGHT) +
                    (ticket.items[index].externalCodes.size * CODE_BLOCK_HEIGHT)
            }
        } else {
            0
        }
        val pageHeight = maxOf(
            420,
            286 + itemsHeight + (if (ticket.showPrices) 30 else 0) + extraExterno,
        )
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, pageHeight, 1).create()
        val page = document.startPage(pageInfo)

        try {
            drawTicket(
                canvas = page.canvas,
                ticket = ticket,
                symbology = symbology,
                itemNameLines = itemNameLines,
                itemExtraLines = itemExtraLines,
                pageHeight = pageHeight,
            )
            document.finishPage(page)
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun drawTicket(
        canvas: Canvas,
        ticket: AreaTicketData,
        symbology: BarcodeSymbology,
        itemNameLines: List<List<String>>,
        itemExtraLines: List<List<String>>,
        pageHeight: Int,
    ) {
        canvas.drawColor(Color.WHITE)
        var y = 20f

        ticket.venueName?.takeIf { it.isNotBlank() }?.let { venue ->
            canvas.drawCenteredText(venue, y, paint(size = 10f, bold = true))
            y += 17f
        }

        canvas.drawCenteredText(
            ticket.areaName.uppercase(Locale.forLanguageTag("es-MX")),
            y,
            paint(size = 16f, bold = true),
        )
        y += 22f
        canvas.drawCenteredText("VALE DE ÁREA · PDF", y, paint(size = 8f))
        y += 13f
        canvas.drawDivider(y, double = true)
        y += 14f

        canvas.drawTwoColumns("Vale #:", ticket.areaTicketCode, y, boldValue = true)
        y += 13f
        val timestamp = ticket.timestamp.toInstant()
            .atZone(VenueTimeZone.zoneId())
            .format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm",
                    Locale.forLanguageTag("es-MX"),
                ),
            )
        canvas.drawTwoColumns("Fecha:", timestamp, y)
        y += 13f
        ticket.staffName?.takeIf { it.isNotBlank() }?.let { staff ->
            canvas.drawTwoColumns("Atendió:", staff, y)
            y += 13f
        }
        canvas.drawDivider(y)
        y += 14f

        ticket.items.forEachIndexed { index, item ->
            val nameX = MARGIN + 24f
            val itemLines = itemNameLines[index]
            canvas.drawText("${item.quantity}x", MARGIN, y, paint(size = 9f))
            itemLines.forEachIndexed { lineIndex, line ->
                canvas.drawText(line, nameX, y + (lineIndex * 12f), paint(size = 9f))
            }
            if (ticket.showPrices) {
                canvas.drawText(
                    item.formattedPrice,
                    PAGE_WIDTH - MARGIN,
                    y,
                    paint(size = 9f, align = Paint.Align.RIGHT),
                )
            }
            y += itemLines.size * 12f
            item.weightSummary?.let { summary ->
                canvas.drawText(summary, nameX, y, paint(size = 8f))
                y += 13f
            }
            item.note?.let { note ->
                canvas.drawText("Nota: $note", nameX, y, paint(size = 8f))
                y += 13f
            }
            if (ticket.externalRoute) {
                itemExtraLines[index].forEach { line ->
                    canvas.drawText(line, nameX, y, paint(size = 8f))
                    y += EXTRA_LINE_HEIGHT
                }
                item.externalCodes.forEach { code ->
                    drawExternalCode(canvas, code, top = y)
                    y += CODE_BLOCK_HEIGHT
                }
            }
            y += 7f
        }

        if (ticket.showPrices) {
            canvas.drawDivider(y)
            y += 18f
            // Caja externa: Avoqado no cobra; su total es sólo una referencia.
            canvas.drawText(
                if (ticket.externalRoute) "Importe de referencia" else "TOTAL",
                MARGIN,
                y,
                paint(size = 13f, bold = true),
            )
            canvas.drawText(
                ticket.formattedTotal,
                PAGE_WIDTH - MARGIN,
                y,
                paint(size = 13f, bold = true, align = Paint.Align.RIGHT),
            )
            y += 16f
        }

        canvas.drawDivider(y, double = true)
        y += 13f
        if (ticket.externalRoute) {
            // Caja externa: el vale va en QR (la pistola 1D de la otra caja no lo levanta ni lo busca como producto).
            drawQr(canvas, ticket.areaTicketCode, top = y, size = QR_SIZE)
            y += QR_SIZE + 15f
        } else {
            drawBarcode(
                canvas = canvas,
                code = ticket.areaTicketCode,
                symbology = symbology,
                left = MARGIN + 3f,
                top = y,
                width = PAGE_WIDTH - ((MARGIN + 3f) * 2),
                height = 52f,
            )
            y += 67f
        }
        canvas.drawCenteredText(ticket.areaTicketCode, y, paint(size = 15f, bold = true))
        y += 21f
        if (ticket.externalRoute) {
            canvas.drawCenteredText("No es comprobante de pago.", y, paint(size = 9f, bold = true))
            y += 14f
            canvas.drawCenteredText("Pásalo en la caja principal.", y, paint(size = 9f, bold = true))
            y += 14f
        } else {
            canvas.drawCenteredText("Presenta este vale en caja", y, paint(size = 9f, bold = true))
            y += 14f
        }
        if (ticket.holdsProduct) {
            canvas.drawCenteredText(
                if (ticket.externalRoute) "Tu producto te espera aquí" else "Tu producto te espera en el área",
                y,
                paint(size = 8f),
            )
            y += 12f
            canvas.drawCenteredText(
                if (ticket.externalRoute) "Regresa con tu ticket de la caja" else "Regresa con el comprobante pagado",
                y,
                paint(size = 8f),
            )
        }

        canvas.drawCenteredText(
            "Generado por Avoqado",
            pageHeight - 12f,
            paint(size = 7f, color = Color.DKGRAY),
        )
    }

    private fun drawBarcode(
        canvas: Canvas,
        code: String,
        symbology: BarcodeSymbology,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val format = when (symbology) {
            BarcodeSymbology.CODE39 -> BarcodeFormat.CODE_39
            BarcodeSymbology.CODE128_C, BarcodeSymbology.CODE128_B -> BarcodeFormat.CODE_128
        }
        val matrix = MultiFormatWriter().encode(
            code,
            format,
            width.toInt().coerceAtLeast(1),
            height.toInt().coerceAtLeast(1),
            mapOf(EncodeHintType.MARGIN to 6),
        )
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val scaleX = width / matrix.width
        var x = 0
        val sampleY = matrix.height / 2
        while (x < matrix.width) {
            if (!matrix[x, sampleY]) {
                x++
                continue
            }
            val start = x
            while (x < matrix.width && matrix[x, sampleY]) x++
            canvas.drawRect(
                left + (start * scaleX),
                top,
                left + (x * scaleX),
                top + height,
                paint,
            )
        }
    }

    /**
     * Caja externa: UN código de pieza — barras de 26 y el código en texto debajo ([CODE_BLOCK_HEIGHT]
     * en total). Si no se puede codificar en barras (acentos, vacío), queda sólo el texto: un código
     * sin barras todavía se teclea en la otra caja; un vale que no sale, no.
     */
    private fun drawExternalCode(canvas: Canvas, code: String, top: Float) {
        try {
            drawBarcode(
                canvas = canvas,
                code = code,
                symbology = BarcodeSymbology.CODE128_B,
                left = MARGIN + 30f,
                top = top,
                width = PAGE_WIDTH - ((MARGIN + 30f) * 2),
                height = 26f,
            )
        } catch (e: Exception) {
            // zxing lanza IllegalArgumentException ANTES de dibujar nada: no queda media barra en la página.
        }
        canvas.drawCenteredText(code, top + 37f, paint(size = 8f))
    }

    /**
     * Caja externa: el código del vale como QR, dibujado celda por celda en un cuadro de [size] centrado.
     * La matriz de zxing ya trae su zona de silencio de 4 módulos, que queda DENTRO del cuadro.
     */
    private fun drawQr(canvas: Canvas, code: String, top: Float, size: Float) {
        val matrix = qrMatrix(code)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val cell = size / matrix.width
        val left = (PAGE_WIDTH - size) / 2f
        for (row in 0 until matrix.height) {
            var col = 0
            while (col < matrix.width) {
                if (!matrix[col, row]) {
                    col++
                    continue
                }
                val start = col
                while (col < matrix.width && matrix[col, row]) col++
                canvas.drawRect(
                    left + (start * cell),
                    top + (row * cell),
                    left + (col * cell),
                    top + ((row + 1) * cell),
                    paint,
                )
            }
        }
    }

    /** El QR del vale con corrección M, la misma que el papel (`ESCPOSPrinter.printQr`); el tamaño no cambia (29×29). */
    internal fun qrMatrix(code: String): BitMatrix = MultiFormatWriter().encode(
        code,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

    private fun Canvas.drawCenteredText(text: String, y: Float, paint: Paint) {
        drawText(text, PAGE_WIDTH / 2f, y, paint.apply { textAlign = Paint.Align.CENTER })
    }

    private fun Canvas.drawTwoColumns(
        label: String,
        value: String,
        y: Float,
        boldValue: Boolean = false,
    ) {
        drawText(label, MARGIN, y, paint(size = 8f))
        drawText(
            value,
            PAGE_WIDTH - MARGIN,
            y,
            paint(size = 8f, bold = boldValue, align = Paint.Align.RIGHT),
        )
    }

    private fun Canvas.drawDivider(y: Float, double: Boolean = false) {
        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 0.8f
        }
        drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        if (double) drawLine(MARGIN, y + 3f, PAGE_WIDTH - MARGIN, y + 3f, linePaint)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        var current = ""
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf(text) }
    }

    private fun paint(
        size: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        color: Int = Color.BLACK,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        textAlign = align
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private companion object {
        const val PAGE_WIDTH = 227
        const val MARGIN = 14f

        // Caja externa. El alto de la página (generate) y el dibujo (drawTicket) leen las MISMAS constantes.
        /** Una línea de extra («+ nombre», 8 pt). */
        const val EXTRA_LINE_HEIGHT = 13

        /** Un código de pieza: barras de 26 + el texto debajo + aire. */
        const val CODE_BLOCK_HEIGHT = 44

        /** Lado del cuadro del QR del vale (las barras de hoy miden 52 de alto). */
        const val QR_SIZE = 72f
    }
}
