package com.avoqado.pos.payment.data

/**
 * 🔴 F1 · cierre android bloque 2 (`plan-android.md`, invariante 1). La compra que sale con [Seguir] con su tarjeta en duda: el
 * carrito queda ligado a ESTE folio por el mismo camino que un abono parcial, con la sucursal, la identidad de la venta y el
 * intento que la frena. [Origen.OTRA_CAJA]: el intento lo mandó otra caja (409 con alcance `ORDER`); esta tablet no lo tiene en
 * su lista durable ni puede firmarlo: sólo conserva el folio.
 */
data class FolioEnDuda(
    val venueId: String,
    val identidadDeVenta: String,
    val orderId: String,
    val requestId: String?,
    val origen: Origen,
    /** r2 #1: lo que el SERVIDOR corrigió al crear A («se cobra el precio que vio el cliente»): el carrito queda con ESE precio. */
    val cambiosDePrecio: List<com.avoqado.pos.pos.data.model.CambioDePrecio> = emptyList(),
) {
    enum class Origen { LOCAL, OTRA_CAJA }
}

/** F1 bloque 2: el intento de un folio terminó en algún lado (ver `CancelacionDeCobroCoordinator.folios`): hay que releer el folio. */
data class DesenlaceDeFolio(val venueId: String, val orderId: String, val requestId: String?)

/**
 * 🔴 F1 bloque 2, con el 412 único de develop (`ORDER_BALANCE_CHANGED`, detalle `remainingCents`): el SERVIDOR rechazó ESTE envío de
 * una venta de Mostrador porque su importe pasa de lo que falta. Nada llegó al lector. La caja toma [cents] como saldo vigente de su
 * venta y vuelve a cobrar desde ahí; nunca reenvía sola.
 */
data class SaldoVigente(val venueId: String, val identidadDeVenta: String, val orderId: String, val cents: Int)

/** F1-textos (catálogo D5, fila 14 · F-E): el principio del aviso; el carrito lo usa para su título («Cobro no enviado», fila 15). */
const val PRINCIPIO_DEL_AVISO_DE_SALDO = "No se cobró la tarjeta: a esta cuenta le faltan "

/**
 * F1-textos (catálogo D5, fila 14 · F-E). El 412 muchas veces no es un cambio de la cuenta (el SERVIDOR corrigió el precio o la tablet
 * redondeó distinto): se dice lo cierto, que no se cobró y cuánto falta. Mismo texto en iOS (ficha).
 */
fun avisoDeSaldoCambiado(cents: Int): String =
    "$PRINCIPIO_DEL_AVISO_DE_SALDO${com.avoqado.pos.core.util.formatMoneyFromCents(cents)}."
