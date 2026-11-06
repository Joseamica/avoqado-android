package com.avoqado.pos.onlineorders.domain

import java.time.Instant

/** Estados del pedido en línea según el SERVIDOR; lo que no conocemos es [UNKNOWN] (nunca una excepción). */
enum class OnlineOrderStatus {
    PENDING_ACCEPTANCE, ACCEPTED, READY, COMPLETED, REJECTED, EXPIRED, CANCELLED_BY_CUSTOMER, CANCELLED_BY_STAFF, UNKNOWN;

    companion object {
        fun from(raw: String?): OnlineOrderStatus =
            entries.firstOrNull { it != UNKNOWN && it.name == raw?.trim() } ?: UNKNOWN
    }
}

data class OnlineOrderModifier(val name: String, val quantity: Int)

data class OnlineOrderItem(
    val productId: String?,
    val categoryId: String?,
    val name: String,
    val quantity: Int,
    val note: String?,
    val modifiers: List<OnlineOrderModifier>,
)

/** Dinero en centavos enteros: la bandeja manda pesos y se convierten con decimal exacto. */
data class OnlineOrder(
    val orderId: String,
    val onlineOrderId: String,
    val orderNumber: String,
    val status: OnlineOrderStatus,
    val paymentMode: String,
    val paymentStatus: String,
    val customerName: String?,
    val customerPhone: String?,
    val note: String?,
    val createdAt: Instant,
    val acceptDeadlineAt: Instant?,
    val estimatedReadyAt: Instant?,
    val totalCents: Long,
    val remainingCents: Long,
    val needsPrint: Boolean,
    val items: List<OnlineOrderItem>,
    /** Pedido pagado con tarjeta (`CARD_ONLINE`): `AUTHORIZED` (apartada) | `CONFIRMING` (el banco confirma) | null. Un servidor viejo no lo manda. */
    val cardStatus: String? = null,
    /** `card.status` del servidor (p. ej. `CAPTURED`); null si no hay tarjeta o el servidor es viejo. */
    val cardChargeStatus: String? = null,
    /** Motivo recibido del cierre: `OUT_OF_ITEMS` | `CLOSED` | `TOO_BUSY` | `OTHER` | `PAYMENT_FAILED`; null si no hay. */
    val rejectReason: String? = null,
    /** Tienda híbrida: `false` = folio que se paga en caja (nadie lo acepta); null = el servidor es viejo y no lo manda. */
    val requiresAcceptance: Boolean? = null,
    /** De dónde pidió la clienta (p. ej. `QR`, `WEB`); null si el servidor es viejo. */
    val channel: String? = null,
    /** R67: cuándo quedó pagado un folio de «Por entregar» («Pagado a las HH:MM»); null si el SERVIDOR no lo manda. */
    val paidAt: Instant? = null,
)

/**
 * Lo que dice el servidor al tocar «Cobrar»: el saldo (lo único que mueve dinero) y el precio de cada producto del pedido, que
 * sólo sirve para enseñar «Ver productos». [precios] es null si ya hubo un pago o si algún producto no se pudo leer completo:
 * el saldo vale igual. Tienda híbrida (A3): [estadoDeLaCuenta] (`Order.status`; `CANCELLED` = el folio venció o lo cancelaron)
 * y [pagadoA] (el último cobro que entró) dicen, al releer un folio, si ya no se puede cobrar o si otra caja ya lo cobró.
 */
data class SaldoDelPedido(
    val saldoCents: Long,
    val precios: List<PrecioDeRenglon>? = null,
    val estadoDeLaCuenta: String? = null,
    val pagadoA: Instant? = null,
)

/**
 * Un producto del detalle del pedido: nombre guardado, cantidad, lo que cobra el renglón entero (con extras) en centavos, y su
 * nota y extras de AHORA (para no enseñar los de una bandeja vieja si otra caja los cambió).
 */
data class PrecioDeRenglon(
    val name: String,
    val quantity: Int,
    val totalCents: Long,
    val note: String? = null,
    val modifierNames: List<String> = emptyList(),
)

/** [pending] y [active] vienen en TODA respuesta (conteos exactos del servidor, aunque la lista sea de otro filtro). */
data class Inbox(
    val serverTime: Instant,
    val pending: Int,
    val active: Int,
    val done: Int,
    val orders: List<OnlineOrder>,
    val storeOff: Boolean,
    val truncated: Boolean,
    val nextCursor: String?,
)

/**
 * Rappi 2b: un pedido de plataforma que el negocio cobra en EFECTIVO con su repartidor («Rappi · por cobrar»). Centavos enteros tal
 * como los manda `GET online-orders/delivery-cash`; [remainingBalanceCents] = venta + propina + lo de la plataforma.
 */
data class PedidoPorCobrar(
    val orderId: String,
    val orderNumber: String,
    val provider: String,
    val customerName: String?,
    val createdAt: Instant,
    val remainingBalanceCents: Long,
    val saleCents: Long,
    val tipCents: Long,
    val thirdPartyCents: Long,
    val thirdPartyLabel: String,
)

/** [disponible] = `false` si el servidor no tiene la ruta (404) o la persona no puede leerla (403): la sección no sale. */
data class ListaPorCobrar(
    val pedidos: List<PedidoPorCobrar>,
    val total: Int,
    val truncated: Boolean,
    val nextCursor: String?,
    val disponible: Boolean = true,
)

/** Conteos exactos del servidor de «Por pagar» y «Por entregar» (no los de la página leída). */
data class ConteosPorPagar(val toPay: Int, val toDeliver: Int)

/** Cursores de «Cargar más» por sección; null = no hay más. */
data class CursoresPorPagar(val toPay: String?, val toDeliver: String?)

/**
 * Tienda híbrida: folios que se pagan en caja ([porPagar], con [OnlineOrder.remainingCents] = «Falta $X» y
 * [OnlineOrder.acceptDeadlineAt] = vencimiento) y los ya pagados que esperan entrega ([porEntregar]).
 * [disponible] = `false` si el servidor no tiene la ruta (404) o la persona no puede leerla (403): la sección no sale.
 * [guardadoHaceMin] sólo viene de `porPagarGuardado()` («de hace N min»); en una lectura en vivo es null.
 */
data class ListaPorPagar(
    val disponible: Boolean,
    val showTile: Boolean,
    val pollSeconds: Int,
    val counts: ConteosPorPagar,
    val porPagar: List<OnlineOrder>,
    val porEntregar: List<OnlineOrder>,
    val nextCursors: CursoresPorPagar,
    val guardadoHaceMin: Long? = null,
)

sealed interface AccionResultado {
    data object Hecho : AccionResultado
    data class YaNoDisponible(val status: OnlineOrderStatus) : AccionResultado
    data object SinConexion : AccionResultado
    /** `409 ONLINE_ORDER_CONFIRMING_PAYMENT`: el banco aún confirma el pago; no es error rojo. */
    data class ConfirmandoPago(val mensaje: String) : AccionResultado
    data class Error(val mensaje: String) : AccionResultado
}
