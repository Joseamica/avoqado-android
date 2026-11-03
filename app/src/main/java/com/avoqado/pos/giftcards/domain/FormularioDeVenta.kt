package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.AjustesDeTarjetas
import com.avoqado.pos.core.util.aCentavos
import kotlinx.serialization.json.*
import java.math.BigDecimal

data class SobreEnFormulario(val montoCents: Int?, val canal: String, val nombre: String, val telefonoDigitos: String, val correo: String, val mensaje: String, val lada: String = "52")
data class FormularioDeVenta(val sobres: List<SobreEnFormulario>, val deParteDe: String)
fun telefonoMx(digitos: String): String? = digitos.takeIf { it.length == 10 && it.all { c -> c in '0'..'9' } }?.let { "+52$it" }
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
