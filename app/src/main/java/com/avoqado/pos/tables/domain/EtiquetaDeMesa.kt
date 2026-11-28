package com.avoqado.pos.tables.domain

private val PALABRA_MESA = Regex("^(mesa|table)(?!\\p{L})[\\s#:.\\-–]*", RegexOption.IGNORE_CASE)
private val DOS_LETRAS = Regex("\\p{L}{2}")

/**
 * Cómo se nombra una mesa en pantalla, comanda y ticket (hz:3a60856a:D4, 27-nov).
 * El número es texto libre: un plano puede tener «Mesa 1» y «Bar 1». Un número o código («5», «A1», «12B») lleva «Mesa»;
 * lo que ya trae una palabra («Bar 1», «Terraza 3») va tal cual; y nunca «Mesa Mesa 1». Igual que tableName() del DASHBOARD.
 */
fun etiquetaDeMesa(numero: String): String {
    val n = numero.trim()
    val sinPalabra = PALABRA_MESA.replace(n, "").ifBlank { n }
    return if (DOS_LETRAS.containsMatchIn(sinPalabra)) sinPalabra else "Mesa $sinPalabra"
}
