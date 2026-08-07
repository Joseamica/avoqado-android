package com.avoqado.pos.pos.data

/**
 * Etiqueta EAN-13 de peso variable: `PP + PLU(5) + gramos(5) + C`.
 *
 * El prefijo PP es privado del venue (normalmente 20..29). La etiqueta sólo
 * aporta identidad y peso; el catálogo de Avoqado sigue siendo autoridad de
 * producto y precio por kilogramo.
 */
data class VariableWeightBarcode(
    val plu: String,
    val weightKg: Double,
)

fun decodeVariableWeightBarcode(raw: String, prefix: String): VariableWeightBarcode? {
    val code = raw.trim()
    if (prefix.length != 2 || !prefix.all(Char::isDigit)) return null
    if (code.length != 13 || !code.all(Char::isDigit) || !code.startsWith(prefix)) return null
    if (checkDigit(code.dropLast(1)) != code.last().digitToInt()) return null

    val grams = code.substring(7, 12).toIntOrNull() ?: return null
    if (grams <= 0) return null
    return VariableWeightBarcode(
        plu = code.substring(2, 7),
        weightKg = grams / 1_000.0,
    )
}
