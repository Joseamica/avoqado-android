package com.avoqado.pos.core.util

/**
 * Un teléfono como lo escribe la gente («+52 55 9187 3922», «52 1 55…», «(55) 9187-3922») → los dígitos del número, sin la
 * lada que la persona repitió. Una sola regla para todo el POS (ANDROID y WINDOWS).
 *
 * 🔴 Flautas Coyoacán (19-nov-2026): en «Vender tarjeta» escribió «+52 5591873922», el campo sólo aceptaba 10 dígitos
 * pelones y «Cobrar» se quedó gris con cualquier número. Y el campo con bandera ([com.avoqado.pos.designsystem.components.AvoqadoPhoneInput])
 * guardaba «525591873922» con México elegido: el recibo por WhatsApp salía a «+52525591873922».
 *
 * Reglas, en este orden:
 * - México, 13 dígitos que empiezan con «521» (el «1» del celular viejo): se quita «521».
 * - México o Norteamérica, exactamente lada + 10 dígitos: se quita la lada (así se quita también tecleando de uno en uno,
 *   donde el «+» no llega).
 * - Con «+» delante y la lada repetida: se quita la lada (en México y Norteamérica, sólo si sobran dígitos: «+5255123456»
 *   se queda con sus 10).
 * - Lo demás se queda como está: un número de 10 dígitos que empieza con 52 no pierde su principio.
 */
fun digitosNacionales(texto: String, lada: String = LADA_MEXICO): String {
    val limpio = texto.trim()
    val digitos = limpio.filter { it in '0'..'9' }
    val diezDigitos = lada == LADA_MEXICO || lada == LADA_NORTEAMERICA
    return when {
        lada == LADA_MEXICO && digitos.length == 13 && digitos.startsWith("521") -> digitos.drop(3)
        diezDigitos && digitos.length == 10 + lada.length && digitos.startsWith(lada) -> digitos.drop(lada.length)
        limpio.startsWith("+") && digitos.startsWith(lada) && (!diezDigitos || digitos.length > 10) -> digitos.drop(lada.length)
        else -> digitos
    }
}

/**
 * El celular de México listo para el SERVIDOR («+525591873922»), o null si no quedan 10 dígitos. Acepta espacios, guiones,
 * puntos, paréntesis y el «+52»; cualquier otra cosa (una letra, dígitos de ancho completo) no pasa.
 */
fun telefonoMx(texto: String): String? {
    if (!CARACTERES_DE_TELEFONO.matches(texto.trim())) return null
    return digitosNacionales(texto, LADA_MEXICO).takeIf { it.length == 10 }?.let { "+$LADA_MEXICO$it" }
}

private const val LADA_MEXICO = "52"
private const val LADA_NORTEAMERICA = "1"
private val CARACTERES_DE_TELEFONO = Regex("^[0-9 +\\-().]+$")
