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
