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
 * - Lo escrito se normaliza sin cambiar su significado: dígitos de cualquier alfabeto («５») pasan a 0-9, «＋» a «+», todo
 *   espacio a espacio, todo guion (y el signo menos «−») a «-», y las marcas invisibles de copiar y pegar (U+202A, U+202E, U+200E…) se quitan.
 *   Nada que cambie el número se borra en silencio (Codex r3): una letra se queda y el número no pasa.
 * - Con «+» delante (también «(+52)»), la persona dijo la lada: si es la del país, se quita (en México también el «1» del
 *   celular viejo: «+52 1 55…», que ningún celular de México empieza con 1); si es OTRA lada, los dígitos se quedan como están y [telefonoMx] no los acepta.
 * - Sin «+», México: 13 dígitos que empiezan con «521» pierden «521»; 12 que empiezan con «52» (y el tercero no es «1», que
 *   es el inicio de «521…») pierden «52». Norteamérica: 11 que empiezan con «1» pierden el «1». Así se quita también tecleando
 *   de uno en uno.
 * - Lo demás se queda como está: un número de 10 dígitos que empieza con 52 no pierde su principio.
 */
fun digitosNacionales(texto: String, lada: String = LADA_MEXICO): String {
    val limpio = sinAdornos(texto)
    val digitos = limpio.filter { it in '0'..'9' }
    if (conLadaEscrita(limpio)) {
        if (!digitos.startsWith(lada)) return digitos
        val resto = digitos.drop(lada.length)
        // Ningún celular de México empieza con 1: tras «+52», un «1» es el prefijo viejo del celular, falte o no un dígito
        // (Codex r4: «+52 1 (55) 9187-392» daba «1559187392», con el «1» contado como parte del número).
        return if (lada == LADA_MEXICO && resto.startsWith("1")) resto.drop(1) else resto
    }
    return sinLadaRepetida(digitos, lada)
}

/**
 * Lo que guarda un campo de teléfono de texto libre (las tarjetas de regalo): lo escrito, normalizado (ver arriba) y sin
 * tope. 🔴 Las marcas de dirección (U+202E) se quitan aquí, ANTES de mostrarse: podían enseñar «55 9187 2293» mientras se
 * mandaba «5591873922» (Codex r2). Una letra se queda a la vista y [telefonoMx] la rechaza; nada se corta (Codex r3).
 */
fun filtrarTecleoDeTelefono(texto: String): String = normalizar(texto)

/**
 * Lo que deja escribir el campo con bandera: lo normalizado, pero sólo dígitos, espacios, «+», paréntesis, guiones y
 * puntos. Una letra no aparece (como siempre en ese campo); lo que se ve es lo que se usa.
 */
fun filtrarTecleoDelCampoConBandera(texto: String): String = normalizar(texto).filter { it in '0'..'9' || it in " +()-." }

/**
 * El texto que enseña el campo con bandera: lo que la persona escribió (con su «+», que dice la lada) mientras siga dando los
 * mismos dígitos que tiene quien lo usa; si quien lo usa los cambió (lo vació al enviar, por ejemplo), esos dígitos.
 * Así pegar y teclear «+34 612 345 678» dan lo mismo (Codex r1 y r2).
 */
fun textoVisibleDelCampo(escrito: String, digitos: String, lada: String): String =
    if (digitosDelCampo(escrito, lada) == digitos) escrito else digitos

/**
 * El celular de México listo para el SERVIDOR («+525591873922»), o null si no quedan 10 dígitos de México. Acepta espacios,
 * guiones, puntos, paréntesis, las marcas invisibles de copiar un contacto y el «+52» o «(+52)»; una letra, otra lada con
 * «+», un «+» fuera de lugar o un número que empieza con 0 o 1 (ningún celular de México: «+52 1» con un dígito de menos,
 * Codex r3) no pasan.
 */
fun telefonoMx(texto: String): String? {
    val limpio = sinAdornos(texto)
    if (!CARACTERES_DE_TELEFONO.matches(limpio)) return null
    if (masFueraDeLugar(limpio)) return null
    if (conLadaEscrita(limpio) && !limpio.filter { it in '0'..'9' }.startsWith(LADA_MEXICO)) return null
    return digitosNacionales(limpio, LADA_MEXICO)
        .takeIf { it.length == 10 && it[0] !in "01" }
        ?.let { "+$LADA_MEXICO$it" }
}

/**
 * Cuántos dígitos nacionales lleva un teléfono según su lada. Una sola regla para el kiosco y el campo con bandera.
 *
 * México (+52) y Norteamérica (+1): exactamente 10. Otra lada: de 8 en adelante, sin pasar de los 15 dígitos de E.164
 * contando la lada, y lada + número suman AL MENOS 11: el SERVIDOR lee «+» + 10 dígitos como un celular mexicano mal
 * guardado (`sinPrefijoMexicanoViejo`), así que «+49 20123456» caería en la ficha de «+52 4920123456» (kiosco, Codex r2 D3).
 */
fun digitosNacionalesValidos(lada: String): IntRange =
    if (lada == LADA_MEXICO || lada == LADA_NORTEAMERICA) 10..10
    else maxOf(8, 11 - lada.length)..(15 - lada.length)

/**
 * Lo que el campo con bandera le entrega a quien lo usa: los dígitos nacionales ([digitosNacionales]) cuando lo escrito ya
 * es un número de ESTE país. Si no lo es, «+» y los dígitos escritos, para que [telefonoCompleto] no lo acepte y el campo
 * diga por qué (hz:nube0000:na519219, Codex r1):
 * - «+» con OTRA lada: la bandera sólo la cambia la persona. Antes esos dígitos se pegaban a la lada elegida: con México,
 *   «+34 612 345 678» salía a «+5234612345678».
 * - un «+» que no va al principio («.+7 912…»): antes se ignoraba y el número pasaba con la lada elegida.
 * - la lada sola, sin número («+», «+52», «+52 1»): antes llegaba vacío y Reservas guardaba sin teléfono con «+52» a la vista.
 */
fun digitosDelCampo(escrito: String, lada: String): String {
    val limpio = sinAdornos(escrito)
    if ('+' !in limpio) return digitosNacionales(escrito, lada)
    val digitos = limpio.filter { it in '0'..'9' }
    if (masFueraDeLugar(limpio) || !digitos.startsWith(lada)) return "+$digitos"
    return digitosNacionales(escrito, lada).ifEmpty { "+$digitos" }
}

/** El «+» de lo escrito va al principio (también «(+52)») y es uno solo. */
fun masBienPuesto(escrito: String): Boolean = !masFueraDeLugar(sinAdornos(escrito))

/**
 * Qué le falta al número del campo con bandera, en una línea para enseñarla junto al campo; null si está completo o vacío.
 * [digitos] es lo que entrega el campo ([digitosDelCampo]); [pais] el nombre del país elegido («México»).
 */
fun avisoDelTelefono(digitos: String, lada: String, pais: String): String? {
    if (digitos.isEmpty()) return null
    if (digitos.any { it !in '0'..'9' }) return "Revisa la lada del número"   // el campo lo dice con más detalle
    val rango = digitosNacionalesValidos(lada)
    val lleva = if (rango.first == rango.last) "${rango.first}" else "de ${rango.first} a ${rango.last}"
    return when {
        digitos.length < rango.first -> conCuantos(rango.first - digitos.length, "Falta", "Faltan") + ": un número de $pais lleva $lleva"
        digitos.length > rango.last -> conCuantos(digitos.length - rango.last, "Sobra", "Sobran") + ": un número de $pais lleva $lleva"
        // Ni en México ni en Norteamérica un número empieza con 0 o 1 (el «1» de México es el prefijo viejo del celular).
        (lada == LADA_MEXICO || lada == LADA_NORTEAMERICA) && digitos[0] in "01" -> "Un número de $pais no empieza con ${digitos[0]}"
        else -> null
    }
}

/** El número del campo con bandera se puede mandar: no está vacío y [avisoDelTelefono] no tiene nada que decir. */
fun telefonoCompleto(digitos: String, lada: String): Boolean =
    digitos.isNotEmpty() && avisoDelTelefono(digitos, lada, "") == null

private fun conCuantos(n: Int, uno: String, varios: String): String =
    if (n == 1) "$uno 1 dígito" else "$varios $n dígitos"

/** «+52 …» o «(+52) …»: la persona escribió la lada. */
private fun conLadaEscrita(limpio: String): Boolean = limpio.trimStart('(').startsWith("+")

/** Un «+» que no va al principio («55+9187…») o más de uno: no es un teléfono (Codex r2). */
private fun masFueraDeLugar(limpio: String): Boolean =
    limpio.count { it == '+' } > 1 || ('+' in limpio && !conLadaEscrita(limpio))

private fun sinLadaRepetida(digitos: String, lada: String): String = when {
    lada == LADA_MEXICO && digitos.length == 13 && digitos.startsWith("521") -> digitos.drop(3)
    lada == LADA_MEXICO && digitos.length == 12 && digitos.startsWith(LADA_MEXICO) && digitos[2] != '1' -> digitos.drop(2)
    lada == LADA_NORTEAMERICA && digitos.length == 11 && digitos.startsWith(LADA_NORTEAMERICA) -> digitos.drop(1)
    else -> digitos
}

/** Dígitos de cualquier alfabeto a 0-9, «＋（）．» a «+().», espacios a « », guiones a «-», sin marcas invisibles. */
private fun normalizar(texto: String): String = buildString {
    for (c in texto) {
        val tipo = Character.getType(c)
        when {
            tipo == Character.FORMAT.toInt() -> Unit
            Character.isDigit(c) -> Character.digit(c, 10).takeIf { it >= 0 }?.let { append('0' + it) } ?: append(c)
            c == '＋' -> append('+')
            c == '（' -> append('(')
            c == '）' -> append(')')
            c == '．' -> append('.')
            tipo == Character.DASH_PUNCTUATION.toInt() || c == '−' -> append('-')   // U+2212, el «menos» (Codex r4)
            c.isWhitespace() || Character.isSpaceChar(c) -> append(' ')
            else -> append(c)
        }
    }
}

/** Lo normalizado, sin espacios ni guiones: lo que se valida. */
private fun sinAdornos(texto: String): String = normalizar(texto).filterNot { it == ' ' || it == '-' }

private const val LADA_MEXICO = "52"
private const val LADA_NORTEAMERICA = "1"
private val CARACTERES_DE_TELEFONO = Regex("^[0-9+().]+$")
