package com.avoqado.pos.printing.receiptlayout

import kotlinx.serialization.json.Json

/**
 * La receta canónica EMBEBIDA: lo que imprime el aparato cuando no tiene receta en caché o la
 * que tiene no es íntegra (spec § 9). `JSON` es el `JSON.stringify(CANONICAL_LAYOUT)` exacto del
 * servidor y su sha256 (primeros 16 hex) es `TEMPLATE_HASH`: si alguien cambia un default allá,
 * `ReceiptLayoutParserTest` cae aquí.
 */
object CanonicalLayout {
    const val SCHEMA_VERSION = 1
    const val TEMPLATE_HASH = "b5ba786e2e92cb9f"

    const val JSON = """[{"type":"logo","size":"M","align":"center"},{"type":"businessName","align":"center","emphasis":"double"},{"type":"fiscal","align":"center"},{"type":"address","align":"center"},{"type":"phone","align":"center"},{"type":"separator","style":"line"},{"type":"orderInfo","showOrderType":true},{"type":"staff"},{"type":"separator","style":"line"},{"type":"items","showModifiers":true,"showNotes":true},{"type":"separator","style":"line"},{"type":"totals","showSubtotal":true,"showTax":true,"showDiscount":true,"showTip":true},{"type":"payment","showChange":true,"showCardLastFour":true},{"type":"areaDelivery"},{"type":"qr","caption":"Escanea para tu recibo y factura"},{"type":"fiscalNotice"},{"type":"separator","style":"line"},{"type":"text","lines":["Gracias por su compra"],"align":"center","emphasis":"normal"},{"type":"reference","showTransactionId":true,"showAppVersion":false},{"type":"signature"}]"""

    val BLOCKS: List<ReceiptBlock> by lazy {
        val parsed = ReceiptLayoutParser.parseTolerant(Json.parseToJsonElement(JSON))
        check(parsed.dropped == 0 && ReceiptLayoutParser.isIntact(parsed.blocks)) { "La canónica embebida no es íntegra" }
        parsed.blocks
    }
}
