package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.Serializable

/**
 * La liga del recibo digital de una venta ya cobrada: lo que hace falta para dibujar el QR
 * cuando se REIMPRIME un ticket desde el historial.
 *
 * 🔴 Por qué existe: el QR del ticket se dibuja con `receiptUrl`, y esa llave sólo viajaba en la
 * respuesta del COBRO. Al reimprimir desde Ventas, la app no la tenía, así que el ticket salía sin
 * QR (Asana «POS - Reimpresion de Ticket sin QR de facturacion», 11-sep-2026). El servidor la sirve
 * ahora en `GET /mobile/venues/:venueId/payments/:paymentId/receipt`.
 */
@Serializable
data class ReceiptLink(
    val accessKey: String = "",
    val receiptUrl: String = "",
    /**
     * Sólo decide la LEYENDA del ticket («…y factura» vs «recibo digital»).
     * El QR se imprime igual: lleva al recibo, se pueda facturar o no.
     */
    val autofacturaAvailable: Boolean = false,
)

@Serializable
internal data class ReceiptLinkResponse(
    val success: Boolean = false,
    val receipt: ReceiptLink? = null,
)

/**
 * Tres desenlaces, no dos. Un 403/404/500 **no es «sin conexión»**: decirle al cajero que no hay
 * red cuando el servidor sí respondió lo manda a revisar el WiFi por un problema que no está ahí.
 */
sealed interface ResultadoLigaRecibo {
    data class Obtenida(val liga: ReceiptLink) : ResultadoLigaRecibo

    /** No se pudo llegar al servidor (sin red, DNS, timeout). */
    data object SinRed : ResultadoLigaRecibo

    /** El servidor respondió, pero con un error. */
    data class FalloDelServidor(val codigo: Int) : ResultadoLigaRecibo
}
