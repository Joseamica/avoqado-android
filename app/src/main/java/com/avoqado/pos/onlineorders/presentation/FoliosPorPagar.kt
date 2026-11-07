package com.avoqado.pos.onlineorders.presentation

import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
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

/**
 * R100: las marcas de tarjeta que una lectura 2xx y COMPLETA del negocio [venueId] ya cuenta (quien llama garantiza las dos cosas:
 * un 403/404 «no disponible» o una lectura parcial nunca llegan aquí). [vistos] = las órdenes que trajo esa lectura; [porPagarCompleto]
 * = los folios de su «Por pagar» si la sección vino ENTERA (null si esta lectura no lo dice). Se suelta una marca si:
 * - la orden está a la vista y lo que falta bajó al menos lo cobrado (`remaining <= saldoAntes - monto`), o
 * - es un folio de ESTE negocio y ya no está en un «Por pagar» completo.
 * Nunca la de otro negocio. Una marca de A5 (sin negocio) sólo con la orden a la vista: su ausencia no prueba nada. Una lista de
 * antes del cobro no suelta nada: trae la orden con todo su saldo. PURA.
 */
fun tarjetasConfirmadas(
    marcas: List<FolioCobradoConTarjeta>,
    venueId: String,
    vistos: List<OnlineOrder>,
    porPagarCompleto: Set<String>? = null,
): List<FolioCobradoConTarjeta> {
    val porId = vistos.associateBy { it.orderId }
    return marcas.filter { marca ->
        if (marca.venueId != null && marca.venueId != venueId) return@filter false
        val visto = porId[marca.orderId]
        when {
            visto != null -> visto.remainingCents <= marca.saldoAntesCents - marca.montoCents
            marca.venueId == null -> false
            else -> marca.esFolio && porPagarCompleto != null && marca.orderId !in porPagarCompleto
        }
    }
}

/**
 * R100: lo cobrado con tarjeta en ESTE aparato que la lista a la vista ([lista]) todavía no cuenta, por folio de «Por pagar» -> monto.
 * Junta las marcas ([marcas], ya del negocio) y los positivos que siguen en la lista durable de cobros ([positivos], orden -> monto).
 * La pantalla los pasa a «Por entregar» y el número del menú no los cuenta. PURA.
 */
fun cobradosConTarjetaEnLaLista(
    marcas: List<FolioCobradoConTarjeta>,
    positivos: Map<String, Long>,
    lista: ListaPorPagar?,
): Map<String, Long> {
    val porPagar = lista?.porPagar.orEmpty().associateBy { it.orderId }
    val deMarcas = marcas.filter { m -> porPagar[m.orderId]?.let { it.remainingCents > m.saldoAntesCents - m.montoCents } == true }
        .groupBy { it.orderId }.mapValues { (_, m) -> m.sumOf { it.montoCents } }
    return (deMarcas.keys + positivos.keys.filter { it in porPagar }).associateWith { deMarcas[it] ?: positivos.getValue(it) }
}

/** R100 (Codex r2 #3): cómo se veía un folio ANTES de una acción: su sección, su estado y lo que falta. */
data class HuellaDeFolio(val seccion: String, val status: OnlineOrderStatus, val remainingCents: Long)

/** La huella de [orderId] en [lista] (`toPay` / `toDeliver`); null si no está. PURA. */
fun huellaDe(lista: ListaPorPagar?, orderId: String): HuellaDeFolio? {
    lista ?: return null
    lista.porPagar.firstOrNull { it.orderId == orderId }?.let { return HuellaDeFolio("toPay", it.status, it.remainingCents) }
    lista.porEntregar.firstOrNull { it.orderId == orderId }?.let { return HuellaDeFolio("toDeliver", it.status, it.remainingCents) }
    return null
}

/**
 * R100 (Codex r2 #3), la regla de la TPV y del DASHBOARD: tras una acción confirmada («Listo», «Entregado», «Cancelar folio») el
 * folio se esconde mientras la lista lo siga trayendo con la huella de ANTES de la acción; así una relectura que falló nunca devuelve
 * un «Listo» o un «Cancelar» vivos. En cuanto el SERVIDOR lo trae distinto, vuelve a salir. PURA.
 */
fun sinLosYaAtendidos(lista: ListaPorPagar, ocultos: Map<String, HuellaDeFolio>): ListaPorPagar {
    if (ocultos.isEmpty()) return lista
    val porPagar = lista.porPagar.filterNot { ocultos[it.orderId] == huellaDe(lista, it.orderId) }
    val porEntregar = lista.porEntregar.filterNot { ocultos[it.orderId] == huellaDe(lista, it.orderId) }
    if (porPagar.size == lista.porPagar.size && porEntregar.size == lista.porEntregar.size) return lista
    return lista.copy(
        porPagar = porPagar,
        porEntregar = porEntregar,
        counts = ConteosPorPagar(
            (lista.counts.toPay - (lista.porPagar.size - porPagar.size)).coerceAtLeast(0),
            (lista.counts.toDeliver - (lista.porEntregar.size - porEntregar.size)).coerceAtLeast(0),
        ),
    )
}

/** Lo que queda escondido tras una lectura buena: sólo los folios que la lista sigue trayendo IGUAL; los demás se podan. PURA. */
fun ocultosQueSiguen(ocultos: Map<String, HuellaDeFolio>, lista: ListaPorPagar): Map<String, HuellaDeFolio> =
    ocultos.filter { (id, huella) -> huellaDe(lista, id) == huella }

/** R98 (A): un folio ya marcado «Listo» sigue en «Por entregar» con su etiqueta y sólo «Entregado» (nunca «Listo» otra vez). */
fun yaEstaListo(pedido: OnlineOrder): Boolean = pedido.status == OnlineOrderStatus.READY

/** Cuánto falta para que venza un folio. [pronto] = 5 min o menos: el punto se pinta en ámbar (nunca rojo, nunca el texto). */
data class VencimientoFolio(val texto: String, val pronto: Boolean)

/**
 * Con red «Vence en N min» (N redondeado hacia arriba: nunca promete de más); sin red la hora fija «Vence a las HH:MM», porque un
 * contador sin conexión miente. Ya vencido: «Venció a las HH:MM», sin cuenta ni color. `null` si el folio no trae vencimiento, o si
 * tiene abono (A5, Codex r1 #5): el SERVIDOR conserva vivo un folio con dinero; la tarjeta dice «Ya pagó $X de $Y» y «Falta $Z».
 */
fun textoDeVencimiento(pedido: OnlineOrder, ahora: Instant, sinConexion: Boolean): VencimientoFolio? {
    if (hayAbono(pedido)) return null
    val vence = pedido.acceptDeadlineAt ?: return null
    if (!vence.isAfter(ahora)) return VencimientoFolio(TextosPedidosEnLinea.yaVencio(horaDelNegocio(vence)), pronto = false)
    if (sinConexion) return VencimientoFolio(TextosPedidosEnLinea.venceALas(horaDelNegocio(vence)), pronto = false)
    val restante = Duration.between(ahora, vence)
    val minutos = restante.toMinutes() + if (restante.toMillis() % 60_000 > 0) 1 else 0
    return VencimientoFolio(TextosPedidosEnLinea.venceEn(minutos.toInt()), pronto = minutos <= 5)
}
