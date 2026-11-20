package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.AjustesDeTarjetas
import com.avoqado.pos.core.util.aCentavos
import com.avoqado.pos.core.util.telefonoMx
import kotlinx.serialization.json.*
import java.math.BigDecimal

/**
 * Una tarjeta del formulario. [telefonoDigitos] guarda el teléfono TAL COMO SE ESCRIBIÓ («+52 55 9187 3922»): lo limpia
 * [telefonoMx] al validar y al armar el cuerpo (Flautas, 19-nov: el «+52» no pasaba). El nombre se conserva por las
 * ventas guardadas.
 */
data class SobreEnFormulario(val montoCents: Int?, val canal: String, val nombre: String, val telefonoDigitos: String, val correo: String, val mensaje: String, val lada: String = "52")
data class FormularioDeVenta(val sobres: List<SobreEnFormulario>, val deParteDe: String)
fun FormularioDeVenta.invalidos(a: AjustesDeTarjetas): Set<String> = buildSet {
    if (sobres.size !in 1..20) add("sobres")
    sobres.forEachIndexed { i, s ->
        if (s.montoCents == null || s.montoCents !in a.minAmount.aCentavos()..a.maxAmount.aCentavos()) add("monto$i")
        if (s.canal !in setOf("PRINTED", "HANDED_TO_BUYER", "WHATSAPP", "EMAIL")) add("canal$i")
        if (s.canal == "WHATSAPP" && (s.lada != "52" || telefonoMx(s.telefonoDigitos) == null)) add("telefono$i")
        if (s.canal == "EMAIL" && !s.correo.trim().let { it.length <= 120 && it.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) }) add("correo$i")
        if (s.nombre.trim().length > 80) add("nombre$i")
        if (s.mensaje.length > 200) add("mensaje$i")
    }
    if (deParteDe.trim().length > 80 || (deParteDe.isBlank() && sobres.any { it.canal in setOf("WHATSAPP", "EMAIL") })) add("deParteDe")
    if (a.topePorOperacion != null && sobres.sumOf { (it.montoCents ?: 0).toLong() } >= a.topePorOperacion.aCentavos()) add("total")
}
fun FormularioDeVenta.cuerpo(llave: String, iniciadoPor: String, iniciadoPorNombre: String): String = buildJsonObject {
    put("idempotencyKey", llave); put("source", "AVOQADO_ANDROID")
    put("iniciadoPor", iniciadoPor); put("iniciadoPorNombre", iniciadoPorNombre)
    if (deParteDe.isNotBlank()) put("purchaserName", deParteDe.trim().take(80))
    putJsonArray("sobres") {
        sobres.forEach { s -> add(buildJsonObject {
            put("amount", JsonPrimitive(BigDecimal.valueOf((s.montoCents ?: 0).toLong(), 2)))
            put("deliveryChannel", s.canal)
            if (s.nombre.isNotBlank()) put("recipientName", s.nombre.trim())
            if (s.canal == "WHATSAPP") telefonoMx(s.telefonoDigitos)?.let { put("recipientPhone", it) }
            if (s.canal == "EMAIL") put("recipientEmail", s.correo.trim())
            if (s.mensaje.isNotBlank()) put("message", s.mensaje)
        }) }
    }
}.toString()

/**
 * Por qué «Cobrar» está gris, en palabras, para el renglón de abajo del encabezado: «Revisa el celular y «De parte de»».
 * Antes decía sólo «Revisa los campos de la tarjeta» y «De parte de» quedaba hasta abajo, fuera de la vista (Flautas, 19-nov).
 * Con varias tarjetas dice cuál. null = no hay nada que revisar.
 */
fun motivoDeCobrarApagado(invalidos: Set<String>, tarjetas: Int): String? {
    if (invalidos.isEmpty()) return null
    val campos = listOf(
        "monto" to "el monto", "canal" to "cómo la entregas", "telefono" to "el celular", "correo" to "el correo",
        "nombre" to "el nombre", "mensaje" to "el mensaje",
    )
    val partes = buildList {
        if ("sobres" in invalidos) add("cuántas tarjetas")
        for (i in 0 until tarjetas) for ((clave, texto) in campos) {
            if ("$clave$i" in invalidos) add(if (tarjetas > 1) "$texto (tarjeta ${i + 1})" else texto)
        }
        if ("deParteDe" in invalidos) add("«${TextosDeTarjetas.DE_PARTE_DE}»")
        if ("total" in invalidos) add("el total")
    }.ifEmpty { return TextosDeTarjetas.REVISA_LOS_CAMPOS }
    val lista = if (partes.size == 1) partes[0] else partes.dropLast(1).joinToString(", ") + " y " + partes.last()
    return "Revisa $lista"
}
