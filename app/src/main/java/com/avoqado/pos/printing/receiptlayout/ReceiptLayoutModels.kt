package com.avoqado.pos.printing.receiptlayout

import kotlinx.serialization.Serializable

/*
 * Espejo de avoqado-server `src/services/shared/receiptLayout/types.ts` y `schema.ts`, y de
 * avoqado-ios `Printing/ReceiptLayout/ReceiptLayoutModels.swift`. Cambiar uno sin los otros dos
 * rompe los casos dorados: es exactamente para lo que existen.
 */

enum class Align(val wire: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");
    companion object { fun of(wire: String): Align? = entries.firstOrNull { it.wire == wire } }
}

enum class Emphasis(val wire: String) {
    NORMAL("normal"), BOLD("bold"), DOUBLE("double");
    companion object { fun of(wire: String): Emphasis? = entries.firstOrNull { it.wire == wire } }
}

enum class LogoSize(val wire: String, val widthPct: Int) {
    S("S", 40), M("M", 60), L("L", 80);
    companion object { fun of(wire: String): LogoSize? = entries.firstOrNull { it.wire == wire } }
}

enum class SeparatorStyle(val wire: String) {
    LINE("line"), DOUBLE("double"), BLANK("blank");
    companion object { fun of(wire: String): SeparatorStyle? = entries.firstOrNull { it.wire == wire } }
}

enum class ImageRef(val wire: String) {
    LOGO("logo"), AVOQADO_MARK("avoqadoMark");
    companion object { fun of(wire: String): ImageRef? = entries.firstOrNull { it.wire == wire } }
}

/** Un bloque de la receta con TODOS sus defaults explícitos, igual que lo deja el Zod del servidor. */
sealed interface ReceiptBlock {
    val type: String

    data class Logo(val size: LogoSize = LogoSize.M, val align: Align = Align.CENTER) : ReceiptBlock { override val type: String get() = "logo" }
    data class BusinessName(val align: Align = Align.CENTER, val emphasis: Emphasis = Emphasis.DOUBLE) : ReceiptBlock { override val type: String get() = "businessName" }
    data class Fiscal(val align: Align = Align.CENTER) : ReceiptBlock { override val type: String get() = "fiscal" }
    data class Address(val align: Align = Align.CENTER) : ReceiptBlock { override val type: String get() = "address" }
    data class Phone(val align: Align = Align.CENTER) : ReceiptBlock { override val type: String get() = "phone" }
    data class Text(val lines: List<String>, val align: Align = Align.CENTER, val emphasis: Emphasis = Emphasis.NORMAL) : ReceiptBlock { override val type: String get() = "text" }
    data class OrderInfo(val showOrderType: Boolean = true) : ReceiptBlock { override val type: String get() = "orderInfo" }
    data object Staff : ReceiptBlock { override val type: String get() = "staff" }
    data class Items(val showModifiers: Boolean = true, val showNotes: Boolean = true) : ReceiptBlock { override val type: String get() = "items" }
    data class Totals(
        val showSubtotal: Boolean = true,
        val showTax: Boolean = true,
        val showDiscount: Boolean = true,
        val showTip: Boolean = true,
    ) : ReceiptBlock { override val type: String get() = "totals" }
    data class Payment(val showChange: Boolean = true, val showCardLastFour: Boolean = true) : ReceiptBlock { override val type: String get() = "payment" }
    data object AmountInWords : ReceiptBlock { override val type: String get() = "amountInWords" }
    data object AreaDelivery : ReceiptBlock { override val type: String get() = "areaDelivery" }
    data class Qr(val caption: String = ReceiptLabels.QR_DEFAULT_CAPTION) : ReceiptBlock { override val type: String get() = "qr" }
    data object FiscalNotice : ReceiptBlock { override val type: String get() = "fiscalNotice" }
    data class Reference(val showTransactionId: Boolean = true, val showAppVersion: Boolean = false) : ReceiptBlock { override val type: String get() = "reference" }
    data class Separator(val style: SeparatorStyle = SeparatorStyle.LINE) : ReceiptBlock { override val type: String get() = "separator" }
    data object Signature : ReceiptBlock { override val type: String get() = "signature" }
}

/** Lo que produce el intérprete. Sólo `ESCPOSPrinter.renderReceipt` lo convierte a bytes. */
sealed interface LogicalLine {
    data class Text(val text: String, val align: Align, val bold: Boolean, val double: Boolean) : LogicalLine
    data class Image(val ref: ImageRef, val widthPct: Int, val align: Align) : LogicalLine
    data class Qr(val data: String) : LogicalLine
    data class Barcode(val data: String) : LogicalLine
    data class Feed(val lines: Int) : LogicalLine
    data object Cut : LogicalLine
}

/** La venta al imprimir: centavos enteros, instante ISO-8601 y zona IANA del venue. */
@Serializable
data class ReceiptInput(val sale: ReceiptSale, val venue: ReceiptVenueInfo)

@Serializable
data class ReceiptSale(
    val kind: String = "SALE",
    val orderNumber: String,
    val orderType: String,
    val occurredAt: String,
    val timezone: String,
    val items: List<ReceiptSaleItem> = emptyList(),
    val subtotalCents: Long,
    val taxCents: Long,
    val discountCents: Long? = null,
    val tipCents: Long? = null,
    val totalCents: Long,
    /** null = pre-cuenta: todavía no hay pago y el bloque `payment` no imprime nada. */
    val tender: ReceiptTender? = null,
    val staffName: String? = null,
    val transactionId: String? = null,
    val receiptUrl: String? = null,
    val areaDeliveryCode: String? = null,
    val reprint: ReceiptReprint? = null,
    val appVersion: String? = null,
    val autofacturaAvailable: Boolean? = null,
)

@Serializable
data class ReceiptReprint(val printedAt: String)

@Serializable
data class ReceiptSaleItem(
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalPriceCents: Long,
    val modifiers: List<String>? = null,
    val note: String? = null,
    val isCortesia: Boolean? = null,
    val weightSummary: String? = null,
    val areaSourceLabel: String? = null,
    val isComboHeader: Boolean? = null,
    val isComboComponent: Boolean? = null,
)

@Serializable
data class ReceiptTender(
    /** "CASH" | "CARD" | "OTHER" */
    val kind: String,
    val label: String,
    val cardBrand: String? = null,
    val cardLastFour: String? = null,
    val authCode: String? = null,
    val referenceNumber: String? = null,
    val tenderedCents: Long? = null,
    val changeCents: Long? = null,
    val merchantAccountId: String? = null,
)

/**
 * Todo opcional A PROPÓSITO: este tipo también llega dentro de `receiptInfo` en el payload de
 * settings, y un emisor malformado no puede tumbar el parseo de la configuración del local.
 */
@Serializable
data class ReceiptFiscalEmisor(
    val id: String? = null,
    val legalName: String? = null,
    val rfc: String? = null,
    val lugarExpedicion: String? = null,
    val merchantAccountIds: List<String> = emptyList(),
)

@Serializable
data class ReceiptLegacyFiscal(val legalName: String? = null, val rfc: String? = null)

@Serializable
data class ReceiptVenueInfo(
    val name: String,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zipCode: String? = null,
    val phone: String? = null,
    val hasLogo: Boolean = false,
    val fiscalEmisors: List<ReceiptFiscalEmisor> = emptyList(),
    val principalEmisorId: String? = null,
    val legacy: ReceiptLegacyFiscal = ReceiptLegacyFiscal(),
)

/** Etiquetas congeladas del ticket (espejo de `labels.es.ts`). */
object ReceiptLabels {
    const val ORDEN = "Orden #:"
    const val DEVOLUCION = "Devolución #:"
    const val DEVOLUCION_TITULO = "DEVOLUCIÓN"
    const val FECHA = "Fecha:"
    const val TIPO = "Tipo:"
    const val REIMPRESION = "Reimpresión:"
    const val ATENDIO = "Atendió:"
    const val CANT = "Cant"
    const val ARTICULO = "Artículo"
    const val PRECIO = "Precio"
    const val NOTA = "Nota:"
    const val CORTESIA = "CORTESÍA"
    const val SUBTOTAL = "Subtotal:"
    const val DESCUENTO = "Descuento:"
    const val IVA_INCLUIDO = "IVA incluido:"
    const val PROPINA = "Propina:"
    const val TOTAL = "TOTAL:"
    const val PAGO = "Pago:"
    const val TARJETA = "Tarjeta:"
    const val AUTORIZACION = "Autorización:"
    const val REFERENCIA = "Referencia:"
    const val RECIBIDO = "Recibido:"
    const val CAMBIO = "Cambio:"
    const val ENTREGA_POR_AREA = "ENTREGA POR ÁREA"
    const val PRESENTA_COMPROBANTE = "Presenta este comprobante en el área"
    const val FISCAL_NOTICE = "Este comprobante no es un CFDI"
    const val RFC = "RFC:"
    const val LUGAR_EXPEDICION = "Lugar de expedición: CP"
    const val TEL = "Tel:"
    const val ID = "ID:"
    const val VERSION = "Avoqado v"
    const val POWERED_BY = "Powered by Avoqado"
    const val QR_DEFAULT_CAPTION = "Escanea para tu recibo y factura"
    const val QR_SIN_FACTURA = "Escanea para tu recibo digital"
}
