package com.avoqado.pos.customers.domain

import com.avoqado.pos.customers.data.model.Customer
import java.text.Normalizer

/**
 * Búsqueda de clientes SIN red: la misma regla que el servidor (`GET /customers?search=`), sobre los clientes que esta
 * tablet ya recibió. Con red manda el servidor (busca en TODO el negocio); esto sólo corre cuando no hay.
 *
 * - Teléfono: si el texto tiene ≥ 4 dígitos y sólo `[0-9 +-()]`, se comparan dígitos contra dígitos; si trae más de 10
 *   dígitos y empieza con `521` o `52`, esa lada se quita del texto buscado (los clientes viejos sin lada también casan).
 * - Nombre: cada palabra debe aparecer en el nombre, el apellido o el correo, sin importar mayúsculas ni acentos.
 *
 * 🔴 Antes había TRES copias de un filtro que comparaba el teléfono letra por letra («81 2944 0042» nunca casaba con
 * «+528129440042»). Una sola función, con su tabla de pruebas, para que no vuelvan a divergir.
 */
const val AVISO_SIN_RED_CLIENTES = "Sin conexión: buscando sólo entre los clientes guardados en la tablet"

private val SOLO_TELEFONO = Regex("^[0-9 +\\-()]+$")
private val NO_DIGITO = Regex("[^0-9]")
private val MARCAS_DE_ACENTO = Regex("\\p{Mn}+")
private val ESPACIOS = Regex("\\s+")

fun filtrarClientesLocal(clientes: List<Customer>, texto: String): List<Customer> {
    val limpio = texto.trim()
    if (limpio.isEmpty()) return clientes

    digitosDeTelefonoBuscado(limpio)?.let { buscado ->
        return clientes.filter { cliente -> cliente.phone?.replace(NO_DIGITO, "")?.contains(buscado) == true }
    }

    val palabras = sinAcentos(limpio).split(ESPACIOS).filter { it.isNotEmpty() }
    return clientes.filter { cliente ->
        val campos = listOfNotNull(cliente.firstName, cliente.lastName, cliente.email).map(::sinAcentos)
        palabras.all { palabra -> campos.any { campo -> campo.contains(palabra) } }
    }
}

/** ¿El texto escrito es un teléfono? Decide si siembra el TELÉFONO o el NOMBRE del alta de cliente. */
fun esTextoDeTelefono(texto: String): Boolean = texto.isNotBlank() && SOLO_TELEFONO.matches(texto.trim())

/** Los dígitos a buscar si el texto parece teléfono (≥ 4 dígitos), sin la lada sobrante; null si es un nombre. */
internal fun digitosDeTelefonoBuscado(texto: String): String? {
    if (!esTextoDeTelefono(texto)) return null
    val digitos = texto.replace(NO_DIGITO, "")
    if (digitos.length < 4) return null
    return when {
        digitos.length > 10 && digitos.startsWith("521") -> digitos.removePrefix("521")
        digitos.length > 10 && digitos.startsWith("52") -> digitos.removePrefix("52")
        else -> digitos
    }
}

/** Minúsculas y sin acentos («Muñoz» → «munoz»), como `unaccent` + `ILIKE` en el servidor. */
internal fun sinAcentos(texto: String): String =
    Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(MARCAS_DE_ACENTO, "")

/**
 * «{n} de {total}: escribe más para afinar» (auditoría A #10 y B3). La tablet pide 50; el servidor dice cuántos cumplen
 * la búsqueda en TODO el negocio — el exacto (`meta.totalMatches`) aunque haya topado en 5000. Sin «cargar más» a
 * propósito: con texto, afinar es más rápido que hojear.
 * null = no hay nada que decir: sin texto (la lista son los recientes), sin total (servidor viejo), o ya se ven todos.
 */
fun avisoDeMasResultados(texto: String, mostrados: Int, total: Int?, topada: Boolean): String? {
    if (texto.isBlank() || total == null) return null
    if (total <= mostrados && !topada) return null
    return "$mostrados de $total: escribe más para afinar"
}
