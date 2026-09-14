package com.avoqado.pos.printing.receiptlayout

import java.text.Normalizer

/**
 * Primitivas de texto del ticket. Puerto 1:1 de avoqado-server `sanitizeText.ts`, `columns.ts` y
 * `address.ts`.
 *
 * 🔴 Las longitudes son unidades UTF-16 (`String.length`), igual que `.length` de JavaScript. Tras
 * `force` todo carácter es Latin-1 y ocupa exactamente una celda del papel.
 */
object ReceiptText {
    const val QTY_WIDTH = 5
    const val PRICE_WIDTH = 10

    private fun isRemoved(cp: Int): Boolean =
        cp in 0x00..0x1F || cp in 0x7F..0x9F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 ||
            cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF

    private fun isPrintableLatin1(cp: Int): Boolean = cp in 0x20..0x7E || cp in 0xA0..0xFF

    /**
     * Nunca lanza: controles, bidi y ancho cero se BORRAN; lo no imprimible se vuelve '?'.
     * Recorre por CODE POINT (como la bandera `u` de la regex del servidor): un emoji es UN '?', no dos.
     * Se vuelve a llamar antes de emitir bytes: defensa en profundidad contra un ESC guardado.
     */
    fun force(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isRemoved(cp) -> Unit
                isPrintableLatin1(cp) -> out.appendCodePoint(cp)
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    /** true = el papel no imprime algo de este texto (la regla de `validateLayout` del servidor). */
    fun hasForbiddenChars(raw: String): Boolean {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            if (!isPrintableLatin1(cp)) return true
        }
        return false
    }

    /** El `\s` de JavaScript, exacto: `trim()` y `split(/\s+/)` del servidor cortan por estos. */
    private fun isJsSpace(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    fun jsTrim(s: String): String = s.trim(::isJsSpace)

    private fun words(s: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (c in s) {
            if (isJsSpace(c)) {
                if (current.isNotEmpty()) { out += current.toString(); current.clear() }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    fun wrap(text: String, width: Int): List<String> {
        val ws = words(text)
        if (ws.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in ws) {
            var rest = word
            while (rest.length > width) {
                if (current.isNotEmpty()) { lines += current; current = "" }
                lines += rest.substring(0, width)
                rest = rest.substring(width)
            }
            if (current.isEmpty()) current = rest
            else if (current.length + 1 + rest.length <= width) current += " $rest"
            else { lines += current; current = rest }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    fun divider(width: Int, char: Char = '-'): String = char.toString().repeat(width)

    /** Etiqueta y valor. Si no caben juntos: etiqueta arriba y valor abajo a la derecha. Nunca recorta. */
    fun twoColumnLines(left: String, right: String, width: Int): List<String> {
        if (left.length + 1 + right.length <= width) {
            return listOf(left + " ".repeat(width - left.length - right.length) + right)
        }
        return wrap(left, width) + wrap(right, width).map { it.padStart(width) }
    }

    /**
     * Cantidad · artículo · precio. El nombre se ENVUELVE bajo su columna; nunca se recorta. La columna
     * de cantidad mide 5 y CRECE con la cantidad (D2): «10000» no se pega al nombre y «123456» no se pasa
     * del papel. `String.length` ya cuenta unidades UTF-16, igual que JavaScript.
     */
    fun itemLines(qty: String, name: String, price: String, width: Int, indent: Int = 0): List<String> {
        val qtyWidth = maxOf(QTY_WIDTH, qty.length + 1)
        val priceWidth = maxOf(PRICE_WIDTH, price.length)
        val nameWidth = width - qtyWidth - priceWidth
        val chunks = wrap(name, maxOf(1, nameWidth - 1 - indent)).map { " ".repeat(indent) + it }
        return chunks.mapIndexed { i, chunk ->
            if (i == 0) qty.padEnd(qtyWidth) + chunk.padEnd(maxOf(0, nameWidth)) + price.padStart(priceWidth)
            else " ".repeat(qtyWidth) + chunk
        }
    }

    fun indentedLines(text: String, width: Int): List<String> = wrap(text, width - 2).map { "  $it" }

    private fun normalizeForMatch(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** «Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600». No repite lo que `address` ya dice. */
    fun addressLine(address: String?, city: String?, state: String?, zipCode: String?): String? {
        val parts = mutableListOf<String>()
        fun add(value: String?) {
            val v = value?.let(::jsTrim)?.takeIf { it.isNotEmpty() } ?: return
            if (parts.none { normalizeForMatch(it).contains(normalizeForMatch(v)) }) parts += v
        }
        add(address)
        add(city)
        add(state)
        val cp = zipCode?.let(::jsTrim)?.takeIf { it.isNotEmpty() }
        if (cp != null && parts.none { it.contains(cp) }) parts += "CP $cp"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
