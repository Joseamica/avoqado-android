package com.avoqado.pos.core.util

/**
 * Un teléfono como lo escribe la gente («+52 55 9187 3922», «52 1 55…», «(55) 9187-3922») → los dígitos del número, sin la
 * lada que la persona repitió. Una sola regla para todo el POS (ANDROID y WINDOWS).
 *
 * 🔴 Flautas Coyoacán (19-nov-2026): en «Vender tarjeta» escribió «+52 5591873922», el campo sólo aceptaba 10 dígitos
 * pelones y «Cobrar» se quedó gris con cualquier número. Y el campo con bandera
 * ([com.avoqado.pos.designsystem.components.AvoqadoPhoneInput]) guardaba «525591873922» con México elegido: el recibo por
 * WhatsApp salía a «+52525591873922».
 *
 * Reglas:
 * - Con «+» delante, la persona dijo la lada: si es la del país, se quita (en México también el «1» del celular viejo:
 *   «+52 1 55…»); si es OTRA lada, los dígitos se quedan como están y [telefonoMx] no los acepta (Codex r1: «+53 5 123 4567»
 *   no se vuelve un celular mexicano).
 * - Sin «+», México: 13 dígitos que empiezan con «521» pierden «521»; 12 que empiezan con «52» (y el tercero no es «1», que
 *   es el inicio de «521…») pierden «52». Norteamérica: 11 que empiezan con «1» pierden el «1». Así se quita también tecleando
 *   de uno en uno, donde el «+» no llega.
 * - Lo demás se queda como está: un número de 10 dígitos que empieza con 52 no pierde su principio.
 */
fun digitosNacionales(texto: String, lada: String = LADA_MEXICO): String {
    val limpio = sinAdornos(texto)
    val digitos = limpio.filter { it in '0'..'9' }
    if (limpio.startsWith("+")) {
        if (!digitos.startsWith(lada)) return digitos
        val resto = digitos.drop(lada.length)
        return if (lada == LADA_MEXICO && resto.length == 11 && resto.startsWith("1")) resto.drop(1) else resto
    }
    return sinLadaRepetida(digitos, lada)
}

/**
 * Lo que guarda el campo con bandera ([com.avoqado.pos.designsystem.components.AvoqadoPhoneInput]): sólo dígitos y sin la
 * lada repetida, decidido por el largo y nunca por el «+». El campo sólo guarda dígitos, así que al teclear el «+» se pierde
 * en la primera tecla: si pegar dependiera del «+», pegar y teclear lo mismo darían números distintos (Codex r1).
 */
fun digitosDelCampoConBandera(texto: String, lada: String): String =
    sinLadaRepetida(sinAdornos(texto).filter { it in '0'..'9' }, lada)

/**
 * El celular de México listo para el SERVIDOR («+525591873922»), o null si no quedan 10 dígitos de México. Acepta espacios
 * (también los no separables), guiones, puntos, paréntesis, las marcas invisibles que se pegan al copiar un contacto y el
 * «+52»; una letra, dígitos de ancho completo u otra lada con «+» no pasan.
 */
fun telefonoMx(texto: String): String? {
    val limpio = sinAdornos(texto)
    if (!CARACTERES_DE_TELEFONO.matches(limpio)) return null
    if (limpio.startsWith("+") && !limpio.filter { it in '0'..'9' }.startsWith(LADA_MEXICO)) return null
    return digitosNacionales(limpio, LADA_MEXICO).takeIf { it.length == 10 }?.let { "+$LADA_MEXICO$it" }
}

private fun sinLadaRepetida(digitos: String, lada: String): String = when {
    lada == LADA_MEXICO && digitos.length == 13 && digitos.startsWith("521") -> digitos.drop(3)
    lada == LADA_MEXICO && digitos.length == 12 && digitos.startsWith(LADA_MEXICO) && digitos[2] != '1' -> digitos.drop(2)
    lada == LADA_NORTEAMERICA && digitos.length == 11 && digitos.startsWith(LADA_NORTEAMERICA) -> digitos.drop(1)
    else -> digitos
}

/** Quita espacios de cualquier tipo, guiones de cualquier tipo y las marcas invisibles (U+202A, U+200E…) de copiar y pegar. */
private fun sinAdornos(texto: String): String = texto.filterNot { c ->
    c.isWhitespace() || Character.isSpaceChar(c) ||
        Character.getType(c) == Character.FORMAT.toInt() || Character.getType(c) == Character.DASH_PUNCTUATION.toInt()
}

private const val LADA_MEXICO = "52"
private const val LADA_NORTEAMERICA = "1"
private val CARACTERES_DE_TELEFONO = Regex("^[0-9+().]+$")
