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
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Respaldo digital del vale de área.
 *
 * El PDF conserva el mismo código inmutable que el papel. Es una salida operativa alternativa
 * dentro de AREA_TICKETS; no intenta emular bytes ESC/POS ni certifica ancho/corte de impresora.
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
        val itemsHeight = ticket.items.indices.sumOf { index ->
            val item = ticket.items[index]
            (itemNameLines[index].size * 12) +
                (if (item.weightSummary != null) 13 else 0) +
                (if (item.note != null) 13 else 0) +
                7
        }
        val pageHeight = maxOf(420, 286 + itemsHeight + if (ticket.showPrices) 30 else 0)
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, pageHeight, 1).create()
        val page = document.startPage(pageInfo)

        try {
            drawTicket(
                canvas = page.canvas,
                ticket = ticket,
                symbology = symbology,
                itemNameLines = itemNameLines,
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
            y += 7f
        }

        if (ticket.showPrices) {
            canvas.drawDivider(y)
            y += 18f
            canvas.drawText("TOTAL", MARGIN, y, paint(size = 13f, bold = true))
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
        canvas.drawCenteredText(ticket.areaTicketCode, y, paint(size = 15f, bold = true))
        y += 21f
        canvas.drawCenteredText("Presenta este vale en caja", y, paint(size = 9f, bold = true))
        y += 14f
        if (ticket.holdsProduct) {
            canvas.drawCenteredText("Tu producto te espera en el área", y, paint(size = 8f))
            y += 12f
            canvas.drawCenteredText("Regresa con el comprobante pagado", y, paint(size = 8f))
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
            BarcodeSymbology.CODE128_C -> BarcodeFormat.CODE_128
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
    }
}
