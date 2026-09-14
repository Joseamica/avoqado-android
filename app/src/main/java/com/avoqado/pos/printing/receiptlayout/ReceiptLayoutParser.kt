package com.avoqado.pos.printing.receiptlayout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class TolerantParse(val blocks: List<ReceiptBlock>, val dropped: Int)

data class EffectiveLayout(val blocks: List<ReceiptBlock>, val usedFallback: Boolean, val dropped: Int)

/**
 * Espejo de `parseLayoutTolerant.ts` + `validateLayout.ts` (sólo lo que decide si la receta se
 * puede imprimir). 🔴 `ignoreUnknownKeys` NO cubre un miembro desconocido de la unión: por eso cada
 * elemento se lee a mano y un bloque ilegible se DESCARTA sin tumbar a los demás.
 */
object ReceiptLayoutParser {
    const val MAX_TEXT_LINES = 6
    const val MAX_TEXT_CHARS = 48
    const val MAX_TEXT_BLOCKS = 8
    const val MAX_SEPARATORS = 10

    /** Conjunto CERRADO (spec § 5.2): nunca se agrega un obligatorio nuevo. */
    val MANDATORY = listOf("fiscal", "orderInfo", "items", "totals", "payment", "areaDelivery", "signature")

    fun parseTolerant(raw: JsonElement?): TolerantParse {
        if (raw !is JsonArray) return TolerantParse(emptyList(), 0)
        var dropped = 0
        val blocks = raw.mapNotNull { item -> parseBlock(item) ?: run { dropped++; null } }
        return TolerantParse(blocks, dropped)
    }

    /** La regla de las apps: si tras descartar lo desconocido la receta no es íntegra, la canónica embebida. */
    fun effective(raw: JsonElement?): EffectiveLayout {
        val parsed = parseTolerant(raw)
        if (parsed.blocks.isEmpty() || !isIntact(parsed.blocks)) {
            return EffectiveLayout(CanonicalLayout.BLOCKS, usedFallback = true, dropped = parsed.dropped)
        }
        return EffectiveLayout(parsed.blocks, usedFallback = false, dropped = parsed.dropped)
    }

    fun isIntact(blocks: List<ReceiptBlock>): Boolean {
        if (blocks.isEmpty()) return false
        val counts = blocks.groupingBy { it.type }.eachCount()
        if (MANDATORY.any { (counts[it] ?: 0) != 1 }) return false
        for ((type, n) in counts) {
            if (type in MANDATORY) continue
            val max = when (type) {
                "text" -> MAX_TEXT_BLOCKS
                "separator" -> MAX_SEPARATORS
                else -> 1
            }
            if (n > max) return false
        }
        if (blocks.last().type != "signature") return false
        return blocks.none { block ->
            when (block) {
                is ReceiptBlock.Text -> block.lines.any(ReceiptText::hasForbiddenChars)
                is ReceiptBlock.Qr -> ReceiptText.hasForbiddenChars(block.caption)
                else -> false
            }
        }
    }

    private class Invalid : RuntimeException()

    private fun parseBlock(element: JsonElement): ReceiptBlock? {
        val o = element as? JsonObject ?: return null
        val typeValue = o["type"] as? JsonPrimitive ?: return null
        if (!typeValue.isString) return null
        return try {
            when (typeValue.content) {
                "logo" -> ReceiptBlock.Logo(o.enumOr("size", LogoSize.M, LogoSize::of), o.enumOr("align", Align.CENTER, Align::of))
                "businessName" -> ReceiptBlock.BusinessName(o.enumOr("align", Align.CENTER, Align::of), o.enumOr("emphasis", Emphasis.DOUBLE, Emphasis::of))
                "fiscal" -> ReceiptBlock.Fiscal(o.enumOr("align", Align.CENTER, Align::of))
                "address" -> ReceiptBlock.Address(o.enumOr("align", Align.CENTER, Align::of))
                "phone" -> ReceiptBlock.Phone(o.enumOr("align", Align.CENTER, Align::of))
                "text" -> ReceiptBlock.Text(o.textLines(), o.enumOr("align", Align.CENTER, Align::of), o.enumOr("emphasis", Emphasis.NORMAL, Emphasis::of))
                "orderInfo" -> ReceiptBlock.OrderInfo(o.boolOr("showOrderType", true))
                "staff" -> ReceiptBlock.Staff
                "items" -> ReceiptBlock.Items(o.boolOr("showModifiers", true), o.boolOr("showNotes", true))
                "totals" -> ReceiptBlock.Totals(o.boolOr("showSubtotal", true), o.boolOr("showTax", true), o.boolOr("showDiscount", true), o.boolOr("showTip", true))
                "payment" -> ReceiptBlock.Payment(o.boolOr("showChange", true), o.boolOr("showCardLastFour", true))
                "amountInWords" -> ReceiptBlock.AmountInWords
                "areaDelivery" -> ReceiptBlock.AreaDelivery
                "qr" -> ReceiptBlock.Qr(o.shortStringOr("caption", ReceiptLabels.QR_DEFAULT_CAPTION))
                "fiscalNotice" -> ReceiptBlock.FiscalNotice
                "reference" -> ReceiptBlock.Reference(o.boolOr("showTransactionId", true), o.boolOr("showAppVersion", false))
                "separator" -> ReceiptBlock.Separator(o.enumOr("style", SeparatorStyle.LINE, SeparatorStyle::of))
                "signature" -> ReceiptBlock.Signature
                else -> null
            }
        } catch (_: Invalid) {
            null
        }
    }

    /** Llave ausente → default. Presente (aunque sea null) → tiene que ser del tipo exacto. Igual que `.default()` de Zod. */
    private fun <T> JsonObject.enumOr(key: String, default: T, of: (String) -> T?): T {
        val element = this[key] ?: return default
        val p = element as? JsonPrimitive ?: throw Invalid()
        if (!p.isString) throw Invalid()
        return of(p.content) ?: throw Invalid()
    }

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
        val element = this[key] ?: return default
        val p = element as? JsonPrimitive ?: throw Invalid()
        if (p.isString) throw Invalid()
        return p.booleanOrNull ?: throw Invalid()
    }

    private fun JsonObject.shortStringOr(key: String, default: String): String {
        val element = this[key] ?: return default
        val p = element as? JsonPrimitive ?: throw Invalid()
        if (!p.isString || p.content.length > MAX_TEXT_CHARS) throw Invalid()
        return p.content
    }

    private fun JsonObject.textLines(): List<String> {
        val array = this["lines"] as? JsonArray ?: throw Invalid()
        if (array.isEmpty() || array.size > MAX_TEXT_LINES) throw Invalid()
        return array.map { item ->
            val p = item as? JsonPrimitive ?: throw Invalid()
            if (!p.isString || p.content.length > MAX_TEXT_CHARS) throw Invalid()
            p.content
        }
    }
}
