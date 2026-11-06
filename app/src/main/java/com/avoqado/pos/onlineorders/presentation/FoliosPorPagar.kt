package com.avoqado.pos.onlineorders.presentation

import com.avoqado.pos.onlineorders.domain.OnlineOrder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/** Tienda híbrida (A2): lógica PURA de las secciones «Por pagar» y «Por entregar» (la pantalla sólo la pinta). */

/** «Ya pagó $X de $Y»: hubo un abono y todavía falta algo. */
internal fun hayAbono(pedido: OnlineOrder): Boolean = pedido.remainingCents in 1 until pedido.totalCents

internal fun yaPagadoCents(pedido: OnlineOrder): Long = (pedido.totalCents - pedido.remainingCents).coerceAtLeast(0)

/**
 * El número de una sección nunca contradice su lista (m1 de A1: un folio dañado se salta y el servidor lo sigue contando). Con más
 * páginas por traer vale el exacto del servidor; si no, lo que de verdad se ve.
 */
fun numeroDeSeccion(conteo: Int, mostrados: Int, hayMas: Boolean): Int = if (hayMas) max(conteo, mostrados) else mostrados

/** La insignia de «Pedidos en línea» en «Más»: lo que espera una persona (aceptar o cobrar). */
fun numeroDelMenu(porAceptar: Int, porPagar: Int): Int = porAceptar + porPagar

private fun normalizar(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * R61: con 3 o más caracteres (sin contar espacios ni guiones) busca por folio, sin importar mayúsculas ni la W ni el guion, y por
 * nombre sin importar acentos. Con menos, no filtra.
 */
fun filtrarFolios(lista: List<OnlineOrder>, texto: String): List<OnlineOrder> {
    val nombre = normalizar(texto).trim()
    val folio = nombre.replace("-", "").replace(" ", "")
    if (folio.length < 3) return lista
    return lista.filter {
        normalizar(it.orderNumber).contains(folio) || normalizar(it.customerName.orEmpty()).contains(nombre)
    }
}

/** Cuánto falta para que venza un folio. [pronto] = 5 min o menos: el punto se pinta en ámbar (nunca rojo, nunca el texto). */
data class VencimientoFolio(val texto: String, val pronto: Boolean)

/**
 * Con red «Vence en N min» (N redondeado hacia arriba: nunca promete de más); sin red la hora fija «Vence a las HH:MM», porque un
 * contador sin conexión miente. Ya vencido: «Venció a las HH:MM», sin cuenta ni color. `null` si el folio no trae vencimiento.
 */
fun textoDeVencimiento(pedido: OnlineOrder, ahora: Instant, sinConexion: Boolean): VencimientoFolio? {
    val vence = pedido.acceptDeadlineAt ?: return null
    if (!vence.isAfter(ahora)) return VencimientoFolio(TextosPedidosEnLinea.yaVencio(horaDelNegocio(vence)), pronto = false)
    if (sinConexion) return VencimientoFolio(TextosPedidosEnLinea.venceALas(horaDelNegocio(vence)), pronto = false)
    val restante = Duration.between(ahora, vence)
    val minutos = restante.toMinutes() + if (restante.toMillis() % 60_000 > 0) 1 else 0
    return VencimientoFolio(TextosPedidosEnLinea.venceEn(minutos.toInt()), pronto = minutos <= 5)
}
