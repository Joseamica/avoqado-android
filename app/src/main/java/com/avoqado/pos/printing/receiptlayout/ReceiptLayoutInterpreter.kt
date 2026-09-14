package com.avoqado.pos.printing.receiptlayout

import kotlin.math.abs
import com.avoqado.pos.printing.receiptlayout.ReceiptLabels as L
import com.avoqado.pos.printing.receiptlayout.ReceiptText as T

/**
 * El intérprete del ticket (spec § 6): receta + venta + ancho → líneas lógicas. PURO: sin
 * impresora, sin reloj, sin red. Puerto 1:1 de avoqado-server `interpret.ts` y de los `*.ts` dentro de `blocks/`;
 * `ReceiptLayoutGoldenTest` es la prueba de que los dos coinciden.
 */
object ReceiptLayoutInterpreter {
    private const val AVOQADO_MARK_WIDTH_PCT = 15

    fun interpret(blocks: List<ReceiptBlock>, input: ReceiptInput, width: Int): List<LogicalLine> =
        blocks.flatMap { render(it, input, width) }

    private fun text(value: String, align: Align = Align.LEFT, bold: Boolean = false, double: Boolean = false) =
        LogicalLine.Text(value, align, bold, double)

    private fun feed(lines: Int = 1) = LogicalLine.Feed(lines)

    private fun String?.present(): Boolean = !this.isNullOrEmpty()

    private fun render(block: ReceiptBlock, input: ReceiptInput, width: Int): List<LogicalLine> = when (block) {
        is ReceiptBlock.Logo ->
            if (!input.venue.hasLogo) emptyList() else listOf(LogicalLine.Image(ImageRef.LOGO, block.size.widthPct, block.align), feed())
        is ReceiptBlock.BusinessName -> titleLines(T.force(input.venue.name), width, block.align, block.emphasis)
        is ReceiptBlock.Fiscal -> fiscal(block, input, width)
        is ReceiptBlock.Address -> {
            val v = input.venue
            T.addressLine(v.address, v.city, v.state, v.zipCode)
                ?.let { a -> T.wrap(T.force(a), width).map { text(it, block.align) } }
                ?: emptyList()
        }
        is ReceiptBlock.Phone ->
            if (!input.venue.phone.present()) emptyList()
            else T.wrap("${L.TEL} ${T.force(input.venue.phone!!)}", width).map { text(it, block.align) }
        is ReceiptBlock.Text -> block.lines.flatMap { wrapWithEmphasis(T.force(it), width, block.align, block.emphasis) }
        is ReceiptBlock.Separator ->
            if (block.style == SeparatorStyle.BLANK) listOf(feed())
            else listOf(text(T.divider(width, if (block.style == SeparatorStyle.DOUBLE) '=' else '-')))
        is ReceiptBlock.OrderInfo -> orderInfo(block, input, width)
        ReceiptBlock.Staff ->
            if (!input.sale.staffName.present()) emptyList()
            else T.twoColumnLines(L.ATENDIO, T.force(input.sale.staffName!!), width).map { text(it) }
        is ReceiptBlock.Items -> items(block, input, width)
        is ReceiptBlock.Totals -> totals(block, input, width)
        is ReceiptBlock.Payment -> payment(block, input, width)
        ReceiptBlock.AmountInWords -> T.wrap(ReceiptFormat.amountInWords(input.sale.totalCents), width).map { text(it, Align.CENTER) }
        ReceiptBlock.AreaDelivery -> areaDelivery(input, width)
        is ReceiptBlock.Qr -> qr(block, input, width)
        ReceiptBlock.FiscalNotice -> T.wrap(L.FISCAL_NOTICE, width).map { text(it, Align.CENTER) }
        is ReceiptBlock.Reference -> reference(block, input, width)
        ReceiptBlock.Signature -> listOf(
            feed(),
            LogicalLine.Image(ImageRef.AVOQADO_MARK, AVOQADO_MARK_WIDTH_PCT, Align.CENTER),
            feed(),
            text(L.POWERED_BY, Align.CENTER),
            LogicalLine.Cut,
        )
    }

    /** Un valor de una línea: a doble ancho si cabe; si no, negritas completas le ganan a letra grande partida. */
    private fun titleLines(value: String, width: Int, align: Align, emphasis: Emphasis): List<LogicalLine> {
        if (emphasis == Emphasis.DOUBLE) {
            if (value.length * 2 <= width) return listOf(text(value, align, double = true))
            return T.wrap(value, width).map { text(it, align, bold = true) }
        }
        return T.wrap(value, width).map { text(it, align, bold = emphasis == Emphasis.BOLD) }
    }

    private fun wrapWithEmphasis(value: String, width: Int, align: Align, emphasis: Emphasis): List<LogicalLine> {
        val double = emphasis == Emphasis.DOUBLE
        return T.wrap(value, if (double) width / 2 else width).map { text(it, align, bold = emphasis == Emphasis.BOLD, double = double) }
    }

    private fun fiscal(block: ReceiptBlock.Fiscal, input: ReceiptInput, width: Int): List<LogicalLine> {
        val emisor = FiscalEmisorResolver.resolve(input.venue, input.sale.tender?.merchantAccountId) ?: return emptyList()
        val valores = listOfNotNull(
            emisor.legalName?.takeIf { it.isNotEmpty() },
            emisor.rfc?.takeIf { it.isNotEmpty() }?.let { "${L.RFC} $it" },
            emisor.lugarExpedicion?.takeIf { it.isNotEmpty() }?.let { "${L.LUGAR_EXPEDICION} $it" },
        )
        return valores.flatMap { v -> T.wrap(T.force(v), width).map { text(it, block.align) } }
    }

    private fun orderInfo(block: ReceiptBlock.OrderInfo, input: ReceiptInput, width: Int): List<LogicalLine> {
        val sale = input.sale
        val refund = sale.kind == "REFUND"
        fun row(label: String, value: String) = T.twoColumnLines(label, value, width).map { text(it) }
        val lines = mutableListOf<LogicalLine>()
        if (refund) lines += text(L.DEVOLUCION_TITULO, Align.CENTER, bold = true)
        lines += row(if (refund) L.DEVOLUCION else L.ORDEN, T.force(sale.orderNumber))
        lines += row(L.FECHA, ReceiptFormat.dateTime(sale.occurredAt, sale.timezone))
        if (block.showOrderType) lines += row(L.TIPO, T.force(sale.orderType))
        sale.reprint?.let { lines += row(L.REIMPRESION, ReceiptFormat.dateTime(it.printedAt, sale.timezone)) }
        return lines
    }

    private fun items(block: ReceiptBlock.Items, input: ReceiptInput, width: Int): List<LogicalLine> {
        val lines = mutableListOf<LogicalLine>()
        lines += T.itemLines(L.CANT, L.ARTICULO, L.PRECIO, width).map { text(it, bold = true) }
        lines += text(T.divider(width))
        fun aux(value: String) = T.indentedLines(value, width).map { text(it) }
        for (item in input.sale.items) {
            val component = item.isComboComponent == true
            val name = T.force(if (component) "${item.quantity}x ${item.name}" else item.name)
            val price = when {
                component -> ""
                item.isCortesia == true -> L.CORTESIA
                else -> ReceiptFormat.money(item.totalPriceCents)
            }
            lines += T.itemLines(if (component) "" else item.quantity.toString(), name, price, width, if (component) 2 else 0)
                .map { text(it, bold = item.isComboHeader == true) }
            if (item.weightSummary.present()) lines += aux(T.force(item.weightSummary!!))
            if (item.areaSourceLabel.present()) lines += aux(T.force(item.areaSourceLabel!!))
            if (block.showModifiers) item.modifiers.orEmpty().forEach { lines += aux("+ ${T.force(it)}") }
            if (block.showNotes && item.note.present()) lines += aux("${L.NOTA} ${T.force(item.note!!)}")
        }
        return lines
    }

    private fun totals(block: ReceiptBlock.Totals, input: ReceiptInput, width: Int): List<LogicalLine> {
        val sale = input.sale
        fun money(label: String, cents: Long) = T.twoColumnLines(label, ReceiptFormat.money(cents), width).map { text(it) }
        val lines = mutableListOf<LogicalLine>()
        if (block.showSubtotal) lines += money(L.SUBTOTAL, sale.subtotalCents)
        if (block.showDiscount && (sale.discountCents ?: 0L) != 0L) lines += money(L.DESCUENTO, -abs(sale.discountCents!!))
        if (block.showTax) lines += money(L.IVA_INCLUIDO, sale.taxCents)
        if (block.showTip && (sale.tipCents ?: 0L) != 0L) lines += money(L.PROPINA, sale.tipCents!!)
        lines += text(T.divider(width))
        val total = ReceiptFormat.money(sale.totalCents)
        val half = width / 2
        lines += if (L.TOTAL.length + 1 + total.length <= half) {
            T.twoColumnLines(L.TOTAL, total, half).map { text(it, bold = true, double = true) }
        } else {
            T.twoColumnLines(L.TOTAL, total, width).map { text(it, bold = true) }
        }
        return lines
    }

    private fun payment(block: ReceiptBlock.Payment, input: ReceiptInput, width: Int): List<LogicalLine> {
        val t = input.sale.tender ?: return emptyList()
        fun row(label: String, value: String, bold: Boolean = false) = T.twoColumnLines(label, value, width).map { text(it, bold = bold) }
        val lines = mutableListOf<LogicalLine>(feed())
        lines += row(L.PAGO, T.force(t.label))
        if (t.kind == "CARD") {
            if (block.showCardLastFour && t.cardLastFour.present()) {
                lines += row(L.TARJETA, if (t.cardBrand.present()) "${T.force(t.cardBrand!!)} **** ${t.cardLastFour}" else "**** ${t.cardLastFour}")
            }
            if (t.authCode.present()) lines += row(L.AUTORIZACION, T.force(t.authCode!!))
            if (t.referenceNumber.present()) lines += row(L.REFERENCIA, T.force(t.referenceNumber!!))
        }
        if (t.kind == "CASH") {
            t.tenderedCents?.let { lines += row(L.RECIBIDO, ReceiptFormat.money(it)) }
            if (block.showChange && (t.changeCents ?: 0L) > 0L) lines += row(L.CAMBIO, ReceiptFormat.money(t.changeCents!!), bold = true)
        }
        return lines
    }

    private fun areaDelivery(input: ReceiptInput, width: Int): List<LogicalLine> {
        val code = input.sale.areaDeliveryCode?.let(T::jsTrim)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(feed(), text(T.divider(width, '=')), text(L.ENTREGA_POR_AREA, Align.CENTER, bold = true)) +
            T.wrap(L.PRESENTA_COMPROBANTE, width).map { text(it, Align.CENTER) } +
            listOf(feed(), LogicalLine.Barcode(code), feed(), text(code, Align.CENTER, bold = true, double = code.length * 2 <= width))
    }

    private fun qr(block: ReceiptBlock.Qr, input: ReceiptInput, width: Int): List<LogicalLine> {
        val url = input.sale.receiptUrl?.let(T::jsTrim)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        // La leyenda POR DEFECTO no promete factura donde el negocio no la tiene; una propia se respeta.
        val caption = if (block.caption == L.QR_DEFAULT_CAPTION && input.sale.autofacturaAvailable == false) L.QR_SIN_FACTURA else block.caption
        return listOf(feed(), text(T.divider(width))) +
            T.wrap(T.force(caption), width).map { text(it, Align.CENTER) } +
            listOf(feed(), LogicalLine.Qr(url), feed())
    }

    private fun reference(block: ReceiptBlock.Reference, input: ReceiptInput, width: Int): List<LogicalLine> {
        val lines = mutableListOf<LogicalLine>()
        if (block.showTransactionId && input.sale.transactionId.present()) {
            lines += T.wrap("${L.ID} ${T.force(input.sale.transactionId!!)}", width).map { text(it, Align.CENTER) }
        }
        if (block.showAppVersion && input.sale.appVersion.present()) {
            lines += T.wrap("${L.VERSION}${T.force(input.sale.appVersion!!)}", width).map { text(it, Align.CENTER) }
        }
        return lines
    }
}
