package com.avoqado.pos.onlineorders.presentation

import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
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

/** A3: el botón de cobrar un folio dice lo único que se puede sin red (la tarjeta y la tarjeta de regalo necesitan conexión). */
fun textoDeCobro(sinConexion: Boolean): String =
    if (sinConexion) TextosPedidosEnLinea.COBRAR_EN_EFECTIVO else TextosPedidosEnLinea.COBRAR

/** A3: «Cancelar folio» no existe en un folio con dinero ni sin `orders:cancel-unpaid`; sin red vuelve con la conexión. */
fun muestraCancelarFolio(pedido: OnlineOrder, puedeCancelar: Boolean, sinConexion: Boolean): Boolean =
    puedeCancelar && !sinConexion && sinDinero(pedido)

/**
 * A3: por qué ya no se cobra un folio cuya cuenta se canceló. Pasada su hora (o sin hora conocida), venció; antes, otra caja o el panel
 * lo canceló. Las dos dicen «cóbralo como venta normal». Lo usan la relectura al tocar «Cobrar» y el 400 dentro del cobro.
 */
fun textoDeFolioCerrado(venceA: Instant?, ahora: Instant): String =
    if (venceA == null || !venceA.isAfter(ahora)) TextosPedidosEnLinea.FOLIO_VENCIDO else TextosPedidosEnLinea.FOLIO_CANCELADO

/** R67: «Pagado a las HH:MM» en «Por entregar»; null si el SERVIDOR no manda `paidAt` (se queda como hoy). */
fun textoDePago(pedido: OnlineOrder): String? = pedido.paidAt?.let { TextosPedidosEnLinea.pagadoALas(horaDelNegocio(it)) }

/**
 * A3: lo cobrado en efectivo en ESTE aparato que la cola todavía no sube ([enCola]) sale de «Por pagar» y entra primero a «Por
 * entregar», sin esperar a la red: así nadie lo cobra dos veces. La lista del servidor no se toca: esto es sólo lo que se pinta.
 */
fun conLoCobradoEnElAparato(lista: ListaPorPagar, enCola: Set<String>): ListaPorPagar {
    val (movidos, quedan) = lista.porPagar.partition { it.orderId in enCola }
    if (movidos.isEmpty()) return lista
    val ids = movidos.map { it.orderId }.toSet()
    return lista.copy(
        porPagar = quedan,
        porEntregar = movidos + lista.porEntregar.filter { it.orderId !in ids },
        counts = ConteosPorPagar((lista.counts.toPay - movidos.size).coerceAtLeast(0), lista.counts.toDeliver + movidos.size),
    )
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
