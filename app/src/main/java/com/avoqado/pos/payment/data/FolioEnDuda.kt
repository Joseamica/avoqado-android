package com.avoqado.pos.payment.data

/**
 * 🔴 F1 · cierre android bloque 2 (`plan-android.md`, invariante 1). La compra que sale con [Seguir]: el carrito queda ligado a ESTE folio
 * por el mismo camino que un abono parcial, con la sucursal y la identidad de la venta. [requestId]: la tarjeta PROPIA en duda (su llave
 * en la lista de esta tablet). null = el folio sin duda propia (tras un `409 ORDER`: la tarjeta de otra caja la dice el SERVIDOR, ronda 4).
 */
data class FolioEnDuda(
    val venueId: String,
    val identidadDeVenta: String,
    val orderId: String,
    val requestId: String?,
    /** r2 #1: lo que el SERVIDOR corrigió al crear A («se cobra el precio que vio el cliente»): el carrito queda con ESE precio. */
    val cambiosDePrecio: List<com.avoqado.pos.pos.data.model.CambioDePrecio> = emptyList(),
)

/** F1 bloque 2: el intento de un folio terminó en algún lado (ver `CancelacionDeCobroCoordinator.folios`): hay que releer el folio. */
data class DesenlaceDeFolio(val venueId: String, val orderId: String, val requestId: String?)

/**
 * 🔴 F1 bloque 2 (412 `REMAINING_BALANCE_MISMATCH`): el SERVIDOR rechazó ESTE envío porque el saldo de la cuenta no es el que vio la
 * caja. Nada llegó al lector. La caja toma [cents] como saldo vigente de su venta y vuelve a cobrar desde ahí; nunca reenvía sola.
 */
data class SaldoVigente(val venueId: String, val identidadDeVenta: String, val orderId: String, val cents: Int)

fun avisoDeSaldoCambiado(cents: Int): String =
    "El saldo de esta cuenta cambió: ahora faltan ${com.avoqado.pos.core.util.formatMoneyFromCents(cents)}."
