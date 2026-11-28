package com.avoqado.pos.customerdisplay

import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.kiosk.domain.nationalDigitsFor

/**
 * El WhatsApp que el cliente teclea en la pantalla del cliente (la segunda pantalla de la D3) para mandarse el recibo.
 *
 * 🔴 hz:nube0000:nae727f6 (24-nov-2026): «Enviar» se prendía con 8 a 15 dígitos sin importar el país. Con México elegido,
 * 8 o 9 dígitos salían a «+5212345678»: el recibo iba a un número que no existe o de otra persona.
 *
 * La regla de cuántos dígitos lleva cada país es la del kiosco ([nationalDigitsFor]): México y Norteamérica exactamente 10;
 * otra lada, su rango sin pasar de los 15 dígitos de E.164. El teclado sólo da dígitos, así que aquí no hay letras ni «+».
 */
internal fun avisoDelWhatsAppDelCliente(digitos: String, pais: Country): String? {
    if (digitos.isEmpty()) return null
    val rango = nationalDigitsFor(pais)
    val lleva = if (rango.first == rango.last) "${rango.first}" else "de ${rango.first} a ${rango.last}"
    val diezFijos = pais.dialCode == "52" || pais.dialCode == "1"
    return when {
        // Ni en México ni en Norteamérica un número empieza con 0 o 1 (el «1» de México es el prefijo viejo del celular).
        diezFijos && digitos[0] in "01" -> "Un número de ${pais.name} no empieza con ${digitos[0]}"
        digitos.length < rango.first -> conCuantos(rango.first - digitos.length, "Falta", "Faltan") + ": un número de ${pais.name} lleva $lleva"
        digitos.length > rango.last -> conCuantos(digitos.length - rango.last, "Sobra", "Sobran") + ": un número de ${pais.name} lleva $lleva"
        else -> null
    }
}

/** «Enviar» se puede tocar: hay número y no falta ni sobra nada. */
internal fun whatsAppDelClienteCompleto(digitos: String, pais: Country): Boolean =
    digitos.isNotEmpty() && avisoDelWhatsAppDelCliente(digitos, pais) == null

/** El número listo para mandar («+525591873922»), o null si no está completo. Nunca se manda uno a medias. */
internal fun whatsAppDelCliente(digitos: String, pais: Country): String? =
    if (whatsAppDelClienteCompleto(digitos, pais)) "+${pais.dialCode}$digitos" else null

/** Hasta dónde deja teclear el teclado con ese país. */
internal fun digitosMaximosDelWhatsApp(pais: Country): Int = nationalDigitsFor(pais).last

private fun conCuantos(n: Int, uno: String, varios: String): String =
    if (n == 1) "$uno 1 dígito" else "$varios $n dígitos"
